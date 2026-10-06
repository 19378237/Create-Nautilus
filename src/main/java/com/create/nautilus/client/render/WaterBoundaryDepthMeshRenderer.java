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

/**
 * Small private renderer used by the glass-water boundary pipeline.
 *
 * <p>The gate program is depth-only and alpha-aware. The event program writes the nearest
 * authoritative water transition to a D32 attachment and stores its direction in RG8. A second
 * colour-only draw detects another visible transition behind the nearest one; such a pixel is
 * deliberately marked conflicting so the shader pack keeps its native result.</p>
 */
final class WaterBoundaryDepthMeshRenderer {
    static final int EVENT_NEAREST = 0;
    static final int EVENT_SECONDARY_CONFLICT = 1;
    static final int EVENT_FORCED_CONFLICT = 2;
    private static final FloatBuffer MATRIX_UPLOAD = BufferUtils.createFloatBuffer(16);

    private static final String GATE_VERTEX_SOURCE = """
            #version 150
            in vec3 Position;
            in vec2 UV;
            in float GateOwner;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            out vec2 nautilusAtlasUv;
            flat out float nautilusGateOwner;
            void main() {
                nautilusAtlasUv = UV;
                nautilusGateOwner = GateOwner;
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            }
            """;
    private static final String GATE_FRAGMENT_SOURCE = """
            #version 150
            uniform sampler2D BlockAtlas;
            uniform float AlphaCutoff;
            in vec2 nautilusAtlasUv;
            flat in float nautilusGateOwner;
            out float GateOwnerValue;
            void main() {
                // The hidden gate represents the transparent aperture, not its opaque frame.
                // Always use the base atlas level so TAA does not change the alpha decision.
                float alpha = textureLod(BlockAtlas, nautilusAtlasUv, 0.0).a;
                float opaqueThreshold = 1.0 - clamp(AlphaCutoff, 0.0, 0.5);
                if (!(alpha < opaqueThreshold)) {
                    discard;
                }
                GateOwnerValue = nautilusGateOwner / 65535.0;
            }
            """;

    private static final String EVENT_VERTEX_SOURCE = """
            #version 150
            in vec3 Position;
            in float EventKind;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            flat out float nautilusEventKind;
            void main() {
                nautilusEventKind = EventKind;
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
            }
            """;
    private static final String EVENT_FRAGMENT_SOURCE = """
            #version 150
            uniform sampler2D GateDepth;
            uniform sampler2D GateOwner;
            uniform sampler2D SceneDepth;
            uniform sampler2D FirstEventDepth;
            uniform vec2 ViewportSize;
            uniform int HasSceneDepth;
            uniform int ReversedDepth;
            uniform int InitialMediumWater;
            uniform int EventMode;
            flat in float nautilusEventKind;
            out vec2 BoundaryStatus;

            bool depthIsClear(float depth) {
                return ReversedDepth != 0 ? depth <= 0.0000001 : depth >= 0.9999999;
            }

            bool liesBehind(float candidate, float referenceDepth) {
                return ReversedDepth != 0
                        ? candidate < referenceDepth - 0.0000005
                        : candidate > referenceDepth + 0.0000005;
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                float gate = texture(GateDepth, uv).r;
                if (depthIsClear(gate)) {
                    discard;
                }
                float eventOwner = abs(nautilusEventKind);
                float gateOwner = texture(GateOwner, uv).r * 65535.0;
                if (eventOwner < 0.5 || abs(gateOwner - eventOwner) > 0.5) {
                    discard;
                }
                // A water event is useful only on a ray which first crossed a real alpha-aware
                // optical gate. Equality is accepted for glass directly touching the water.
                bool beforeGate = ReversedDepth != 0
                        ? gl_FragCoord.z > gate + 0.000001
                        : gl_FragCoord.z < gate - 0.000001;
                if (beforeGate) {
                    discard;
                }

                if (HasSceneDepth != 0) {
                    vec2 texel = 1.0 / vec2(textureSize(SceneDepth, 0));
                    float endpoint = ReversedDepth != 0 ? 1.0 : 0.0;
                    for (int y = -1; y <= 1; ++y) {
                        for (int x = -1; x <= 1; ++x) {
                            float sampleDepth = texture(SceneDepth,
                                    uv + vec2(x, y) * texel).r;
                            endpoint = ReversedDepth != 0
                                    ? min(endpoint, sampleDepth)
                                    : max(endpoint, sampleDepth);
                        }
                    }
                    bool behindOpaque = ReversedDepth != 0
                            ? gl_FragCoord.z <= endpoint
                            : gl_FragCoord.z >= endpoint;
                    if (behindOpaque) {
                        discard;
                    }
                }

                if (EventMode == 1) {
                    float first = texture(FirstEventDepth, uv).r;
                    if (depthIsClear(first) || !liesBehind(gl_FragCoord.z, first)) {
                        discard;
                    }
                    BoundaryStatus = vec2(0.0, 1.0);
                    return;
                }
                if (EventMode == 2) {
                    BoundaryStatus = vec2(0.0, 1.0);
                    return;
                }
                bool entering = nautilusEventKind > 0.0;
                if (EventMode == 0 && entering == (InitialMediumWater != 0)) {
                    // Air rays must enter first and water rays must leave first. A reversed nearest
                    // event proves a semantic/gate pairing conflict, so fail this pixel open.
                    BoundaryStatus = vec2(0.0, 1.0);
                    return;
                }
                // Normalised RG8 encoding: 0.25 = air->water, 0.75 = water->air.
                BoundaryStatus = vec2(entering ? 0.25 : 0.75, 0.0);
            }
            """;

    private static int gateProgram = -1;
    private static int gateModelView = -1;
    private static int gateProjection = -1;
    private static int gateAtlas = -1;
    private static int gateAlpha = -1;
    private static int gateVao = -1;
    private static int gateVbo = -1;
    private static int gateVertexCount;

    private static int eventProgram = -1;
    private static int eventModelView = -1;
    private static int eventProjection = -1;
    private static int eventGateDepth = -1;
    private static int eventGateOwner = -1;
    private static int eventSceneDepth = -1;
    private static int eventFirstDepth = -1;
    private static int eventViewport = -1;
    private static int eventHasSceneDepth = -1;
    private static int eventReversedDepth = -1;
    private static int eventInitialMediumWater = -1;
    private static int eventMode = -1;
    private static int eventVao = -1;
    private static int eventVbo = -1;
    private static int eventVertexCount;
    private static boolean failed;

    private WaterBoundaryDepthMeshRenderer() {
    }

    static boolean uploadGate(final FloatBuffer vertices) {
        gateVertexCount = 0;
        if (!vertices.hasRemaining() || failed || !ensureReady()) {
            return !failed;
        }
        if (vertices.remaining() % 6 != 0) {
            return false;
        }
        final int count = vertices.remaining();
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30C.glBindVertexArray(gateVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, gateVbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STREAM_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 24, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, 24, 12L);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 1, GL11C.GL_FLOAT, false, 24, 20L);
            gateVertexCount = count / 6;
            return true;
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            GL30C.glBindVertexArray(previousVao);
        }
    }

    static boolean uploadEvents(final FloatBuffer vertices) {
        eventVertexCount = 0;
        if (!vertices.hasRemaining() || failed || !ensureReady()) {
            return !failed;
        }
        if (vertices.remaining() % 4 != 0) {
            return false;
        }
        final int count = vertices.remaining();
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArray = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30C.glBindVertexArray(eventVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, eventVbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertices, GL15C.GL_STREAM_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 16, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 1, GL11C.GL_FLOAT, false, 16, 12L);
            eventVertexCount = count / 4;
            return true;
        } finally {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArray);
            GL30C.glBindVertexArray(previousVao);
        }
    }

    static boolean drawGate(final Matrix4f modelView,
                            final Matrix4f projection,
                            final int atlasTexture,
                            final float alphaCutoff) {
        if (gateVertexCount <= 0 || atlasTexture <= 0 || failed || !ensureReady()) {
            return gateVertexCount <= 0 || (!failed && atlasTexture > 0);
        }
        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final boolean samplers = GL.getCapabilities().OpenGL33;
        final int previousSampler = samplers
                ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        try {
            GL20C.glUseProgram(gateProgram);
            GL30C.glBindVertexArray(gateVao);
            uploadMatrix(gateProjection, projection);
            uploadMatrix(gateModelView, modelView);
            if (samplers) {
                GL33C.glBindSampler(0, 0);
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, atlasTexture);
            GL20C.glUniform1i(gateAtlas, 0);
            GL20C.glUniform1f(gateAlpha, alphaCutoff);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, gateVertexCount);
            return true;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            if (samplers) {
                GL33C.glBindSampler(0, previousSampler);
            }
            GL13C.glActiveTexture(previousActive);
            GL30C.glBindVertexArray(previousVao);
            GL20C.glUseProgram(previousProgram);
        }
    }

    static boolean drawEvents(final Matrix4f modelView,
                              final Matrix4f projection,
                              final int gateDepthTexture,
                              final int gateOwnerTexture,
                              final int sceneDepthTexture,
                              final int firstEventDepthTexture,
                               final int viewportWidth,
                               final int viewportHeight,
                               final boolean reversedDepth,
                               final boolean initialMediumWater,
                               final int mode) {
        if (eventVertexCount <= 0 || gateDepthTexture <= 0 || gateOwnerTexture <= 0
                || viewportWidth <= 0 || viewportHeight <= 0
                || failed || !ensureReady()) {
            return eventVertexCount <= 0 || (!failed && gateDepthTexture > 0
                    && gateOwnerTexture > 0
                    && viewportWidth > 0 && viewportHeight > 0);
        }
        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final boolean samplers = GL.getCapabilities().OpenGL33;
        final int[] previousTextures = new int[4];
        final int[] previousSamplers = new int[4];
        for (int unit = 0; unit < 4; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            previousTextures[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            previousSamplers[unit] = samplers
                    ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : -1;
        }
        try {
            GL20C.glUseProgram(eventProgram);
            GL30C.glBindVertexArray(eventVao);
            uploadMatrix(eventProjection, projection);
            uploadMatrix(eventModelView, modelView);
            bindTexture(0, gateDepthTexture, eventGateDepth, samplers);
            bindTexture(1, gateOwnerTexture, eventGateOwner, samplers);
            bindTexture(2, Math.max(0, sceneDepthTexture), eventSceneDepth, samplers);
            bindTexture(3, Math.max(0, firstEventDepthTexture), eventFirstDepth, samplers);
            GL20C.glUniform2f(eventViewport, viewportWidth, viewportHeight);
            GL20C.glUniform1i(eventHasSceneDepth, sceneDepthTexture > 0 ? 1 : 0);
            GL20C.glUniform1i(eventReversedDepth, reversedDepth ? 1 : 0);
            GL20C.glUniform1i(eventInitialMediumWater, initialMediumWater ? 1 : 0);
            GL20C.glUniform1i(eventMode, mode);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, eventVertexCount);
            return true;
        } finally {
            for (int unit = 3; unit >= 0; unit--) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTextures[unit]);
                if (samplers) {
                    GL33C.glBindSampler(unit, previousSamplers[unit]);
                }
            }
            GL13C.glActiveTexture(previousActive);
            GL30C.glBindVertexArray(previousVao);
            GL20C.glUseProgram(previousProgram);
        }
    }

    private static void bindTexture(final int unit,
                                    final int texture,
                                    final int uniform,
                                    final boolean samplers) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        if (samplers) {
            GL33C.glBindSampler(unit, 0);
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL20C.glUniform1i(uniform, unit);
    }

    private static void uploadMatrix(final int uniform, final Matrix4f value) {
        MATRIX_UPLOAD.clear();
        value.get(MATRIX_UPLOAD);
        GL20C.glUniformMatrix4fv(uniform, false, MATRIX_UPLOAD);
    }

    private static boolean ensureReady() {
        if (gateProgram >= 0 && eventProgram >= 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            gateProgram = link(GATE_VERTEX_SOURCE, GATE_FRAGMENT_SOURCE, true);
            gateModelView = uniform(gateProgram, "ModelViewMat");
            gateProjection = uniform(gateProgram, "ProjMat");
            gateAtlas = uniform(gateProgram, "BlockAtlas");
            gateAlpha = uniform(gateProgram, "AlphaCutoff");

            eventProgram = link(EVENT_VERTEX_SOURCE, EVENT_FRAGMENT_SOURCE, false);
            eventModelView = uniform(eventProgram, "ModelViewMat");
            eventProjection = uniform(eventProgram, "ProjMat");
            eventGateDepth = uniform(eventProgram, "GateDepth");
            eventGateOwner = uniform(eventProgram, "GateOwner");
            eventSceneDepth = uniform(eventProgram, "SceneDepth");
            eventFirstDepth = uniform(eventProgram, "FirstEventDepth");
            eventViewport = uniform(eventProgram, "ViewportSize");
            eventHasSceneDepth = uniform(eventProgram, "HasSceneDepth");
            eventReversedDepth = uniform(eventProgram, "ReversedDepth");
            eventInitialMediumWater = uniform(eventProgram, "InitialMediumWater");
            eventMode = uniform(eventProgram, "EventMode");

            gateVao = GL30C.glGenVertexArrays();
            gateVbo = GL15C.glGenBuffers();
            eventVao = GL30C.glGenVertexArrays();
            eventVbo = GL15C.glGenBuffers();
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            failed = true;
            destroyProgramsAndBuffers();
            CreateNautilus.LOGGER.warn(
                    "[water-boundary] private depth shader creation failed; using native water",
                    exception);
            return false;
        }
    }

    private static int link(final String vertexSource,
                            final String fragmentSource,
                            final boolean gate) {
        int vertex = -1;
        int fragment = -1;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource,
                    gate ? "gate vertex" : "event vertex");
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource,
                    gate ? "gate fragment" : "event fragment");
            final int owner = GL20C.glCreateProgram();
            try {
                GL20C.glAttachShader(owner, vertex);
                GL20C.glAttachShader(owner, fragment);
                GL20C.glBindAttribLocation(owner, 0, "Position");
                GL20C.glBindAttribLocation(owner, 1, gate ? "UV" : "EventKind");
                if (gate) {
                    GL20C.glBindAttribLocation(owner, 2, "GateOwner");
                    GL30C.glBindFragDataLocation(owner, 0, "GateOwnerValue");
                } else {
                    GL30C.glBindFragDataLocation(owner, 0, "BoundaryStatus");
                }
                GL20C.glLinkProgram(owner);
                if (GL20C.glGetProgrami(owner, GL20C.GL_LINK_STATUS)
                        == GL11C.GL_FALSE) {
                    throw new IllegalStateException("private boundary program link failed: "
                            + GL20C.glGetProgramInfoLog(owner));
                }
                return owner;
            } catch (final RuntimeException exception) {
                GL20C.glDeleteProgram(owner);
                throw exception;
            }
        } finally {
            if (vertex >= 0) GL20C.glDeleteShader(vertex);
            if (fragment >= 0) GL20C.glDeleteShader(fragment);
        }
    }

    private static int compile(final int type, final String source, final String label) {
        final int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            final String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException(label + " compilation failed: " + log);
        }
        return shader;
    }

    private static int uniform(final int owner, final String name) {
        final int location = GL20C.glGetUniformLocation(owner, name);
        if (location < 0) {
            throw new IllegalStateException("private boundary uniform not found: " + name);
        }
        return location;
    }

    static synchronized void resetFailure() {
        failed = false;
    }

    static synchronized void destroy() {
        destroyProgramsAndBuffers();
        failed = false;
        gateVertexCount = 0;
        eventVertexCount = 0;
    }

    private static void destroyProgramsAndBuffers() {
        if (gateVbo >= 0) GL15C.glDeleteBuffers(gateVbo);
        if (eventVbo >= 0) GL15C.glDeleteBuffers(eventVbo);
        if (gateVao >= 0) GL30C.glDeleteVertexArrays(gateVao);
        if (eventVao >= 0) GL30C.glDeleteVertexArrays(eventVao);
        if (gateProgram >= 0) GL20C.glDeleteProgram(gateProgram);
        if (eventProgram >= 0) GL20C.glDeleteProgram(eventProgram);
        gateProgram = gateModelView = gateProjection = gateAtlas = gateAlpha = -1;
        eventProgram = eventModelView = eventProjection = eventGateDepth = eventGateOwner = -1;
        eventSceneDepth = eventFirstDepth = eventViewport = eventHasSceneDepth = -1;
        eventReversedDepth = eventMode = -1;
        gateVao = gateVbo = eventVao = eventVbo = -1;
    }
}
