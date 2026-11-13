package com.create.nautilus.physics;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Body contact with exact compartment voxels clipped by their horizontal water surface. */
public final class CompartmentWaterContact {
    private static final int[][] FACES = {{0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1},
            {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 6, 7, 5}};

    private CompartmentWaterContact() { }

    public static double depth(final Pose3dc pose, final AABB body, final double surfaceY,
                               final Predicate<BlockPos> waterCell) {
        final AABB bounds = body.deflate(0.001D); // Same skin as NeoForge's native fluid sampler.
        if (surfaceY <= bounds.minY) return 0.0D;
        final AABB wetBounds = new AABB(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, Math.min(bounds.maxY, surfaceY), bounds.maxZ);
        final Vector3d low = new Vector3d(Double.POSITIVE_INFINITY);
        final Vector3d high = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int corner = 0; corner < 8; corner++) {
            final Vector3d local = pose.transformPositionInverse(new Vector3d(
                    (corner & 1) == 0 ? wetBounds.minX : wetBounds.maxX,
                    (corner & 2) == 0 ? wetBounds.minY : wetBounds.maxY,
                    (corner & 4) == 0 ? wetBounds.minZ : wetBounds.maxZ));
            low.min(local);
            high.max(local);
        }
        // Like NeoForge's native sampler, report the top of an intersecting fluid cell rather
        // than clipping the returned depth to the body's height. A distant part of the room may
        // hold a much higher free surface, but it cannot extend this finite cell through a breach.
        final Vector3d bodyOrigin = new Vector3d(bounds.minX, bounds.minY, bounds.minZ);
        final AABB relativeBody = new AABB(0.0D, 0.0D, 0.0D,
                bounds.maxX - bounds.minX, wetBounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ);
        final double surfaceDepth = surfaceY - bounds.minY;
        double deepest = 0.0D;
        for (int x = (int) Math.floor(low.x); x < Math.ceil(high.x); x++) {
            for (int y = (int) Math.floor(low.y); y < Math.ceil(high.y); y++) {
                for (int z = (int) Math.floor(low.z); z < Math.ceil(high.z); z++) {
                    final BlockPos cell = new BlockPos(x, y, z);
                    if (!waterCell.test(cell)) continue;
                    final Vector3d[] corners = corners(pose, cell, bodyOrigin);
                    double highestCorner = Double.NEGATIVE_INFINITY;
                    for (final Vector3d corner : corners) highestCorner = Math.max(highestCorner, corner.y);
                    if (Math.min(surfaceDepth, highestCorner) <= deepest || !intersects(corners, relativeBody)) continue;
                    deepest = Math.max(deepest, Math.min(surfaceDepth, columnTop(corners, relativeBody)));
                }
            }
        }
        return deepest;
    }

    private static Vector3d[] corners(final Pose3dc pose, final BlockPos cell, final Vector3d origin) {
        final Vector3d[] corners = new Vector3d[8];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = pose.transformPosition(new Vector3d(cell.getX() + (i & 1),
                    cell.getY() + ((i >> 1) & 1), cell.getZ() + ((i >> 2) & 1))).sub(origin);
        }
        return corners;
    }

    /** Highest point of this voxel in the body's horizontal footprint, before the liquid cap. */
    private static double columnTop(final Vector3d[] corners, final AABB body) {
        double top = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY;
        boolean hasHorizontalArea = false;
        for (final int[] face : FACES) {
            List<Vector3d> polygon = List.of(corners[face[0]], corners[face[1]], corners[face[2]], corners[face[3]]);
            polygon = clip(polygon, true, 0.0D, false);
            polygon = clip(polygon, true, body.maxX, true);
            polygon = clip(polygon, false, 0.0D, false);
            polygon = clip(polygon, false, body.maxZ, true);
            double twiceArea = 0.0D;
            if (polygon.size() < 3) continue;
            Vector3d previous = polygon.getLast();
            for (final Vector3d point : polygon) {
                top = Math.max(top, point.y);
                bottom = Math.min(bottom, point.y);
                twiceArea += previous.x * point.z - point.x * previous.z;
                previous = point;
            }
            hasHorizontalArea |= Math.abs(twiceArea) > 1.0E-18D;
        }
        // The additional clipping faces are vertical: their highest points also lie on an
        // original voxel face. No caps or full polyhedron construction are needed for this max.
        return hasHorizontalArea && bottom < body.maxY ? top : Double.NEGATIVE_INFINITY;
    }

    private static List<Vector3d> clip(final List<Vector3d> polygon, final boolean xAxis,
                                       final double boundary, final boolean keepLess) {
        if (polygon.isEmpty()) return polygon;
        final List<Vector3d> result = new ArrayList<>(polygon.size() + 1);
        Vector3d previous = polygon.getLast();
        double previousDistance = (xAxis ? previous.x : previous.z) - boundary;
        if (!keepLess) previousDistance = -previousDistance;
        for (final Vector3d point : polygon) {
            double distance = (xAxis ? point.x : point.z) - boundary;
            if (!keepLess) distance = -distance;
            if ((previousDistance <= 0.0D) != (distance <= 0.0D)) {
                result.add(new Vector3d(previous).lerp(point,
                        previousDistance / (previousDistance - distance)));
            }
            if (distance <= 0.0D) result.add(point);
            previous = point;
            previousDistance = distance;
        }
        return result;
    }

    /** Separating axes for a transformed unit voxel and the world-space body box. */
    private static boolean intersects(final Vector3d[] corners, final AABB box) {
        final Vector3d origin = corners[0];
        final Vector3d[] edges = {
                new Vector3d(corners[1]).sub(origin).mul(0.5D),
                new Vector3d(corners[2]).sub(origin).mul(0.5D),
                new Vector3d(corners[4]).sub(origin).mul(0.5D)
        };
        final Vector3d half = new Vector3d(box.maxX - box.minX,
                box.maxY - box.minY, box.maxZ - box.minZ).mul(0.5D);
        final Vector3d delta = new Vector3d(box.minX + half.x,
                box.minY + half.y, box.minZ + half.z).sub(origin);
        for (final Vector3d edge : edges) delta.sub(edge);
        final Vector3d[] axes = {new Vector3d(1, 0, 0),
                new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)};
        for (final Vector3d axis : axes) {
            if (separated(axis, delta, half, edges)) return false;
            for (final Vector3d edge : edges) {
                if (separated(new Vector3d(axis).cross(edge), delta, half, edges)) return false;
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            if (separated(new Vector3d(edges[(axis + 1) % 3])
                    .cross(edges[(axis + 2) % 3]), delta, half, edges)) return false;
        }
        return true;
    }

    private static boolean separated(final Vector3d axis, final Vector3d delta,
                                      final Vector3d half, final Vector3d[] edges) {
        final double radius = half.x * Math.abs(axis.x) + half.y * Math.abs(axis.y)
                + half.z * Math.abs(axis.z) + Math.abs(axis.dot(edges[0]))
                + Math.abs(axis.dot(edges[1])) + Math.abs(axis.dot(edges[2]));
        return Math.abs(axis.dot(delta)) > radius + 1.0E-10D * axis.length();
    }
}
