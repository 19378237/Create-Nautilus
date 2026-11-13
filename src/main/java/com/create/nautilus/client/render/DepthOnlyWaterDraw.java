package com.create.nautilus.client.render;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/** Keeps a virtual volume's fog boundary without writing a visible water material. */
final class DepthOnlyWaterDraw {
    private DepthOnlyWaterDraw() { }

    static void draw(final MeshData mesh) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final int drawBuffers = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
            final ByteBuffer masks = stack.malloc(drawBuffers * 4);
            for (int index = 0; index < drawBuffers; index++) {
                masks.position(index * 4);
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, index, masks);
            }
            try {
                // Photon writes both refraction and translucent color. Disable every MRT slot,
                // leaving the current shader's geometry, depth test and depth writes intact.
                GL11C.glColorMask(false, false, false, false);
                BufferUploader.draw(mesh);
            } finally {
                // Preserve indexed masks exactly, without changing RenderSystem's mask cache.
                // The caller clears its Iris shader only after this restoration completes.
                for (int index = 0; index < drawBuffers; index++) {
                    final int offset = index * 4;
                    GL30C.glColorMaski(index, masks.get(offset) != 0, masks.get(offset + 1) != 0,
                            masks.get(offset + 2) != 0, masks.get(offset + 3) != 0);
                }
            }
        }
    }
}
