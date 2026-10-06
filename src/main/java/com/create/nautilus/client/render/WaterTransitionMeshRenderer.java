package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

import java.nio.FloatBuffer;

/** Private raw-GL renderer for camera-relative glass medium-transition triangles. */
final class WaterTransitionMeshRenderer {
    private static final String VERTEX_SOURCE = """
            #version 150
            in vec3 Position;
            in float EventKind;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            out vec3 nautilusCameraRelative;
            flat out float nautilusEventKind;
            void main() {
                nautilusCameraRelative = Position;
                nautilusEventKind = EventKind;
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 150
             uniform sampler2D PreviousDepth;
             uniform sampler2D CoverageStatus;
             uniform sampler2D SceneDepth;
             uniform vec2 ViewportSize;
             uniform int HasPreviousDepth;
             uniform int PreviousIsCoverage;
             uniform int RequireCoverage;
             uniform int CoveragePass;
             uniform int InitialWater;
             uniform int ReversedDepth;
             uniform int HasSceneDepth;
            uniform float ExpectedKind;
            uniform float InvalidStatus;
            uniform int OverflowPass;
            uniform int StatusOnly;
            in vec3 nautilusCameraRelative;
            flat in float nautilusEventKind;
             out vec4 Segments;
             out float Status;
             void main() {
                 vec2 uv = gl_FragCoord.xy / ViewportSize;
                 if (CoveragePass != 0) {
                      if (abs(nautilusEventKind) < 1.5) {
                          discard;
                      }
                      // Gate pass: the caller attaches an R32F texture as colour attachment zero.
                      // Only the red component is stored; alpha-aware coverage is handled by the
                      // dedicated gate mesh and no water interval is initialised here.
                      float gateDistance = length(nautilusCameraRelative);
                      Segments = vec4(gateDistance);
                      Status = 0.0;
                      return;
                  }
                 // EventKind=2 is an aperture-only triangle, never a medium transition.
                  if (abs(nautilusEventKind) > 1.5) {
                      discard;
                   }
                   if (HasSceneDepth != 0) {
                       // Private geometry is intentionally unjittered.  Shader packs jitter their
                       // opaque depth by less than one pixel, so accept the conservative 3x3
                       // endpoint: an event is discarded only when it lies behind every possible
                       // same-ray sample.  Near silhouettes this may fail open for one pixel, but
                       // it cannot turn a hidden third water segment into a temporal false alarm.
                       vec2 sceneTexel = 1.0 / vec2(textureSize(SceneDepth, 0));
                       float sceneDepth = ReversedDepth != 0 ? 1.0 : 0.0;
                       for (int sampleY = -1; sampleY <= 1; ++sampleY) {
                           for (int sampleX = -1; sampleX <= 1; ++sampleX) {
                               float candidate = texture(SceneDepth, uv
                                       + vec2(sampleX, sampleY) * sceneTexel).r;
                               sceneDepth = ReversedDepth != 0
                                       ? min(sceneDepth, candidate)
                                       : max(sceneDepth, candidate);
                           }
                       }
                       bool behindOpaque = ReversedDepth != 0
                               ? gl_FragCoord.z <= sceneDepth
                              : gl_FragCoord.z >= sceneDepth;
                      if (behindOpaque) {
                          discard;
                      }
                  }
                 if (StatusOnly == 0 && RequireCoverage != 0
                         && texture(CoverageStatus, uv).r < 0.25) {
                     discard;
                 }
                 if (HasPreviousDepth != 0) {
                     float previous = texture(PreviousDepth, uv).r;
                    // Both values come from DEPTH_COMPONENT32F rasterisation of this exact mesh.
                    // Strict comparison rejects the previously peeled surface without swallowing
                    // a distinct, very close transition at large view distances.
                    bool rejected;
                    if (ReversedDepth != 0) {
                        rejected = PreviousIsCoverage != 0
                                ? gl_FragCoord.z > previous
                                : gl_FragCoord.z >= previous;
                    } else {
                        rejected = PreviousIsCoverage != 0
                                ? gl_FragCoord.z < previous
                                : gl_FragCoord.z <= previous;
                    }
                     if (rejected) {
                        discard;
                    }
                }
                bool invalid = OverflowPass != 0
                        || nautilusEventKind * ExpectedKind < 0.5;
                if (StatusOnly != 0) {
                    if (!invalid) {
                        discard;
                    }
                    // The validation draw targets both attachments.  Poison every segment
                    // channel as well as setting R8 status so composite adapters cannot
                    // accidentally consume a partially written interval.
                    Segments = vec4(-2.0);
                    Status = InvalidStatus;
                    return;
                }
                 float distanceToCamera = length(nautilusCameraRelative);
                 if (invalid) {
                     Segments = vec4(-2.0);
                 } else if (InitialWater != 0 && ExpectedKind < -0.5) {
                     // On the first water-to-air event the caller enables R and G together:
                     // the first segment is [0, distance]. No full-screen water seed is needed.
                     Segments = vec4(0.0, distanceToCamera,
                             distanceToCamera, distanceToCamera);
                 } else {
                     Segments = vec4(distanceToCamera);
                 }
                 Status = invalid ? InvalidStatus : 0.0;
            }
            """;
    private static final String GATE_VERTEX_SOURCE = """
            #version 150
            in vec3 Position;
            in vec2 UV;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            out vec3 nautilusCameraRelative;
            out vec2 nautilusAtlasUv;
            void main() {
                nautilusCameraRelative = Position;
                nautilusAtlasUv = UV;
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            }
            """;
    private static final String GATE_FRAGMENT_SOURCE = """
            #version 150
            uniform sampler2D BlockAtlas;
            uniform sampler2D CoverageMask;
            uniform float AlphaCutoff;
            uniform vec2 ViewportSize;
            uniform int RequireCoverage;
            uniform int CoverageOnly;
            in vec3 nautilusCameraRelative;
            in vec2 nautilusAtlasUv;
            out vec4 Segments;
            out float Status;
            void main() {
                // Always sample the base atlas level.  Derivative-selected mip levels can blend
                // an opaque frame into a clear pane and make a static window gate flicker under
                // shader-pack TAA while the game is paused.
                float alpha = textureLod(BlockAtlas, nautilusAtlasUv, 0.0).a;
                // The hidden gate represents the optical aperture, not the visible frame.
                // Vanilla/Create framed glass commonly stores the clear pane as alpha=0 and
                // the metal/lead frame as alpha=1, so retaining high-alpha texels inverted the
                // mask and recreated a block-shaped effect.  AlphaCutoff is the render layer's
                // conservative opacity tolerance (0 for translucent/solid, 0.1 for cutout).
                float opaqueThreshold = 1.0 - clamp(AlphaCutoff, 0.0, 0.5);
                if (!(alpha < opaqueThreshold)) {
                    discard;
                }
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                if (RequireCoverage != 0 && texture(CoverageMask, uv).r < 0.5) {
                    discard;
                }
                Segments = CoverageOnly != 0
                        ? vec4(1.0) : vec4(length(nautilusCameraRelative));
                Status = 0.0;
            }
            """;

    private static int program = -1;
    private static int modelViewUniform = -1;
    private static int projectionUniform = -1;
    private static int previousDepthUniform = -1;
    private static int coverageStatusUniform = -1;
    private static int sceneDepthUniform = -1;
    private static int viewportUniform = -1;
    private static int hasPreviousUniform = -1;
    private static int previousIsCoverageUniform = -1;
    private static int requireCoverageUniform = -1;
    private static int coveragePassUniform = -1;
    private static int initialWaterUniform = -1;
    private static int reversedDepthUniform = -1;
    private static int hasSceneDepthUniform = -1;
    private static int expectedKindUniform = -1;
    private static int invalidStatusUniform = -1;
    private static int overflowUniform = -1;
    private static int statusOnlyUniform = -1;
    private static int vao = -1;
    private static int vbo = -1;
    private static int uploadedVertexCount;
    private static int gateProgram = -1;
    private static int gateModelViewUniform = -1;
    private static int gateProjectionUniform = -1;
    private static int gateAtlasUniform = -1;
    private static int gateCoverageUniform = -1;
    private static int gateAlphaUniform = -1;
    private static int gateViewportUniform = -1;
    private static int gateRequireCoverageUniform = -1;
    private static int gateCoverageOnlyUniform = -1;
    private static int gateVao = -1;
    private static int gateVbo = -1;
    private static int uploadedGateVertexCount;
    private static boolean failed;

    private WaterTransitionMeshRenderer() {
    }

    /** Uploads one immutable transition mesh for all peel and validation draws in this frame. */
    static boolean upload(final FloatBuffer vertices) {
        uploadedVertexCount = 0;
        if (!vertices.hasRemaining() || failed || !ensureReady()) {
            return !failed;
        }
        if (vertices.remaining() % 4 != 0) {
            return false;
        }
        final int floatCount = vertices.remaining();

        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STREAM_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 16, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 1, GL11C.GL_FLOAT, false, 16, 12L);
            uploadedVertexCount = floatCount / 4;
            return true;
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            GL30C.glBindVertexArray(previousVao);
        }
    }

    /** Uploads alpha-aware gate vertices as position XYZ plus block-atlas UV. */
    static boolean uploadGate(final FloatBuffer vertices) {
        uploadedGateVertexCount = 0;
        if (!vertices.hasRemaining() || failed || !ensureReady()) {
            return !failed;
        }
        if (vertices.remaining() % 5 != 0) {
            return false;
        }
        final int floatCount = vertices.remaining();

        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30C.glBindVertexArray(gateVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, gateVbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STREAM_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 20, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 20, 12L);
            uploadedGateVertexCount = floatCount / 5;
            return true;
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            GL30C.glBindVertexArray(previousVao);
        }
    }

    /** Draws one atlas/alpha batch into the currently attached R32F gate target. */
    static boolean drawGate(final Matrix4f modelView,
                            final Matrix4f projection,
                            final int atlasTexture,
                            final float alphaCutoff,
                            final int coverageTexture,
                            final boolean requireCoverage,
                            final boolean coverageOnly,
                            final int viewportWidth,
                            final int viewportHeight) {
        if (uploadedGateVertexCount <= 0 || atlasTexture <= 0
                || (requireCoverage && coverageTexture <= 0)
                || viewportWidth <= 0 || viewportHeight <= 0
                || failed || !ensureReady()) {
            return uploadedGateVertexCount <= 0 || (!failed && atlasTexture > 0
                    && (!requireCoverage || coverageTexture > 0)
                    && viewportWidth > 0 && viewportHeight > 0);
        }
        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final boolean samplerObjects = GL.getCapabilities().OpenGL33;
        final int previousSampler = samplerObjects
                ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        final int previousCoverageTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int previousCoverageSampler = samplerObjects
                ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        try {
            GL20C.glUseProgram(gateProgram);
            GL30C.glBindVertexArray(gateVao);
            final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
            projection.get(matrix);
            GL20C.glUniformMatrix4fv(gateProjectionUniform, false, matrix);
            matrix.clear();
            modelView.get(matrix);
            GL20C.glUniformMatrix4fv(gateModelViewUniform, false, matrix);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            if (samplerObjects) {
                GL33C.glBindSampler(0, 0);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, atlasTexture);
            GL20C.glUniform1i(gateAtlasUniform, 0);
            GL20C.glUniform1f(gateAlphaUniform, alphaCutoff);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            if (samplerObjects) {
                GL33C.glBindSampler(1, 0);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                    requireCoverage ? coverageTexture : 0);
            GL20C.glUniform1i(gateCoverageUniform, 1);
            GL20C.glUniform2f(gateViewportUniform, viewportWidth, viewportHeight);
            GL20C.glUniform1i(gateRequireCoverageUniform, requireCoverage ? 1 : 0);
            GL20C.glUniform1i(gateCoverageOnlyUniform, coverageOnly ? 1 : 0);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, uploadedGateVertexCount);
            return true;
        } finally {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousCoverageTexture);
            if (samplerObjects) {
                GL33C.glBindSampler(1, previousCoverageSampler);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            if (samplerObjects) {
                GL33C.glBindSampler(0, previousSampler);
            }
            GL13C.glActiveTexture(previousActive);
            GL30C.glBindVertexArray(previousVao);
            GL20C.glUseProgram(previousProgram);
        }
    }

    /**
     * Draws the mesh uploaded by {@link #upload(FloatBuffer)}. Event kind is +1 for air-to-water and -1
     * for water-to-air. The framebuffer, draw buffers, colour masks and depth state are
     * caller-owned.
     */
    static boolean draw(final Matrix4f modelView,
                        final Matrix4f projection,
                         final int previousDepthTexture,
                         final boolean hasPreviousDepth,
                         final boolean previousIsCoverage,
                         final int coverageStatusTexture,
                         final boolean requireCoverage,
                         final int sceneDepthTexture,
                         final boolean hasSceneDepth,
                         final int viewportWidth,
                         final int viewportHeight,
                         final float expectedKind,
                         final boolean overflowPass,
                         final boolean statusOnly,
                         final boolean coveragePass,
                         final boolean initialWater,
                         final boolean reversedDepth,
                         final float invalidStatus) {
        if (uploadedVertexCount <= 0 || failed || !ensureReady()) {
            return !failed;
        }

        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final boolean samplerObjects = GL.getCapabilities().OpenGL33;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        final int previousDepthBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int previousDepthSampler = samplerObjects
                ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        final int previousCoverageBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int previousCoverageSampler = samplerObjects
                 ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        final int previousSceneDepthBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int previousSceneDepthSampler = samplerObjects
                ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        try {
            GL20C.glUseProgram(program);
            GL30C.glBindVertexArray(vao);

            final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
            projection.get(matrix);
            GL20C.glUniformMatrix4fv(projectionUniform, false, matrix);
            matrix.clear();
            modelView.get(matrix);
            GL20C.glUniformMatrix4fv(modelViewUniform, false, matrix);

            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            if (samplerObjects) {
                GL33C.glBindSampler(0, 0);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                    hasPreviousDepth ? previousDepthTexture : 0);
            GL20C.glUniform1i(previousDepthUniform, 0);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            if (samplerObjects) {
                GL33C.glBindSampler(1, 0);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                    requireCoverage ? coverageStatusTexture : 0);
            GL20C.glUniform1i(coverageStatusUniform, 1);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            if (samplerObjects) {
                GL33C.glBindSampler(2, 0);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,
                    hasSceneDepth ? sceneDepthTexture : 0);
            GL20C.glUniform1i(sceneDepthUniform, 2);
            GL20C.glUniform2f(viewportUniform, viewportWidth, viewportHeight);
            GL20C.glUniform1i(hasPreviousUniform, hasPreviousDepth ? 1 : 0);
            GL20C.glUniform1i(previousIsCoverageUniform,
                    previousIsCoverage ? 1 : 0);
            GL20C.glUniform1i(requireCoverageUniform, requireCoverage ? 1 : 0);
            GL20C.glUniform1i(coveragePassUniform, coveragePass ? 1 : 0);
            GL20C.glUniform1i(initialWaterUniform, initialWater ? 1 : 0);
            GL20C.glUniform1i(reversedDepthUniform, reversedDepth ? 1 : 0);
            GL20C.glUniform1i(hasSceneDepthUniform, hasSceneDepth ? 1 : 0);
            GL20C.glUniform1f(expectedKindUniform, expectedKind);
            GL20C.glUniform1f(invalidStatusUniform, invalidStatus);
            GL20C.glUniform1i(overflowUniform, overflowPass ? 1 : 0);
            GL20C.glUniform1i(statusOnlyUniform, statusOnly ? 1 : 0);

            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, uploadedVertexCount);
            return true;
        } finally {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousSceneDepthBinding);
            if (samplerObjects) {
                GL33C.glBindSampler(2, previousSceneDepthSampler);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousCoverageBinding);
            if (samplerObjects) {
                GL33C.glBindSampler(1, previousCoverageSampler);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousDepthBinding);
            if (samplerObjects) {
                GL33C.glBindSampler(0, previousDepthSampler);
            }
            GL13C.glActiveTexture(previousActive);
            GL30C.glBindVertexArray(previousVao);
            GL20C.glUseProgram(previousProgram);
        }
    }

    private static boolean ensureReady() {
        if (program >= 0) {
            return true;
        }
        try {
            program = compileProgram();
            modelViewUniform = uniform("ModelViewMat");
            projectionUniform = uniform("ProjMat");
            previousDepthUniform = uniform("PreviousDepth");
            coverageStatusUniform = uniform("CoverageStatus");
            sceneDepthUniform = uniform("SceneDepth");
            viewportUniform = uniform("ViewportSize");
            hasPreviousUniform = uniform("HasPreviousDepth");
            previousIsCoverageUniform = uniform("PreviousIsCoverage");
            requireCoverageUniform = uniform("RequireCoverage");
            coveragePassUniform = uniform("CoveragePass");
            initialWaterUniform = uniform("InitialWater");
            reversedDepthUniform = uniform("ReversedDepth");
            hasSceneDepthUniform = uniform("HasSceneDepth");
            expectedKindUniform = uniform("ExpectedKind");
            invalidStatusUniform = uniform("InvalidStatus");
            overflowUniform = uniform("OverflowPass");
            statusOnlyUniform = uniform("StatusOnly");
            vao = GL30C.glGenVertexArrays();
            vbo = GL15C.glGenBuffers();
            gateProgram = compileGateProgram();
            gateModelViewUniform = uniform(gateProgram, "ModelViewMat");
            gateProjectionUniform = uniform(gateProgram, "ProjMat");
            gateAtlasUniform = uniform(gateProgram, "BlockAtlas");
            gateCoverageUniform = uniform(gateProgram, "CoverageMask");
            gateAlphaUniform = uniform(gateProgram, "AlphaCutoff");
            gateViewportUniform = uniform(gateProgram, "ViewportSize");
            gateRequireCoverageUniform = uniform(gateProgram, "RequireCoverage");
            gateCoverageOnlyUniform = uniform(gateProgram, "CoverageOnly");
            gateVao = GL30C.glGenVertexArrays();
            gateVbo = GL15C.glGenBuffers();
            CreateNautilus.LOGGER.info(
                    "[water-segments] private transition renderer ready (program={})", program);
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            failed = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to initialise the water-medium transition renderer", exception);
            destroy();
            return false;
        }
    }

    private static int uniform(final String name) {
        return uniform(program, name);
    }

    private static int uniform(final int owner, final String name) {
        final int location = GL20C.glGetUniformLocation(owner, name);
        if (location < 0) {
            throw new IllegalStateException("private transition uniform not found: " + name);
        }
        return location;
    }

    private static int compileProgram() {
        int vertex = -1;
        int fragment = -1;
        int result = -1;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, VERTEX_SOURCE, "vertex");
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SOURCE, "fragment");
            result = GL20C.glCreateProgram();
            GL20C.glAttachShader(result, vertex);
            GL20C.glAttachShader(result, fragment);
            GL20C.glBindAttribLocation(result, 0, "Position");
            GL20C.glBindAttribLocation(result, 1, "EventKind");
            GL30C.glBindFragDataLocation(result, 0, "Segments");
            GL30C.glBindFragDataLocation(result, 1, "Status");
            GL20C.glLinkProgram(result);
            if (GL20C.glGetProgrami(result, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                throw new IllegalStateException(
                        "private transition program link failed: "
                                + GL20C.glGetProgramInfoLog(result));
            }
            return result;
        } catch (final RuntimeException exception) {
            if (result >= 0) GL20C.glDeleteProgram(result);
            throw exception;
        } finally {
            if (vertex >= 0) GL20C.glDeleteShader(vertex);
            if (fragment >= 0) GL20C.glDeleteShader(fragment);
        }
    }

    private static int compileGateProgram() {
        int vertex = -1;
        int fragment = -1;
        int result = -1;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, GATE_VERTEX_SOURCE, "gate vertex");
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, GATE_FRAGMENT_SOURCE,
                    "gate fragment");
            result = GL20C.glCreateProgram();
            GL20C.glAttachShader(result, vertex);
            GL20C.glAttachShader(result, fragment);
            GL20C.glBindAttribLocation(result, 0, "Position");
            GL20C.glBindAttribLocation(result, 1, "UV");
            GL30C.glBindFragDataLocation(result, 0, "Segments");
            GL30C.glBindFragDataLocation(result, 1, "Status");
            GL20C.glLinkProgram(result);
            if (GL20C.glGetProgrami(result, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                throw new IllegalStateException(
                        "private gate program link failed: " + GL20C.glGetProgramInfoLog(result));
            }
            return result;
        } catch (final RuntimeException exception) {
            if (result >= 0) GL20C.glDeleteProgram(result);
            throw exception;
        } finally {
            if (vertex >= 0) GL20C.glDeleteShader(vertex);
            if (fragment >= 0) GL20C.glDeleteShader(fragment);
        }
    }

    private static int compile(final int type, final String source, final String stage) {
        final int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            final String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException(
                    "private transition " + stage + " shader compile failed: " + log);
        }
        return shader;
    }

    static void resetFailure() {
        failed = false;
    }

    static void destroy() {
        uploadedVertexCount = 0;
        uploadedGateVertexCount = 0;
        if (vbo >= 0) {
            GL15C.glDeleteBuffers(vbo);
            vbo = -1;
        }
        if (vao >= 0) {
            GL30C.glDeleteVertexArrays(vao);
            vao = -1;
        }
        if (program >= 0) {
            GL20C.glDeleteProgram(program);
            program = -1;
        }
        if (gateVbo >= 0) {
            GL15C.glDeleteBuffers(gateVbo);
            gateVbo = -1;
        }
        if (gateVao >= 0) {
            GL30C.glDeleteVertexArrays(gateVao);
            gateVao = -1;
        }
        if (gateProgram >= 0) {
            GL20C.glDeleteProgram(gateProgram);
            gateProgram = -1;
        }
        modelViewUniform = -1;
        projectionUniform = -1;
        previousDepthUniform = -1;
        coverageStatusUniform = -1;
        sceneDepthUniform = -1;
        viewportUniform = -1;
        hasPreviousUniform = -1;
        previousIsCoverageUniform = -1;
        requireCoverageUniform = -1;
        coveragePassUniform = -1;
        initialWaterUniform = -1;
        reversedDepthUniform = -1;
        hasSceneDepthUniform = -1;
        expectedKindUniform = -1;
        invalidStatusUniform = -1;
        overflowUniform = -1;
        statusOnlyUniform = -1;
        gateModelViewUniform = -1;
        gateProjectionUniform = -1;
        gateAtlasUniform = -1;
        gateCoverageUniform = -1;
        gateAlphaUniform = -1;
        gateViewportUniform = -1;
        gateRequireCoverageUniform = -1;
        gateCoverageOnlyUniform = -1;
    }
}
