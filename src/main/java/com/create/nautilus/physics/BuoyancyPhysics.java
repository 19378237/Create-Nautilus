package com.create.nautilus.physics;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Supplements Sable's hull buoyancy with the submerged dry air in tracked compartments.
 *
 * <p>Sable's Rapier backend is authoritative for physical hull-voxel displacement, total mass,
 * gravity and centre of mass. As in release 0.1, flooding removes dry-air displacement while
 * BallastMassSynchronizer carries the water's mass. The small correction from native hull lift
 * to the configured envelope is gated by both cavity size and remaining dry air. A completely
 * flooded compartment therefore supplies no Nautilus lift or hull correction.</p>
 */
public final class BuoyancyPhysics {
    /**
     * Sable 2.0.3's Rapier backend applies this world-up force for every cubic metre of a
     * physical carrier block overlapping parent-level water. Subtract this native contribution
     * before adding the dry-cavity-gated hull correction, so hull lift is never applied twice.
     */
    private static final double SABLE_NATIVE_SOLID_BUOYANCY_PER_CUBIC_METRE = 10.5D;
    private static final double MINIMUM_USEFUL_AIR_FRACTION = 0.05D;
    private static final double FULL_AIR_EFFICIENCY_FRACTION = 0.25D;

    private BuoyancyPhysics() {
    }

    public static Result tick(final ServerSubLevel carrier, final RigidBodyHandle body,
                              final CarrierState state,
                              final CompartmentFluidInventory.Snapshot internalWater,
                              final double timeStep) {
        return tick(carrier, body, state, internalWater, timeStep,
                new WaterEnvironment.QueryContext(carrier.getLevel()));
    }

    static Result tick(final ServerSubLevel carrier, final RigidBodyHandle body,
                        final CarrierState state,
                        final CompartmentFluidInventory.Snapshot internalWater,
                        final double timeStep, final WaterEnvironment.QueryContext water) {
        final BoundingBox3ic bounds = carrier.getPlot().getBoundingBox();
        if (bounds == null || !(timeStep > 0.0D)) {
            return Result.EMPTY;
        }
        final WaterQueryDiagnostics diagnostics = water.diagnostics();

        final long gameTime = carrier.getLevel().getGameTime();
        final boolean diagnosticDue = CreateNautilus.LOGGER.isDebugEnabled()
                && (state.lastHydrostaticDiagnosticTick() == Long.MIN_VALUE
                || gameTime - state.lastHydrostaticDiagnosticTick() >= 100L);
        final Vector3d solidWeightedCentre = new Vector3d();
        final Vector3d openTopWeightedCentre = new Vector3d();
        final Vector3d airtightWeightedCentre = new Vector3d();
        final double openTopAirEfficiency = Math.clamp(
                NautilusConfig.OPEN_TOP_AIR_DISPLACEMENT_EFFICIENCY, 0.0D, 1.0D);
        final double sealedAirEfficiency = Math.clamp(
                NautilusConfig.SEALED_AIR_DISPLACEMENT_EFFICIENCY, 0.0D, 1.0D);
        final Set<UUID> openTopRooms = state.breaches().stream()
                .filter(breach -> breach.atmosphericOpening())
                .map(breach -> breach.compartmentId())
                .collect(Collectors.toUnmodifiableSet());
        // Sable 2.0.3 already integrates physical-block buoyancy in Rapier's native
        // compute_buoyancy pass. Keep total and wet geometry for the gated native-lift correction.
        double totalSolidGeometry = 0.0D;
        double submergedSolidGeometry = 0.0D;
        double submergedOpenTopAirVolume = 0.0D;
        double submergedAirtightAirVolume = 0.0D;
        double totalCompartmentCapacityVolume = 0.0D;
        double totalDryAirVolume = 0.0D;
        double maximumDragVolume = 0.0D;
        double submergedDragVolume = 0.0D;

        // Hull voxels are sampled directly for Sable-native lift diagnostics and continuous drag.
        final long hullPrepareStart = diagnostics == null ? 0L : System.nanoTime();
        final PlotBlockCursor hull = PlotBlockCursor.create(carrier, bounds, diagnostics != null);
        final long hullPrepareNanos = diagnostics == null ? 0L : System.nanoTime() - hullPrepareStart;
        long effectiveHullCells = 0L;
        while (hull.advance()) {
            final BlockPos pos = hull.position();
            final BlockState block = hull.blockState();
            // Captured/source fluid cells and non-colliding decorations are not physical
            // hull voxels. Nautilus never creates internal water blocks, but excluding
            // both categories keeps imported plots and torches/particles from creating
            // displacement or drag volume.
            if (block.getCollisionShape(carrier.getLevel(), pos).isEmpty()) {
                continue;
            }
            final double solidVolume = Math.max(0.0D,
                    PhysicsBlockPropertyHelper.getVolume(block));
            if (solidVolume <= 0.0D) {
                continue;
            }
            if (diagnostics != null) effectiveHullCells++;
            totalSolidGeometry += solidVolume;
            maximumDragVolume += solidVolume;
            final CompartmentWaterGeometry.ProjectedInterval interval =
                    CompartmentWaterGeometry.projectedCell(carrier, pos);
            if (diagnostics != null) diagnostics.recordProjection(false);
            final OptionalDouble outsideSurface =
                    WaterEnvironment.waterSurfaceIntersectingCell(carrier, pos, water, interval);
            if (outsideSurface.isEmpty()) {
                continue;
            }
            final double wetFraction = interval.fractionBelow(outsideSurface.getAsDouble());
            final double wetSolidGeometry = solidVolume * wetFraction;
            submergedSolidGeometry += wetSolidGeometry;
            solidWeightedCentre.fma(wetSolidGeometry, new Vector3d(
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
            submergedDragVolume += wetSolidGeometry;
        }
        if (diagnostics != null) {
            final long boundsCells = Math.max(0L, (long) bounds.maxX() - bounds.minX() + 1L)
                    * Math.max(0L, (long) bounds.maxY() - bounds.minY() + 1L)
                    * Math.max(0L, (long) bounds.maxZ() - bounds.minZ() + 1L);
            diagnostics.recordHullScan(boundsCells, hull.inspectedCells(), effectiveHullCells,
                    hull.skippedEmptySectionCells(), hull.skippedEmptySections(),
                    hull.nativeSectionReads(), hull.fallbackReads(), hullPrepareNanos);
        }

        // Only the dry part below the exterior surface provides compartment displacement.
        final CompartmentDisplacement displacement = CompartmentDisplacement.create(carrier);
        for (final CompartmentState room : state.compartments()) {
            final boolean openTop = openTopRooms.contains(room.id());
            final CompartmentDisplacement.Domain owned = displacement.domain(room);
            if (owned != null) {
                // Foreign hulls and smaller nested cavities occupy real space even though their
                // blocks are stored in another plot. Keep the saved room/stock intact, but spend
                // this room's water only in its exclusive physical displacement domain.
                totalCompartmentCapacityVolume += owned.capacity;
                totalDryAirVolume += owned.dryVolume;
                maximumDragVolume += owned.dryVolume;
                if (owned.dryVolume <= 1.0E-9D) continue;
                for (final long packed : room.waterCells()) {
                    final BlockPos pos = BlockPos.of(packed);
                    final CompartmentWaterGeometry.ProjectedInterval interval =
                            CompartmentWaterGeometry.projectedCell(carrier, pos);
                    if (diagnostics != null) diagnostics.recordProjection(false);
                    final OptionalDouble outsideSurface =
                            WaterEnvironment.waterSurfaceIntersectingCell(carrier, pos, water, interval);
                    if (outsideSurface.isEmpty()) continue;
                    final CompartmentDisplacement.Contribution contribution =
                            owned.submergedDry(pos, outsideSurface.getAsDouble());
                    if (openTop) {
                        submergedOpenTopAirVolume += contribution.volume();
                        openTopWeightedCentre.add(contribution.weightedCentre());
                    } else {
                        submergedAirtightAirVolume += contribution.volume();
                        airtightWeightedCentre.add(contribution.weightedCentre());
                    }
                    submergedDragVolume += contribution.volume();
                }
                continue;
            }
            totalCompartmentCapacityVolume += room.volumeBlocks();
            // Only Attachment water defines the gradual room-wide free surface. A real source
            // remains a local one-block mass and must never be spread over the entire compartment.
            // The dispatcher has already synchronized mass/rotationPoint. Resolve the current
            // liquid plane and intervals together, and finish before the next mass update.
            final CompartmentWaterGeometry.PreparedSurface prepared =
                    CompartmentWaterGeometry.preparedSurface(carrier, room);
            final double insideSurface = prepared.surface();
            for (final long packed : room.cells()) {
                final BlockPos pos = BlockPos.of(packed);
                // Prepared water cells exclude real sources. Keep room.cells() so those source
                // cells explicitly contribute zero dry air, and retain the original FMA order.
                final CompartmentWaterGeometry.ProjectedInterval shared =
                        prepared.interval(packed, diagnostics);
                final CompartmentWaterGeometry.ProjectedInterval interval =
                        shared == null ? CompartmentWaterGeometry.projectedCell(carrier, pos) : shared;
                if (diagnostics != null) diagnostics.recordProjection(shared != null);
                final boolean realSourceWater = internalWater.containsSource(pos);
                final double attachmentFloodedFraction = interval.fractionBelow(insideSurface);
                final double dryEnvelopeFraction = realSourceWater ? 0.0D
                        : Math.clamp(1.0D - attachmentFloodedFraction, 0.0D, 1.0D);
                totalDryAirVolume += dryEnvelopeFraction;
                maximumDragVolume += dryEnvelopeFraction;
                if (dryEnvelopeFraction <= 1.0E-9D) continue;

                final OptionalDouble outsideSurface =
                        WaterEnvironment.waterSurfaceIntersectingCell(carrier, pos, water, interval);
                if (outsideSurface.isEmpty()) {
                    continue;
                }
                final double belowOutside = interval.fractionBelow(outsideSurface.getAsDouble());
                final double displacedAir = Math.clamp(belowOutside - attachmentFloodedFraction,
                        0.0D, dryEnvelopeFraction);
                if (openTop) {
                    submergedOpenTopAirVolume += displacedAir;
                    openTopWeightedCentre.fma(displacedAir, new Vector3d(
                            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
                } else {
                    submergedAirtightAirVolume += displacedAir;
                    airtightWeightedCentre.fma(displacedAir, new Vector3d(
                            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
                }
                submergedDragVolume += displacedAir;
            }
        }

        final double dragSubmersion = Math.clamp(submergedDragVolume
                / Math.max(1.0D, maximumDragVolume), 0.0D, 1.0D);
        // The exact flooded fraction has already been removed; do not gate air lift a second time.
        final double effectiveOpenTopSubmersion = submergedOpenTopAirVolume
                * openTopAirEfficiency;
        // Sealed and side-breached compartments contribute only their remaining dry air.
        final double effectiveAirtightSubmersion = submergedAirtightAirVolume
                * sealedAirEfficiency;
        final Vector3d gravity = DimensionPhysicsData.getGravity(
                carrier.getLevel(), carrier.logicalPose().position());
        final double gravityMagnitude = gravity.length();
        final double configuredDisplacedMassPerCubicMetre =
                (NautilusConfig.WATER_DENSITY / 1000.0D)
                        * NautilusConfig.buoyancyCoefficient();
        final double openTopAirDisplacedMass = effectiveOpenTopSubmersion
                * configuredDisplacedMassPerCubicMetre;
        final double airtightAirDisplacedMass = effectiveAirtightSubmersion
                * configuredDisplacedMassPerCubicMetre;
        final double additionalAirDisplacedMass = openTopAirDisplacedMass
                + airtightAirDisplacedMass;
        // Restore release 0.1's correction: useful dry cavities approach the configured envelope;
        // pure solid structures and fully flooded rooms retain Sable's native hull lift alone.
        final double cavityFraction = totalCompartmentCapacityVolume > 1.0E-9D
                ? Math.clamp(totalCompartmentCapacityVolume
                / Math.max(1.0E-9D, totalSolidGeometry + totalCompartmentCapacityVolume),
                0.0D, 1.0D) : 0.0D;
        final double cavitySizeGate = smoothStep(cavityFraction,
                MINIMUM_USEFUL_AIR_FRACTION, FULL_AIR_EFFICIENCY_FRACTION);
        final double dryFraction = totalCompartmentCapacityVolume > 1.0E-9D
                ? Math.clamp(totalDryAirVolume / totalCompartmentCapacityVolume, 0.0D, 1.0D)
                : 0.0D;
        final double dryCavityGate = smoothStep01(dryFraction);
        final double estimatedNativeSolidEquivalentMass = gravityMagnitude > 1.0E-9D
                ? submergedSolidGeometry * SABLE_NATIVE_SOLID_BUOYANCY_PER_CUBIC_METRE
                / gravityMagnitude : 0.0D;
        final double targetSolidEquivalentMass = submergedSolidGeometry
                * configuredDisplacedMassPerCubicMetre;
        final double solidEnvelopeCorrectionMass = Math.max(0.0D,
                targetSolidEquivalentMass - estimatedNativeSolidEquivalentMass)
                * cavitySizeGate * dryCavityGate;
        final double supplementalDisplacedMass = additionalAirDisplacedMass
                + solidEnvelopeCorrectionMass;
        final Vector3d supplementalForce = new Vector3d(gravity).negate()
                .mul(supplementalDisplacedMass);
        final MassData rigidMassData = carrier.getMassTracker();
        final double rigidMass = rigidMassData.getMass();

        final double totalRoomCapacity = totalCompartmentCapacityVolume;
        final double totalRoomWaterMb = state.compartments().stream()
                .mapToDouble(room -> Math.clamp(room.waterMb(), 0.0D,
                        Math.max(0.0D, room.waterCapacityMb())))
                .sum();

        /*
         * Wake a sleeping full compartment once when hydrostatic inputs start changing. A
         * non-zero buoyancy velocity increment below already wakes the body, so the explicit zero
         * increment is needed only when no compartment force remains. CarrierState coalesces a
         * continuous flood into one wake episode instead of keeping the body awake forever.
         */
        final boolean hasTrackedHydrostaticState = !state.compartments().isEmpty()
                || totalRoomWaterMb > 1.0E-6D || state.topologyRevision() != 0L;
        final boolean shouldWake = hasTrackedHydrostaticState
                && state.shouldWakeForHydrostaticChange(
                supplementalDisplacedMass, totalRoomWaterMb);
        if (shouldWake && supplementalDisplacedMass <= 1.0E-9D) {
            body.addLinearAndAngularVelocity(new Vector3d(), new Vector3d());
        }

        if (hasTrackedHydrostaticState && diagnosticDue) {
            final double fillFraction = totalRoomCapacity > 1.0E-9D
                    ? totalRoomWaterMb / (totalRoomCapacity * 1000.0D) : 0.0D;
            final double estimatedNativeSolidForceY = submergedSolidGeometry
                    * SABLE_NATIVE_SOLID_BUOYANCY_PER_CUBIC_METRE;
            final double predictedNetVerticalAcceleration = rigidMass > 1.0E-9D
                    ? gravity.y() + (estimatedNativeSolidForceY + supplementalForce.y()) / rigidMass
                    : 0.0D;
            CreateNautilus.LOGGER.debug(
                    "Hydrostatic balance carrier {}: attachmentFill={}%, realWaterSources={}, realWaterMass={}, rigidMass={}, nativeWetSolidVolume={}, nativeSolidForceY={}, nativeSolidEquivalentMass={}, cavityFraction={}, cavitySizeGate={}, dryFraction={}, dryCavityGate={}, openAirMass={}, airtightAirMass={}, solidCorrectionMass={}, supplementalMass={}, predictedNetAccelerationY={}, predictionScope=gravityNativeHullAndNautilusOnly",
                    state.carrierId(), fillFraction * 100.0D,
                    internalWater.sourceCount(), internalWater.totalNativeMass(), rigidMass,
                    submergedSolidGeometry, estimatedNativeSolidForceY,
                    estimatedNativeSolidEquivalentMass, cavityFraction, cavitySizeGate,
                    dryFraction, dryCavityGate, openTopAirDisplacedMass,
                    airtightAirDisplacedMass,
                    solidEnvelopeCorrectionMass, supplementalDisplacedMass,
                    predictedNetVerticalAcceleration);
            state.setLastHydrostaticDiagnosticTick(gameTime);
        }

        // A zero buoyancy coefficient (or zero gravity) must not fall through to the velocity API:
        // addLinearAndAngularVelocity wakes even when both vectors are zero. The transition wake
        // above is deliberately coalesced; calling the main path here would wake every sub-step.
        if (supplementalDisplacedMass <= 1.0E-9D
                || supplementalForce.lengthSquared() <= 1.0E-18D) {
            return new Result(dragSubmersion, Vec3.ZERO);
        }

        final Vector3d supplementalWeightedCentre = new Vector3d();
        if (submergedSolidGeometry > 1.0E-9D && solidEnvelopeCorrectionMass > 1.0E-9D) {
            supplementalWeightedCentre.fma(solidEnvelopeCorrectionMass / submergedSolidGeometry,
                    solidWeightedCentre);
        }
        supplementalWeightedCentre.fma(
                openTopAirEfficiency * configuredDisplacedMassPerCubicMetre,
                openTopWeightedCentre);
        supplementalWeightedCentre.fma(
                sealedAirEfficiency * configuredDisplacedMassPerCubicMetre,
                airtightWeightedCentre);
        final Vector3d centreOfBuoyancy = supplementalWeightedCentre
                .div(supplementalDisplacedMass);
        final Vector3d centreOfMass = rigidMassData.getCenterOfMass() == null
                ? null : new Vector3d(rigidMassData.getCenterOfMass());
        if (!(rigidMass > 1.0E-9D) || centreOfMass == null) {
            return new Result(dragSubmersion,
                    new Vec3(supplementalForce.x(), supplementalForce.y(),
                            supplementalForce.z()));
        }

        /*
         * Apply the hydrostatic result as acceleration, not as a synthetic-mass impulse.
         *
         * Compartment water is stored in Nautilus attachments and mirrored into Sable's mass
         * tracker. The tracker is authoritative for gameplay mass, while an impulse is converted
         * by the physics backend's current rigid-body state and may be ignored by a sleeping body
         * when an otherwise constant queued force does not request a wake-up. Deriving acceleration
         * here defensively keeps hydrostatics tied to the current merged Sable mass even during a
         * tracker rebuild or a temporary backend-state divergence.
         *
         * Archimedes' law is equivalently a = g * displacedMass / rigidMass.  Adding that exact
         * velocity change through RigidBodyHandle keeps the result tied to Sable's current mass,
         * wakes a sleeping body, and is independent of backend impulse-to-mass conversion. This is
         * not a sink/float override: gravity remains Sable's native gravity and the ordinary mass
         * ratio alone decides the sign of the net acceleration.
         */
        final Vector3d worldVelocityDelta = new Vector3d(supplementalForce)
                .mul(timeStep / rigidMass);
        final Vector3d localImpulseForTorque = carrier.logicalPose().transformNormalInverse(
                new Vector3d(supplementalForce).mul(timeStep));
        final Vector3d localAngularVelocityDelta = new Vector3d(centreOfBuoyancy)
                .sub(centreOfMass)
                .cross(localImpulseForTorque, new Vector3d());
        rigidMassData.getInverseInertiaTensor().transform(localAngularVelocityDelta);
        final Vector3d worldAngularVelocityDelta = carrier.logicalPose()
                .transformNormal(localAngularVelocityDelta);
        body.addLinearAndAngularVelocity(worldVelocityDelta, worldAngularVelocityDelta);

        // Keep Aeronautics' force diagram/API diagnostics intact without applying the same lift a
        // second time.  The recorded point force is the local hydrostatic impulse for this substep.
        final QueuedForceGroup levitation = carrier.getOrCreateQueuedForceGroup(
                ForceGroups.LEVITATION.get());
        levitation.recordPointForce(centreOfBuoyancy, localImpulseForTorque);

        return new Result(dragSubmersion,
                new Vec3(supplementalForce.x(), supplementalForce.y(),
                        supplementalForce.z()));
    }

    private static double smoothStep(final double value, final double lower, final double upper) {
        if (upper <= lower + 1.0E-9D) return value >= upper ? 1.0D : 0.0D;
        return smoothStep01((value - lower) / (upper - lower));
    }

    private static double smoothStep01(final double value) {
        final double clamped = Math.clamp(value, 0.0D, 1.0D);
        return clamped * clamped * (3.0D - 2.0D * clamped);
    }

    /** Nautilus's extra dry-air lift and gated correction; excludes the native hull lift. */
    public record Result(double submersion, Vec3 supplementalForce) {
        static final Result EMPTY = new Result(0.0D, Vec3.ZERO);

        /**
         * @deprecated use {@link #supplementalForce()}; this value never represented total lift.
         */
        @Deprecated
        public Vec3 force() {
            return supplementalForce;
        }
    }

}
