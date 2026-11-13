package com.create.nautilus.hydrogen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;

/**
 * The only code path capable of making stored hydrogen explode.
 *
 * <p>There is deliberately no pressure, temperature, fire or neighbouring
 * machine-failure trigger here. This makes the stated safety contract easy to
 * audit: only carrier impact, external explosion and direct destruction enter
 * this class.</p>
 */
public final class HydrogenExplosion {
    public enum Trigger {
        CARRIER_IMPACT,
        EXTERNAL_EXPLOSION,
        DIRECT_DESTRUCTION
    }

    private static final ThreadLocal<Set<TankKey>> ACTIVE =
            ThreadLocal.withInitial(HashSet::new);
    private static final int MAX_BLASTS_PER_TICK = 32;
    private static final ArrayDeque<Blast> PENDING = new ArrayDeque<>();
    private static final Set<TankKey> PENDING_KEYS = new HashSet<>();

    private HydrogenExplosion() {
    }

    public static void detonateAt(final Level level, final BlockPos pos,
                                  final Trigger trigger) {
        final BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof GasTankBlockEntity gasTank) {
            detonateTank(gasTank, trigger);
        }
    }

    public static void detonateTank(final GasTankBlockEntity tank,
                                    final Trigger trigger) {
        finishRupture(captureRupture(tank, trigger));
    }

    /** Claims the vessel's gas without destroying blocks; paired with finishRupture. */
    static Rupture captureRupture(final GasTankBlockEntity tank,
                                  final Trigger trigger) {
        if (tank.getLevel() == null || tank.getLevel().isClientSide) {
            return null;
        }

        final Level storageLevel = tank.getLevel();
        final SubLevel containing = Sable.HELPER.getContaining(tank);
        final Level owningLevel = containing == null ? storageLevel : containing.getLevel();
        if (!(owningLevel instanceof ServerLevel level)) {
            return null;
        }

        final BlockPos controllerPos = tank.getController();
        final TankKey key = new TankKey(level, containing, controllerPos.asLong());
        if (PENDING_KEYS.contains(key) || !ACTIVE.get().add(key)) {
            return null;
        }
        boolean captured = false;
        try {
            // Capture the complete Create structure before draining or
            // breaking anything; either operation can immediately split the
            // multiblock and invalidate controller dimensions.
            final List<BlockPos> tankSegments = tank.multiblockPositions();
            final int capacity = Math.max(1, tank.totalCapacity());
            final FluidStack released = tank.drainAllForRupture();
            // Use the segment that was actually hit/broken. The controller is
            // Create's lower south-west corner and caused every ordinary-world
            // multiblock explosion to appear there.
            Vec3 center = Vec3.atCenterOf(tank.getBlockPos());
            if (containing != null) {
                center = containing.logicalPose().transformPosition(center);
            }
            Blast blast = null;
            if (!released.isEmpty() && HydrogenFluids.isHydrogen(released)) {
                final double fill = Math.clamp(released.getAmount() / (double) capacity, 0.0D, 1.0D);
                final boolean compressed = released.getFluid().isSame(
                        HydrogenFluids.COMPRESSED_HYDROGEN_GAS.get());
                final float radius = (float) ((compressed ? 4.0D : 2.0D) + fill);
                blast = new Blast(key, level, center.x, center.y, center.z, radius, compressed);
            }
            final boolean destroyImpacted = trigger == Trigger.CARRIER_IMPACT
                    || (trigger == Trigger.EXTERNAL_EXPLOSION && containing != null);
            final Rupture rupture = new Rupture(key, storageLevel,
                    tank.getBlockPos().immutable(), tankSegments, destroyImpacted, blast);
            captured = true;
            return rupture;
        } finally {
            // Successful captures retain ownership across Create's onRemove.
            if (!captured) ACTIVE.get().remove(key);
        }
    }

    /** Uses only captured state, so the original block entity may already be removed. */
    static void finishRupture(final Rupture rupture) {
        if (rupture == null) return;
        try {
            final Level storageLevel = rupture.storageLevel();
            // Mining/explosion events remove their target. Sable callbacks do not.
            if (rupture.destroyImpacted()
                    && storageLevel.getBlockState(rupture.impactedPos()).getBlock() instanceof GasTankBlock) {
                storageLevel.destroyBlock(rupture.impactedPos(), false);
            }
            // Empty/oxygen vessels break only their originally impacted segment.
            if (rupture.blast() == null) return;
            for (final BlockPos segment : rupture.segments()) {
                // Preserve the committed replacement at the triggering position.
                if (storageLevel.getBlockState(segment).getBlock() instanceof GasTankBlock) {
                    storageLevel.destroyBlock(segment, false);
                }
            }
            PENDING_KEYS.add(rupture.key());
            PENDING.addLast(rupture.blast());
        } finally {
            ACTIVE.get().remove(rupture.key());
        }
    }

    record Rupture(TankKey key, Level storageLevel, BlockPos impactedPos,
                   List<BlockPos> segments, boolean destroyImpacted, Blast blast) {}

    /** Emitting a blast may enqueue neighbours, but never recurses into another explode call. */
    static void processPending() {
        for (int processed = 0; processed < MAX_BLASTS_PER_TICK && !PENDING.isEmpty(); processed++) {
            final Blast blast = PENDING.removeFirst();
            PENDING_KEYS.remove(blast.key());
            final ServerLevel level = blast.level();
            final double x = blast.x(), y = blast.y(), z = blast.z();
            final float radius = blast.radius();
            level.explode(null, x, y, z, radius, true, Level.ExplosionInteraction.BLOCK);
            level.sendParticles(ParticleTypes.FLASH, x, y, z, 2, 0.1D, 0.1D, 0.1D, 0.0D);
            level.sendParticles(ParticleTypes.FLAME, x, y, z,
                    blast.compressed() ? 48 : 24, radius * 0.25D, radius * 0.25D,
                    radius * 0.25D, 0.08D);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z,
                    blast.compressed() ? 64 : 32, radius * 0.35D, radius * 0.35D,
                    radius * 0.35D, 0.04D);
        }
    }

    static void clearLevel(final Level level) {
        PENDING.removeIf(blast -> {
            if (blast.level() != level) return false;
            PENDING_KEYS.remove(blast.key());
            return true;
        });
    }

    static void clearAll() {
        PENDING.clear();
        PENDING_KEYS.clear();
        ACTIVE.remove();
    }

    private record TankKey(Level level, SubLevel carrier, long controller) {}

    private record Blast(TankKey key, ServerLevel level, double x, double y, double z,
                         float radius, boolean compressed) {}
}
