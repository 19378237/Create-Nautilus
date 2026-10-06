package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fills Sable's close/far hull-depth textures with raw GL so Iris cannot redirect the draws.
 *
 * <p>Under a shader pack, Iris redirects Sable's own occlusion pass into its gbuffers and the
 * close/far masks stay cleared. This renderer builds the dry compartments' boundary faces and
 * draws them into Sable's depth texture attachments with {@link RawDepthRenderer} (private
 * shader + raw {@code glDrawArrays}), the same path already proven to survive Iris. The water
 * program then uses the identical depth-texture discard as the no-shader pipeline.</p>
 */
public final class DryHullDepthMasks {
    private static int closeFbo = -1;
    private static int closeTexture = -1;
    private static int farFbo = -1;
    private static int farTexture = -1;
    private static int width = -1;
    private static int height = -1;
    private static boolean renderedThisFrame;

    private DryHullDepthMasks() {
    }

    /** Draws the dry boundary into the private close/far depth textures. */
    public static boolean render(final Matrix4f modelView, final Matrix4f projection,
                                 final Vec3 camera) {
        renderedThisFrame = false;
        final List<Float> positions = new ArrayList<>();
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getMainRenderTarget() == null) {
            return false;
        }
        if (!ensureFbos(minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height)) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container != null) {
            for (final var carrierEntry : ClientCompartmentVisualCache.snapshots().entrySet()) {
                if (!(container.getSubLevel(carrierEntry.getKey())
                        instanceof final ClientSubLevel carrier)) {
                    continue;
                }
                final Set<Long> dryCells = new HashSet<>();
                for (final ClientCompartmentVisualCache.RoomSnapshot room
                        : carrierEntry.getValue()) {
                    if (room.waterFraction() > 1.0E-5D) {
                        continue;
                    }
                    for (final BlockPos cell : room.cells()) {
                        dryCells.add(cell.asLong());
                    }
                }
                if (dryCells.isEmpty()) {
                    continue;
                }
                addDryBoundary(positions, carrier, dryCells, camera);
            }
        }
        if (positions.isEmpty()) {
            return false;
        }

        final MaskState state = captureState();
        try {
            // Close mask: nearest boundary surface (clear 1.0, GL_LESS).
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, closeFbo);
            GL11C.glViewport(0, 0, width, height);
            GL11C.glDepthMask(true);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LESS);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glClearDepth(1.0D);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            RawDepthRenderer.draw(positions, modelView, projection);

            // Far mask: farthest boundary surface (clear 0.0, GL_GREATER).
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, farFbo);
            GL11C.glDepthFunc(GL11C.GL_GREATER);
            GL11C.glClearDepth(0.0D);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            RawDepthRenderer.draw(positions, modelView, projection);
        } finally {
            restoreState(state);
        }
        renderedThisFrame = true;
        return true;
    }

    public static boolean valid() {
        return renderedThisFrame && closeTexture >= 0 && farTexture >= 0;
    }

    public static int closeTextureId() {
        return closeTexture;
    }

    public static int farTextureId() {
        return farTexture;
    }

    public static int maskWidth() {
        return width;
    }

    public static int maskHeight() {
        return height;
    }

    private static boolean ensureFbos(final int targetWidth, final int targetHeight) {
        if (closeFbo >= 0 && width == targetWidth && height == targetHeight) {
            return true;
        }
        destroy();
        closeTexture = createDepthTexture(targetWidth, targetHeight);
        farTexture = createDepthTexture(targetWidth, targetHeight);
        closeFbo = createDepthFbo(closeTexture);
        farFbo = createDepthFbo(farTexture);
        if (closeFbo < 0 || farFbo < 0) {
            destroy();
            return false;
        }
        width = targetWidth;
        height = targetHeight;
        CreateNautilus.LOGGER.info(
                "[occlusion] dry hull depth masks ready: {}x{}", width, height);
        return true;
    }

    private static int createDepthTexture(final int targetWidth, final int targetHeight) {
        final int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH_COMPONENT32F,
                targetWidth, targetHeight, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT,
                (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S,
                GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T,
                GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        return texture;
    }

    private static int createDepthFbo(final int texture) {
        final int fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, texture, 0);
        GL20C.glDrawBuffer(GL11C.GL_NONE);
        GL20C.glReadBuffer(GL11C.GL_NONE);
        final int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        return status == GL30C.GL_FRAMEBUFFER_COMPLETE ? fbo : -1;
    }

    public static void destroy() {
        if (closeFbo >= 0) {
            GL30C.glDeleteFramebuffers(closeFbo);
            closeFbo = -1;
        }
        if (farFbo >= 0) {
            GL30C.glDeleteFramebuffers(farFbo);
            farFbo = -1;
        }
        if (closeTexture >= 0) {
            GL11C.glDeleteTextures(closeTexture);
            closeTexture = -1;
        }
        if (farTexture >= 0) {
            GL11C.glDeleteTextures(farTexture);
            farTexture = -1;
        }
        width = -1;
        height = -1;
        renderedThisFrame = false;
    }

    private static void addDryBoundary(final List<Float> positions,
                                       final ClientSubLevel carrier,
                                       final Set<Long> dryCells,
                                       final Vec3 camera) {
        final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        final List<Float> local = new ArrayList<>();
        for (final long key : dryCells) {
            final BlockPos pos = BlockPos.of(key);
            for (final Direction direction : Direction.values()) {
                neighbor.setWithOffset(pos, direction);
                if (dryCells.contains(neighbor.asLong())) {
                    continue;
                }
                emitLocalFace(local, pos, direction);
            }
        }
        for (int face = 0; face < local.size(); face += 12) {
            final Vector3d[] corners = new Vector3d[4];
            for (int corner = 0; corner < 4; corner++) {
                final int offset = face + corner * 3;
                corners[corner] = carrier.renderPose().transformPosition(
                        new Vector3d(local.get(offset), local.get(offset + 1),
                                local.get(offset + 2)));
            }
            addVertex(positions, corners[0], camera);
            addVertex(positions, corners[1], camera);
            addVertex(positions, corners[2], camera);
            addVertex(positions, corners[0], camera);
            addVertex(positions, corners[2], camera);
            addVertex(positions, corners[3], camera);
        }
    }

    private static void addVertex(final List<Float> out, final Vector3d point,
                                  final Vec3 camera) {
        out.add((float) (point.x - camera.x));
        out.add((float) (point.y - camera.y));
        out.add((float) (point.z - camera.z));
    }

    private static void emitLocalFace(final List<Float> out,
                                      final BlockPos pos,
                                      final Direction direction) {
        final int nx = direction.getStepX();
        final int ny = direction.getStepY();
        final int nz = direction.getStepZ();
        final int axis;
        final double plane;
        if (nx != 0) {
            axis = 0;
            plane = nx > 0 ? pos.getX() + 1 : pos.getX();
        } else if (ny != 0) {
            axis = 1;
            plane = ny > 0 ? pos.getY() + 1 : pos.getY();
        } else {
            axis = 2;
            plane = nz > 0 ? pos.getZ() + 1 : pos.getZ();
        }
        final double minA;
        final double minB;
        if (axis == 0) {
            minA = pos.getY();
            minB = pos.getZ();
        } else if (axis == 1) {
            minA = pos.getX();
            minB = pos.getZ();
        } else {
            minA = pos.getX();
            minB = pos.getY();
        }
        addLocalVertex(out, axis, plane, minA, minB, 0, 0);
        addLocalVertex(out, axis, plane, minA, minB, 1, 0);
        addLocalVertex(out, axis, plane, minA, minB, 1, 1);
        addLocalVertex(out, axis, plane, minA, minB, 0, 1);
    }

    private static void addLocalVertex(final List<Float> out, final int axis,
                                       final double plane, final double minA,
                                       final double minB, final int da, final int db) {
        final double a = minA + da;
        final double b = minB + db;
        if (axis == 0) {
            out.add((float) plane);
            out.add((float) a);
            out.add((float) b);
        } else if (axis == 1) {
            out.add((float) a);
            out.add((float) plane);
            out.add((float) b);
        } else {
            out.add((float) a);
            out.add((float) b);
            out.add((float) plane);
        }
    }

    private record MaskState(int drawFbo, int readFbo, int depthFunc, boolean depthMask,
                             boolean depthTest, boolean blend, float clearDepth,
                             int viewportX, int viewportY, int viewportW, int viewportH) {
    }

    private static MaskState captureState() {
        final IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        final FloatBuffer clearDepth = BufferUtils.createFloatBuffer(1);
        GL11C.glGetFloatv(GL11C.GL_DEPTH_CLEAR_VALUE, clearDepth);
        return new MaskState(
                GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                GL11C.glIsEnabled(GL11C.GL_BLEND),
                clearDepth.get(0),
                viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
    }

    private static void restoreState(final MaskState state) {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.drawFbo());
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, state.readFbo());
        GL11C.glViewport(state.viewportX(), state.viewportY(),
                state.viewportW(), state.viewportH());
        GL11C.glDepthFunc(state.depthFunc());
        GL11C.glDepthMask(state.depthMask());
        GL11C.glClearDepth(state.clearDepth());
        if (state.depthTest()) {
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        } else {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        }
        if (state.blend()) {
            GL11C.glEnable(GL11C.GL_BLEND);
        } else {
            GL11C.glDisable(GL11C.GL_BLEND);
        }
    }
}
