package com.create.nautilus.physics;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Removes exact compartment voxels from the part of one parent-water column touching a body. */
public final class ParentWaterContact {
    private static final double EPSILON = 1.0E-9D;
    private static final int MAX_CANDIDATE_CELLS = 512;
    private static final int MAX_FRAGMENTS = 128;

    private ParentWaterContact() { }

    /** One carrier's union of known room cells. The pose is read only during this query. */
    public record OcclusionVolume(Pose3dc pose, Predicate<BlockPos> contains) { }

    /**
     * A replacement for the parent loop's FluidState height, not a replacement FluidState.
     * Fully covered water must end strictly below the body, including in higher scanned layers.
     * An exposed column reaching the body's top retains the original, possibly higher surface.
     * An unsupported pose or pathological subdivision leaves the native water sample intact.
     */
    public static float height(final AABB body, final BlockPos cell, final float nativeHeight,
                               final List<OcclusionVolume> volumes) {
        if (volumes.isEmpty()) return nativeHeight;
        final AABB skin = body.deflate(0.001D);
        final double bottom = Math.max(skin.minY, cell.getY());
        final double top = Math.min(skin.maxY, cell.getY() + (double) nativeHeight);
        final double minX = Math.max(skin.minX, cell.getX());
        final double maxX = Math.min(skin.maxX, cell.getX() + 1.0D);
        final double minZ = Math.max(skin.minZ, cell.getZ());
        final double maxZ = Math.min(skin.maxZ, cell.getZ() + 1.0D);
        if (!(minX < maxX && bottom < top && minZ < maxZ)) return nativeHeight;

        // Coordinates remain within one parent block instead of subtracting large world/plot
        // coordinates at every clip. The y origin also makes remaining.maxY the wet body depth.
        final Vector3d origin = new Vector3d(minX, skin.minY, minZ);
        final AABB wet = new AABB(0.0D, bottom - skin.minY, 0.0D,
                maxX - minX, top - skin.minY, maxZ - minZ);
        List<Fragment> remaining = List.of(Fragment.box(wet));
        int candidates = 0;
        for (final OcclusionVolume volume : volumes) {
            final Vector3d localOrigin = volume.pose().transformPositionInverse(new Vector3d(origin));
            final Vector3d dx = volume.pose().transformNormalInverse(new Vector3d(1.0D, 0.0D, 0.0D));
            final Vector3d dy = volume.pose().transformNormalInverse(new Vector3d(0.0D, 1.0D, 0.0D));
            final Vector3d dz = volume.pose().transformNormalInverse(new Vector3d(0.0D, 0.0D, 1.0D));
            if (!localOrigin.isFinite() || !dx.isFinite() || !dy.isFinite() || !dz.isFinite()) return nativeHeight;
            final Vector3d[] axes = {new Vector3d(dx.x, dy.x, dz.x),
                    new Vector3d(dx.y, dy.y, dz.y), new Vector3d(dx.z, dy.z, dz.z)};
            for (final Vector3d axis : axes) {
                if (!(axis.lengthSquared() > 0.0D) || !Double.isFinite(axis.lengthSquared())) return nativeHeight;
            }
            final Vector3d low = new Vector3d(Double.POSITIVE_INFINITY);
            final Vector3d high = new Vector3d(Double.NEGATIVE_INFINITY);
            for (int corner = 0; corner < 8; corner++) {
                final Vector3d point = new Vector3d((corner & 1) == 0 ? wet.minX : wet.maxX,
                        (corner & 2) == 0 ? wet.minY : wet.maxY, (corner & 4) == 0 ? wet.minZ : wet.maxZ);
                final Vector3d local = new Vector3d(axes[0].dot(point), axes[1].dot(point), axes[2].dot(point))
                        .add(localOrigin);
                low.min(local); high.max(local);
            }
            final double nx = Math.ceil(high.x) - Math.floor(low.x);
            final double ny = Math.ceil(high.y) - Math.floor(low.y);
            final double nz = Math.ceil(high.z) - Math.floor(low.z);
            if (nx * ny * nz > MAX_CANDIDATE_CELLS - candidates
                    || Math.min(low.x, Math.min(low.y, low.z)) < Integer.MIN_VALUE
                    || Math.max(high.x, Math.max(high.y, high.z)) > Integer.MAX_VALUE) return nativeHeight;
            for (int x = (int) Math.floor(low.x); x < Math.ceil(high.x); x++) {
                for (int y = (int) Math.floor(low.y); y < Math.ceil(high.y); y++) {
                    for (int z = (int) Math.floor(low.z); z < Math.ceil(high.z); z++) {
                        candidates++;
                        if (!volume.contains().test(new BlockPos(x, y, z))) continue;
                        final Plane[] planes = {
                                Plane.of(axes[0], x + 1.0D - localOrigin.x),
                                Plane.of(new Vector3d(axes[0]).negate(), localOrigin.x - x),
                                Plane.of(axes[1], y + 1.0D - localOrigin.y),
                                Plane.of(new Vector3d(axes[1]).negate(), localOrigin.y - y),
                                Plane.of(axes[2], z + 1.0D - localOrigin.z),
                                Plane.of(new Vector3d(axes[2]).negate(), localOrigin.z - z)};
                        final List<Fragment> outside = new ArrayList<>();
                        for (final Fragment fragment : remaining) {
                            Fragment inside = fragment;
                            for (final Plane plane : planes) {
                                final double[] range = inside.distanceRange(plane);
                                if (range[1] <= EPSILON) continue;
                                if (range[0] >= -EPSILON) {
                                    outside.add(inside); inside = null; break;
                                }
                                final Fragment exposed = inside.clip(plane.opposite());
                                if (exposed != null) outside.add(exposed);
                                if (outside.size() > MAX_FRAGMENTS) return nativeHeight;
                                inside = inside.clip(plane);
                                if (inside == null) break;
                            }
                            // The final inside fragment is covered by this voxel and discarded.
                            if (outside.size() > MAX_FRAGMENTS) return nativeHeight;
                        }
                        remaining = outside;
                        if (remaining.isEmpty()) return (float) (skin.minY - cell.getY() - 1.0D);
                    }
                }
            }
        }
        final double exposedTop = remaining.stream().mapToDouble(Fragment::maxY).max().orElseThrow();
        if (exposedTop >= wet.maxY - EPSILON) return nativeHeight;
        return (float) Math.min(nativeHeight, skin.minY - cell.getY() + exposedTop);
    }

    private record Plane(Vector3d normal, double offset) {
        static Plane of(final Vector3d normal, final double offset) {
            final double length = normal.length();
            return new Plane(new Vector3d(normal).div(length), offset / length);
        }
        double distance(final Vector3d point) { return normal.dot(point) - offset; }
        Plane opposite() { return new Plane(new Vector3d(normal).negate(), -offset); }
    }

    /** Convex faces produced only by clipping a single axis-aligned water/body intersection. */
    private record Fragment(List<List<Vector3d>> faces) {
        static Fragment box(final AABB b) {
            final Vector3d[] p = new Vector3d[8];
            for (int i = 0; i < 8; i++) p[i] = new Vector3d((i & 1) == 0 ? b.minX : b.maxX,
                    (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
            final int[][] indices = {{0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1},
                    {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 6, 7, 5}};
            final List<List<Vector3d>> faces = new ArrayList<>(6);
            for (final int[] face : indices) faces.add(List.of(p[face[0]], p[face[1]], p[face[2]], p[face[3]]));
            return new Fragment(faces);
        }

        double[] distanceRange(final Plane plane) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (final List<Vector3d> face : faces) for (final Vector3d p : face) {
                final double d = plane.distance(p); min = Math.min(min, d); max = Math.max(max, d);
            }
            return new double[]{min, max};
        }

        Fragment clip(final Plane plane) {
            final List<List<Vector3d>> clipped = new ArrayList<>();
            final List<Vector3d> cap = new ArrayList<>();
            for (final List<Vector3d> face : faces) {
                final List<Vector3d> polygon = new ArrayList<>();
                Vector3d previous = face.getLast();
                double previousDistance = plane.distance(previous);
                for (final Vector3d point : face) {
                    final double distance = plane.distance(point);
                    if ((previousDistance <= 0.0D) != (distance <= 0.0D)) {
                        final Vector3d intersection = new Vector3d(previous).lerp(point,
                                previousDistance / (previousDistance - distance));
                        polygon.add(intersection);
                        if (cap.stream().noneMatch(p -> p.distanceSquared(intersection) <= EPSILON * EPSILON)) cap.add(intersection);
                    }
                    if (distance <= 0.0D) polygon.add(point);
                    previous = point; previousDistance = distance;
                }
                if (polygon.size() >= 3) clipped.add(polygon);
            }
            if (cap.size() >= 3) {
                final Vector3d centre = new Vector3d();
                cap.forEach(centre::add); centre.div(cap.size());
                final Vector3d u = new Vector3d(Math.abs(plane.normal().x) < 0.8D ? 1.0D : 0.0D,
                        Math.abs(plane.normal().x) < 0.8D ? 0.0D : 1.0D, 0.0D).cross(plane.normal()).normalize();
                final Vector3d v = new Vector3d(plane.normal()).cross(u);
                cap.sort(Comparator.comparingDouble(p -> {
                    final Vector3d relative = new Vector3d(p).sub(centre);
                    return Math.atan2(relative.dot(v), relative.dot(u));
                }));
                clipped.add(cap);
            }
            if (clipped.size() < 4) return null;
            final Fragment result = new Fragment(clipped);
            return result.hasVolume() ? result : null;
        }

        boolean hasVolume() {
            final Vector3d centre = new Vector3d();
            int count = 0;
            for (final List<Vector3d> face : faces) for (final Vector3d p : face) { centre.add(p); count++; }
            centre.div(count);
            double sixVolume = 0.0D;
            for (final List<Vector3d> face : faces) {
                final Vector3d a = new Vector3d(face.getFirst()).sub(centre);
                for (int i = 1; i + 1 < face.size(); i++) {
                    sixVolume += Math.abs(a.dot(new Vector3d(face.get(i)).sub(centre)
                            .cross(new Vector3d(face.get(i + 1)).sub(centre))));
                }
            }
            return sixVolume > 1.0E-18D;
        }

        double maxY() {
            double max = Double.NEGATIVE_INFINITY;
            for (final List<Vector3d> face : faces) for (final Vector3d p : face) max = Math.max(max, p.y);
            return max;
        }
    }
}
