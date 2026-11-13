package com.create.nautilus.client.render;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.IntArrays;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;

/**
 * Sorts one color submission across all its rooms/carriers. MeshData.sortQuads ignores TRIANGLES.
 * This preserves each complete vertex, winding and material attribute. Native glass still lives
 * in separate section draws: triangle sorting here cannot interleave those draws with water.
 */
final class WaterTriangleSorting {
    private WaterTriangleSorting() {
    }

    static void sort(final MeshData mesh, final Matrix4f modelView) {
        final MeshData.DrawState state = mesh.drawState();
        if (state.mode() != VertexFormat.Mode.TRIANGLES) {
            throw new IllegalArgumentException("Water sorting requires independent triangles");
        }
        sortTriangles(mesh.vertexBuffer(), state.vertexCount(), state.format().getVertexSize(),
                state.format().getOffset(VertexFormatElement.POSITION), modelView);
    }

    static void sortTriangles(final ByteBuffer vertices, final int vertexCount,
                              final int stride, final int positionOffset,
                              final Matrix4f modelView) {
        if (vertexCount % 3 != 0 || positionOffset < 0 || positionOffset + 12 > stride) {
            throw new IllegalArgumentException("Invalid water triangle layout");
        }
        final int triangleCount = vertexCount / 3;
        if (triangleCount < 2) return;
        final int triangleBytes = Math.multiplyExact(stride, 3);
        final byte[] original = new byte[Math.multiplyExact(vertexCount, stride)];
        // Absolute access leaves the caller's position, limit and byte order unchanged.
        vertices.get(0, original);
        final ByteBuffer source = ByteBuffer.wrap(original).order(vertices.order());
        final int[] order = new int[triangleCount];
        final double[] distances = new double[triangleCount];
        final Vector3f center = new Vector3f();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            order[triangle] = triangle;
            center.zero();
            for (int corner = 0; corner < 3; corner++) {
                final int offset = triangle * triangleBytes + corner * stride + positionOffset;
                center.add(source.getFloat(offset), source.getFloat(offset + 4),
                        source.getFloat(offset + 8));
            }
            center.div(3.0F);
            modelView.transformPosition(center);
            distances[triangle] = (double) center.x * center.x
                    + (double) center.y * center.y + (double) center.z * center.z;
        }
        IntArrays.stableSort(order, (left, right) -> {
            final int distance = Double.compare(distances[right], distances[left]);
            if (distance != 0) return distance;
            // Equal-distance geometry must not flicker when snapshot/map iteration changes.
            return java.util.Arrays.compareUnsigned(original, left * triangleBytes,
                    (left + 1) * triangleBytes, original, right * triangleBytes,
                    (right + 1) * triangleBytes);
        });
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            vertices.put(triangle * triangleBytes, original, order[triangle] * triangleBytes,
                    triangleBytes);
        }
    }
}
