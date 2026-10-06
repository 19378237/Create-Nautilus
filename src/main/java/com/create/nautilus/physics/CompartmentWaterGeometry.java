package com.create.nautilus.physics;

import com.create.nautilus.data.CompartmentState;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.Collection;

/** Exact-cell water-height helpers shared by flow integration and rendering. */
public final class CompartmentWaterGeometry {
    private static final int BISECTION_STEPS = 24;

    private CompartmentWaterGeometry() {
    }

    /**
     * Solves a world-horizontal free-surface height from the compartment's
     * actual voxel set and stored water fraction.
     *
     * <p>Each rotated cell contributes the analytic volume of a rectangular box clipped by a
     * world-horizontal plane. Bisection then finds the plane whose summed contribution equals the
     * stored volume. Unlike the old projected-height interpolation this remains volume-correct
     * while the carrier is rolled or pitched.</p>
     */
    public static double surfaceY(final ServerSubLevel carrier,
                                  final CompartmentState room) {
        final double fraction = room.waterCapacityMb() <= 0.0D ? 0.0D
                : Math.clamp(room.waterMb() / room.waterCapacityMb(), 0.0D, 1.0D);
        return surfaceY(carrier.logicalPose(), room.cells().stream()
                .map(BlockPos::of).toList(), room.bounds(), fraction);
    }

    /** Client/server-shared solve using the pose appropriate to that side. */
    public static double surfaceY(final Pose3dc pose, final Collection<BlockPos> cells,
                                  final AABB fallbackBounds, final double waterFraction) {
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

    /** True when a world-horizontal plane crosses this exact compartment cell. */
    public static boolean intersectsSurface(final ServerSubLevel carrier,
                                            final BlockPos cell,
                                            final double worldSurfaceY) {
        final ProjectedInterval interval = projectedInterval(carrier, new AABB(cell));
        return worldSurfaceY >= interval.low() - 1.0E-5D
                && worldSurfaceY <= interval.high() + 1.0E-5D;
    }

    /**
     * Returns the continuous fraction of one compartment voxel below a world-horizontal plane.
     * Buoyancy uses the complementary fraction as the amount of dry, water-displacing cabin air.
     */
    public static double fractionBelowSurface(final ServerSubLevel carrier,
                                              final BlockPos cell,
                                              final double worldSurfaceY) {
        return projectedInterval(carrier, new AABB(cell)).fractionBelow(worldSurfaceY);
    }

    public static double lowestWorldY(final ServerSubLevel carrier, final BlockPos cell) {
        return projectedInterval(carrier, new AABB(cell)).low();
    }

    public static double highestWorldY(final ServerSubLevel carrier, final BlockPos cell) {
        return projectedInterval(carrier, new AABB(cell)).high();
    }

    /**
     * Lowest world-space point of one specific voxel face.
     *
     * <p>An atmospheric opening is the face between the retained basin cell and the exterior
     * opening cell, not the complete one-block opening volume.  Sampling the complete voxel made
     * a rolled carrier start flooding when a low corner on the opposite face touched water even
     * though the actual inlet was still dry.</p>
     */
    public static double lowestFaceWorldY(final ServerSubLevel carrier,
                                          final BlockPos cell,
                                          final Direction face) {
        return projectedFaceInterval(carrier.logicalPose(), new AABB(cell), face).low();
    }

    /** Highest world-space point of one specific voxel face. */
    public static double highestFaceWorldY(final ServerSubLevel carrier,
                                           final BlockPos cell,
                                           final Direction face) {
        return projectedFaceInterval(carrier.logicalPose(), new AABB(cell), face).high();
    }

    /**
     * Continuous area fraction of a voxel face below a world-horizontal liquid plane.
     *
     * <p>The transformed face is a parallelogram parameterised by two uniform coordinates. Its
     * projected Y distribution is therefore the sum of two uniform variables, which is already
     * handled exactly by {@link ProjectedInterval#fractionBelow(double)}.  A horizontal opening
     * changes from dry to wet at its sill; a tilted opening is wetted progressively from its
     * lowest corner.</p>
     */
    public static double fractionOfFaceBelowSurface(final ServerSubLevel carrier,
                                                    final BlockPos cell,
                                                    final Direction face,
                                                    final double worldSurfaceY) {
        return projectedFaceInterval(carrier.logicalPose(), new AABB(cell), face)
                .fractionBelow(worldSurfaceY);
    }

    private static ProjectedInterval projectedInterval(final ServerSubLevel carrier,
                                                       final AABB bounds) {
        return projectedInterval(carrier.logicalPose(), bounds);
    }

    private static ProjectedInterval projectedInterval(final Pose3dc pose,
                                                       final AABB bounds) {
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    final Vector3d world = pose.transformPosition(new Vector3d(
                            x == 0 ? bounds.minX : bounds.maxX,
                            y == 0 ? bounds.minY : bounds.maxY,
                            z == 0 ? bounds.minZ : bounds.maxZ), new Vector3d());
                    low = Math.min(low, world.y());
                    high = Math.max(high, world.y());
                }
            }
        }
        final Vector3d origin = pose.transformPosition(new Vector3d(
                bounds.minX, bounds.minY, bounds.minZ), new Vector3d());
        final double xWidth = Math.abs(pose.transformPosition(new Vector3d(
                bounds.maxX, bounds.minY, bounds.minZ), new Vector3d()).y() - origin.y());
        final double yWidth = Math.abs(pose.transformPosition(new Vector3d(
                bounds.minX, bounds.maxY, bounds.minZ), new Vector3d()).y() - origin.y());
        final double zWidth = Math.abs(pose.transformPosition(new Vector3d(
                bounds.minX, bounds.minY, bounds.maxZ), new Vector3d()).y() - origin.y());
        return new ProjectedInterval(low, high, xWidth, yWidth, zWidth);
    }

    private static ProjectedInterval projectedFaceInterval(final Pose3dc pose,
                                                           final AABB bounds,
                                                           final Direction face) {
        final Vector3d[] corners = new Vector3d[4];
        int index = 0;
        for (int first = 0; first <= 1; first++) {
            for (int second = 0; second <= 1; second++) {
                final double x;
                final double y;
                final double z;
                switch (face.getAxis()) {
                    case X -> {
                        x = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                                ? bounds.minX : bounds.maxX;
                        y = first == 0 ? bounds.minY : bounds.maxY;
                        z = second == 0 ? bounds.minZ : bounds.maxZ;
                    }
                    case Y -> {
                        x = first == 0 ? bounds.minX : bounds.maxX;
                        y = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                                ? bounds.minY : bounds.maxY;
                        z = second == 0 ? bounds.minZ : bounds.maxZ;
                    }
                    case Z -> {
                        x = first == 0 ? bounds.minX : bounds.maxX;
                        y = second == 0 ? bounds.minY : bounds.maxY;
                        z = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                                ? bounds.minZ : bounds.maxZ;
                    }
                    default -> throw new IllegalStateException("Unexpected face axis");
                }
                corners[index++] = pose.transformPosition(new Vector3d(x, y, z), new Vector3d());
            }
        }

        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (final Vector3d corner : corners) {
            low = Math.min(low, corner.y());
            high = Math.max(high, corner.y());
        }
        // With the loop ordering above, 0->2 and 0->1 are the two independent face edges.
        final double firstWidth = Math.abs(corners[2].y() - corners[0].y());
        final double secondWidth = Math.abs(corners[1].y() - corners[0].y());
        return new ProjectedInterval(low, high, firstWidth, secondWidth, 0.0D);
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

            // CDF of the sum of independent uniform variables with possibly different widths:
            // F(t) = 1/(n! product(a_i)) * sum_S (-1)^|S| max(t-sum(a_S),0)^n.
            // A rotated box's world Y coordinate has exactly this distribution, so this is the
            // true clipped-volume fraction rather than a sampled/height-linear approximation.
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
