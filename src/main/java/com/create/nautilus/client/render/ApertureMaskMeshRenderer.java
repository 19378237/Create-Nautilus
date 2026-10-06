package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

import java.nio.FloatBuffer;

/** Private, persistent-VBO rasterizer for the single D24S8 optical-medium mask. */
final class ApertureMaskMeshRenderer {
    private static final FloatBuffer MATRIX_UPLOAD = BufferUtils.createFloatBuffer(16);

    private static final String MASK_VERTEX = """
            #version 150
            in vec3 Position;
            in vec2 UV;
            in float EventSlot;
            in float FaceSign;
            in float OutwardSign;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform vec3 EventVoxelOrigin;
            flat out float nautilusEventSlot;
            flat out float nautilusFaceSign;
            flat out float nautilusOutwardSign;
            out vec2 nautilusAtlasUv;
            out vec3 nautilusLocalPosition;
            out vec3 nautilusViewPosition;
            void main() {
                nautilusEventSlot = EventSlot;
                nautilusFaceSign = FaceSign;
                nautilusOutwardSign = OutwardSign;
                nautilusAtlasUv = UV;
                // Event planes are deliberately camera-independent and use small coordinates
                // relative to the sparse atlas' frozen voxel origin. Position itself is relative
                // to the aperture-cache origin so large plot coordinates never enter a float VBO.
                nautilusLocalPosition = Position - EventVoxelOrigin;
                vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
                nautilusViewPosition = viewPosition.xyz;
                gl_Position = ProjMat * viewPosition;
            }
            """;

    private static final String MASK_FRAGMENT = """
            #version 150
            uniform sampler2D EventPlanes;
            uniform sampler2D SceneDepth;
            uniform vec2 EventPlaneTextureSize;
            uniform vec2 TargetSize;
            uniform float SceneDepthScale;
            uniform vec2 SceneDepthUvOffset;
            uniform float EventPlaneBase;
            uniform int HasSceneDepth;
            uniform int ReversedDepth;
            uniform mat4 ModelViewMat;
            flat in float nautilusEventSlot;
            flat in float nautilusFaceSign;
            flat in float nautilusOutwardSign;
            in vec3 nautilusLocalPosition;
            in vec3 nautilusViewPosition;

            vec4 eventPlane(float linearIndex) {
                float x = mod(linearIndex, EventPlaneTextureSize.x);
                float y = floor(linearIndex / EventPlaneTextureSize.x);
                vec2 uv = (vec2(x, y) + vec2(0.5)) / EventPlaneTextureSize;
                return texture(EventPlanes, uv);
            }

            void main() {
                // Provider-confirmed optical model triangles describe the physical membrane.
                // Texture decorations must not punch repeating holes in the medium boundary.
                float slot = floor(nautilusEventSlot + 0.5);
                if (slot < 0.0 || EventPlaneTextureSize.x < 1.0
                        || EventPlaneTextureSize.y < 1.0) discard;
                float first = EventPlaneBase + slot * 3.0;
                vec4 negativePlane = eventPlane(first);
                vec4 positivePlane = eventPlane(first + 1.0);
                vec4 metadata = eventPlane(first + 2.0);
                if (metadata.y < 0.5 || abs(metadata.z - slot) > 0.25
                        || any(notEqual(negativePlane, negativePlane))
                        || any(notEqual(positivePlane, positivePlane))) discard;
                int axis = int(floor(metadata.x + 0.5));
                bool floodedExteriorShell = metadata.y > 2.5;
                float outwardCode = floodedExteriorShell ? metadata.y - 2.0 : metadata.y;
                float eventOutwardSign = outwardCode > 1.5 ? 1.0 : -1.0;
                if (axis < 0 || axis > 2
                        || abs(abs(nautilusFaceSign) - 1.0) > 0.25
                        || abs(abs(nautilusOutwardSign) - 1.0) > 0.25
                        || abs(eventOutwardSign - nautilusOutwardSign) > 0.25)
                    discard;
                vec3 axisVector = axis == 0 ? vec3(1.0, 0.0, 0.0)
                        : axis == 1 ? vec3(0.0, 1.0, 0.0)
                        : vec3(0.0, 0.0, 1.0);
                vec3 sideStep = axisVector * 0.0009765625;
                // Provider geometry already lies on the real glass surface. Keep the mask there:
                // this is the exterior water-side face, with no extra inward or outward cell shift.
                vec3 semanticPosition = nautilusLocalPosition;
                if (floodedExteriorShell) {
                    // The virtual surface owns the flooded room's fog. The optical membrane owns
                    // only the exterior ocean interval, so room-water/exterior-water equality
                    // must not erase it. Both viewing directions are valid: composite decoding
                    // chooses an outside prefix or an inside suffix from the ray direction.
                    vec3 localOutward = axisVector * eventOutwardSign;
                    vec4 exteriorPlane = eventOutwardSign > 0.0
                            ? positivePlane : negativePlane;
                    float exteriorField = dot(exteriorPlane.xyz,
                            semanticPosition + localOutward * 0.0009765625)
                            + exteriorPlane.w;
                    if (exteriorField > -0.0001) discard;
                } else {
                    float negativeField = dot(negativePlane.xyz,
                            semanticPosition - sideStep)
                            + negativePlane.w;
                    float positiveField = dot(positivePlane.xyz,
                            semanticPosition + sideStep)
                            + positivePlane.w;
                    bool negativeWater = negativeField <= -0.0001;
                    bool positiveWater = positiveField <= -0.0001;
                    if (negativeWater == positiveWater) discard;
                }

                if (HasSceneDepth != 0) {
                    vec2 uv = gl_FragCoord.xy / max(TargetSize, vec2(1.0));
                    vec2 sceneUv = uv * SceneDepthScale + SceneDepthUvOffset;
                    if (any(lessThan(sceneUv, vec2(0.0)))
                            || any(greaterThanEqual(sceneUv, vec2(1.0)))) discard;
                    float scene = texture(SceneDepth, sceneUv).r;
                    // Cutout glass writes scene depth only at the frame pixels even though the
                    // connected texture removes internal frames. Its boundary geometry is
                    // coplanar with that depth, so equality must survive; only geometry strictly
                    // nearer to the camera (water flow, held translucents, etc.) hides the mask.
                    bool hidden = ReversedDepth != 0
                            ? gl_FragCoord.z < scene - 0.0000005
                            : gl_FragCoord.z > scene + 0.0000005;
                    if (hidden) discard;
                }
            }
            """;

    private static int maskProgram = -1;
    private static int maskModelView = -1;
    private static int maskProjection = -1;
    private static int maskEventVoxelOrigin = -1;
    private static int maskEventPlanes = -1;
    private static int maskSceneDepth = -1;
    private static int maskPlaneTextureSize = -1;
    private static int maskTargetSize = -1;
    private static int maskSceneDepthScale = -1;
    private static int maskSceneDepthUvOffset = -1;
    private static int maskPlaneBase = -1;
    private static int maskHasSceneDepth = -1;
    private static int maskReversedDepth = -1;
    private static int nearestSampler = -1;
    private static boolean failed;

    private ApertureMaskMeshRenderer() {
    }

    static GpuBatch upload(final float[] vertices) {
        if (vertices == null || vertices.length == 0
                || vertices.length % ApertureEventCache.VERTEX_STRIDE != 0
                || failed || !ensureReady()) {
            return null;
        }
        final FloatBuffer data = BufferUtils.createFloatBuffer(vertices.length);
        data.put(vertices).flip();
        final int vao = GL30C.glGenVertexArrays();
        final int vbo = GL15C.glGenBuffers();
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, data, GL15C.GL_STATIC_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            final int strideBytes = ApertureEventCache.VERTEX_STRIDE * Float.BYTES;
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, strideBytes, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, strideBytes, 12L);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 1, GL11C.GL_FLOAT, false, strideBytes, 20L);
            GL20C.glEnableVertexAttribArray(3);
            GL20C.glVertexAttribPointer(3, 1, GL11C.GL_FLOAT, false, strideBytes, 24L);
            GL20C.glEnableVertexAttribArray(4);
            GL20C.glVertexAttribPointer(4, 1, GL11C.GL_FLOAT, false, strideBytes, 28L);
            return new GpuBatch(vao, vbo,
                    vertices.length / ApertureEventCache.VERTEX_STRIDE);
        } catch (final RuntimeException | LinkageError exception) {
            if (vbo > 0) GL15C.glDeleteBuffers(vbo);
            if (vao > 0) GL30C.glDeleteVertexArrays(vao);
            throw exception;
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            GL30C.glBindVertexArray(previousVao);
        }
    }

    /**
     * Starts one aperture-mask pass. Shared program, texture and sampler state is
     * captured and installed once; callers must pair a non-null session with
     * {@link #end(DrawSession)}.
     */
    static DrawSession begin(final Matrix4f projection,
                             final int eventPlanes,
                             final int planeTextureWidth,
                             final int planeTextureHeight,
                             final int sceneDepth,
                             final int targetWidth,
                             final int targetHeight,
                             final float sceneDepthScale,
                             final float sceneDepthOffsetU,
                             final float sceneDepthOffsetV,
                             final boolean reversedDepth) {
        if (projection == null || eventPlanes <= 0
                || planeTextureWidth <= 0 || planeTextureHeight <= 0
                || targetWidth <= 0 || targetHeight <= 0
                || !Float.isFinite(sceneDepthScale) || sceneDepthScale <= 0.0F
                || !Float.isFinite(sceneDepthOffsetU)
                || !Float.isFinite(sceneDepthOffsetV)
                || failed || !ensureReady()) {
            return null;
        }

        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int texture0;
        final int texture1;
        final int texture2;
        final int sampler0;
        final int sampler1;
        final int sampler2;
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            texture0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            sampler0 = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            texture1 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            sampler1 = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 1);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            texture2 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            sampler2 = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 2);
        } finally {
            GL13C.glActiveTexture(previousActive);
        }

        final DrawSession session = new DrawSession(
                previousProgram, previousVao, previousActive,
                texture0, texture1, texture2,
                sampler0, sampler1, sampler2);
        try {
            GL20C.glUseProgram(maskProgram);
            uploadMatrix(maskProjection, projection);
            // Units one/two are data textures and keep the private NEAREST sampler.
            bindTexture(1, eventPlanes, maskEventPlanes);
            bindTexture(2, Math.max(0, sceneDepth), maskSceneDepth);
            GL20C.glUniform2f(maskPlaneTextureSize,
                    planeTextureWidth, planeTextureHeight);
            GL20C.glUniform2f(maskTargetSize, targetWidth, targetHeight);
            GL20C.glUniform1f(maskSceneDepthScale, sceneDepthScale);
            GL20C.glUniform2f(maskSceneDepthUvOffset,
                    sceneDepthOffsetU, sceneDepthOffsetV);
            GL20C.glUniform1i(maskHasSceneDepth, sceneDepth > 0 ? 1 : 0);
            GL20C.glUniform1i(maskReversedDepth, reversedDepth ? 1 : 0);
            return session;
        } catch (final RuntimeException | LinkageError exception) {
            end(session);
            throw exception;
        }
    }

    /** Draws one cached carrier batch inside an active mask pass. */
    static boolean draw(final DrawSession session,
                        final GpuBatch batch,
                        final Matrix4f modelView,
                        final int blockAtlas,
                        final float alphaCutoff,
                        final int eventPlaneBase,
                        final int voxelOriginX,
                        final int voxelOriginY,
                        final int voxelOriginZ) {
        if (session == null || session.closed
                || batch == null || batch.vertexCount() <= 0
                || modelView == null || blockAtlas <= 0) {
            return false;
        }
        GL30C.glBindVertexArray(batch.vao());
        uploadMatrix(maskModelView, modelView);
        GL20C.glUniform3f(maskEventVoxelOrigin,
                voxelOriginX, voxelOriginY, voxelOriginZ);
        GL20C.glUniform1f(maskPlaneBase, eventPlaneBase);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, batch.vertexCount());
        return true;
    }

    /** Restores all GL state captured by {@link #begin}. Safe to call twice. */
    static void end(final DrawSession session) {
        if (session == null || session.closed) return;
        session.closed = true;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, session.texture2);
        GL33C.glBindSampler(2, session.sampler2);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, session.texture1);
        GL33C.glBindSampler(1, session.sampler1);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, session.texture0);
        GL33C.glBindSampler(0, session.sampler0);
        GL13C.glActiveTexture(session.activeTexture);
        GL30C.glBindVertexArray(session.vao);
        GL20C.glUseProgram(session.program);
    }

    /** Compatibility wrapper for callers that have not adopted pass sessions yet. */
    @Deprecated
    static boolean draw(final GpuBatch batch,
                        final Matrix4f modelView,
                        final Matrix4f projection,
                        final int blockAtlas,
                        final float alphaCutoff,
                        final int eventPlanes,
                        final int planeTextureWidth,
                        final int planeTextureHeight,
                        final int eventPlaneBase,
                        final int voxelOriginX,
                        final int voxelOriginY,
                        final int voxelOriginZ,
                        final int sceneDepth,
                        final int targetWidth,
                        final int targetHeight,
                        final boolean reversedDepth) {
        if (batch == null || batch.vertexCount() <= 0 || blockAtlas <= 0) return false;
        final DrawSession session = begin(projection, eventPlanes,
                planeTextureWidth, planeTextureHeight, sceneDepth,
                targetWidth, targetHeight, 1.0F, 0.0F, 0.0F, reversedDepth);
        if (session == null) return false;
        try {
            return draw(session, batch, modelView, blockAtlas, alphaCutoff,
                    eventPlaneBase, voxelOriginX, voxelOriginY, voxelOriginZ);
        } finally {
            end(session);
        }
    }

    static void destroy(final GpuBatch batch) {
        if (batch == null) return;
        if (batch.vbo() > 0) GL15C.glDeleteBuffers(batch.vbo());
        if (batch.vao() > 0) GL30C.glDeleteVertexArrays(batch.vao());
    }

    static synchronized void resetFailure() {
        failed = false;
    }

    static synchronized void destroyPrograms() {
        if (maskProgram > 0) GL20C.glDeleteProgram(maskProgram);
        if (nearestSampler > 0) GL33C.glDeleteSamplers(nearestSampler);
        maskProgram = nearestSampler = -1;
        maskModelView = maskProjection = maskEventVoxelOrigin = -1;
        maskEventPlanes = -1;
        maskSceneDepth = maskPlaneTextureSize = maskTargetSize = -1;
        maskSceneDepthScale = maskSceneDepthUvOffset = maskPlaneBase = -1;
        maskHasSceneDepth = maskReversedDepth = -1;
        failed = false;
    }

    private static boolean ensureReady() {
        if (maskProgram > 0 && nearestSampler > 0) return true;
        if (failed) return false;
        try {
            if (!GL.getCapabilities().OpenGL33) {
                throw new IllegalStateException("private aperture sampler requires OpenGL 3.3");
            }
            maskProgram = link(MASK_VERTEX, MASK_FRAGMENT, true);
            maskModelView = uniform(maskProgram, "ModelViewMat");
            maskProjection = uniform(maskProgram, "ProjMat");
            maskEventVoxelOrigin = uniform(maskProgram, "EventVoxelOrigin");
            maskEventPlanes = uniform(maskProgram, "EventPlanes");
            maskSceneDepth = uniform(maskProgram, "SceneDepth");
            maskPlaneTextureSize = uniform(maskProgram, "EventPlaneTextureSize");
            maskTargetSize = uniform(maskProgram, "TargetSize");
            maskSceneDepthScale = uniform(maskProgram, "SceneDepthScale");
            maskSceneDepthUvOffset = uniform(maskProgram, "SceneDepthUvOffset");
            maskPlaneBase = uniform(maskProgram, "EventPlaneBase");
            maskHasSceneDepth = uniform(maskProgram, "HasSceneDepth");
            maskReversedDepth = uniform(maskProgram, "ReversedDepth");
            nearestSampler = GL33C.glGenSamplers();
            if (nearestSampler <= 0 || !GL33C.glIsSampler(nearestSampler)) {
                throw new IllegalStateException("private aperture sampler creation failed");
            }
            GL33C.glSamplerParameteri(nearestSampler,
                    GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(nearestSampler,
                    GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL33C.glSamplerParameteri(nearestSampler,
                    GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL33C.glSamplerParameteri(nearestSampler,
                    GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL33C.glSamplerParameteri(nearestSampler,
                    GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            failed = true;
            destroyPrograms();
            failed = true;
            CreateNautilus.LOGGER.warn(
                    "[water-entry] private D24S8 mask shader creation failed; using native water",
                    exception);
            return false;
        }
    }

    private static void bindSampler(final int unit, final int uniform) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL33C.glBindSampler(unit, nearestSampler);
        GL20C.glUniform1i(uniform, unit);
    }

    private static void bindTexture(final int unit, final int texture,
                                    final int uniform) {
        bindSampler(unit, uniform);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
    }

    private static void uploadMatrix(final int uniform, final Matrix4f matrix) {
        MATRIX_UPLOAD.clear();
        matrix.get(MATRIX_UPLOAD);
        GL20C.glUniformMatrix4fv(uniform, false, MATRIX_UPLOAD);
    }

    private static int link(final String vertexSource,
                            final String fragmentSource,
                            final boolean attributes) {
        final int vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource);
        final int fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource);
        final int program = GL20C.glCreateProgram();
        try {
            GL20C.glAttachShader(program, vertex);
            GL20C.glAttachShader(program, fragment);
            if (attributes) {
                GL20C.glBindAttribLocation(program, 0, "Position");
                GL20C.glBindAttribLocation(program, 1, "UV");
                GL20C.glBindAttribLocation(program, 2, "EventSlot");
                GL20C.glBindAttribLocation(program, 3, "FaceSign");
                GL20C.glBindAttribLocation(program, 4, "OutwardSign");
            }
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                throw new IllegalStateException("private aperture program link failed: "
                        + GL20C.glGetProgramInfoLog(program));
            }
            return program;
        } catch (final RuntimeException exception) {
            GL20C.glDeleteProgram(program);
            throw exception;
        } finally {
            GL20C.glDeleteShader(vertex);
            GL20C.glDeleteShader(fragment);
        }
    }

    private static int compile(final int type, final String source) {
        final int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            final String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("private aperture shader compilation failed: " + log);
        }
        return shader;
    }

    private static int uniform(final int program, final String name) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) throw new IllegalStateException("missing private uniform " + name);
        return location;
    }

    static final class DrawSession {
        private final int program;
        private final int vao;
        private final int activeTexture;
        private final int texture0;
        private final int texture1;
        private final int texture2;
        private final int sampler0;
        private final int sampler1;
        private final int sampler2;
        private boolean closed;

        private DrawSession(final int program,
                            final int vao,
                            final int activeTexture,
                            final int texture0,
                            final int texture1,
                            final int texture2,
                            final int sampler0,
                            final int sampler1,
                            final int sampler2) {
            this.program = program;
            this.vao = vao;
            this.activeTexture = activeTexture;
            this.texture0 = texture0;
            this.texture1 = texture1;
            this.texture2 = texture2;
            this.sampler0 = sampler0;
            this.sampler1 = sampler1;
            this.sampler2 = sampler2;
        }
    }

    record GpuBatch(int vao, int vbo, int vertexCount) {
    }
}
