package com.create.nautilus.client.render;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native eye-water and near-plane media, with the parent ocean masked separately by the caller. */
public final class CameraNativeMedium {
    private CameraNativeMedium() {
    }

    /** Matches Camera's eye-cell WATER test, including its strict height comparison. */
    public static boolean isEyeInParentWater(final Camera camera, final Level level) {
        return level != null && camera.isInitialized() && waterAt(level, camera.getPosition());
    }

    /**
     * Samples after the caller has accepted or masked parent eye-water. Parent near-plane media
     * retain their native priority; each plot then checks real eye-water before its near plane,
     * matching Sable's local-fluid sampler. Virtual water is applied only after this returns NONE.
     */
    public static FogType sample(final Camera camera, final Level level) {
        if (level == null || !camera.isInitialized()) return FogType.NONE;
        final Camera.NearPlane nearPlane = camera.getNearPlane();
        final Vec3 eye = camera.getPosition();
        final Vec3[] samples = {
                eye.add(nearPlane.getPointOnPlane(0.0F, 0.0F)),
                eye.add(nearPlane.getTopLeft()), eye.add(nearPlane.getTopRight()),
                eye.add(nearPlane.getBottomLeft()), eye.add(nearPlane.getBottomRight())
        };
        double minX = eye.x, minY = eye.y, minZ = eye.z;
        double maxX = eye.x, maxY = eye.y, maxZ = eye.z;
        for (final Vec3 point : samples) {
            final FogType medium = sampleAt(level, point);
            if (medium != FogType.NONE) return medium;
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
        }
        // Include the eye as well as its near plane: the plane may already be outside a wet plot.
        final BoundingBox3d bounds = new BoundingBox3d(
                new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(1.0E-6D));
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(level, bounds)) {
            if (!(candidate instanceof ClientSubLevel carrier)) continue;
            if (waterAt(carrier.getLevel(), carrier.renderPose().transformPositionInverse(eye))) {
                return FogType.WATER;
            }
            for (final Vec3 point : samples) {
                final FogType medium = sampleAt(carrier.getLevel(),
                        carrier.renderPose().transformPositionInverse(point));
                if (medium != FogType.NONE) return medium;
            }
        }
        return FogType.NONE;
    }

    private static boolean waterAt(final BlockGetter level, final Vec3 point) {
        final BlockPos cell = BlockPos.containing(point);
        final FluidState fluid = level.getFluidState(cell);
        return fluid.is(FluidTags.WATER)
                && point.y < (double) ((float) cell.getY() + fluid.getHeight(level, cell));
    }

    private static FogType sampleAt(final BlockGetter level, final Vec3 point) {
        final BlockPos cell = BlockPos.containing(point);
        final FluidState fluid = level.getFluidState(cell);
        if (fluid.is(FluidTags.LAVA)) {
            if (point.y <= (double) (fluid.getHeight(level, cell) + (float) cell.getY())) {
                return FogType.LAVA;
            }
        } else if (level.getBlockState(cell).is(Blocks.POWDER_SNOW)) {
            return FogType.POWDER_SNOW;
        }
        return FogType.NONE;
    }
}
