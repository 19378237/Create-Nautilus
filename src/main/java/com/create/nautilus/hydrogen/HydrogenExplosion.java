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

    private static final ThreadLocal<Set<String>> ACTIVE =
            ThreadLocal.withInitial(HashSet::new);

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
        if (tank.getLevel() == null || tank.getLevel().isClientSide) {
            return;
        }

        final SubLevel containing = Sable.HELPER.getContaining(tank);
        final Level owningLevel = containing == null ? tank.getLevel() : containing.getLevel();
        if (!(owningLevel instanceof ServerLevel level)) {
            return;
        }

        final BlockPos controllerPos = tank.getController();
        final String key = level.dimension().location() + ":"
                + System.identityHashCode(containing) + ":" + controllerPos.asLong();
        if (!ACTIVE.get().add(key)) {
            return;
        }
        try {
            // Capture the complete Create structure before draining or
            // breaking anything; either operation can immediately split the
            // multiblock and invalidate controller dimensions.
            final List<BlockPos> tankSegments = tank.multiblockPositions();
            final int capacity = Math.max(1, tank.totalCapacity());
            final FluidStack released = tank.drainAllForRupture();

            // Explosion and mining events already remove their target block.
            // A Sable collision callback does not, so a collision rupture must
            // explicitly break the impacted tank segment, including an empty
            // tank (which then produces no blast).
            if ((trigger == Trigger.CARRIER_IMPACT
                    || (trigger == Trigger.EXTERNAL_EXPLOSION && containing != null))
                    && tank.getLevel() != null) {
                tank.getLevel().destroyBlock(tank.getBlockPos(), false);
            }

            // Empty tanks and oxygen tanks are mechanically broken by the
            // original event but never produce a hydrogen blast.
            if (released.isEmpty() || !HydrogenFluids.isHydrogen(released)) {
                return;
            }

            // A hydrogen-filled multiblock is one pressure vessel. Explicitly
            // rupture every validated segment before creating the blast. This
            // is required in an ordinary ServerLevel, where a BreakEvent only
            // removes the selected block and Create otherwise leaves the rest
            // of the tank standing. Empty and oxygen tanks retain the original
            // single-block destruction behaviour above.
            if (tank.getLevel() != null) {
                for (final BlockPos segment : tankSegments) {
                    tank.getLevel().destroyBlock(segment, false);
                }
            }

            final double fill = Math.clamp(released.getAmount() / (double) capacity,
                    0.0D, 1.0D);
            final boolean compressed = released.getFluid().isSame(
                    HydrogenFluids.COMPRESSED_HYDROGEN_GAS.get());
            final float radius = (float) ((compressed ? 4.0D : 2.0D) + fill);
            // Use the segment that was actually hit/broken. The controller is
            // Create's lower south-west corner and caused every ordinary-world
            // multiblock explosion to appear there.
            Vec3 center = Vec3.atCenterOf(tank.getBlockPos());
            if (containing != null) {
                center = containing.logicalPose().transformPosition(center);
            }
            final double x = center.x;
            final double y = center.y;
            final double z = center.z;

            // Vanilla handles damage, knockback, block destruction and the
            // standard flash/sound. fire=true supplies the specified ignition
            // effect without creating a separate fire-triggered tank path.
            level.explode(null, x, y, z, radius, true,
                    Level.ExplosionInteraction.BLOCK);
            level.sendParticles(ParticleTypes.FLASH, x, y, z,
                    2, 0.1D, 0.1D, 0.1D, 0.0D);
            level.sendParticles(ParticleTypes.FLAME, x, y, z,
                    compressed ? 48 : 24, radius * 0.25D, radius * 0.25D,
                    radius * 0.25D, 0.08D);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z,
                    compressed ? 64 : 32, radius * 0.35D, radius * 0.35D,
                    radius * 0.35D, 0.04D);
        } finally {
            ACTIVE.get().remove(key);
        }
    }
}
