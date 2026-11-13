package com.create.nautilus.physics;

import com.create.nautilus.data.CompartmentState;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Collections;

/** Exact-cell water-height helpers shared by flow integration and rendering. */
public final class CompartmentWaterGeometry {
    private static final int BISECTION_STEPS = 24;
    private static final Map<CompartmentState, PreparedSurface> SERVER_SURFACES =
            Collections.synchronizedMap(new WeakHashMap<>());

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
        return preparedSurface(carrier, room).surface();
    }

    static PreparedSurface preparedSurface(final ServerSubLevel carrier,
                                            final CompartmentState room) {
        final double fraction = room.waterCapacityMb() <= 0.0D ? 0.0D
                : Math.clamp(room.waterMb() / room.waterCapacityMb(), 0.0D, 1.0D);
        final Set<Long> cells = room.waterCells();
        final Pose3dc pose = carrier.logicalPose();
        final PreparedSurface previous = SERVER_SURFACES.get(room);
        final WaterQueryDiagnostics diagnostics = WaterQueryDiagnostics.current();
        if (previous != null && previous.cells == cells && previous.bounds.equals(room.bounds())
                && samePose(previous.pose, pose)) {
            if (Double.compare(previous.fraction, fraction) == 0) {
                if (diagnostics != null) diagnostics.recordGeometryPreparation(1, 0);
                return previous;
            }
            if (diagnostics != null) diagnostics.recordGeometryPreparation(2, 0);
            final double surface = solveSurface(previous.intervals,
                    projectedInterval(previous.pose, room.bounds()), fraction);
            final PreparedSurface updated = new PreparedSurface(cells, room.bounds(), previous.pose,
                    previous.packedCells, previous.intervals, fraction, surface);
            updated.intervalIndices = previous.intervalIndices;
            SERVER_SURFACES.put(room, updated);
            return updated;
        }
        if (diagnostics != null) {
            if (previous == null) diagnostics.recordGeometryPreparation(0, cells.size());
            else {
                // One exclusive mask records all actual rebuild causes without adding parent
                // wrapper counts or labelling a multi-cause rebuild as several rebuilds.
                final int changes = (previous.cells != cells ? 1 : 0)
                        | (!previous.bounds.equals(room.bounds()) ? 2 : 0)
                        | (!samePose(previous.pose, pose) ? 4 : 0);
                diagnostics.recordGeometryPreparation(changes + 2, cells.size());
            }
        }
        final Pose3d snapshot = new Pose3d(pose);
        final long[] packedCells = new long[cells.size()];
        final ProjectedInterval[] intervals = new ProjectedInterval[cells.size()];
        int index = 0;
        for (final long packed : cells) {
            packedCells[index] = packed;
            intervals[index] = projectedInterval(snapshot, new AABB(BlockPos.of(packed)));
            index++;
        }
        final double surface = solveSurface(intervals, projectedInterval(snapshot, room.bounds()), fraction);
        final PreparedSurface prepared = new PreparedSurface(
                cells, room.bounds(), snapshot, packedCells, intervals, fraction, surface);
        SERVER_SURFACES.put(room, prepared);
        return prepared;
    }

    /**
     * Reads already prepared geometry without updating its water fraction or solving a surface.
     * The caller may use intervals only, and must finish before a mass update changes the pose.
     */
    static PreparedSurface matchingPreparedSurface(final ServerSubLevel carrier,
                                                    final CompartmentState room,
                                                    final WaterQueryDiagnostics diagnostics) {
        final PreparedSurface previous = SERVER_SURFACES.get(room);
        if (previous == null) {
            if (diagnostics != null) diagnostics.recordPreparedLookup(false, false, false);
            return null;
        }
        final boolean geometryMatches = previous.cells == room.waterCells()
                && previous.bounds.equals(room.bounds());
        final boolean poseMatches = samePose(previous.pose, carrier.logicalPose());
        if (diagnostics != null) diagnostics.recordPreparedLookup(true, geometryMatches, poseMatches);
        return geometryMatches && poseMatches ? previous : null;
    }

    /** Evaluates a hydraulic candidate without projecting the same room cells again. */
    static double surfaceYForWater(final ServerSubLevel carrier, final CompartmentState room,
                                    final double waterMb) {
        return surfaceYForWater(preparedSurface(carrier, room), room, waterMb);
    }

    /** Reuses geometry within one solve, before any water commit or carrier mass update. */
    static double surfaceYForWater(final PreparedSurface prepared, final CompartmentState room,
                                    final double waterMb) {
        final double fraction = room.waterCapacityMb() <= 0.0D ? 0.0D
                : Math.clamp(waterMb / room.waterCapacityMb(), 0.0D, 1.0D);
        final boolean matchingFraction = Double.compare(prepared.fraction, fraction) == 0;
        final WaterQueryDiagnostics diagnostics = WaterQueryDiagnostics.current();
        if (diagnostics != null) diagnostics.geometryCandidateOutcomes[matchingFraction ? 0 : 1]++;
        return matchingFraction ? prepared.surface
                : solveSurface(prepared.intervals,
                        projectedInterval(prepared.pose, room.bounds()), fraction);
    }

    /** Forward volume evaluation using the same pose and effective cells as the inverse solve. */
    static double waterMbBelowSurface(final ServerSubLevel carrier, final CompartmentState room,
                                       final double worldSurfaceY) {
        return waterMbBelowSurface(preparedSurface(carrier, room), room, worldSurfaceY);
    }

    /** Reuses geometry within one solve; candidate water volume is evaluated for every plane. */
    static double waterMbBelowSurface(final PreparedSurface prepared, final CompartmentState room,
                                       final double worldSurfaceY) {
        if (prepared.intervals.length == 0) return room.waterMb();
        double submergedVolume = 0.0D;
        for (final ProjectedInterval interval : prepared.intervals) {
            submergedVolume += interval.fractionBelow(worldSurfaceY);
        }
        return room.waterCapacityMb() * Math.clamp(
                submergedVolume / prepared.intervals.length, 0.0D, 1.0D);
    }

    public static void forget(final CompartmentState room) { SERVER_SURFACES.remove(room); }

    private static boolean samePose(final Pose3dc first, final Pose3dc second) {
        return first.position().equals(second.position()) && first.orientation().equals(second.orientation())
                && first.rotationPoint().equals(second.rotationPoint()) && first.scale().equals(second.scale());
    }

    /** Reuses the exact prepared projections in the original water-cell encounter order. */
    static BlockPos waterMassCentre(final ServerSubLevel carrier, final CompartmentState room) {
        final PreparedSurface prepared = preparedSurface(carrier, room);
        double weight = 0.0D;
        final Vector3d weighted = new Vector3d();
        for (int index = 0; index < prepared.intervals.length; index++) {
            final double flooded = prepared.intervals[index].fractionBelow(prepared.surface);
            if (flooded <= 1.0E-9D) continue;
            final BlockPos cell = BlockPos.of(prepared.packedCells[index]);
            weight += flooded;
            // Preserve summation order and fma rounding before the final floor to BlockPos.
            weighted.fma(flooded, new Vector3d(
                    cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D));
        }
        if (weight <= 1.0E-9D) return BlockPos.containing(room.bounds().getCenter());
        weighted.div(weight);
        return BlockPos.containing(weighted.x, weighted.y, weighted.z);
    }

    /** A local read of this result must finish before any mass update mutates the carrier pose. */
    static final class PreparedSurface {
        private final Set<Long> cells;
        private final AABB bounds;
        private final Pose3d pose;
        private final long[] packedCells;
        private final ProjectedInterval[] intervals;
        private final double fraction;
        private final double surface;
        private Long2IntOpenHashMap intervalIndices;

        private PreparedSurface(final Set<Long> cells, final AABB bounds, final Pose3d pose,
                                final long[] packedCells, final ProjectedInterval[] intervals,
                                final double fraction, final double surface) {
            this.cells = cells;
            this.bounds = bounds;
            this.pose = pose;
            this.packedCells = packedCells;
            this.intervals = intervals;
            this.fraction = fraction;
            this.surface = surface;
        }

        double surface() { return surface; }

        /** A missing cell belongs to the full envelope, not necessarily to this water-cell subset. */
        ProjectedInterval interval(final long packed, final WaterQueryDiagnostics diagnostics) {
            if (packedCells.length == 0) return null;
            // Share the existing index between visual and buoyancy reads and water-amount updates.
            if (intervalIndices == null) {
                final long started = diagnostics == null ? 0L : System.nanoTime();
                intervalIndices = new Long2IntOpenHashMap(packedCells.length);
                intervalIndices.defaultReturnValue(-1);
                for (int index = 0; index < packedCells.length; index++) {
                    intervalIndices.put(packedCells[index], index);
                }
                if (diagnostics != null) {
                    diagnostics.intervalIndexBuilds++;
                    diagnostics.intervalIndexEntries += packedCells.length;
                    diagnostics.intervalIndexBuildNanos += System.nanoTime() - started;
                }
            }
            final int index = intervalIndices.get(packed);
            return index >= 0 ? intervals[index] : null;
        }

        double lowestWorldY(final BlockPos cell) {
            final ProjectedInterval interval = interval(cell.asLong(), null);
            // room.cells also includes real sources excluded from the prepared water-cell set.
            return interval != null ? interval.low() : projectedInterval(pose, new AABB(cell)).low();
        }
    }

    /** Receives completed bisections only; empty/dry/full shortcuts do not report a solve. */
    @FunctionalInterface
    public interface SurfaceSolveObserver {
        void onSolve(int cells, int iterations);
    }

    /** Client/server-shared solve using the pose appropriate to that side. */
    public static double surfaceY(final Pose3dc pose, final Collection<BlockPos> cells,
                                   final AABB fallbackBounds, final double waterFraction) {
        return surfaceY(pose, cells, fallbackBounds, waterFraction, null);
    }

    /** Optional diagnostics owned by the caller; common code never depends on client classes. */
    public static double surfaceY(final Pose3dc pose, final Collection<BlockPos> cells,
                                   final AABB fallbackBounds, final double waterFraction,
                                   final SurfaceSolveObserver observer) {
        return surfaceY(prepareGeometry(pose, cells, fallbackBounds), waterFraction, observer);
    }

    /** Immutable exact projections, retaining the caller's water-cell encounter order. */
    public static final class PreparedGeometry {
        private final ProjectedInterval[] intervals;
        private final ProjectedInterval fallback;

        private PreparedGeometry(final ProjectedInterval[] intervals, final ProjectedInterval fallback) {
            this.intervals = intervals;
            this.fallback = fallback;
        }
    }

    /** Captures geometry only; later water amounts do not reproject the same cells. */
    public static PreparedGeometry prepareGeometry(final Pose3dc pose,
                                                   final Collection<BlockPos> cells,
                                                   final AABB fallbackBounds) {
        final ProjectedInterval[] intervals = cells.stream()
                .map(position -> projectedInterval(pose, new AABB(position)))
                .toArray(ProjectedInterval[]::new);
        return new PreparedGeometry(intervals, projectedInterval(pose, fallbackBounds));
    }

    /** Uses the original double arithmetic and 24-step solve with already prepared projections. */
    public static double surfaceY(final PreparedGeometry geometry, final double waterFraction,
                                   final SurfaceSolveObserver observer) {
        return solveSurface(geometry.intervals, geometry.fallback,
                Math.clamp(waterFraction, 0.0D, 1.0D), observer);
    }

    private static double solveSurface(final ProjectedInterval[] intervals,
                                        final ProjectedInterval fallback, final double fraction) {
        return solveSurface(intervals, fallback, fraction, null);
    }

    private static double solveSurface(final ProjectedInterval[] intervals,
                                        final ProjectedInterval fallback, final double fraction,
                                        final SurfaceSolveObserver observer) {
        if (intervals.length == 0) return fallback.low() + (fallback.high() - fallback.low()) * fraction;
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
        if (observer != null) observer.onSolve(intervals.length, BISECTION_STEPS);
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
     * The external surface bounds the immersed compartment envelope; stored water contributes
     * its mass separately under the rigidly carried water approximation.
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

    public static ProjectedInterval projectedCell(final Pose3dc pose, final BlockPos cell) {
        return projectedInterval(pose, new AABB(cell));
    }

    static ProjectedInterval projectedCell(final ServerSubLevel carrier, final BlockPos cell) {
        return projectedInterval(carrier, new AABB(cell));
    }

    private static ProjectedInterval projectedInterval(final ServerSubLevel carrier,
                                                       final AABB bounds) {
        return projectedInterval(carrier.logicalPose(), bounds);
    }

    private static ProjectedInterval projectedInterval(final Pose3dc pose,
                                                       final AABB bounds) {
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        double originY = 0.0D;
        double xEndY = 0.0D;
        double yEndY = 0.0D;
        double zEndY = 0.0D;
        final Vector3d local = new Vector3d();
        final Vector3d world = new Vector3d();
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    final double cornerY = pose.transformPosition(local.set(
                            x == 0 ? bounds.minX : bounds.maxX,
                            y == 0 ? bounds.minY : bounds.maxY,
                            z == 0 ? bounds.minZ : bounds.maxZ), world).y();
                    low = Math.min(low, cornerY);
                    high = Math.max(high, cornerY);
                    // Reuse the exact endpoint transforms, including their rounding at large
                    // coordinates, instead of projecting those four corners a second time.
                    if (y == 0 && z == 0) {
                        if (x == 0) originY = cornerY;
                        else xEndY = cornerY;
                    } else if (x == 0) {
                        if (z == 0) yEndY = cornerY;
                        else if (y == 0) zEndY = cornerY;
                    }
                }
            }
        }
        final double xWidth = Math.abs(xEndY - originY);
        final double yWidth = Math.abs(yEndY - originY);
        final double zWidth = Math.abs(zEndY - originY);
        return new ProjectedInterval(low, high, xWidth, yWidth, zWidth);
    }

    static ProjectedInterval projectedFaceInterval(final Pose3dc pose,
                                                    final AABB bounds,
                                                    final Direction face) {
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        double originY = 0.0D;
        double firstEndY = 0.0D;
        double secondEndY = 0.0D;
        final Vector3d local = new Vector3d();
        final Vector3d world = new Vector3d();
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
                final double cornerY = pose.transformPosition(local.set(x, y, z), world).y();
                low = Math.min(low, cornerY);
                high = Math.max(high, cornerY);
                if (first == 0) {
                    if (second == 0) originY = cornerY;
                    else secondEndY = cornerY;
                } else if (second == 0) {
                    firstEndY = cornerY;
                }
            }
        }

        // With the loop ordering above, 0->2 and 0->1 are the two independent face edges.
        final double firstWidth = Math.abs(firstEndY - originY);
        final double secondWidth = Math.abs(secondEndY - originY);
        return new ProjectedInterval(low, high, firstWidth, secondWidth, 0.0D);
    }

    public record ProjectedInterval(double low, double high,
                             double xWidth, double yWidth, double zWidth) {
        public double fractionBelow(final double plane) {
            if (plane <= low) {
                return 0.0D;
            }
            if (plane >= high) {
                return 1.0D;
            }
            return ProjectedBoxVolume.fractionBelow(plane - low, xWidth, yWidth, zWidth);
        }
    }
}
