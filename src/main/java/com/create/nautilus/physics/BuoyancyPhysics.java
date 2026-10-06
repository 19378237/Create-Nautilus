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
 * Computes the submerged part of the carrier's closed displacement envelope.
 *
 * <p>Sable's Rapier backend is authoritative for physical hull-voxel displacement, total mass,
 * gravity and centre of mass. Nautilus adds the displacement of air retained inside a tracked
 * compartment and, only while a useful dry cavity exists, the small difference between the target
 * envelope displacement and Sable's native solid-voxel lift. Exterior flood-fill space is absent
 * from that envelope, and a breached room loses the supplement continuously as its stored water
 * rises.</p>
 */
public final class BuoyancyPhysics {
    /**
     * Sable 2.0.3's Rapier backend applies this world-up force for every cubic metre of a
     * physical carrier block overlapping parent-level water. Nautilus subtracts this known native
     * contribution from its target envelope before applying a supplement, so the solid geometry is
     * never counted twice.
     */
    private static final double SABLE_NATIVE_SOLID_BUOYANCY_PER_CUBIC_METRE = 10.5D;

    private BuoyancyPhysics() {
    }

    public static Result tick(final ServerSubLevel carrier, final RigidBodyHandle body,
                              final CarrierState state,
                              final CompartmentFluidInventory.Snapshot internalWater,
                              final double timeStep) {
        final BoundingBox3ic bounds = carrier.getPlot().getBoundingBox();
        if (bounds == null || !(timeStep > 0.0D)) {
            return Result.EMPTY;
        }

        final WaterEnvironment.QueryContext water =
                new WaterEnvironment.QueryContext(carrier.getLevel());
        final Vector3d solidWeightedCentre = new Vector3d();
        final Vector3d openTopWeightedCentre = new Vector3d();
        final Vector3d airtightWeightedCentre = new Vector3d();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final double openTopAirEfficiency = Math.clamp(
                NautilusConfig.OPEN_TOP_AIR_DISPLACEMENT_EFFICIENCY, 0.0D, 1.0D);
        final double sealedAirEfficiency = Math.clamp(
                NautilusConfig.SEALED_AIR_DISPLACEMENT_EFFICIENCY, 0.0D, 1.0D);
        final Set<UUID> openTopRooms = state.breaches().stream()
                .filter(breach -> breach.atmosphericOpening())
                .map(breach -> breach.compartmentId())
                .collect(Collectors.toUnmodifiableSet());
        // Sable 2.0.3 already integrates physical-block buoyancy in Rapier's native
        // compute_buoyancy pass. Keep both total and wet geometry so the envelope correction below
        // can subtract that native contribution instead of blindly applying the hull a second time.
        double totalSolidGeometry = 0.0D;
        double submergedSolidGeometry = 0.0D;
        double submergedOpenTopAirVolume = 0.0D;
        double submergedAirtightAirVolume = 0.0D;
        double totalCompartmentCapacityVolume = 0.0D;
        double totalDryAirVolume = 0.0D;
        double maximumDragVolume = 0.0D;
        double submergedDragVolume = 0.0D;

        // Hull voxels are sampled directly for Sable-native lift diagnostics and continuous drag.
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    final BlockPos pos = cursor.set(x, y, z);
                    final BlockState block = carrier.getLevel().getBlockState(pos);
                    if (block.isAir()) {
                        continue;
                    }
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
                    totalSolidGeometry += solidVolume;
                    maximumDragVolume += solidVolume;
                    final OptionalDouble outsideSurface =
                            WaterEnvironment.waterSurfaceIntersectingCell(carrier, pos, water);
                    if (outsideSurface.isEmpty()) {
                        continue;
                    }
                    final double wetFraction = CompartmentWaterGeometry.fractionBelowSurface(
                            carrier, pos, outsideSurface.getAsDouble());
                    final double wetSolidGeometry = solidVolume * wetFraction;
                    submergedSolidGeometry += wetSolidGeometry;
                    solidWeightedCentre.fma(wetSolidGeometry, new Vector3d(
                            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
                    submergedDragVolume += wetSolidGeometry;
                }
            }
        }

        // Only tracked dry compartment air supplements Sable's native solid displacement. Whether
        // that air is sealed or retained by an upward opening selects its configured efficiency.
        for (final CompartmentState room : state.compartments()) {
            final boolean openTop = openTopRooms.contains(room.id());
            totalCompartmentCapacityVolume += room.volumeBlocks();
            // Only Attachment water defines the gradual room-wide free surface. A real source
            // remains a local one-block mass and must never be spread over the entire compartment.
            final double insideSurface = CompartmentWaterGeometry.surfaceY(carrier, room);
            for (final long packed : room.cells()) {
                final BlockPos pos = BlockPos.of(packed);
                final boolean realSourceWater = internalWater.containsSource(pos);
                final double attachmentFloodedFraction =
                        CompartmentWaterGeometry.fractionBelowSurface(
                                carrier, pos, insideSurface);
                // Use the union of both representations, not their sum. A real source occupies
                // this complete cell; Attachment water continues to occupy only its continuous
                // fraction in all other cells.
                final double dryEnvelopeFraction = realSourceWater ? 0.0D
                        : Math.clamp(1.0D - attachmentFloodedFraction, 0.0D, 1.0D);
                totalDryAirVolume += dryEnvelopeFraction;
                maximumDragVolume += dryEnvelopeFraction;
                if (dryEnvelopeFraction <= 1.0E-9D) {
                    continue;
                }

                final OptionalDouble outsideSurface =
                        WaterEnvironment.waterSurfaceIntersectingCell(carrier, pos, water);
                if (outsideSurface.isEmpty()) {
                    continue;
                }
                final double belowOutside = CompartmentWaterGeometry.fractionBelowSurface(
                        carrier, pos, outsideSurface.getAsDouble());
                // Effective air displacement is precisely the part below the outside sea plane
                // and above the inside flood plane: envelope - exterior-open - internal water.
                final double displacedAir = Math.clamp(
                        belowOutside - attachmentFloodedFraction,
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
        // `displacedAir` has already removed every flooded fraction. Multiplying open vessels by a
        // second dry-fraction retention curve made flooding reduce lift twice and was the reason a
        // large, mostly dry basin could barely rise. Free-surface instability belongs in the
        // righting moment/flood-flow model; vertical Archimedean displacement remains linear here.
        final double effectiveOpenTopSubmersion = submergedOpenTopAirVolume
                * openTopAirEfficiency;
        // A sealed or side-breached compartment's remaining trapped air contributes only its own
        // exact dry volume. No geometry/mass gate can promote that local air into whole-hull lift.
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

        /*
         * Target-envelope minus native-solid correction.
         *
         * At default Overworld gravity Sable's 10.5 N/m^3 is equivalent to roughly 0.955 native
         * mass units per submerged cubic metre, while the configured water target is 1.0. The
         * difference is small but matters for a large hull. It is enabled gradually only when the
         * carrier actually contains a useful dry cavity. Consequently a pure solid structure and
         * a completely flooded compartment keep Sable's original solid physics, while a real dry
         * envelope reaches its configured total displacement without duplicating the native lift.
         */
        final double cavityFraction = totalCompartmentCapacityVolume > 1.0E-9D
                ? Math.clamp(totalCompartmentCapacityVolume
                / Math.max(1.0E-9D, totalSolidGeometry + totalCompartmentCapacityVolume),
                0.0D, 1.0D) : 0.0D;
        final double cavitySizeGate = smoothStep(cavityFraction,
                NautilusConfig.MINIMUM_USEFUL_AIR_FRACTION,
                NautilusConfig.FULL_AIR_EFFICIENCY_FRACTION);
        final double dryFraction = totalCompartmentCapacityVolume > 1.0E-9D
                ? Math.clamp(totalDryAirVolume / totalCompartmentCapacityVolume, 0.0D, 1.0D)
                : 0.0D;
        final double dryCavityGate = smoothStep01(dryFraction);
        final double estimatedNativeSolidEquivalentMass = gravityMagnitude > 1.0E-9D
                ? submergedSolidGeometry
                * SABLE_NATIVE_SOLID_BUOYANCY_PER_CUBIC_METRE / gravityMagnitude : 0.0D;
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
        final double fillFraction = totalRoomCapacity > 1.0E-9D
                ? totalRoomWaterMb / (totalRoomCapacity * 1000.0D) : 0.0D;

        /*
         * Wake a sleeping full compartment once when hydrostatic inputs start changing. A
         * non-zero air velocity increment below already wakes the body, so the explicit zero
         * increment is needed only when no retained-air force remains. CarrierState coalesces a
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

        final double estimatedNativeSolidForceY = submergedSolidGeometry
                * SABLE_NATIVE_SOLID_BUOYANCY_PER_CUBIC_METRE;
        final double predictedNetVerticalAcceleration = rigidMass > 1.0E-9D
                ? gravity.y() + (estimatedNativeSolidForceY + supplementalForce.y()) / rigidMass
                : 0.0D;

        final long gameTime = carrier.getLevel().getGameTime();
        if (hasTrackedHydrostaticState
                && (state.lastHydrostaticDiagnosticTick() == Long.MIN_VALUE
                || gameTime - state.lastHydrostaticDiagnosticTick() >= 100L)) {
            CreateNautilus.LOGGER.info(
                    "Hydrostatic balance carrier {}: attachmentFill={}%, realWaterSources={}, realWaterMass={}, rigidMass={}, nativeWetSolidVolume={}, nativeSolidForceY={}, nativeSolidEquivalentMass={}, cavityFraction={}, cavitySizeGate={}, dryFraction={}, dryCavityGate={}, openAirMass={}, airtightAirMass={}, solidCorrectionMass={}, supplementalMass={}, predictedNetAccelerationY={}",
                    state.carrierId(), fillFraction * 100.0D,
                    internalWater.sourceCount(), internalWater.totalNativeMass(), rigidMass,
                    submergedSolidGeometry, estimatedNativeSolidForceY,
                    estimatedNativeSolidEquivalentMass, cavityFraction, cavitySizeGate,
                    dryFraction, dryCavityGate, openTopAirDisplacedMass,
                    airtightAirDisplacedMass, solidEnvelopeCorrectionMass,
                    supplementalDisplacedMass, predictedNetVerticalAcceleration);
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
            supplementalWeightedCentre.fma(
                    solidEnvelopeCorrectionMass / submergedSolidGeometry,
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

    private static double smoothStep(final double value, final double lower,
                                     final double upper) {
        if (upper <= lower + 1.0E-9D) {
            return value >= upper ? 1.0D : 0.0D;
        }
        return smoothStep01((value - lower) / (upper - lower));
    }

    private static double smoothStep01(final double value) {
        final double clamped = Math.clamp(value, 0.0D, 1.0D);
        return clamped * clamped * (3.0D - 2.0D * clamped);
    }

    /** Nautilus's extra hydrostatic contribution; Sable's native solid lift is not included. */
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
