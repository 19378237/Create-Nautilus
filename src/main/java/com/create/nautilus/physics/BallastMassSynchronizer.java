package com.create.nautilus.physics;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors virtual compartment water and real source-water blocks into Sable's mass tracker.
 *
 * <p>The two ledgers are intentionally independent. Attachment water remains one continuous mass
 * per compartment. Every real source-water block is injected at its own block position, so a
 * bucket never moves the virtual free surface and its centre of mass cannot jump to a rounded
 * room-wide average.</p>
 */
public final class BallastMassSynchronizer {
    private BallastMassSynchronizer() {
    }

    public static void tick(final SubLevelPhysicsSystem system,
                            final ServerSubLevel carrier,
                            final CarrierState state,
                            final CompartmentFluidInventory.Snapshot realWater) {
        final Object currentTracker = carrier.getSelfMassTracker();
        final boolean trackerWasReplaced = state.appliedWaterMassTracker() != currentTracker;
        if (trackerWasReplaced) {
            // The replacement tracker contains plot blocks only. Runtime-injected masses belonged
            // to the old tracker, so clear both ledgers and inject everything into the new owner.
            state.appliedWaterMasses().clear();
            state.appliedRealWaterMasses().clear();
            state.setAppliedWaterMassTracker(currentTracker);
        }

        boolean changed = syncAttachmentWater(carrier, state);
        changed |= syncRealSourceWater(carrier, state, realWater);
        if (changed) {
            carrier.updateMergedMassData(1.0F);
            // Upload mass, COM and inertia to the active Rapier body; updating only the Java-side
            // tracker would change goggles/API readouts without changing actual motion.
            system.getPipeline().onStatsChanged(carrier);
        }

        final double attachmentNativeMass = state.compartments().stream()
                .mapToDouble(room -> clampedAttachmentWaterMb(room) / 1000.0D
                        * (NautilusConfig.WATER_DENSITY / 1000.0D))
                .sum();
        final long gameTime = carrier.getLevel().getGameTime();
        if ((attachmentNativeMass > 1.0E-6D || realWater.totalNativeMass() > 1.0E-6D)
                && (state.lastBallastDiagnosticTick() == Long.MIN_VALUE
                || gameTime - state.lastBallastDiagnosticTick() >= 100L)) {
            CreateNautilus.LOGGER.debug(
                    "Ballast mass carrier {}: attachmentMass={}, realWaterSources={}, "
                            + "realWaterMass={}, self={}, merged={}, trackerReplaced={}",
                    state.carrierId(), attachmentNativeMass, realWater.sourceCount(),
                    realWater.totalNativeMass(), carrier.getSelfMassTracker().getMass(),
                    carrier.getMassTracker().getMass(), trackerWasReplaced);
            state.setLastBallastDiagnosticTick(gameTime);
        }
    }

    /** Keeps the existing continuous Attachment ballast model unchanged. */
    private static boolean syncAttachmentWater(final ServerSubLevel carrier,
                                               final CarrierState state) {
        final Map<UUID, CarrierState.AppliedWaterMass> next = new HashMap<>();
        final Map<UUID, CarrierState.AppliedWaterMass> remaining =
                new HashMap<>(state.appliedWaterMasses());
        boolean changed = false;
        for (final CompartmentState room : state.compartments()) {
            final double mass = clampedAttachmentWaterMb(room) / 1000.0D
                    * (NautilusConfig.WATER_DENSITY / 1000.0D);
            final BlockPos center = virtualWaterMassCentre(carrier, room);
            // Remove this room from the stale-entry ledger even when its new water mass is zero.
            // Otherwise the cleanup pass would subtract an emptied room a second time.
            final CarrierState.AppliedWaterMass old = remaining.remove(room.id());
            if (old != null && mass > 0.0D && old.position().equals(center)
                    && Math.abs(old.kilograms() - mass) <= 1.0E-6D) {
                // Retain the actually injected amount, so small deltas accumulate toward the
                // next update rather than silently changing the ledger without changing Sable.
                next.put(room.id(), old);
                continue;
            }
            if (old != null) {
                carrier.getSelfMassTracker().addBlockMass(carrier.getLevel(),
                        Blocks.WATER.defaultBlockState(), old.position(), -old.kilograms(), null);
                changed = true;
            }
            if (mass > 0.0D) {
                carrier.getSelfMassTracker().addBlockMass(carrier.getLevel(),
                        Blocks.WATER.defaultBlockState(), center, mass, null);
                next.put(room.id(), new CarrierState.AppliedWaterMass(center, mass));
                changed = true;
            }
        }
        for (final CarrierState.AppliedWaterMass old : remaining.values()) {
            carrier.getSelfMassTracker().addBlockMass(carrier.getLevel(),
                    Blocks.WATER.defaultBlockState(), old.position(), -old.kilograms(), null);
            changed = true;
        }
        state.appliedWaterMasses().clear();
        state.appliedWaterMasses().putAll(next);
        return changed;
    }

    /** Injects one independent mass at every real source-water block. */
    private static boolean syncRealSourceWater(final ServerSubLevel carrier,
                                               final CarrierState state,
                                               final CompartmentFluidInventory.Snapshot measured) {
        final Map<Long, Double> next = new HashMap<>();
        boolean changed = false;
        for (final Map.Entry<Long, CompartmentFluidInventory.SourceWater> entry
                : measured.sources().entrySet()) {
            final long packed = entry.getKey();
            final CompartmentFluidInventory.SourceWater source = entry.getValue();
            final double mass = source.nativeMass();
            final double old = state.appliedRealWaterMasses().getOrDefault(packed, 0.0D);
            final double delta = mass - old;
            if (Math.abs(delta) > 1.0E-6D) {
                carrier.getSelfMassTracker().addBlockMass(carrier.getLevel(),
                        Blocks.WATER.defaultBlockState(), source.position(), delta, null);
                changed = true;
            }
            if (mass > 0.0D) {
                next.put(packed, mass);
            }
        }
        for (final Map.Entry<Long, Double> old : state.appliedRealWaterMasses().entrySet()) {
            if (next.containsKey(old.getKey())) {
                continue;
            }
            carrier.getSelfMassTracker().addBlockMass(carrier.getLevel(),
                    Blocks.WATER.defaultBlockState(), BlockPos.of(old.getKey()),
                    -old.getValue(), null);
            changed = true;
        }
        state.appliedRealWaterMasses().clear();
        state.appliedRealWaterMasses().putAll(next);
        return changed;
    }

    private static double clampedAttachmentWaterMb(final CompartmentState room) {
        return Math.clamp(room.waterMb(), 0.0D, Math.max(0.0D, room.waterCapacityMb()));
    }

    /** Centroid of only the continuous Attachment water represented by its horizontal plane. */
    private static BlockPos virtualWaterMassCentre(final ServerSubLevel carrier,
                                                   final CompartmentState room) {
        if (clampedAttachmentWaterMb(room) <= 0.0D || room.cells().isEmpty()) {
            return BlockPos.containing(room.bounds().getCenter());
        }
        final double surface = CompartmentWaterGeometry.surfaceY(carrier, room);
        double weight = 0.0D;
        final Vector3d weighted = new Vector3d();
        for (final long packed : room.cells()) {
            final BlockPos cell = BlockPos.of(packed);
            final double flooded = CompartmentWaterGeometry.fractionBelowSurface(
                    carrier, cell, surface);
            if (flooded <= 1.0E-9D) {
                continue;
            }
            weight += flooded;
            weighted.fma(flooded, new Vector3d(
                    cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D));
        }
        if (weight <= 1.0E-9D) {
            return BlockPos.containing(room.bounds().getCenter());
        }
        weighted.div(weight);
        return BlockPos.containing(weighted.x, weighted.y, weighted.z);
    }
}
