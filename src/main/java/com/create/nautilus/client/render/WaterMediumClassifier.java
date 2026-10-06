package com.create.nautilus.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.Collection;

/**
 * Camera-independent water-medium predicates used by the semantic boundary segment pass.
 *
 * <p>The renderer represents a liquid boundary as a signed scalar field. Positive values are
 * water, negative values are air, and zero is the exact continuous surface. Keeping the field
 * (rather than a boolean sampled at the centre of a glass block) lets the glass mesh be clipped
 * at the real water line before it is rasterised.</p>
 */
public final class WaterMediumClassifier {
    /** Large finite values are safe to interpolate while behaving like +/- infinity. */
    public static final double DEFINITELY_WATER = 1.0E6D;
    public static final double DEFINITELY_AIR = -1.0E6D;
    public static final double SURFACE_EPSILON = 1.0E-4D;
    private static final int BISECTION_STEPS = 24;

    private WaterMediumClassifier() {
    }

    /** Signed field for a continuous, world-horizontal compartment surface. */
    public static double roomField(final double waterFraction,
                                   final double surfaceY,
                                   final double worldY) {
        if (waterFraction <= 0.0D) {
            return DEFINITELY_AIR;
        }
        if (waterFraction >= 1.0D) {
            return DEFINITELY_WATER;
        }
        return surfaceY - worldY;
    }

    /** True on the water side of a signed field, with one shared boundary epsilon. */
    public static boolean isWaterField(final double field) {
        return field > SURFACE_EPSILON;
    }

    /** Returns a signed field for a real fluid voxel in the supplied level. */
    public static double fluidField(final Level level,
                                    final BlockPos position,
                                    final double sampleY) {
        return fluidField(level, position,
                position.getX() + 0.5D, sampleY, position.getZ() + 0.5D);
    }

    /** Signed field against the exact two-triangle liquid top used by the renderer. */
    public static double fluidField(final Level level,
                                    final BlockPos position,
                                    final double sampleX,
                                    final double sampleY,
                                    final double sampleZ) {
        final FluidState fluid = level.getFluidState(position);
        if (!fluid.is(FluidTags.WATER)) {
            return DEFINITELY_AIR;
        }
        final double[] corners = fluidCornerHeights(level, position, fluid.getType());
        final double x = Math.clamp(sampleX - position.getX(), 0.0D, 1.0D);
        final double z = Math.clamp(sampleZ - position.getZ(), 0.0D, 1.0D);
        final double surface;
        if (x >= z) {
            // NW, NE, SE triangle (the NW-SE diagonal is shared by both triangles).
            surface = corners[0] * (1.0D - x)
                    + corners[1] * (x - z) + corners[2] * z;
        } else {
            // NW, SE, SW triangle.
            surface = corners[0] * (1.0D - z)
                    + corners[2] * x + corners[3] * (z - x);
        }
        return position.getY() + surface - sampleY;
    }

    /** Ordering is north-west, north-east, south-east, south-west. */
    static double[] fluidCornerHeights(final Level level,
                                       final BlockPos position,
                                       final net.minecraft.world.level.material.Fluid fluid) {
        final double centre = renderedFluidHeight(level, fluid, position);
        if (centre >= 1.0D) {
            return new double[]{1.0D, 1.0D, 1.0D, 1.0D};
        }
        final double north = renderedFluidHeight(level, fluid, position.north());
        final double south = renderedFluidHeight(level, fluid, position.south());
        final double east = renderedFluidHeight(level, fluid, position.east());
        final double west = renderedFluidHeight(level, fluid, position.west());
        return new double[]{
                averageFluidCorner(level, fluid, centre, north, west,
                        position.north().west()),
                averageFluidCorner(level, fluid, centre, north, east,
                        position.north().east()),
                averageFluidCorner(level, fluid, centre, south, east,
                        position.south().east()),
                averageFluidCorner(level, fluid, centre, south, west,
                        position.south().west())
        };
    }

    private static double renderedFluidHeight(
            final Level level,
            final net.minecraft.world.level.material.Fluid fluid,
            final BlockPos position) {
        final var state = level.getBlockState(position);
        final FluidState fluidState = state.getFluidState();
        if (fluid.isSame(fluidState.getType())) {
            return fluid.isSame(level.getBlockState(position.above())
                    .getFluidState().getType()) ? 1.0D : fluidState.getOwnHeight();
        }
        return state.isSolid() ? -1.0D : 0.0D;
    }

    private static double averageFluidCorner(
            final Level level,
            final net.minecraft.world.level.material.Fluid fluid,
            final double centre,
            final double first,
            final double second,
            final BlockPos diagonal) {
        if (first >= 1.0D || second >= 1.0D) {
            return 1.0D;
        }
        double weighted = 0.0D;
        double weight = 0.0D;
        if (first > 0.0D || second > 0.0D) {
            final double diagonalHeight = renderedFluidHeight(level, fluid, diagonal);
            if (diagonalHeight >= 1.0D) {
                return 1.0D;
            }
            final double[] result = addWeightedHeight(weighted, weight, diagonalHeight);
            weighted = result[0];
            weight = result[1];
        }
        for (final double value : new double[]{centre, first, second}) {
            final double[] result = addWeightedHeight(weighted, weight, value);
            weighted = result[0];
            weight = result[1];
        }
        return weight > 0.0D ? weighted / weight : 0.0D;
    }

    private static double[] addWeightedHeight(final double weighted,
                                              final double weight,
                                              final double height) {
        if (height >= 0.8D) {
            return new double[]{weighted + height * 10.0D, weight + 10.0D};
        }
        if (height >= 0.0D) {
            return new double[]{weighted + height, weight + 1.0D};
        }
        return new double[]{weighted, weight};
    }

    /**
     * Samples parent-world water at a world-space point. A point exactly on a block boundary is
     * also checked against the block immediately below, which avoids losing the ocean surface at
     * an integer Y due to {@link BlockPos#containing(double, double, double)} choosing the air
     * cell above it.
     */
    public static double parentWorldField(final ClientLevel level,
                                          final Vector3d worldPoint) {
        final BlockPos position = BlockPos.containing(
                worldPoint.x, worldPoint.y, worldPoint.z);
        double field = fluidField(level, position,
                worldPoint.x, worldPoint.y, worldPoint.z);
        if (field > DEFINITELY_AIR * 0.5D) {
            return field;
        }
        final BlockPos below = position.below();
        field = fluidField(level, below,
                worldPoint.x, worldPoint.y, worldPoint.z);
        // Preserve a small negative value above the real surface: it is the air-side endpoint
        // needed to interpolate the water line across a baked quad. Only the finite sentinel
        // means the cell below was not water either.
        return field > DEFINITELY_AIR * 0.5D ? field : DEFINITELY_AIR;
    }

    /** True when the camera point is inside a real parent-world water voxel. */
    public static boolean parentWorldContains(final ClientLevel level,
                                              final Vector3d worldPoint) {
        return isWaterField(parentWorldField(level, worldPoint));
    }

    /**
     * Volume-correct room surface using a matrix frozen for the current render pass.
     *
     * <p>This deliberately mirrors {@code CompartmentWaterGeometry.surfaceY}. Calling that
     * method with a mutable Sable render pose while building the glass mesh could otherwise mix
     * two vehicle poses in one frame.</p>
     */
    public static double frozenRoomSurface(final Matrix4dc pose,
                                           final Collection<BlockPos> cells,
                                           final AABB fallbackBounds,
                                           final double waterFraction) {
        final double fraction = Math.clamp(waterFraction, 0.0D, 1.0D);
        if (cells.isEmpty()) {
            final ProjectedInterval fallback = projectedInterval(pose, fallbackBounds);
            return fallback.low() + (fallback.high() - fallback.low()) * fraction;
        }

        final ProjectedInterval[] intervals = cells.stream()
                .map(position -> projectedInterval(pose, new AABB(position)))
                .toArray(ProjectedInterval[]::new);
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (final ProjectedInterval interval : intervals) {
            low = Math.min(low, interval.low());
            high = Math.max(high, interval.high());
        }
        if (fraction <= 0.0D) {
            return low;
        }
        if (fraction >= 1.0D) {
            return high;
        }

        final double targetVolume = fraction * intervals.length;
        for (int iteration = 0; iteration < BISECTION_STEPS; iteration++) {
            final double middle = (low + high) * 0.5D;
            double submerged = 0.0D;
            for (final ProjectedInterval interval : intervals) {
                submerged += interval.fractionBelow(middle);
            }
            if (submerged < targetVolume) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) * 0.5D;
    }

    private static ProjectedInterval projectedInterval(final Matrix4dc pose,
                                                       final AABB bounds) {
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    final Vector3d world = pose.transformPosition(new Vector3d(
                            x == 0 ? bounds.minX : bounds.maxX,
                            y == 0 ? bounds.minY : bounds.maxY,
                            z == 0 ? bounds.minZ : bounds.maxZ));
                    low = Math.min(low, world.y);
                    high = Math.max(high, world.y);
                }
            }
        }
        final Vector3d origin = pose.transformPosition(new Vector3d(
                bounds.minX, bounds.minY, bounds.minZ));
        final double xWidth = Math.abs(pose.transformPosition(new Vector3d(
                bounds.maxX, bounds.minY, bounds.minZ)).y - origin.y);
        final double yWidth = Math.abs(pose.transformPosition(new Vector3d(
                bounds.minX, bounds.maxY, bounds.minZ)).y - origin.y);
        final double zWidth = Math.abs(pose.transformPosition(new Vector3d(
                bounds.minX, bounds.minY, bounds.maxZ)).y - origin.y);
        return new ProjectedInterval(low, high, xWidth, yWidth, zWidth);
    }

    private record ProjectedInterval(double low, double high,
                                     double xWidth, double yWidth, double zWidth) {
        double fractionBelow(final double plane) {
            if (plane <= low) {
                return 0.0D;
            }
            if (plane >= high) {
                return 1.0D;
            }
            double width0 = 0.0D;
            double width1 = 0.0D;
            double width2 = 0.0D;
            int widthCount = 0;
            if (xWidth > 1.0E-9D) {
                width0 = xWidth;
                widthCount = 1;
            }
            if (yWidth > 1.0E-9D) {
                if (widthCount == 0) width0 = yWidth;
                else width1 = yWidth;
                widthCount++;
            }
            if (zWidth > 1.0E-9D) {
                if (widthCount == 0) width0 = zWidth;
                else if (widthCount == 1) width1 = zWidth;
                else width2 = zWidth;
                widthCount++;
            }
            if (widthCount == 0) {
                return plane >= high ? 1.0D : 0.0D;
            }
            final double t = plane - low;
            double alternatingSum = 0.0D;
            final int subsetCount = 1 << widthCount;
            for (int subset = 0; subset < subsetCount; subset++) {
                double offset = 0.0D;
                int selected = 0;
                if ((subset & 1) != 0) { offset += width0; selected++; }
                if ((subset & 2) != 0) { offset += width1; selected++; }
                if ((subset & 4) != 0) { offset += width2; selected++; }
                final double positive = Math.max(0.0D, t - offset);
                final double squared = positive * positive;
                final double term = widthCount == 1 ? positive
                        : widthCount == 2 ? squared : squared * positive;
                alternatingSum += (selected & 1) == 0 ? term : -term;
            }
            final double product = width0 * (widthCount > 1 ? width1 : 1.0D)
                    * (widthCount > 2 ? width2 : 1.0D);
            final double denominator = (widthCount == 3 ? 6.0D
                    : widthCount == 2 ? 2.0D : 1.0D) * product;
            return Math.clamp(alternatingSum / Math.max(1.0E-12D, denominator),
                    0.0D, 1.0D);
        }
    }
}
