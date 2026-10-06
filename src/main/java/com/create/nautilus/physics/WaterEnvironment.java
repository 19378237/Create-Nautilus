package com.create.nautilus.physics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/** Small, side-effect-free water queries shared by pressure, flooding and buoyancy. */
public final class WaterEnvironment {
    private WaterEnvironment() {
    }

    public static Vector3d toWorld(final ServerSubLevel carrier, final Vector3dc plotPosition) {
        return carrier.logicalPose().transformPosition(plotPosition, new Vector3d());
    }

    public static OptionalDouble waterSurfaceY(final ServerLevel level, final Vector3dc probe) {
        return new QueryContext(level).waterSurfaceY(probe);
    }

    /**
     * Finds the highest connected external-water surface intersecting a transformed plot voxel.
     * Inset corners catch partial immersion even when the voxel centre is still above water.
     */
    public static OptionalDouble waterSurfaceIntersectingCell(final ServerSubLevel carrier,
                                                               final BlockPos cell,
                                                               final QueryContext context) {
        final double worldLow = CompartmentWaterGeometry.lowestWorldY(carrier, cell);
        final double worldHigh = CompartmentWaterGeometry.highestWorldY(carrier, cell);
        final double[] coordinates = {0.0001D, 0.9999D};
        double highest = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (final double x : coordinates) {
            for (final double y : coordinates) {
                for (final double z : coordinates) {
                    final Vector3d world = toWorld(carrier, new Vector3d(
                            cell.getX() + x, cell.getY() + y, cell.getZ() + z));
                    final OptionalDouble candidate = context.waterSurfaceInVerticalBand(
                            world.x(), world.z(), worldLow, worldHigh);
                    if (candidate.isPresent()) {
                        highest = Math.max(highest, candidate.getAsDouble());
                        found = true;
                    }
                }
            }
        }
        if (!found) {
            final Vector3d centre = toWorld(carrier, new Vector3d(
                    cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D));
            final OptionalDouble candidate = context.waterSurfaceInVerticalBand(
                    centre.x(), centre.z(), worldLow, worldHigh);
            if (candidate.isPresent()) {
                highest = candidate.getAsDouble();
                found = true;
            }
        }
        return found ? OptionalDouble.of(highest) : OptionalDouble.empty();
    }

    /**
     * Finds external water touching one actual face of a transformed plot voxel.
     *
     * <p>This is used for geometry-derived open-top inlets.  The queried vertical band is the
     * face's world-Y projection, expanded by a tiny epsilon so an upright horizontal face still
     * has measurable thickness.  Consequently an opening above the sea is dry, a tilted opening
     * is partially wetted as its low edge enters the sea, and a fully submerged opening remains
     * connected to the top of the correct external water column.</p>
     */
    public static OptionalDouble waterSurfaceIntersectingFace(final ServerSubLevel carrier,
                                                               final BlockPos cell,
                                                               final Direction face,
                                                               final QueryContext context) {
        final double faceLow = CompartmentWaterGeometry.lowestFaceWorldY(carrier, cell, face);
        final double faceHigh = CompartmentWaterGeometry.highestFaceWorldY(carrier, cell, face);
        final double epsilon = 1.0E-4D;
        final double[] coordinates = {0.0001D, 0.9999D};
        double highest = Double.NEGATIVE_INFINITY;
        boolean found = false;

        for (final double first : coordinates) {
            for (final double second : coordinates) {
                final Vector3d local = faceSample(cell, face, first, second);
                final Vector3d world = toWorld(carrier, local);
                final OptionalDouble candidate = context.waterSurfaceInVerticalBand(
                        world.x(), world.z(), faceLow - epsilon, faceHigh + epsilon);
                if (candidate.isPresent()) {
                    highest = Math.max(highest, candidate.getAsDouble());
                    found = true;
                }
            }
        }
        final Vector3d centre = toWorld(carrier, faceSample(cell, face, 0.5D, 0.5D));
        final OptionalDouble centreCandidate = context.waterSurfaceInVerticalBand(
                centre.x(), centre.z(), faceLow - epsilon, faceHigh + epsilon);
        if (centreCandidate.isPresent()) {
            highest = Math.max(highest, centreCandidate.getAsDouble());
            found = true;
        }
        return found ? OptionalDouble.of(highest) : OptionalDouble.empty();
    }

    private static Vector3d faceSample(final BlockPos cell, final Direction face,
                                       final double first, final double second) {
        final double fixed = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                ? 0.0001D : 0.9999D;
        return switch (face.getAxis()) {
            case X -> new Vector3d(cell.getX() + fixed,
                    cell.getY() + first, cell.getZ() + second);
            case Y -> new Vector3d(cell.getX() + first,
                    cell.getY() + fixed, cell.getZ() + second);
            case Z -> new Vector3d(cell.getX() + first,
                    cell.getY() + second, cell.getZ() + fixed);
        };
    }

    /**
     * Per-physics-step query cache. A deep ocean consists of many full source blocks; asking
     * {@link FluidState#getHeight} only at the submerged block would incorrectly report the top of
     * that one block. We instead walk to the top of the connected vertical water column and cache
     * the resulting surface for each world X/Z column.
     */
    public static final class QueryContext {
        private static final double MIN_FLUID_OVERLAP = 1.0E-6D;
        private final ServerLevel level;
        /**
         * A world X/Z column may contain multiple disconnected bodies of water.  Cache by an
         * actual connected water voxel, not merely by column, and back-fill every traversed voxel
         * while walking to its free surface.
         */
        private final Map<Long, Double> connectedWaterSurfaces = new HashMap<>();

        public QueryContext(final ServerLevel level) {
            this.level = level;
        }

        public OptionalDouble waterSurfaceY(final Vector3dc probe) {
            final BlockPos start = BlockPos.containing(probe.x(), probe.y(), probe.z());
            final FluidState startFluid = level.getFluidState(start);
            if (!startFluid.is(FluidTags.WATER)) {
                return OptionalDouble.empty();
            }
            // A flowing/partial fluid does not occupy the air portion at the top of its block.
            // Checking only the block tag made points above a shallow layer appear submerged.
            final double occupiedTop = start.getY() + startFluid.getHeight(level, start);
            if (probe.y() >= occupiedTop - MIN_FLUID_OVERLAP) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(connectedSurface(start, startFluid));
        }

        /**
         * Finds water overlapping only the supplied world-Y projection of a transformed voxel.
         * This catches partial immersion when the original corner probe happens to lie in an air
         * block, while the strict lower bound prevents water far below the voxel from being used.
         */
        public OptionalDouble waterSurfaceInVerticalBand(final double worldX,
                                                         final double worldZ,
                                                         final double minimumWorldY,
                                                         final double maximumWorldY) {
            final double low = Math.min(minimumWorldY, maximumWorldY);
            final double high = Math.max(minimumWorldY, maximumWorldY);
            if (!Double.isFinite(low) || !Double.isFinite(high)
                    || high - low <= MIN_FLUID_OVERLAP) {
                return OptionalDouble.empty();
            }

            final int blockX = BlockPos.containing(worldX, low, worldZ).getX();
            final int blockZ = BlockPos.containing(worldX, low, worldZ).getZ();
            final int minimumY = Math.max(level.getMinBuildHeight(),
                    BlockPos.containing(worldX, low, worldZ).getY());
            final int maximumY = Math.min(level.getMaxBuildHeight() - 1,
                    BlockPos.containing(worldX, Math.nextDown(high), worldZ).getY());
            if (maximumY < minimumY) {
                return OptionalDouble.empty();
            }

            final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                    blockX, maximumY, blockZ);
            if (!level.hasChunkAt(cursor)) {
                return OptionalDouble.empty();
            }
            for (int y = maximumY; y >= minimumY; y--) {
                cursor.setY(y);
                final FluidState fluid = level.getFluidState(cursor);
                if (!fluid.is(FluidTags.WATER)) {
                    continue;
                }
                final double occupiedTop = y + fluid.getHeight(level, cursor);
                final double overlap = Math.min(high, occupiedTop) - Math.max(low, y);
                if (overlap > MIN_FLUID_OVERLAP) {
                    return OptionalDouble.of(connectedSurface(cursor.immutable(), fluid));
                }
            }
            return OptionalDouble.empty();
        }

        private double connectedSurface(final BlockPos start, final FluidState startFluid) {
            final Double cachedStart = connectedWaterSurfaces.get(start.asLong());
            if (cachedStart != null) {
                return cachedStart;
            }

            /*
             * A falling/flowing WATER-tagged column is not a hydrostatic reservoir whose pressure
             * head reaches the source block at the top. The previous unconditional upward walk
             * made touching the bottom of a tall waterfall report the waterfall's source Y as the
             * outside sea surface, so the whole carrier acquired full-depth buoyancy at once.
             *
             * Source blocks form static water bodies (ocean/lake columns) and may be followed.
             * A non-source state contributes only the volume actually occupying its own block.
             */
            if (!startFluid.isSource()) {
                final double localSurface = start.getY()
                        + startFluid.getHeight(level, start);
                connectedWaterSurfaces.put(start.asLong(), localSurface);
                return localSurface;
            }

            final List<Long> traversed = new ArrayList<>();
            BlockPos.MutableBlockPos cursor = start.mutable();
            FluidState currentFluid = startFluid;
            double surface;
            while (true) {
                final long key = cursor.asLong();
                final Double cached = connectedWaterSurfaces.get(key);
                if (cached != null) {
                    surface = cached;
                    break;
                }
                traversed.add(key);

                if (cursor.getY() + 1 >= level.getMaxBuildHeight()) {
                    surface = cursor.getY() + currentFluid.getHeight(level, cursor);
                    break;
                }
                cursor.move(0, 1, 0);
                final FluidState above = level.getFluidState(cursor);
                if (!above.is(FluidTags.WATER) || !above.isSource()) {
                    cursor.move(0, -1, 0);
                    surface = cursor.getY() + currentFluid.getHeight(level, cursor);
                    break;
                }
                currentFluid = above;
            }
            for (final long key : traversed) {
                connectedWaterSurfaces.put(key, surface);
            }
            return surface;
        }
    }
}
