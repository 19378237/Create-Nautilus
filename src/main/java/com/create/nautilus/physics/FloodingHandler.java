package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.HullBreach;
import com.create.nautilus.data.NautilusData;
import com.create.nautilus.network.BreachFlowParticlePayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;

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
        if (CarrierSplitMigration.isMovingBlocks()) return;
        final SubLevel found = Sable.HELPER.getContaining(level, position);
        if (!(found instanceof ServerSubLevel carrier)) {
            return;
        }
        final CarrierState state = NautilusData.carrier(carrier);
        if (!oldState.getFluidState().equals(newState.getFluidState())) state.invalidateFluidGeometry();
        // Compare the actual membrane contract for every committed state transition. Replacing a
        // hull block with water or a modded non-colliding state need not pass through vanilla air.
        final boolean wasPassable = CompartmentSealer.isPassable(
                carrier.getLevel(), position, oldState);
        final boolean isPassable = CompartmentSealer.isPassable(
                carrier.getLevel(), position, newState);
        // Even Sable's change hook can run inside a placement followed by cancellation and
        // rollback. Keep only the original membrane state until the next physics callback.
        if (wasPassable != isPassable) state.queueCommittedHullCell(position, wasPassable);
    }

    public static void onBlockBroken(final BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final SubLevel found = Sable.HELPER.getContaining(level, event.getPos());
        if (!(found instanceof ServerSubLevel carrier)) {
            return;
        }

        if (CompartmentSealer.isPassable(carrier.getLevel(), event.getPos(), event.getState())) return;
        // Compatibility fallback only. Neither inventory nor breaches are touched inside this
        // cancellable event, and a final solid voxel simply discards the queued candidate.
        NautilusData.carrier(carrier).queueBrokenHullCell(event.getPos());
    }

    /**
     * Resolves queued breaks after Minecraft/Sable has actually replaced the block with air.
     * This closes the event-order race where the pre-break callback occasionally saw neither the
     * final passage nor a populated compartment cache and silently discarded the hole.
     */
    static CompartmentSealer.ScanResult resolvePendingBreaks(final ServerSubLevel carrier, final CarrierState state) {
        for (final var change : state.takePendingCommittedHullCells().entrySet()) {
            final BlockPos pos = BlockPos.of(change.getKey());
            final boolean passable = CompartmentSealer.isPassable(carrier.getLevel(), pos);
            if (passable == change.getValue()) continue;
            if (passable) state.queueBrokenHullCell(pos); else state.removeBreach(pos);
            state.setLastCompartmentScanTick(Long.MIN_VALUE);
        }
        if (state.shouldDeferTopologyRetry(carrier.getLevel().getGameTime())) return null;
        final java.util.Set<Long> pending = state.takePendingBrokenHullCells();
        if (pending.isEmpty()) return null;

        final List<PendingBreachCandidate> candidates = new ArrayList<>();
        for (final long packed : pending) {
            final BlockPos position = BlockPos.of(packed);
            if (state.breaches().stream().anyMatch(b -> b.position().equals(position))) continue;
            if (!CompartmentSealer.isPassable(carrier.getLevel(), position)) {
                continue;
            }
            state.setLastCompartmentScanTick(Long.MIN_VALUE);
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
            if (!adjacentRooms.isEmpty() && hasImmediateExteriorNeighbour) {
                // A shell/wall junction can open several rooms to the same exterior voxel.
                // Register one membrane; the scan merges the rooms connected through it before
                // reconciling stock, so this opening is neither lost nor counted multiple times.
                final InteriorNeighbour neighbour = adjacentRooms.values().stream()
                        .filter(value -> value.direction() != Direction.DOWN).findFirst()
                        .orElse(adjacentRooms.values().iterator().next());
                candidates.add(new PendingBreachCandidate(position, neighbour));
            }
        }

        if (candidates.isEmpty()) return null;

        // Probe the committed plot with every candidate left as ordinary passable geometry. An
        // interior block removal becomes part of a detected airtight/open-top compartment. A real
        // side or bottom hull opening instead lets the exterior flood reach the old room and the
        // aperture is absent from all detected interior components. This single batch probe also
        // prevents adjacent interior edits from validating one another as an exterior path.
        final CompartmentSealer.ScanResult scan = CompartmentSealer.detectCurrentScan(carrier, state);
        if (!scan.complete()) {
            pending.forEach(packed -> state.queueBrokenHullCell(BlockPos.of(packed)));
            state.setLastCompartmentScanTick(carrier.getLevel().getGameTime());
            state.reportTopologyScanFailure(scan.status().name(), carrier.getLevel().getGameTime());
            return scan;
        }
        final java.util.Set<Long> detectedInterior = scan.compartments().stream()
                .flatMap(room -> room.cells().stream())
                .collect(java.util.stream.Collectors.toSet());
        boolean addedBreach = false;
        for (final PendingBreachCandidate candidate : candidates) {
            if (detectedInterior.contains(candidate.position().asLong())) {
                continue;
            }
            final InteriorNeighbour neighbour = candidate.neighbour();
            addedBreach = true;
            state.addBreach(new HullBreach(candidate.position(), neighbour.room().id(),
                    neighbour.direction(), 1.0D));
            neighbour.room().setSealed(false);
        }
        state.setLastCompartmentScanTick(Long.MIN_VALUE);
        return addedBreach ? null : scan;
    }

    public static void onBlockPlaced(final BlockEvent.EntityPlaceEvent event) {
        // EntityPlaceEvent is cancellable, including by listeners after ours. The committed
        // change queue above observes the final plot after rollback; passable decorations retain
        // their existing breach and do not trigger a rescan or publish temporary topology.
    }

    public static void tick(final ServerSubLevel carrier, final CarrierState state,
                            final double timeStep) {
        tick(carrier, state, timeStep, new WaterEnvironment.QueryContext(carrier.getLevel()));
    }

    static void tick(final ServerSubLevel carrier, final CarrierState state,
                      final double timeStep, final WaterEnvironment.QueryContext water) {
        tick(carrier, state, timeStep, new ExternalApertureSamples(carrier, water));
    }

    static void tick(final ServerSubLevel carrier, final CarrierState state,
                      final double timeStep, final ExternalApertureSamples outside) {
        if (!(timeStep > 0.0D) || state.breaches().isEmpty()) {
            return;
        }

        final Map<UUID, List<HullBreach>> byRoom = new LinkedHashMap<>();
        for (final HullBreach breach : state.breaches()) {
            byRoom.computeIfAbsent(breach.compartmentId(), ignored -> new ArrayList<>())
                    .add(breach);
        }

        for (final Map.Entry<UUID, List<HullBreach>> entry : byRoom.entrySet()) {
            final Optional<CompartmentState> target = state.compartment(entry.getKey());
            if (target.isEmpty()) {
                entry.getValue().forEach(breach -> breach.setCurrentFlowMbPerSecond(0.0D));
                continue;
            }
            integrateRoom(carrier, target.get(), entry.getValue(), outside, timeStep);
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
                                      final ExternalApertureSamples outside,
                                      final double timeStep) {
        final double density = Math.max(1.0E-6D, NautilusConfig.WATER_DENSITY);
        final double gravity = Math.max(1.0E-6D, DimensionPhysicsData.getGravity(
                carrier.getLevel(), carrier.logicalPose().position()).length());
        final double coefficient = Math.max(0.0D, NautilusConfig.FLOOD_SPEED)
                * ORIFICE_FLOW_SCALE;
        final List<ApertureSample> samples = new ArrayList<>(breaches.size());
        double airPathAmbientPressureKpa = Double.NaN;

        // External water, aperture geometry and ambient pressure are properties of this physics
        // sub-step. Sample them exactly once. The equilibrium limiter below can then evaluate
        // several candidate cabin levels without receiving a different answer merely because a
        // world query crossed a block/column boundary between evaluations.
        for (final HullBreach breach : breaches) {
            final ExternalApertureSamples.Sample external = outside.sample(breach);
            final CompartmentWaterGeometry.ProjectedInterval aperture = external.aperture();
            // A destroyed one-block hull voxel remains a volumetric aperture. A generated
            // atmospheric opening, however, is the exact face between the basin interior and the
            // exterior cell. Using that face is what makes an open vessel retain displacement
            // while its lowest inlet edge is still above the local sea surface.
            final OptionalDouble outsideSurface = external.surface();
            final double openingLow = aperture.low();
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
                    ? aperture.fractionBelow(outsideSurface.getAsDouble())
                    : outsideHead > 0.0D ? 1.0D : 0.0D;

            final double outsideGasPressure = external.ambientPressureKpa();
            final double apertureHigh = aperture.high();
            final boolean hasDryAirPath = outsideSurface.isEmpty()
                    || outsideSurface.getAsDouble()
                    <= apertureHigh + AIR_PATH_CLEARANCE_METRES;
            if (hasDryAirPath && !Double.isFinite(airPathAmbientPressureKpa)) {
                airPathAmbientPressureKpa = outsideGasPressure;
            }
            samples.add(new ApertureSample(breach, aperture,
                    outsideSurface.isPresent(), openingLow, outsideHead,
                    outsideWetArea, outsideGasPressure));
        }

        // Finish external sampling before capturing room geometry. Reuse it only until this
        // integration commits water below; each candidate still evaluates its own water volume.
        final CompartmentWaterGeometry.PreparedSurface prepared =
                CompartmentWaterGeometry.preparedSurface(carrier, room);
        final double initialWaterMb = room.waterMb();
        final double insideSurface = CompartmentWaterGeometry.surfaceYForWater(
                prepared, room, initialWaterMb);
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
        final double appliedDelta = clampBeforeHydraulicEquilibrium(carrier, room, prepared,
                samples, insideSurface, initialFlow.netFlowMbPerSecond(),
                capacityLimitedDelta, airPathAmbientPressureKpa, density,
                coefficient, gravity);
        final double inflow = breaches.stream().mapToDouble(breach ->
                Math.max(0.0D, breach.currentFlowMbPerSecond())).sum();
        final double outflow = breaches.stream().mapToDouble(breach ->
                Math.max(0.0D, -breach.currentFlowMbPerSecond())).sum();
        final FlowScales scales = reportedFlowScales(inflow, outflow, appliedDelta / timeStep);
        breaches.forEach(breach -> breach.setCurrentFlowMbPerSecond(
                breach.currentFlowMbPerSecond() * (breach.currentFlowMbPerSecond() >= 0.0D
                        ? scales.inflow() : scales.outflow())));

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
                    ? sample.aperture().fractionBelow(insideSurface)
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
            final CompartmentWaterGeometry.PreparedSurface prepared,
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
        final double endpointSurface = CompartmentWaterGeometry.surfaceYForWater(
                prepared, room, endpointWaterMb);
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
            final double candidateWaterMb = CompartmentWaterGeometry.waterMbBelowSurface(
                    prepared, room, candidateSurface);
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
        final double rootWaterMb = CompartmentWaterGeometry.waterMbBelowSurface(
                prepared, room, lowerSurface);
        final double rootDelta = rootWaterMb - initialWaterMb;
        return proposedDelta > 0.0D
                ? Math.clamp(rootDelta, 0.0D, proposedDelta)
                : Math.clamp(rootDelta, proposedDelta, 0.0D);
    }

    private static boolean oppositeSigns(final double first, final double second) {
        return first > 0.0D && second < 0.0D
                || first < 0.0D && second > 0.0D;
    }

    private static boolean sameStrictSign(final double first, final double second) {
        return first > 0.0D && second > 0.0D
                || first < 0.0D && second < 0.0D;
    }

    /** Preserve the cancelling through-flow while limiting only the unaccepted net transfer. */
    static FlowScales reportedFlowScales(final double inflow, final double outflow,
                                         final double acceptedNetRate) {
        final double acceptedIn = Math.max(0.0D, acceptedNetRate);
        final double acceptedOut = Math.max(0.0D, -acceptedNetRate);
        final double through = Math.max(0.0D, Math.min(inflow - acceptedIn, outflow - acceptedOut));
        return new FlowScales(inflow > 0.0D ? Math.clamp((through + acceptedIn) / inflow, 0.0D, 1.0D) : 0.0D,
                outflow > 0.0D ? Math.clamp((through + acceptedOut) / outflow, 0.0D, 1.0D) : 0.0D);
    }

    record FlowScales(double inflow, double outflow) {}

    private record ApertureSample(HullBreach breach, CompartmentWaterGeometry.ProjectedInterval aperture,
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
