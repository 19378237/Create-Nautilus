package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Minimal raw-GL depth pass for the compartment masks.
 *
 * <p>Minecraft's {@code ShaderInstance.apply()} and {@code BufferUploader} are intercepted by
 * Iris, which redirects the draw into its own gbuffer and never touches Nautilus' close/far
 * framebuffers (the masks stayed cleared). This renderer uses the game's own compiled position
 * shader and issues {@code glDrawArrays} directly, bypassing every Minecraft/Iris/Veil draw hook
 * without compiling any new GLSL.</p>
 *
 * <p>With an Iris shader pack the vanilla position program is replaced and no longer carries the
 * {@code ModelViewMat}/{@code ProjMat} uniforms, so the fallback below compiles a private
 * depth-only GLSL program instead. It is not registered with Iris and is only ever used while
 * Nautilus' own framebuffer is bound, so Iris cannot redirect it.</p>
 */
public final class RawDepthRenderer {
    private static final String VERTEX_SOURCE = """
            #version 150
            in vec3 Position;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 150
            out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0);
            }
            """;
    private static int program = -1;
    private static int modelViewUniform = -1;
    private static int projectionUniform = -1;
    private static int vao = -1;
    private static int vbo = -1;
    private static boolean ownsProgram;
    private static boolean failed;

    private RawDepthRenderer() {
    }

    /**
     * Draws camera-relative positions with the given rotation-only view and projection.
     * The caller owns the depth-test/mask/clear state.
     */
    public static synchronized void draw(final List<Float> positions,
                                         final Matrix4f view,
                                         final Matrix4f projection) {
        if (failed || positions.isEmpty()) {
            return;
        }
        if (!ensureReady()) {
            return;
        }

        final FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(positions.size());
        for (final float value : positions) {
            vertexBuffer.put(value);
        }
        vertexBuffer.flip();

        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL20C.glUseProgram(program);
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertexBuffer, GL15C.GL_STREAM_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 12, 0L);

            final FloatBuffer projectionBuffer = BufferUtils.createFloatBuffer(16);
            projection.get(projectionBuffer);
            GL20C.glUniformMatrix4fv(projectionUniform, false, projectionBuffer);
            final FloatBuffer viewBuffer = BufferUtils.createFloatBuffer(16);
            view.get(viewBuffer);
            GL20C.glUniformMatrix4fv(modelViewUniform, false, viewBuffer);

            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, positions.size() / 3);
        } finally {
            GL20C.glDisableVertexAttribArray(0);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            GL30C.glBindVertexArray(previousVao);
            GL20C.glUseProgram(previousProgram);
        }
    }

    private static boolean ensureReady() {
        if (program >= 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            final ShaderInstance shader = GameRenderer.getPositionShader();
            if (shader != null) {
                program = shader.getId();
                modelViewUniform = GL20C.glGetUniformLocation(program, "ModelViewMat");
                projectionUniform = GL20C.glGetUniformLocation(program, "ProjMat");
            }
            if (program < 0 || modelViewUniform < 0 || projectionUniform < 0) {
                // Iris replaces the vanilla position program; its uniforms are gone. Compile a
                // private depth-only program whose uniform locations are known.
                CreateNautilus.LOGGER.info(
                        "[occlusion] vanilla position shader unusable "
                                + "(program={}, ModelViewMat={}, ProjMat={}); "
                                + "compiling private depth shader",
                        program, modelViewUniform, projectionUniform);
                program = compileDepthProgram();
                ownsProgram = true;
                modelViewUniform = GL20C.glGetUniformLocation(program, "ModelViewMat");
                projectionUniform = GL20C.glGetUniformLocation(program, "ProjMat");
                if (modelViewUniform < 0 || projectionUniform < 0) {
                    throw new IllegalStateException(
                            "private depth shader uniforms not found "
                                    + "(ModelViewMat=" + modelViewUniform
                                    + ", ProjMat=" + projectionUniform + ")");
                }
            }
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            CreateNautilus.LOGGER.info(
                    "[occlusion] raw depth pass ready (vanilla program={})", program);
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            discardCurrentResourcesOnRenderThread();
            failed = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to use the vanilla position shader for compartment occlusion",
                    exception);
            return false;
        }
    }

    /**
     * Drops all private GL state and clears the one-generation failure latch. The exact old object
     * ids are captured before an off-thread deletion is queued, so a subsequently recreated
     * renderer cannot be deleted by a stale reload callback.
     */
    public static synchronized void reset() {
        final int doomedProgram = ownsProgram ? program : -1;
        final int doomedVao = vao;
        final int doomedVbo = vbo;
        program = -1;
        modelViewUniform = -1;
        projectionUniform = -1;
        vao = -1;
        vbo = -1;
        ownsProgram = false;
        failed = false;

        final Runnable deletion = () -> deleteResources(doomedProgram, doomedVao, doomedVbo);
        if (RenderSystem.isOnRenderThread()) {
            deletion.run();
        } else {
            RenderSystem.recordRenderCall(deletion::run);
        }
    }

    /** Full lifecycle alias used on level/client shutdown. */
    public static void destroy() {
        reset();
    }

    private static void discardCurrentResourcesOnRenderThread() {
        deleteResources(ownsProgram ? program : -1, vao, vbo);
        program = -1;
        modelViewUniform = -1;
        projectionUniform = -1;
        vao = -1;
        vbo = -1;
        ownsProgram = false;
    }

    private static void deleteResources(final int doomedProgram,
                                        final int doomedVao,
                                        final int doomedVbo) {
        if (doomedVbo >= 0) {
            GL15C.glDeleteBuffers(doomedVbo);
        }
        if (doomedVao >= 0) {
            GL30C.glDeleteVertexArrays(doomedVao);
        }
        if (doomedProgram >= 0) {
            GL20C.glDeleteProgram(doomedProgram);
        }
    }

    private static int compileDepthProgram() throws IllegalStateException {
        final int vertex = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
        final int fragment = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
        int candidateProgram = -1;
        boolean linked = false;
        try {
            GL20C.glShaderSource(vertex, VERTEX_SOURCE);
            GL20C.glCompileShader(vertex);
            checkShader(vertex, "vertex");
            GL20C.glShaderSource(fragment, FRAGMENT_SOURCE);
            GL20C.glCompileShader(fragment);
            checkShader(fragment, "fragment");

            candidateProgram = GL20C.glCreateProgram();
            GL20C.glAttachShader(candidateProgram, vertex);
            GL20C.glAttachShader(candidateProgram, fragment);
            GL20C.glBindAttribLocation(candidateProgram, 0, "Position");
            GL20C.glLinkProgram(candidateProgram);
            if (GL20C.glGetProgrami(candidateProgram,
                    GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                final String log = GL20C.glGetProgramInfoLog(candidateProgram);
                throw new IllegalStateException("private depth shader link failed: " + log);
            }
            linked = true;
            return candidateProgram;
        } finally {
            if (!linked && candidateProgram >= 0) {
                GL20C.glDeleteProgram(candidateProgram);
            }
            GL20C.glDeleteShader(vertex);
            GL20C.glDeleteShader(fragment);
        }
    }

    private static void checkShader(final int shader, final String stage)
            throws IllegalStateException {
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            final String log = GL20C.glGetShaderInfoLog(shader);
            throw new IllegalStateException(
                    "private depth " + stage + " shader compile failed: " + log);
        }
    }
}
