package com.create.nautilus.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

/**
 * Client-side external water-surface query for the porthole underwater fog.
 *
 * <p>The composite shader needs one scalar: the world Y of the water surface above/around the
 * dry hull. The column at the camera position is scanned from the top of the world downwards;
 * inside a sealed hull the column still passes through the roof into the outside world, so the
 * first water found is the external surface the hull is immersed in (ocean, lake or cave pool).
 * When no water exists in the column the uniform is left at an impossible value and the shader
 * disables the porthole effect for this frame.</p>
 */
public final class ClientWaterSurfaceProbe {
    /** Sentinel used by the injected shader: {@code > -1.0E8} means a real water surface. */
    public static final float NO_WATER = -1.0E9F;

    private static long lastScanTick = Long.MIN_VALUE;
    private static int cachedScanX = Integer.MIN_VALUE;
    private static int cachedScanZ = Integer.MIN_VALUE;
    private static float cachedSurfaceY = NO_WATER;
    private static boolean valueLogged;

    private ClientWaterSurfaceProbe() {
    }

    /**
     * Returns the world Y of the topmost external water surface around the dry hull, or
     * {@link #NO_WATER}. The result is cached per game tick and per scan column.
     */
    public static float currentSurfaceY() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return NO_WATER;
        }
        final long tick = minecraft.level.getGameTime();
        final BlockPos camera = BlockPos.containing(
                minecraft.gameRenderer.getMainCamera().getPosition());
        final BlockPos scan = scanColumnOrigin(minecraft.level, camera);
        if (tick == lastScanTick && scan.getX() == cachedScanX
                && scan.getZ() == cachedScanZ) {
            return cachedSurfaceY;
        }
        lastScanTick = tick;
        cachedScanX = scan.getX();
        cachedScanZ = scan.getZ();
        cachedSurfaceY = scanColumn(minecraft.level, scan);
        if (!valueLogged) {
            valueLogged = true;
            com.create.nautilus.CreateNautilus.LOGGER.info(
                    "[water-entry] surface probe column=({},{}) surfaceY={}",
                    scan.getX(), scan.getZ(), cachedSurfaceY);
        }
        return cachedSurfaceY;
    }

    /**
     * The camera column passes straight through the moving plot, whose parent-world cells are
     * air; for a floating hull the first water in that column is below the keel, not the surface
     * the hull is immersed in. Scan horizontally outside every carrier plot instead.
     */
    private static BlockPos scanColumnOrigin(final ClientLevel level, final BlockPos camera) {
        int scanX = camera.getX();
        final int scanZ = camera.getZ();
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            int maxX = Integer.MIN_VALUE;
            int minX = Integer.MAX_VALUE;
            for (final var subLevel : container.getAllSubLevels()) {
                if (subLevel instanceof final ClientSubLevel carrier) {
                    final var bounds = carrier.getPlot().getBoundingBox();
                    if (bounds != null) {
                        maxX = Math.max(maxX, bounds.maxX());
                        minX = Math.min(minX, bounds.minX());
                    }
                }
            }
            if (minX != Integer.MAX_VALUE) {
                if (camera.getX() <= maxX + 1) {
                    scanX = maxX + 2;
                } else if (camera.getX() >= minX - 1) {
                    scanX = minX - 2;
                }
            }
        }
        return new BlockPos(scanX, camera.getY(), scanZ);
    }

    private static float scanColumn(final ClientLevel level, final BlockPos camera) {
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                camera.getX(), level.getMaxBuildHeight() - 1, camera.getZ());
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            cursor.setY(y);
            final FluidState fluid = level.getFluidState(cursor);
            if (fluid.is(FluidTags.WATER)) {
                return (float) (y + fluid.getHeight(level, cursor));
            }
        }
        return NO_WATER;
    }
}
