package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.HullBreach;
import com.create.nautilus.data.NautilusData;
import com.create.nautilus.network.BreachFlowParticlePayload;
import com.create.nautilus.network.CompartmentVisualSync;
import com.create.nautilus.network.DryCompartmentSync;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Tracks hull breaches and integrates continuous, signed linear hydraulic flow.
 * No real water block is ever placed in a carrier plot.
 */
public final class FloodingHandler {
    /** Do not turn sub-pixel equilibrium corrections into a permanent particle fountain. */
    private static final double MIN_VISIBLE_FLOW_MB_PER_SECOND = 200.0D;
    /** Sub-millimetre corrections are visually irrelevant but avoid floating-point chatter. */
    private static final double HYDRAULIC_HEAD_DEADBAND_METRES = 0.0005D;
    /**
     * The fixed gameplay-scale discharge coefficient retains the historical default of 0.05.
     * Feeding that value directly into the orifice equation made an ordinary one-block breach
     * take tens of seconds to raise a small cabin by one block.  Convert it to a practical
     * discharge coefficient while preserving zero as a complete flooding disable switch.
     */
    private static final double ORIFICE_FLOW_SCALE = 20.0D;
    /** Prevents pose/fluid-height rounding at a visibly dry rim from becoming positive inflow. */
    private static final double OPEN_TOP_OVERFLOW_CLEARANCE_METRES = 0.01D;
    /** A partially exposed aperture keeps the room gas connected to ambient air. */
    private static final double AIR_PATH_CLEARANCE_METRES = 1.0E-4D;
    private FloodingHandler() {
    }

    /**
     * Receives Sable's post-commit plot mutation. This is the authoritative topology wake-up;
     * NeoForge BreakEvent remains only a compatibility/latency path.
     */
    public static void onCommittedBlockChange(final ServerLevel level, final BlockPos position,
                                              final BlockState oldState,
                                              final BlockState newState) {
        final SubLevel found = Sable.HELPER.getContaining(level, position);
        if (!(found instanceof ServerSubLevel carrier)) {
            return;
        }
        final boolean blockRemoved = !oldState.isAir() && newState.isAir();
        final boolean blockAdded = oldState.isAir() && !newState.isAir();
        final boolean explicitOpenableChanged = oldState.getBlock() == newState.getBlock()
                && (oldState.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || oldState.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                || oldState.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)
                && oldState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                != newState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
        // Sable forwards every neighbour-driven BlockState change. Connected panes, stairs and
        // similar hull blocks may change shape without a block being removed; those updates must
        // invalidate neither watertightness nor create a HullBreach. Only an actual air transition
        // or an explicitly operated door-like membrane is a hydraulic topology event.
        if (!blockRemoved && !blockAdded && !explicitOpenableChanged) {
            return;
        }
        final boolean wasPassable = CompartmentSealer.isPassable(
                carrier.getLevel(), position, oldState);
        final boolean isPassable = CompartmentSealer.isPassable(
                carrier.getLevel(), position, newState);
        if (wasPassable == isPassable) {
            return;
        }

        final CarrierState state = NautilusData.carrier(carrier);
        if (!wasPassable) {
            // The new state is already committed, so resolvePendingBreaks can validate exterior
            // connectivity on the next physics callback without the pre-break race.
            state.queueBrokenHullCell(position);
        } else {
            state.removeBreach(position);
        }
        state.setLastCompartmentScanTick(Long.MIN_VALUE);
    }

    public static void onBlockBroken(final BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final SubLevel found = Sable.HELPER.getContaining(level, event.getPos());
        if (!(found instanceof ServerSubLevel carrier)) {
            return;
        }

        final CarrierState state = NautilusData.carrier(carrier);
        // Keep the closed topology available even when a block is destroyed immediately after
        // assembly, before the normal periodic scan has run.
        if (state.lastCompartmentScanTick() == Long.MIN_VALUE) {
            CompartmentSealer.rescanInto(carrier, state);
        }
        state.queueBrokenHullCell(event.getPos());
        // BreakEvent is pre-commit. Never create a hydraulic opening here: when several interior
        // blocks are changed together, their newly passable cells can make one another look like
        // an exterior neighbour. resolvePendingBreaks classifies them from the committed geometry.
        state.removeBreach(event.getPos());

        // BreakEvent fires before the removed state is committed. Force the dispatcher to rescan
        // on the next physics callback, when the plot contains the actual post-break topology.
        state.setLastCompartmentScanTick(Long.MIN_VALUE);
    }

    /**
     * Resolves queued breaks after Minecraft/Sable has actually replaced the block with air.
     * This closes the event-order race where the pre-break callback occasionally saw neither the
     * final passage nor a populated compartment cache and silently discarded the hole.
     */
    static void resolvePendingBreaks(final ServerSubLevel carrier, final CarrierState state) {
        final java.util.Set<Long> pending = state.takePendingBrokenHullCells();
        if (pending.isEmpty()) {
            return;
        }

        final List<PendingBreachCandidate> candidates = new ArrayList<>();
        for (final long packed : pending) {
            final BlockPos position = BlockPos.of(packed);
            state.removeBreach(position);
            if (!CompartmentSealer.isPassable(carrier.getLevel(), position)) {
                continue;
            }
            final Map<UUID, InteriorNeighbour> adjacentRooms = new LinkedHashMap<>();
            boolean hasImmediateExteriorNeighbour = false;
            for (final Direction direction : Direction.values()) {
                final BlockPos adjacent = position.relative(direction);
                final Optional<CompartmentState> room = state.compartmentAt(adjacent);
                if (room.isPresent()) {
                    adjacentRooms.putIfAbsent(room.get().id(),
                            new InteriorNeighbour(room.get(), direction));
                } else if (CompartmentSealer.isPassable(carrier.getLevel(), adjacent)) {
                    hasImmediateExteriorNeighbour = true;
                }
            }
            if (adjacentRooms.size() == 1 && hasImmediateExteriorNeighbour) {
                final InteriorNeighbour neighbour = adjacentRooms.values().iterator().next();
                candidates.add(new PendingBreachCandidate(position, neighbour));
            }
        }

        if (candidates.isEmpty()) {
            state.setLastCompartmentScanTick(Long.MIN_VALUE);
            return;
        }

        // Probe the committed plot with every candidate left as ordinary passable geometry. An
        // interior block removal becomes part of a detected airtight/open-top compartment. A real
        // side or bottom hull opening instead lets the exterior flood reach the old room and the
        // aperture is absent from all detected interior components. This single batch probe also
        // prevents adjacent interior edits from validating one another as an exterior path.
        final java.util.Set<Long> detectedInterior = CompartmentSealer
                .detectCurrentCompartments(carrier, state).stream()
                .flatMap(room -> room.cells().stream())
                .collect(java.util.stream.Collectors.toSet());
        for (final PendingBreachCandidate candidate : candidates) {
            if (detectedInterior.contains(candidate.position().asLong())) {
                continue;
            }
            final InteriorNeighbour neighbour = candidate.neighbour();
            state.addBreach(new HullBreach(candidate.position(), neighbour.room().id(),
                    neighbour.direction(), 1.0D));
            neighbour.room().setSealed(false);
        }
        state.setLastCompartmentScanTick(Long.MIN_VALUE);
    }

    public static void onBlockPlaced(final BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final SubLevel found = Sable.HELPER.getContaining(level, event.getPos());
        if (!(found instanceof ServerSubLevel carrier)) {
            return;
        }

        final CarrierState state = NautilusData.carrier(carrier);
        state.removeBreach(event.getPos());
        // EntityPlaceEvent is the earliest reliable repair notification available to NeoForge.
        // Rebuild and publish immediately so a repaired hull cannot spend a physics tick using a
        // stale breach/occlusion snapshot. Sable's post-commit hook remains the fallback for block
        // mutations which do not emit this event.
        CompartmentSealer.rescanInto(carrier, state);
        // Decide whether this topology can be rendered before publishing its dry-water mask.
        // An oversized repair must withdraw both views in this same update, not one tick apart.
        CompartmentVisualSync.syncTopology(carrier, state);
        DryCompartmentSync.syncToTrackingPlayers(carrier,
                NautilusPhysicsDispatcher.physicalDryCells(carrier, state),
                NautilusPhysicsDispatcher.visualOcclusionCells(carrier, state));
        CompartmentVisualSync.syncLevels(carrier, state);
        state.setLastVisualSyncTick(carrier.getLevel().getGameTime());
    }

    public static void tick(final ServerSubLevel carrier, final CarrierState state,
                            final double timeStep) {
        if (!(timeStep > 0.0D) || state.breaches().isEmpty()) {
            return;
        }

        final Map<UUID, List<HullBreach>> byRoom = new LinkedHashMap<>();
        for (final HullBreach breach : state.breaches()) {
            byRoom.computeIfAbsent(breach.compartmentId(), ignored -> new ArrayList<>())
                    .add(breach);
        }

        final WaterEnvironment.QueryContext water =
                new WaterEnvironment.QueryContext(carrier.getLevel());
        for (final Map.Entry<UUID, List<HullBreach>> entry : byRoom.entrySet()) {
            final Optional<CompartmentState> target = state.compartment(entry.getKey());
            if (target.isEmpty()) {
                entry.getValue().forEach(breach -> breach.setCurrentFlowMbPerSecond(0.0D));
                continue;
            }
            integrateRoom(carrier, target.get(), entry.getValue(), water, timeStep);
        }

        final long gameTime = carrier.getLevel().getGameTime();
        if (state.lastBreachParticleTick() != gameTime) {
            state.setLastBreachParticleTick(gameTime);
            for (final HullBreach breach : state.breaches()) {
                // A wide open top can contain dozens of generated one-square-metre inlets. Sending
                // one bubble burst per inlet produced the dense "breathing" field visible in the
                // test video. Keep directional jets for actual destroyed hull blocks, but render
                // open-top overflow solely through the rising compartment water surface.
                if (!breach.atmosphericOpening()
                        && Math.abs(breach.currentFlowMbPerSecond())
                        >= MIN_VISIBLE_FLOW_MB_PER_SECOND) {
                    BreachFlowParticlePayload.send(carrier, breach);
                }
            }
        }
    }

    private static void integrateRoom(final ServerSubLevel carrier,
                                      final CompartmentState room,
                                      final List<HullBreach> breaches,
                                      final WaterEnvironment.QueryContext water,
                                      final double timeStep) {
        final double density = Math.max(1.0E-6D, NautilusConfig.WATER_DENSITY);
        final double gravity = Math.max(1.0E-6D, DimensionPhysicsData.getGravity(
                carrier.getLevel(), carrier.logicalPose().position()).length());
        final double coefficient = Math.max(0.0D, NautilusConfig.FLOOD_SPEED)
                * ORIFICE_FLOW_SCALE;
        final List<BlockPos> roomCells = room.cells().stream()
                .map(BlockPos::of)
                .toList();
        final List<ApertureSample> samples = new ArrayList<>(breaches.size());
        double airPathAmbientPressureKpa = Double.NaN;

        // External water, aperture geometry and ambient pressure are properties of this physics
        // sub-step. Sample them exactly once. The equilibrium limiter below can then evaluate
        // several candidate cabin levels without receiving a different answer merely because a
        // world query crossed a block/column boundary between evaluations.
        for (final HullBreach breach : breaches) {
            // Geometry-derived atmospheric openings always connect through the bottom face of the
            // exterior cell into the basin below. Reconciliation temporarily walks adjacent cells
            // and may rebind a saved direction; never let that turn a top opening into a lower
            // side-face hydraulic sample.
            final Direction apertureFace = breach.atmosphericOpening()
                    ? Direction.DOWN : breach.interiorDirection();
            // A destroyed one-block hull voxel remains a volumetric aperture. A generated
            // atmospheric opening, however, is the exact face between the basin interior and the
            // exterior cell. Using that face is what makes an open vessel retain displacement
            // while its lowest inlet edge is still above the local sea surface.
            final OptionalDouble outsideSurface = breach.atmosphericOpening()
                    ? WaterEnvironment.waterSurfaceIntersectingFace(carrier, breach.position(),
                    apertureFace, water)
                    : WaterEnvironment.waterSurfaceIntersectingCell(
                    carrier, breach.position(), water);
            final double openingLow = breach.atmosphericOpening()
                    ? CompartmentWaterGeometry.lowestFaceWorldY(carrier, breach.position(),
                    apertureFace)
                    : CompartmentWaterGeometry.lowestWorldY(carrier, breach.position());
            // Every top inlet is evaluated independently. The first submerged portion may admit
            // water even while a higher part of the same rim remains exposed; the clipped face
            // fraction below scales that initial flow continuously.
            final double outsideEntryHeight = breach.atmosphericOpening()
                    ? openingLow + OPEN_TOP_OVERFLOW_CLEARANCE_METRES
                    : openingLow;
            final double outsideHead = outsideSurface.isPresent()
                    ? Math.max(0.0D, outsideSurface.getAsDouble() - outsideEntryHeight) : 0.0D;
            final double outsideWetArea = breach.atmosphericOpening()
                    && outsideSurface.isPresent() && outsideHead > 0.0D
                    ? CompartmentWaterGeometry.fractionOfFaceBelowSurface(
                    carrier, breach.position(), apertureFace, outsideSurface.getAsDouble())
                    : outsideHead > 0.0D ? 1.0D : 0.0D;

            final Vector3d worldHole = WaterEnvironment.toWorld(carrier, new Vector3d(
                    breach.position().getX() + 0.5D,
                    breach.position().getY() + 0.5D,
                    breach.position().getZ() + 0.5D));
            final double outsideGasPressure = PressureEqualizer.ambientPressureKpa(
                    carrier.getLevel(), worldHole);
            final double apertureHigh = breach.atmosphericOpening()
                    ? CompartmentWaterGeometry.highestFaceWorldY(carrier, breach.position(),
                    apertureFace)
                    : CompartmentWaterGeometry.highestWorldY(carrier, breach.position());
            final boolean hasDryAirPath = outsideSurface.isEmpty()
                    || outsideSurface.getAsDouble()
                    <= apertureHigh + AIR_PATH_CLEARANCE_METRES;
            if (hasDryAirPath && !Double.isFinite(airPathAmbientPressureKpa)) {
                airPathAmbientPressureKpa = outsideGasPressure;
            }
            samples.add(new ApertureSample(breach, apertureFace,
                    outsideSurface.isPresent(), openingLow, outsideHead,
                    outsideWetArea, outsideGasPressure));
        }

        final double initialWaterMb = room.waterMb();
        final double insideSurface = surfaceYForWater(
                carrier, room, roomCells, initialWaterMb);
        final FlowEvaluation initialFlow = evaluateNetFlow(carrier, samples,
                room, initialWaterMb, insideSurface, airPathAmbientPressureKpa,
                density, coefficient, gravity, true);

        // Preserve gradual convergence even for extreme depth/pressure or very large dt. This is
        // a stability bound derived from the same linear coefficient, not an instant target sync.
        final double convergenceCoefficient = coefficient;
        final double maximumFractionThisStep = 1.0D
                - Math.exp(-convergenceCoefficient
                * Math.max(0.01D, initialFlow.totalArea()) * timeStep);
        final double maximumDelta = room.waterCapacityMb() * maximumFractionThisStep;
        final double requestedDelta = initialFlow.netFlowMbPerSecond() * timeStep;
        final double rateLimitedDelta = Math.clamp(
                requestedDelta, -maximumDelta, maximumDelta);
        // The theoretical orifice flow can remain positive when a completely submerged room has
        // already reached its finite capacity (or negative after it has fully drained). Clamp to
        // the amount the attachment can actually accept before publishing currentFlow. Without
        // this step addWater/removeWater correctly rejected the excess, but the stale theoretical
        // flow kept spawning bubbles forever.
        final double capacityLimitedDelta = Math.clamp(rateLimitedDelta,
                -initialWaterMb, room.waterCapacityMb() - initialWaterMb);
        final double appliedDelta = clampBeforeHydraulicEquilibrium(carrier, room,
                roomCells, samples, insideSurface, initialFlow.netFlowMbPerSecond(),
                capacityLimitedDelta, airPathAmbientPressureKpa, density,
                coefficient, gravity);
        final double scale = Math.abs(requestedDelta) > 1.0E-9D
                ? appliedDelta / requestedDelta : 0.0D;
        if (scale < 1.0D) {
            breaches.forEach(breach -> breach.setCurrentFlowMbPerSecond(
                    breach.currentFlowMbPerSecond() * scale));
        }

        if (appliedDelta > 0.0D) {
            room.addWater(appliedDelta);
        } else if (appliedDelta < 0.0D) {
            room.removeWater(-appliedDelta);
        }
    }

    /**
     * Evaluates all apertures against one candidate cabin water surface.
     *
     * <p>The method is deliberately side-effect free unless {@code publishFlows} is true. Root
     * searches can therefore reuse the exact production flow equation without making particles
     * or API readers observe one of the intermediate bisection candidates.</p>
     */
    private static FlowEvaluation evaluateNetFlow(final ServerSubLevel carrier,
                                                   final List<ApertureSample> samples,
                                                   final CompartmentState room,
                                                   final double candidateWaterMb,
                                                   final double insideSurface,
                                                   final double airPathAmbientPressureKpa,
                                                   final double density,
                                                   final double coefficient,
                                                   final double gravity,
                                                   final boolean publishFlows) {
        double combinedFlow = 0.0D;
        double totalArea = 0.0D;
        // A dry route holds cabin gas at ambient pressure. Once every aperture is submerged, use
        // the ideal-gas state stored by PressureEqualizer so compression participates in the same
        // root solve as liquid head instead of kicking the following substep backwards.
        final double candidateInsidePressureKpa = Double.isFinite(airPathAmbientPressureKpa)
                ? airPathAmbientPressureKpa
                : NautilusConfig.SURFACE_PRESSURE_KPA * room.standardGasMb()
                / Math.max(1.0D, room.waterCapacityMb() - candidateWaterMb);
        for (final ApertureSample sample : samples) {
            final HullBreach breach = sample.breach();
            final double insideHead = candidateWaterMb >= 1.0D
                    ? Math.max(0.0D, insideSurface - sample.openingLow()) : 0.0D;
            final double insideWetArea = breach.atmosphericOpening()
                    ? CompartmentWaterGeometry.fractionOfFaceBelowSurface(
                    carrier, breach.position(), sample.apertureFace(), insideSurface)
                    : insideHead > 0.0D ? 1.0D : 0.0D;

            final double flow;
            final double activeArea;
            // A breach exposed to air drains only when cabin water physically reaches its sill.
            // This same rule must be used by both the real step and every equilibrium candidate.
            if (!sample.outsideWaterPresent()) {
                final double drainHead = insideHead < HYDRAULIC_HEAD_DEADBAND_METRES
                        ? 0.0D : insideHead;
                activeArea = breach.areaSquareMetres() * insideWetArea;
                flow = drainHead > 0.0D
                        ? -coefficient * activeArea
                        * Math.sqrt(2.0D * gravity * drainHead) * 1000.0D : 0.0D;
            } else {
                // Free-surface heads already contain hydrostatic liquid pressure. Add only the gas
                // pressure difference; including rho*g*outsideHead twice would double inflow.
                final double pressureHead = (sample.outsideGasPressureKpa()
                        - candidateInsidePressureKpa) * 1000.0D
                        / Math.max(1.0E-9D, density * gravity);
                double signedHead = sample.outsideHead() - insideHead + pressureHead;

                // A positive flow needs external liquid at the aperture; a negative flow needs
                // cabin liquid at the aperture. Gas pressure alone never creates water.
                if ((signedHead > 0.0D && sample.outsideHead() <= 0.0D)
                        || (signedHead < 0.0D && insideHead <= 0.0D)
                        || Math.abs(signedHead) < HYDRAULIC_HEAD_DEADBAND_METRES) {
                    signedHead = 0.0D;
                }
                // A tilted open rim enters the sea corner-first. Scale by the actually wetted
                // share of its face rather than enabling the full aperture on first contact.
                final double activeAreaFraction = signedHead > 0.0D
                        ? sample.outsideWetArea()
                        : signedHead < 0.0D ? insideWetArea : 0.0D;
                activeArea = breach.areaSquareMetres() * activeAreaFraction;
                flow = coefficient * activeArea
                        * Math.copySign(Math.sqrt(2.0D * gravity
                        * Math.abs(signedHead)), signedHead) * 1000.0D;
            }

            final double finiteFlow = Math.clamp(Double.isFinite(flow) ? flow : 0.0D,
                    -NautilusConfig.maxFloodRateMbPerSecond(),
                    NautilusConfig.maxFloodRateMbPerSecond());
            if (publishFlows) {
                breach.setCurrentFlowMbPerSecond(finiteFlow);
            }
            combinedFlow += finiteFlow;
            totalArea += activeArea;
        }
        return new FlowEvaluation(combinedFlow, totalArea);
    }

    /**
     * Prevents one explicit Torricelli step from crossing the total-flow equilibrium.
     *
     * <p>Because {@code Q ~ sqrt(abs(head))}, the derivative becomes unbounded as hydraulic head
     * approaches zero. A capacity-only Euler limit can consequently overshoot the root even with
     * a small time step, after which the next sub-step reverses direction and the rendered surface
     * visibly twitches. When the proposed endpoint has the opposite net-flow sign, bisect the
     * candidate water-volume interval and retain the last point on the starting side of
     * {@code sum(Q_i) = 0}. This also handles multiple inlets and drains at different heights.</p>
     */
    private static double clampBeforeHydraulicEquilibrium(
            final ServerSubLevel carrier,
            final CompartmentState room,
            final List<BlockPos> roomCells,
            final List<ApertureSample> samples,
            final double initialSurface,
            final double initialNetFlow,
            final double proposedDelta,
            final double airPathAmbientPressureKpa,
            final double density,
            final double coefficient,
            final double gravity) {
        if (proposedDelta == 0.0D || initialNetFlow == 0.0D
                || !Double.isFinite(proposedDelta) || !Double.isFinite(initialNetFlow)) {
            return proposedDelta;
        }

        final double initialWaterMb = room.waterMb();
        final double endpointWaterMb = initialWaterMb + proposedDelta;
        final double endpointSurface = surfaceYForWater(
                carrier, room, roomCells, endpointWaterMb);
        final double endpointFlow = evaluateNetFlow(carrier, samples, room,
                endpointWaterMb, endpointSurface, airPathAmbientPressureKpa,
                density, coefficient, gravity, false)
                .netFlowMbPerSecond();
        if (!oppositeSigns(initialNetFlow, endpointFlow)) {
            return proposedDelta;
        }

        double lowerSurface = initialSurface;
        double upperSurface = endpointSurface;
        for (int iteration = 0; iteration < 12; iteration++) {
            final double candidateSurface = (lowerSurface + upperSurface) * 0.5D;
            // The coupled pressure term depends on free gas volume, so convert this candidate
            // plane to exact clipped voxel volume as well. This is still much cheaper than running
            // the inverse 24-step surface solve for every bisection candidate.
            final double candidateWaterMb = waterMbBelowSurface(
                    carrier, room, roomCells, candidateSurface);
            final double candidateFlow = evaluateNetFlow(carrier, samples, room,
                    candidateWaterMb, candidateSurface, airPathAmbientPressureKpa,
                    density, coefficient, gravity, false)
                    .netFlowMbPerSecond();
            if (sameStrictSign(initialNetFlow, candidateFlow)) {
                lowerSurface = candidateSurface;
            } else {
                upperSurface = candidateSurface;
            }
        }
        // Bisection in world-surface space avoids running the room-wide 24-step inverse-volume
        // solve twelve times. Convert the final plane back to exact clipped voxel volume once.
        final double rootWaterMb = waterMbBelowSurface(carrier, room, roomCells, lowerSurface);
        final double rootDelta = rootWaterMb - initialWaterMb;
        return proposedDelta > 0.0D
                ? Math.clamp(rootDelta, 0.0D, proposedDelta)
                : Math.clamp(rootDelta, proposedDelta, 0.0D);
    }

    private static double surfaceYForWater(final ServerSubLevel carrier,
                                           final CompartmentState room,
                                           final List<BlockPos> roomCells,
                                           final double waterMb) {
        final double fraction = room.waterCapacityMb() <= 0.0D ? 0.0D
                : Math.clamp(waterMb / room.waterCapacityMb(), 0.0D, 1.0D);
        return CompartmentWaterGeometry.surfaceY(carrier.logicalPose(), roomCells,
                room.bounds(), fraction);
    }

    private static double waterMbBelowSurface(final ServerSubLevel carrier,
                                              final CompartmentState room,
                                              final List<BlockPos> roomCells,
                                              final double worldSurfaceY) {
        if (roomCells.isEmpty()) {
            return room.waterMb();
        }
        double submergedVolume = 0.0D;
        for (final BlockPos cell : roomCells) {
            submergedVolume += CompartmentWaterGeometry.fractionBelowSurface(
                    carrier, cell, worldSurfaceY);
        }
        final double fraction = Math.clamp(submergedVolume / roomCells.size(), 0.0D, 1.0D);
        return room.waterCapacityMb() * fraction;
    }

    private static boolean oppositeSigns(final double first, final double second) {
        return first > 0.0D && second < 0.0D
                || first < 0.0D && second > 0.0D;
    }

    private static boolean sameStrictSign(final double first, final double second) {
        return first > 0.0D && second > 0.0D
                || first < 0.0D && second < 0.0D;
    }

    private record ApertureSample(HullBreach breach, Direction apertureFace,
                                  boolean outsideWaterPresent,
                                  double openingLow, double outsideHead,
                                  double outsideWetArea, double outsideGasPressureKpa) {
    }

    private record FlowEvaluation(double netFlowMbPerSecond, double totalArea) {
    }

    private record InteriorNeighbour(CompartmentState room, Direction direction) {
    }

    private record PendingBreachCandidate(BlockPos position, InteriorNeighbour neighbour) {
    }
}
