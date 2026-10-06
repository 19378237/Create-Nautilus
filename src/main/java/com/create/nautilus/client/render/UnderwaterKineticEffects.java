package com.create.nautilus.client.render;

import com.create.nautilus.kinetics.PropellerEfficiency;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Client wake effect restricted to Aeronautics propellers.
 *
 * <p>Ordinary shafts, gears and other Create machinery deliberately receive
 * no tint, overlay or ambient bubbles.</p>
 */
public final class UnderwaterKineticEffects {
    private UnderwaterKineticEffects() {
    }

    public static void tick(final KineticBlockEntity blockEntity) {
        if (!PropellerEfficiency.isAeronauticsPropeller(blockEntity)
                || blockEntity.getLevel() == null
                || !blockEntity.getLevel().isClientSide
                || Math.abs(blockEntity.getSpeed()) <= 1.0F) {
            return;
        }
        final SubLevel containing = Sable.HELPER.getContaining(
                blockEntity.getLevel(), blockEntity.getBlockPos());
        if (!(containing instanceof final ClientSubLevel carrier)) {
            return;
        }
        final double submerged = localSubmersion(carrier, blockEntity.getBlockPos());
        if (submerged <= 0.0D) {
            return;
        }
        final Vector3d center = carrier.renderPose().transformPosition(
                new Vector3d(blockEntity.getBlockPos().getX() + 0.5D,
                        blockEntity.getBlockPos().getY() + 0.5D,
                        blockEntity.getBlockPos().getZ() + 0.5D));
        spawnPropellerWake(blockEntity, carrier, center, submerged);
    }

    private static void spawnPropellerWake(final KineticBlockEntity blockEntity,
                                           final ClientSubLevel carrier,
                                           final Vector3d center,
                                           final double submerged) {
        Direction facing = Direction.SOUTH;
        if (blockEntity.getBlockState().hasProperty(BlockStateProperties.FACING)) {
            facing = blockEntity.getBlockState().getValue(BlockStateProperties.FACING);
        }
        final Vector3d direction = carrier.renderPose().transformNormal(
                new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
        final int count = Math.clamp((int) Math.ceil(
                Math.abs(blockEntity.getSpeed()) / 64.0D * submerged), 1, 6);
        for (int index = 0; index < count; index++) {
            final double spread = 0.18D;
            final double x = center.x - direction.x * 0.55D
                    + (carrier.getLevel().random.nextDouble() - 0.5D) * spread;
            final double y = center.y - direction.y * 0.55D
                    + (carrier.getLevel().random.nextDouble() - 0.5D) * spread;
            final double z = center.z - direction.z * 0.55D
                    + (carrier.getLevel().random.nextDouble() - 0.5D) * spread;
            carrier.getLevel().addParticle(index % 3 == 0
                            ? ParticleTypes.BUBBLE_POP : ParticleTypes.BUBBLE,
                    x, y, z,
                    -direction.x * 0.08D,
                    0.015D - direction.y * 0.08D,
                    -direction.z * 0.08D);
        }
    }

    private static double localSubmersion(final ClientSubLevel carrier,
                                          final BlockPos localCenter) {
        int wet = isWet(carrier, localCenter) ? 1 : 0;
        for (final Direction direction : Direction.values()) {
            wet += isWet(carrier, localCenter.relative(direction)) ? 1 : 0;
        }
        return wet / 7.0D;
    }

    private static boolean isWet(final ClientSubLevel carrier, final BlockPos local) {
        final Vector3d world = carrier.renderPose().transformPosition(
                new Vector3d(local.getX() + 0.5D,
                        local.getY() + 0.5D,
                        local.getZ() + 0.5D));
        return !isOccluded(carrier.getLevel(), world)
                && carrier.getLevel().getFluidState(BlockPos.containing(world.x, world.y, world.z))
                .is(FluidTags.WATER);
    }

    private static boolean isOccluded(final Level level, final Vector3d point) {
        final WaterOcclusionContainer<?> container = WaterOcclusionContainer.getContainer(level);
        return container != null && container.isOccluded(new Vec3(point.x, point.y, point.z));
    }
}
