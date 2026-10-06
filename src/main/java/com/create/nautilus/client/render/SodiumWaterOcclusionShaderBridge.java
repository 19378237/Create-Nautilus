package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.physics.CompartmentWaterGeometry;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.SableClient;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.render.water_occlusion.WaterOcclusionRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.api.client.render.framebuffer.AdvancedFboTextureAttachment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.AABB;
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
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Binds the exact compartment voxel atlas, or Sable's entry/exit depth textures, to an active
 * Sodium terrain program.
 *
 * <p>Iris water programs use a camera-relative per-fragment lookup in
 * {@link ShaderVoxelMaskAtlas}. Plain Sodium keeps Sable's close/far depth-mask path as its
 * established no-shader baseline. Both bindings are scoped to the parent-world translucent draw
 * and are restored before another transparent producer is submitted.</p>
 */
public final class SodiumWaterOcclusionShaderBridge {
    /** OpenGL's depth-clamp capability; Sable enables it while drawing its hull-depth meshes. */
    private static final int DEPTH_CLAMP = 0x864F;
    /** First user clip plane. Kept because shader packs may leave it active around terrain. */
    private static final int CLIP_DISTANCE_ZERO = 0x3000;
    private static final FramebufferAccess FRAMEBUFFERS = findFramebufferAccess();
    private static final Method IRIS_SHADOW_QUERY = findIrisShadowQuery();
    private static BindingState activeBinding;
    private static VoxelBindingState activeVoxelBinding;
    private static boolean failureLogged;
    private static boolean activationLogged;
    private static boolean missingUniformLogged;
    private static long lastPathLogNanos;
    private static boolean boxDataLogged;
    private static long lastBoxDataLogNanos;
    private static long lastSceneBoxLogNanos;
    private static boolean projectionLogged;
    private static long projectionLogNanos;
    private static boolean headModelViewLogged;
    private static boolean liveMatrixSourcesLogged;
    /**
     * Sampler assignments are immutable for one linked Iris terrain program.  Cache the unit
     * selected from those assignments instead of synchronously enumerating every active uniform
     * on every water draw.  The generation is advanced by every pipeline reset so a recycled GL
     * program name can never inherit the previous pipeline's decision.
     */
    private static final ConcurrentMap<TextureUnitCacheKey, Integer>
            VOXEL_TEXTURE_UNIT_CACHE = new ConcurrentHashMap<>();
    private static volatile int voxelTextureUnitPipelineGeneration = 1;
    private static String liveProjectionSource = "unavailable";
    private static String liveModelViewSource = "unavailable";
    /*
     * The water pass's real matrices only become current when Sodium binds the pass (begin()).
     * Rebuilding the masks inside begin() corrupted Iris's framebuffer/state (whole-ocean black),
     * so the matrices are merely recorded there and the next frame's HEAD rebuild uses them. The
     * projection is frame-constant; the one-frame camera lag is imperceptible at normal speeds.
     */
    private static final Matrix4f LAST_WATER_MODEL_VIEW = new Matrix4f();
    private static final Matrix4f LAST_WATER_PROJECTION = new Matrix4f();
    private static boolean lastWaterMatricesValid;
    /** Depth range of the active water pass; shader packs can reverse it (near=1, far=0). */
    private static boolean waterDepthReversed;
    private static float waterDepthNear = 0.0F;
    private static float waterDepthFar = 1.0F;
    private static boolean depthRangeLogged;

    private SodiumWaterOcclusionShaderBridge() {
    }

    /**
     * Builds Sable's close/far hull depth textures at Sable's native pre-translucent frame stage.
     *
     * <p>Sable 2.0.3 deliberately disables its vanilla LevelRenderer water-occlusion mixin when
     * Sodium is installed, but its Sodium renderer does not provide an equivalent call to
     * {@link WaterOcclusionRenderer#preRenderTranslucent(Matrix4f, Matrix4f)}. Binding the two
     * textures without this step therefore only binds null/stale framebuffers. This method fills
     * that missing integration point from {@code LevelRenderer.renderLevel}, at the same call site
     * used by Sable's vanilla branch. The later Sodium hook only binds these completed textures to
     * the active translucent program.</p>
     */
    public static boolean prepareDepthBuffers(final Matrix4f modelView,
                                               final Matrix4f projection,
                                               final double cameraX,
                                               final double cameraY,
                                               final double cameraZ) {
        if (!headModelViewLogged) {
            headModelViewLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] head modelView translation=({}, {}, {}) "
                            + "m02={} m12={} m22={}",
                    modelView.m30(), modelView.m31(), modelView.m32(),
                    modelView.m02(), modelView.m12(), modelView.m22());
        }
        /* Iris uses the exact voxel atlas. Never build Sable's FBOs under a pack: Iris redirects
         * those draws into pack-owned targets. update() runs on the render thread at this exact
         * parent-world translucent stage and restores every GL binding it touches. */
        if (WaterOcclusionPassBridge.isShaderPackActive()) {
            try {
                ShaderVoxelMaskAtlas.update(cameraX, cameraY, cameraZ);
            } catch (final RuntimeException | LinkageError exception) {
                logFailureOnce("Unable to update exact compartment voxel atlas", exception);
            }
            return true;
        }
        /*
         * Prefer the water program's captured matrices when available. Both these uniforms and
         * drawChunkLayer's fallback operate on camera-relative positions; their translation may
         * contain view effects, but is not the absolute world-camera offset. Mask vertices already
         * subtract the camera, so consumers must neither add it back nor strip view translation.
         */
        final Matrix4f activeModelView = lastWaterMatricesValid
                ? new Matrix4f(LAST_WATER_MODEL_VIEW)
                : new Matrix4f(modelView);
        final Matrix4f activeProjection = lastWaterMatricesValid
                ? new Matrix4f(LAST_WATER_PROJECTION)
                : new Matrix4f(projection);
        if (!projectionLogged) {
            projectionLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] mask pass projection m22={} m23={} (passed m22={})",
                    activeProjection.m22(), activeProjection.m23(), projection.m22());
        }
        if (!SodiumOcclusionShaderSource.isSafeToUseCustomOcclusion()) {
            return false;
        }
        try {
            if (isIrisShadowPass() || !WaterOcclusionRenderer.isEnabled()) {
                return true;
            }
            final WaterOcclusionRenderer renderer = SableClient.WATER_OCCLUSION_RENDERER;

            final RenderPassState state = captureRenderPassState();
            try {
                // update() can upload newly rebuilt region meshes on the first affected frame.
                // Keep that upload inside the same state sandbox because it can alter VAO/VBO
                // bindings before the depth passes begin.
                renderer.update();
                RenderSystem.disableScissor();
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                RenderSystem.enableDepthTest();
                GL11C.glEnable(GL11C.GL_DEPTH_TEST);
                RenderSystem.depthMask(true);
                GL11C.glDepthMask(true);
                RenderSystem.depthFunc(GL11C.GL_LEQUAL);
                GL11C.glDepthFunc(GL11C.GL_LEQUAL);
                RenderSystem.enableCull();
                GL11C.glEnable(GL11C.GL_CULL_FACE);
                GL11C.glFrontFace(GL11C.GL_CCW);
                GL11C.glCullFace(GL11C.GL_BACK);
                GL11C.glDisable(CLIP_DISTANCE_ZERO);
                /*
                 * This pass produces depth masks only. Sable's framebuffers currently also own
                 * colour attachments, and AdvancedFbo temporarily changes the active target.
                 * Explicitly disabling colour writes makes an unexpected bind/unbind or a shader
                 * pack framebuffer wrapper harmless: the white occlusion hull can never be
                 * submitted to Iris' colour buffers.
                 */
                RenderSystem.colorMask(false, false, false, false);
                GL11C.glColorMask(false, false, false, false);
                RenderSystem.disableBlend();
                GL11C.glDisable(GL11C.GL_BLEND);
                /*
                 * Sable's private updateFramebuffers() only runs inside preRenderTranslucent().
                 * Call it solely to (re)allocate the depth targets at the current main-render
                 * size; its own region pass is immediately overwritten by rebuildOcclusionDepths()
                 * below, so skipping it on every ordinary frame halves the per-frame CPU work.
                 */
                final AdvancedFbo existingClose = (AdvancedFbo) FRAMEBUFFERS.close().get(renderer);
                final AdvancedFbo existingFar = (AdvancedFbo) FRAMEBUFFERS.far().get(renderer);
                final var renderTarget = Minecraft.getInstance().getMainRenderTarget();
                if (existingClose == null || existingFar == null
                        || existingClose.getWidth() != renderTarget.width
                        || existingClose.getHeight() != renderTarget.height) {
                    renderer.preRenderTranslucent(
                            new Matrix4f(activeModelView), new Matrix4f(activeProjection));
                }
                rebuildOcclusionDepths(
                        new Matrix4f(activeModelView), new Matrix4f(activeProjection));
            } finally {
                restoreRenderPassState(state);
            }
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logFailureOnce("Unable to prepare compartment water-occlusion resources for Sodium",
                    exception);
            return false;
        }
    }

    /** Compatibility entry point for callers which do not carry Sodium's camera origin. */
    public static boolean begin() {
        final var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return begin(new Matrix4f(RenderSystem.getModelViewMatrix()),
                new Matrix4f(RenderSystem.getProjectionMatrix()),
                camera.x, camera.y, camera.z);
    }

    /** Compatibility entry point for callers which do not carry Sodium's current draw matrices. */
    public static boolean begin(final double cameraX,
                                final double cameraY,
                                final double cameraZ) {
        return begin(new Matrix4f(RenderSystem.getModelViewMatrix()),
                new Matrix4f(RenderSystem.getProjectionMatrix()),
                cameraX, cameraY, cameraZ);
    }

    /** Called after Sodium has bound the program, with this draw's exact ChunkRenderMatrices. */
    public static boolean begin(final Matrix4f drawModelView,
                                final Matrix4f drawProjection,
                                final double cameraX,
                                final double cameraY,
                                final double cameraZ) {
        end();
        if (!SodiumOcclusionShaderSource.isSafeToUseCustomOcclusion()) {
            return false;
        }
        // Iris also reuses Sodium's translucent-pass object while rendering its shadow map.
        // That program intentionally has no Nautilus uniforms; test the pass before looking up
        // the uniform so a normal shadow frame is not reported as a broken integration.
        if (isIrisShadowPass()) {
            return true;
        }
        final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program <= 0) {
            return false;
        }
        final int enabled = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.ENABLE_UNIFORM);
        if (enabled < 0) {
            if (!missingUniformLogged) {
                missingUniformLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[occlusion] current program {} has no Nautilus uniforms; "
                                + "fragment occlusion cannot run in this pass",
                        program);
            }
            return false;
        }
        final boolean irisShaderPack = WaterOcclusionPassBridge.isShaderPackActive();
        final Matrix4f waterModelView;
        final Matrix4f waterProjection;
        if (irisShaderPack) {
            /*
             * DefaultChunkRenderer uploads ChunkRenderMatrices after ShaderChunkRenderer.begin
             * returns. Reading iris_* here would therefore observe the previous draw. The values
             * captured at drawChunkLayer HEAD are the exact objects Sodium uploads next.
             */
            waterModelView = drawModelView == null ? null : new Matrix4f(drawModelView);
            waterProjection = drawProjection == null ? null : new Matrix4f(drawProjection);
            liveModelViewSource = "drawChunkLayer.modelView";
            liveProjectionSource = "drawChunkLayer.projection";
        } else {
            final Matrix4f liveModelView = readWaterModelView(program);
            final Matrix4f liveProjection = readWaterProjection(program);
            waterModelView = liveModelView != null
                    ? liveModelView : new Matrix4f(RenderSystem.getModelViewMatrix());
            waterProjection = liveProjection != null
                    ? liveProjection : new Matrix4f(RenderSystem.getProjectionMatrix());
        }
        if (waterModelView != null) {
            LAST_WATER_MODEL_VIEW.set(waterModelView);
        }
        if (waterProjection != null) {
            LAST_WATER_PROJECTION.set(waterProjection);
        }
        int waterViewportX = 0;
        int waterViewportY = 0;
        int waterViewportWidth = 0;
        int waterViewportHeight = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final FloatBuffer depthRange = stack.mallocFloat(2);
            GL11C.glGetFloatv(GL11C.GL_DEPTH_RANGE, depthRange);
            waterDepthNear = depthRange.get(0);
            waterDepthFar = depthRange.get(1);
            if (irisShaderPack) {
                final IntBuffer viewport = stack.mallocInt(4);
                GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
                waterViewportX = viewport.get(0);
                waterViewportY = viewport.get(1);
                waterViewportWidth = viewport.get(2);
                waterViewportHeight = viewport.get(3);
            }
        }
        waterDepthReversed = waterDepthNear > waterDepthFar;
        if (!depthRangeLogged) {
            depthRangeLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] water pass depth range near={} far={} reversed={}",
                    waterDepthNear, waterDepthFar, waterDepthReversed);
        }
        lastWaterMatricesValid = waterModelView != null && waterProjection != null;

        if (!liveMatrixSourcesLogged && irisShaderPack) {
            liveMatrixSourcesLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] water draw matrices projection={} modelView={} waterMaterialId={}",
                    liveProjectionSource, liveModelViewSource,
                    IrisWaterMaterialBridge.waterMaterialId());
        }

        try {
            if (!WaterOcclusionRenderer.isEnabled()) {
                GL20C.glUniform1f(enabled, 0.0F);
                return true;
            }
            if (!irisShaderPack && !isOcclusionRelevant()) {
                /*
                 * Keep the fragment shader's two depth samples dormant while no compartment is
                 * anywhere near the camera. The mask only removes ocean inside a dry hull, which
                 * cannot be visible from more than a few hundred blocks away.
                 */
                GL20C.glUniform1f(enabled, 0.0F);
                return true;
            }
            /* Iris programs carry the exact voxel atlas; plain Sodium carries Sable's close/far
             * samplers as the unchanged no-shader baseline. */
            final boolean voxelPath = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.VOXEL_ATLAS_UNIFORM) >= 0;
            final boolean depthPath = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.CLOSE_SAMPLER_UNIFORM) >= 0;
            /* The atlas is the only Iris path. Any missing input fails open for this draw. */
            if (irisShaderPack) {
                if (!voxelPath || waterModelView == null || waterProjection == null) {
                    GL20C.glUniform1f(enabled, 0.0F);
                    logOcclusionPath("disabled (voxel uniforms or draw matrices unavailable)");
                    return true;
                }
                final int textureUnit = cachedUnusedTextureUnit(program);
                if (textureUnit < 0) {
                    GL20C.glUniform1f(enabled, 0.0F);
                    logOcclusionPath("disabled (no free texture unit)");
                    return true;
                }
                final boolean waveLayoutKnown = uploadVoxelFrameUniforms(program);
                if (!waveLayoutKnown) {
                    GL20C.glUniform1f(enabled, 0.0F);
                    logOcclusionPath("disabled (camera-relative water varying unavailable)");
                    return true;
                }
                if (ShaderVoxelMaskAtlas.upload(
                        program, textureUnit, cameraX, cameraY, cameraZ,
                        waterModelView, waterProjection,
                        waterViewportX, waterViewportY,
                        waterViewportWidth, waterViewportHeight,
                        waveLayoutKnown)) {
                    setDepthActiveUniform(program, 0.0F);
                    setVirtualWaterPassUniform(program, 0.0F);
                    uploadWaterBlockId(program);
                    GL20C.glUniform1f(enabled, 1.0F);
                    activeVoxelBinding = new VoxelBindingState(program, enabled);
                    logOcclusionPath("exact-voxel-atlas");
                    if (!activationLogged) {
                        activationLogged = true;
                        CreateNautilus.LOGGER.info(
                                "Continuous dry-compartment water occlusion is active "
                                        + "(exact-voxel-atlas path, program={})",
                                program);
                    }
                    return true;
                }
                ShaderVoxelMaskAtlas.restoreBinding();
                GL20C.glUniform1f(enabled, 0.0F);
                logOcclusionPath("disabled (voxel atlas unavailable)");
                return true;
            }
            if (depthPath) {
                /*
                 * No shader pack: Sable's own close/far masks are the working baseline and are
                 * not subject to Iris interception.
                 */
                if (bindDepthTexturePath(program, enabled)) {
                    setDepthActiveUniform(program, 1.0F);
                    if (!activationLogged) {
                        activationLogged = true;
                        CreateNautilus.LOGGER.info(
                                "Continuous dry-compartment water occlusion is active "
                                        + "(depth-texture path, program={})",
                                program);
                    }
                    return true;
                }
            }
            if (!missingUniformLogged) {
                missingUniformLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[occlusion] current program {} has no Nautilus uniforms; "
                                + "fragment occlusion cannot run in this pass",
                        program);
            }
            GL20C.glUniform1f(enabled, 0.0F);
            return false;
        } catch (final RuntimeException | LinkageError exception) {
            ShaderVoxelMaskAtlas.restoreBinding();
            GL20C.glUniform1f(enabled, 0.0F);
            logFailureOnce("Unable to bind compartment water-occlusion resources to Sodium",
                    exception);
            return false;
        }
    }

    private static void logOcclusionPath(final String path) {
        final long now = System.nanoTime();
        if (now - lastPathLogNanos < 5_000_000_000L) {
            return;
        }
        lastPathLogNanos = now;
        CreateNautilus.LOGGER.info("[occlusion] occlusion path: {}", path);
    }

    /**
     * Directly inspects the cached compartment snapshots for a dry room (the AFTER_SKY mask
     * renderer may run after this pass in the actual frame order, so a static flag set there is
     * not reliable here).
     */
    private static boolean hasDryRoomsNearby() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return false;
        }
        for (final var carrierEntry : ClientCompartmentVisualCache.snapshots().entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel carrier)) {
                continue;
            }
            for (final ClientCompartmentVisualCache.RoomSnapshot room
                    : carrierEntry.getValue()) {
                if (room.waterFraction() <= 1.0E-5D && !room.cells().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Restores texture bindings before Sodium unbinds the terrain program. */
    public static void end() {
        final VoxelBindingState voxelBinding = activeVoxelBinding;
        activeVoxelBinding = null;
        if (voxelBinding != null) {
            disableVoxelBinding(voxelBinding);
        }
        ShaderVoxelMaskAtlas.restoreBinding();
        final BindingState binding = activeBinding;
        activeBinding = null;
        if (binding != null) {
            restore(binding);
        }
        setVirtualWaterPass(false);
        if (IrisCompositeShaderSource.PORTHOLE_ENABLED) {
            WaterEntryMaskRenderer.bindForComposite();
        }
    }

    /** Camera-relative rotation used by the previous water pass (for the entry-depth mask). */
    public static Matrix4f lastWaterModelView() {
        return new Matrix4f(LAST_WATER_MODEL_VIEW);
    }

    /**
     * Reads the rotation-only model view bound to an arbitrary GL program (used for the Iris
     * composite passes, which carry the same camera rotation as the water pass).
     */
    public static Matrix4f readProgramModelView(final int program) {
        return readWaterModelView(program);
    }

    /**
     * Legacy composite-only box upload retained for the disabled porthole experiment. Parent
     * water clipping never calls this method and declares none of these uniforms.
     */
    public static int uploadCompartmentBoxesTo(final int program,
                                               final Matrix4f viewRotation) {
        if (GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.COMPARTMENT_COUNT_UNIFORM) < 0) {
            return -1;
        }
        return uploadCompartmentBoxes(program, viewRotation);
    }

    /**
     * Uploads the compartment boxes in scene space (camera-relative world, no view rotation).
     *
     * <p>Used by the Iris composite programs: the composite shader reconstructs its ray with
     * {@code view_to_scene_space()}, which is guaranteed correct at draw time, so the box test no
     * longer depends on reading {@code gbufferModelView} at {@code Program.use()} time (that
     * uniform can still hold the previous frame's value when the mixin runs).</p>
     */
    public static int uploadCompartmentBoxesSceneSpace(final int program) {
        if (GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.COMPARTMENT_COUNT_UNIFORM) < 0) {
            return -1;
        }
        final int count = uploadCompartmentBoxes(program, null);
        final long now = System.nanoTime();
        if (now - lastSceneBoxLogNanos > 5_000_000_000L) {
            lastSceneBoxLogNanos = now;
            final Minecraft minecraft = Minecraft.getInstance();
            final var camera = minecraft.gameRenderer.getMainCamera().getPosition();
            CreateNautilus.LOGGER.info(
                    "[composite-box-scene] program={} count={} cameraY={}",
                    program, count, camera.y);
        }
        return count;
    }

    /** Projection used by the previous water pass (for the entry-depth mask). */
    public static Matrix4f lastWaterProjection() {
        return new Matrix4f(LAST_WATER_PROJECTION);
    }

    public static boolean lastWaterMatricesValid() {
        return lastWaterMatricesValid;
    }

    private static void setSampler(final int program, final String name, final int unit) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20C.glUniform1i(location, unit);
        }
    }

    /** Reads the live projection uniform from the currently bound water program. */
    private static Matrix4f readWaterProjection(final int program) {
        /*
         * Iris' Sodium transformer rewrites the vertex shader's gl_ProjectionMatrix to
         * iris_ProjectionMatrix.  A pack can also expose gbufferProjection in the same linked
         * program, but that is captured global pack state and need not match this terrain draw.
         * Reconstruct gl_FragCoord with the matrix which actually produced gl_Position.
         */
        for (final String name : new String[]{
                "iris_ProjectionMatrix", "gbufferProjection", "u_ProjectionMatrix"}) {
            final int location = GL20C.glGetUniformLocation(program, name);
            if (location < 0) {
                continue;
            }
            final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
            GL20C.glGetUniformfv(program, location, buffer);
            final Matrix4f matrix = new Matrix4f();
            matrix.set(buffer);
            if (matrix.m22() != 0.0F && !Float.isNaN(matrix.m22())) {
                liveProjectionSource = name;
                return matrix;
            }
        }
        liveProjectionSource = "unavailable";
        return null;
    }

    /** Reads the live model-view uniform from the currently bound water program. */
    private static Matrix4f readWaterModelView(final int program) {
        /* See readWaterProjection(): gl_ModelViewMatrix is rewritten to iris_ModelViewMatrix. */
        for (final String name : new String[]{
                "iris_ModelViewMatrix", "gbufferModelView", "u_ModelViewMatrix"}) {
            final int location = GL20C.glGetUniformLocation(program, name);
            if (location < 0) {
                continue;
            }
            final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
            GL20C.glGetUniformfv(program, location, buffer);
            final Matrix4f matrix = new Matrix4f();
            matrix.set(buffer);
            if (!Float.isNaN(matrix.m00())) {
                liveModelViewSource = name;
                return matrix;
            }
        }
        liveModelViewSource = "unavailable";
        return null;
    }

    /**
     * Enables the pack-specific physical-position varying. Each adapter anchors its wave delta to
     * Sodium's {@code getVertexPosition()}, so no projection or model-view inverse is needed here.
     */
    private static boolean uploadVoxelFrameUniforms(final int program) {
        final int readyLocation = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.VOXEL_POSITION_READY_UNIFORM);
        if (readyLocation < 0) {
            return false;
        }
        GL20C.glUniform1f(readyLocation, 1.0F);
        return true;
    }

    /** Whether any registered dry compartment is close enough to affect visible ocean. */
    private static boolean isOcclusionRelevant() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !ClientDryCompartmentCache.hasOcclusionRegions()) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return false;
        }
        final var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        final var snapshots = ClientCompartmentVisualCache.snapshots();
        for (final var carrierEntry : snapshots.entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel carrier)) {
                continue;
            }
            for (final ClientCompartmentVisualCache.RoomSnapshot room : carrierEntry.getValue()) {
                final var bounds = room.bounds();
                final var center = carrier.renderPose().transformPosition(
                        new org.joml.Vector3d(
                                (bounds.minX + bounds.maxX) * 0.5D,
                                (bounds.minY + bounds.maxY) * 0.5D,
                                (bounds.minZ + bounds.maxZ) * 0.5D));
                final double dx = center.x - camera.x;
                final double dy = center.y - camera.y;
                final double dz = center.z - camera.z;
                if (dx * dx + dy * dy + dz * dz < 512.0D * 512.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Exempts the current program's fluid fragments from the dry-compartment ocean mask.
     *
     * <p>Virtual cabin water is submitted through the same Iris water material as parent ocean,
     * so the shader cannot tell it apart. The drawing hooks toggle this uniform around only the
     * virtual-surface draws; parent-water chunk rendering always sees zero.</p>
     */
    public static void setVirtualWaterPass(final boolean virtualWater) {
        final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program <= 0) {
            return;
        }
        setVirtualWaterPassUniform(program, virtualWater ? 1.0F : 0.0F);
    }

    private static void setVirtualWaterPassUniform(final int program, final float value) {
        final int location = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.VIRTUAL_WATER_UNIFORM);
        if (location >= 0) {
            GL20C.glUniform1f(location, value);
        }
    }

    private static void setDepthActiveUniform(final int program, final float value) {
        final int location = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.DEPTH_ACTIVE_UNIFORM);
        if (location >= 0) {
            GL20C.glUniform1f(location, value);
        }
    }

    /** Depth-texture path: binds Sable's close/far masks for the no-shader Sodium water program. */
    private static boolean bindDepthTexturePath(final int program, final int enabled) {
        final int closeId;
        final int farId;
        final int maskWidth;
        final int maskHeight;
        AdvancedFbo sableClose = null;
        AdvancedFbo sableFar = null;
        if (DryHullDepthMasks.valid()) {
            closeId = DryHullDepthMasks.closeTextureId();
            farId = DryHullDepthMasks.farTextureId();
            maskWidth = DryHullDepthMasks.maskWidth();
            maskHeight = DryHullDepthMasks.maskHeight();
        } else if (FRAMEBUFFERS != null) {
            final AdvancedFbo close;
            final AdvancedFbo far;
            try {
                close = (AdvancedFbo) FRAMEBUFFERS.close().get(
                        SableClient.WATER_OCCLUSION_RENDERER);
                far = (AdvancedFbo) FRAMEBUFFERS.far().get(
                        SableClient.WATER_OCCLUSION_RENDERER);
            } catch (final ReflectiveOperationException | RuntimeException
                    | LinkageError exception) {
                logFailureOnce("Unable to read Sable water-occlusion framebuffers", exception);
                return false;
            }
            if (close == null || far == null) {
                return false;
            }
            final AdvancedFboTextureAttachment closeDepth = close.getDepthTextureAttachment();
            final AdvancedFboTextureAttachment farDepth = far.getDepthTextureAttachment();
            if (closeDepth == null || farDepth == null) {
                return false;
            }
            closeId = closeDepth.getId();
            farId = farDepth.getId();
            maskWidth = close.getWidth();
            maskHeight = close.getHeight();
            sableClose = close;
            sableFar = far;
        } else {
            return false;
        }
        /*
         * Preserve the verified no-shader baseline units. The Iris atlas path above performs a
         * dynamic free-unit scan and never enters this method.
         */
        final int[] units = {13, 14};

        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[0]);
        final int previousClose = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[1]);
        final int previousFar = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final boolean samplerObjects = GL.getCapabilities().OpenGL33;
        final int previousCloseSampler = samplerObjects
                ? GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, units[0]) : -1;
        final int previousFarSampler = samplerObjects
                ? GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, units[1]) : -1;
        final BindingState binding = new BindingState(
                program, enabled, units[0], units[1],
                previousActive, previousClose, previousFar,
                previousCloseSampler, previousFarSampler);

        try {
            if (samplerObjects) {
                GL33C.glBindSampler(units[0], 0);
                GL33C.glBindSampler(units[1], 0);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[0]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, closeId);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                    GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                    GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S,
                    GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T,
                    GL12C.GL_CLAMP_TO_EDGE);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + units[1]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, farId);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                    GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                    GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S,
                    GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T,
                    GL12C.GL_CLAMP_TO_EDGE);
            GL13C.glActiveTexture(previousActive);

            setSampler(program, SodiumOcclusionShaderSource.CLOSE_SAMPLER_UNIFORM, units[0]);
            setSampler(program, SodiumOcclusionShaderSource.FAR_SAMPLER_UNIFORM, units[1]);
            final int screenSize = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.SCREEN_SIZE_UNIFORM);
            final int viewportOrigin = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.VIEWPORT_ORIGIN_UNIFORM);
            final int depthScale = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.DEPTH_SCALE_UNIFORM);
            final IntBuffer viewport = BufferUtils.createIntBuffer(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            if (screenSize >= 0) {
                if (viewportOrigin >= 0) {
                    GL20C.glUniform2f(viewportOrigin, viewport.get(0), viewport.get(1));
                }
                GL20C.glUniform2f(screenSize,
                        Math.max(1, viewport.get(2)), Math.max(1, viewport.get(3)));
            }
            if (depthScale >= 0) {
                GL20C.glUniform2f(depthScale,
                        (float) maskWidth / Math.max(1, viewport.get(2)),
                        (float) maskHeight / Math.max(1, viewport.get(3)));
            }
            final float depthRangeScale = 1.0F / (waterDepthFar - waterDepthNear);
            final float depthRangeBias = -waterDepthNear * depthRangeScale;
            final int rangeScale = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.DEPTH_RANGE_SCALE_UNIFORM);
            if (rangeScale >= 0) {
                GL20C.glUniform1f(rangeScale, depthRangeScale);
            }
            final int rangeBias = GL20C.glGetUniformLocation(program,
                    SodiumOcclusionShaderSource.DEPTH_RANGE_BIAS_UNIFORM);
            if (rangeBias >= 0) {
                GL20C.glUniform1f(rangeBias, depthRangeBias);
            }
            setVirtualWaterPassUniform(program, 0.0F);
            GL20C.glUniform1f(enabled, 1.0F);
            activeBinding = binding;
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            restore(binding);
            return false;
        }
    }

    /**
     * Uploads each tracked compartment as a view-space oriented box.
     *
     * <p>The fragment shader tests the pack's own {@code position_view} varying against these
     * boxes, so the upload must use the same view rotation as the water program
     * ({@code gbufferModelView}) and the same camera position. The pose is baked to world, then
     * centered on the camera and rotated into view space.</p>
     */
    private static int uploadCompartmentBoxes(final int program,
                                              final Matrix4f viewRotation) {
        final int countLocation = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.COMPARTMENT_COUNT_UNIFORM);
        if (countLocation < 0) {
            return -1;
        }
        final int maximum = SodiumOcclusionShaderSource.MAX_COMPARTMENTS;
        final FloatBuffer center = BufferUtils.createFloatBuffer(maximum * 3);
        final FloatBuffer axis0 = BufferUtils.createFloatBuffer(maximum * 3);
        final FloatBuffer axis1 = BufferUtils.createFloatBuffer(maximum * 3);
        final FloatBuffer axis2 = BufferUtils.createFloatBuffer(maximum * 3);
        final FloatBuffer half = BufferUtils.createFloatBuffer(maximum * 3);
        final FloatBuffer waterLevel = BufferUtils.createFloatBuffer(maximum);

        int count = 0;
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
            final var camera = minecraft.gameRenderer.getMainCamera().getPosition();
            final var snapshots = ClientCompartmentVisualCache.snapshots();
            if (container != null) {
                for (final var carrierEntry : snapshots.entrySet()) {
                    if (!(container.getSubLevel(carrierEntry.getKey())
                            instanceof final ClientSubLevel carrier)) {
                        continue;
                    }
                    final Matrix4d pose = carrier.renderPose().bakeIntoMatrix(new Matrix4d());
                    for (final ClientCompartmentVisualCache.RoomSnapshot room
                            : carrierEntry.getValue()) {
                        if (count >= maximum) {
                            break;
                        }
                        if (room.waterFraction() > 1.0E-5D) {
                            // Wet rooms keep their parent water; only dry hulls are culled.
                            continue;
                        }
                        final AABB bounds = room.bounds();
                        final Vector3d centerWorld = pose.transformPosition(new Vector3d(
                                (bounds.minX + bounds.maxX) * 0.5D,
                                (bounds.minY + bounds.maxY) * 0.5D,
                                (bounds.minZ + bounds.maxZ) * 0.5D), new Vector3d());
                        final Vector3d axisXWorld = pose.transformDirection(
                                new Vector3d(1.0D, 0.0D, 0.0D), new Vector3d());
                        final Vector3d axisYWorld = pose.transformDirection(
                                new Vector3d(0.0D, 1.0D, 0.0D), new Vector3d());
                        final Vector3d axisZWorld = pose.transformDirection(
                                new Vector3d(0.0D, 0.0D, 1.0D), new Vector3d());
                        final double scaleX = axisXWorld.length();
                        final double scaleY = axisYWorld.length();
                        final double scaleZ = axisZWorld.length();
                        axisXWorld.normalize();
                        axisYWorld.normalize();
                        axisZWorld.normalize();

                        final Vector3f centerView = viewRotation == null
                                ? new Vector3f(
                                (float) (centerWorld.x - camera.x),
                                (float) (centerWorld.y - camera.y),
                                (float) (centerWorld.z - camera.z))
                                : viewRotation.transformPosition(new Vector3f(
                                (float) (centerWorld.x - camera.x),
                                (float) (centerWorld.y - camera.y),
                                (float) (centerWorld.z - camera.z)), new Vector3f());
                        final Vector3f axisXView = viewRotation == null
                                ? new Vector3f((float) axisXWorld.x, (float) axisXWorld.y,
                                (float) axisXWorld.z)
                                : viewRotation.transformDirection(
                                new Vector3f((float) axisXWorld.x, (float) axisXWorld.y,
                                        (float) axisXWorld.z), new Vector3f());
                        final Vector3f axisYView = viewRotation == null
                                ? new Vector3f((float) axisYWorld.x, (float) axisYWorld.y,
                                (float) axisYWorld.z)
                                : viewRotation.transformDirection(
                                new Vector3f((float) axisYWorld.x, (float) axisYWorld.y,
                                        (float) axisYWorld.z), new Vector3f());
                        final Vector3f axisZView = viewRotation == null
                                ? new Vector3f((float) axisZWorld.x, (float) axisZWorld.y,
                                (float) axisZWorld.z)
                                : viewRotation.transformDirection(
                                new Vector3f((float) axisZWorld.x, (float) axisZWorld.y,
                                        (float) axisZWorld.z), new Vector3f());

                        center.put(centerView.x).put(centerView.y).put(centerView.z);
                        axis0.put(axisXView.x).put(axisXView.y).put(axisXView.z);
                        axis1.put(axisYView.x).put(axisYView.y).put(axisYView.z);
                        axis2.put(axisZView.x).put(axisZView.y).put(axisZView.z);
                        half.put((float) ((bounds.maxX - bounds.minX) * 0.5D * scaleX))
                                .put((float) ((bounds.maxY - bounds.minY) * 0.5D * scaleY))
                                .put((float) ((bounds.maxZ - bounds.minZ) * 0.5D * scaleZ));
                        if (room.waterFraction() > 1.0E-5D) {
                            waterLevel.put((float) CompartmentWaterGeometry.surfaceY(
                                    carrier.renderPose(), room.cells(), room.bounds(),
                                    room.waterFraction()));
                        } else {
                            waterLevel.put(ClientWaterSurfaceProbe.NO_WATER);
                        }
                        count++;
                    }
                }
            }
        }
        final int actualCount = count;
        while (count < maximum) {
            center.put(0.0F).put(0.0F).put(0.0F);
            axis0.put(0.0F).put(0.0F).put(0.0F);
            axis1.put(0.0F).put(0.0F).put(0.0F);
            axis2.put(0.0F).put(0.0F).put(0.0F);
            half.put(0.0F).put(0.0F).put(0.0F);
            waterLevel.put(ClientWaterSurfaceProbe.NO_WATER);
            count++;
        }
        center.flip();
        axis0.flip();
        axis1.flip();
        axis2.flip();
        half.flip();
        waterLevel.flip();

        GL20C.glUniform1i(countLocation, actualCount);
        uploadVec3Array(program, SodiumOcclusionShaderSource.COMPARTMENT_CENTER_UNIFORM, center);
        uploadVec3Array(program, SodiumOcclusionShaderSource.COMPARTMENT_AXIS0_UNIFORM, axis0);
        uploadVec3Array(program, SodiumOcclusionShaderSource.COMPARTMENT_AXIS1_UNIFORM, axis1);
        uploadVec3Array(program, SodiumOcclusionShaderSource.COMPARTMENT_AXIS2_UNIFORM, axis2);
        uploadVec3Array(program, SodiumOcclusionShaderSource.COMPARTMENT_HALF_UNIFORM, half);
        uploadFloatArray(program,
                SodiumOcclusionShaderSource.COMPARTMENT_WATER_LEVEL_UNIFORM, waterLevel);
        final long now = System.nanoTime();
        if (!boxDataLogged || now - lastBoxDataLogNanos > 5_000_000_000L) {
            boxDataLogged = true;
            lastBoxDataLogNanos = now;
            final var camera = minecraft.gameRenderer.getMainCamera().getPosition();
            CreateNautilus.LOGGER.info(
                    "[composite-box] data count={} center0=({}, {}, {}) level0={} "
                            + "sceneSpace={} camera=({}, {}, {})",
                    actualCount,
                    center.get(0), center.get(1), center.get(2),
                    waterLevel.get(0),
                    viewRotation == null,
                    camera.x, camera.y, camera.z);
        }
        return actualCount;
    }

    private static void uploadVec3Array(final int program, final String name,
                                        final FloatBuffer values) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20C.glUniform3fv(location, values);
        }
    }

    private static void uploadFloatArray(final int program, final String name,
                                         final FloatBuffer values) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location >= 0) {
            GL20C.glUniform1fv(location, values);
        }
    }

    /** Legacy composite-only matrix upload; unused by exact parent-water voxel clipping. */
    private static void uploadModelView(final int program) {
        final int location = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.MODEL_VIEW_UNIFORM);
        if (location < 0) {
            return;
        }
        final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        LAST_WATER_MODEL_VIEW.get(buffer);
        GL20C.glUniformMatrix4fv(location, false, buffer);
    }

    /**
     * Clears Java-only diagnostics so a later pipeline can be observed again.
     *
     * <p>This method is called while Iris compiles shaders and therefore must perform no GL work.
     * Render-pass bindings are always restored by {@link #end()} on the render thread.</p>
     */
    public static void onPipelineReset() {
        failureLogged = false;
        activationLogged = false;
        liveMatrixSourcesLogged = false;
        liveProjectionSource = "unavailable";
        liveModelViewSource = "unavailable";
        final int nextTextureUnitGeneration = voxelTextureUnitPipelineGeneration + 1;
        voxelTextureUnitPipelineGeneration = nextTextureUnitGeneration > 0
                ? nextTextureUnitGeneration : 1;
        VOXEL_TEXTURE_UNIT_CACHE.clear();
        SodiumOcclusionShaderSource.onPipelineReset();
    }

    /**
     * Uploads the active pack's water block id for legacy {@code mc_Entity} fluid markers
     * (Bliss uses 8, Complementary 32000, modern packs 10001). The value comes from Iris' own
     * block.properties id map, so it always matches the pack that is currently compiled.
     */
    private static void uploadWaterBlockId(final int program) {
        final int location = GL20C.glGetUniformLocation(program,
                SodiumOcclusionShaderSource.WATER_BLOCK_ID_UNIFORM);
        if (location < 0) {
            return;
        }
        final int waterId = IrisWaterMaterialBridge.waterMaterialId();
        GL20C.glUniform1f(location, waterId < 0 ? -1.0F : (float) waterId);
    }

    /**
     * Finds two units that the current shader does not sample.
     *
     * <p>Using fixed units 6/7 corrupts packs which already assign shadow or noise samplers there.
     * Active sampler uniforms are inspected instead, then the two highest free units are used for
     * the duration of this one terrain pass.</p>
     */
    private static int[] findUnusedTextureUnits(final int program) {
        final int maximum = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        if (maximum < 2) {
            return null;
        }
        final boolean[] used = new boolean[maximum];
        final int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
        final IntBuffer size = BufferUtils.createIntBuffer(1);
        final IntBuffer type = BufferUtils.createIntBuffer(1);
        for (int index = 0; index < count; index++) {
            size.put(0, 0);
            type.put(0, 0);
            final String name = GL20C.glGetActiveUniform(program, index, size, type);
            if (name == null || !isSamplerType(type.get(0))
                    || name.startsWith("NautilusWaterOcclusion")) {
                continue;
            }
            final int arraySize = Math.max(1, size.get(0));
            for (int element = 0; element < arraySize; element++) {
                final String elementName = element == 0 ? name
                        : name.replace("[0]", "[" + element + "]");
                final int location = GL20C.glGetUniformLocation(program, elementName);
                if (location < 0) {
                    continue;
                }
                final int unit = GL20C.glGetUniformi(program, location);
                if (unit >= 0 && unit < maximum) {
                    used[unit] = true;
                }
            }
        }

        int first = -1;
        int second = -1;
        for (int unit = maximum - 1; unit >= 0; unit--) {
            if (used[unit]) {
                continue;
            }
            if (first < 0) {
                first = unit;
            } else {
                second = unit;
                break;
            }
        }
        return second < 0 ? null : new int[]{first, second};
    }

    /**
     * Resolves one authoritative sampler layout once per linked program and pipeline generation.
     * A concurrent pipeline reset invalidates the in-flight result and fails this draw open.
     */
    private static int cachedUnusedTextureUnit(final int program) {
        if (program <= 0) return -1;
        final int pipelineGeneration = voxelTextureUnitPipelineGeneration;
        final TextureUnitCacheKey key = new TextureUnitCacheKey(program, pipelineGeneration);
        final Integer cached = VOXEL_TEXTURE_UNIT_CACHE.get(key);
        if (cached != null) return cached;
        if (!GL20C.glIsProgram(program)
                || GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL11C.GL_TRUE) {
            return -1;
        }
        final int resolved = findUnusedTextureUnit(program);
        if (pipelineGeneration != voxelTextureUnitPipelineGeneration) return -1;
        final Integer raced = VOXEL_TEXTURE_UNIT_CACHE.putIfAbsent(key, resolved);
        return raced == null ? resolved : raced;
    }

    /** Finds one unit not sampled by the active pack; absence disables clipping for this draw. */
    private static int findUnusedTextureUnit(final int program) {
        final int maximum = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        if (maximum < 1) {
            return -1;
        }
        final boolean[] used = new boolean[maximum];
        final int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
        final IntBuffer size = BufferUtils.createIntBuffer(1);
        final IntBuffer type = BufferUtils.createIntBuffer(1);
        for (int index = 0; index < count; index++) {
            size.put(0, 0);
            type.put(0, 0);
            final String name = GL20C.glGetActiveUniform(program, index, size, type);
            if (name == null || !isSamplerType(type.get(0))
                    || name.startsWith("Nautilus")) {
                continue;
            }
            final int arraySize = Math.max(1, size.get(0));
            for (int element = 0; element < arraySize; element++) {
                final String elementName = element == 0 ? name
                        : name.replace("[0]", "[" + element + "]");
                final int location = GL20C.glGetUniformLocation(program, elementName);
                if (location < 0) {
                    continue;
                }
                final int unit = GL20C.glGetUniformi(program, location);
                if (unit >= 0 && unit < maximum) {
                    used[unit] = true;
                }
            }
        }
        for (int unit = maximum - 1; unit >= 0; unit--) {
            if (!used[unit]) {
                return unit;
            }
        }
        return -1;
    }

    /** OpenGL sampler enum families through cube-array and multisample samplers. */
    private static boolean isSamplerType(final int type) {
        return type >= 0x8B5D && type <= 0x8B64
                || type >= 0x8DC0 && type <= 0x8DD8
                || type >= 0x900C && type <= 0x900F
                || type >= 0x9108 && type <= 0x910D;
    }

    private static void restore(final BindingState binding) {
        /*
         * The terrain program is reused by Iris when FloodedChamberRenderer enters the terrain-
         * translucent phase later in the frame. Leaving this value at one made the virtual room
         * surface sample whatever textures happened to be restored below and discard one side
         * unpredictably. Disable the test while its original program still exists, before
         * detaching Sable's samplers.
         */
        final int currentProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (GL20C.glIsProgram(binding.program())) {
            if (currentProgram != binding.program()) {
                GL20C.glUseProgram(binding.program());
            }
            GL20C.glUniform1f(binding.enabledUniform(), 0.0F);
            if (currentProgram != binding.program()) {
                GL20C.glUseProgram(currentProgram);
            }
        }
        if (binding.previousCloseSampler() >= 0) {
            GL33C.glBindSampler(binding.closeUnit(), binding.previousCloseSampler());
            GL33C.glBindSampler(binding.farUnit(), binding.previousFarSampler());
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + binding.closeUnit());
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding.previousCloseTexture());
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + binding.farUnit());
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding.previousFarTexture());
        GL13C.glActiveTexture(binding.previousActiveTexture());
    }

    /** Prevents Iris from applying the parent-ocean atlas to later Sable/virtual-water draws. */
    private static void disableVoxelBinding(final VoxelBindingState binding) {
        final int currentProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (!GL20C.glIsProgram(binding.program())) {
            return;
        }
        if (currentProgram != binding.program()) {
            GL20C.glUseProgram(binding.program());
        }
        GL20C.glUniform1f(binding.enabledUniform(), 0.0F);
        setVirtualWaterPassUniform(binding.program(), 0.0F);
        if (currentProgram != binding.program()) {
            GL20C.glUseProgram(currentProgram);
        }
    }

    /**
     * Rebuilds Sable's close/far hull-depth textures with Sable's native cull-face semantics.
     *
     * <p>Close buffer (cull back, clear 1.0 outside / 0.0 inside) stores the nearest outward
     * face; far buffer (cull front, clear 1.0) stores the farthest outward face. A parent-ocean
     * fragment whose window depth lies strictly between them is geometrically inside the sealed
     * dry volume and is discarded by the shader. Depth clamp stays off so walls behind the camera
     * are clipped instead of writing a bogus near-plane value into the far buffer.</p>
     */
    private static void rebuildOcclusionDepths(
            final Matrix4f modelView,
            final Matrix4f projection)
            throws ReflectiveOperationException {
        final long now = System.nanoTime();
        if (now - projectionLogNanos > 5_000_000_000L) {
            projectionLogNanos = now;
            CreateNautilus.LOGGER.info(
                    "[occlusion] mask rebuild projection m22={} m23={}",
                    projection.m22(), projection.m23());
        }
        if (FRAMEBUFFERS == null) {
            return;
        }
        final WaterOcclusionRenderer renderer = SableClient.WATER_OCCLUSION_RENDERER;
        final AdvancedFbo close = (AdvancedFbo) FRAMEBUFFERS.close().get(renderer);
        final AdvancedFbo far = (AdvancedFbo) FRAMEBUFFERS.far().get(renderer);
        if (close == null || far == null) {
            return;
        }

        final RenderPassState state = captureRenderPassState();
        try {
            RenderSystem.disableBlend();
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glColorMask(false, false, false, false);
            RenderSystem.disableScissor();
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            RenderSystem.enableDepthTest();
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            RenderSystem.depthMask(true);
            GL11C.glDepthMask(true);
            RenderSystem.depthFunc(GL11C.GL_LEQUAL);
            GL11C.glDepthFunc(GL11C.GL_LEQUAL);
            RenderSystem.enableCull();
            GL11C.glEnable(GL11C.GL_CULL_FACE);
            GL11C.glFrontFace(GL11C.GL_CCW);
            GL11C.glCullFace(GL11C.GL_BACK);
            RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionShader);

            final var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            final boolean cameraInside = ClientDryCompartmentCache
                    .isCameraInsideRegisteredDryRegion(camera.getPosition());

            // Close buffer: nearest outward face.  Inside a sealed room no face can pass a 0.0
            // clear, leaving the near plane -- exactly Sable's intended camera-inside state.
            close.bind(true);
            GL11C.glClearDepth(cameraInside ? 0.0D : 1.0D);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            ContinuousCompartmentOcclusionRegion.renderAllRegistered(
                    modelView, projection);

            // Far buffer: farthest outward face.  Front-face culling keeps only the far wall
            // outside the room; from inside it keeps the wall in front of the camera (its back
            // face), so ocean beyond the hull is never mistaken for cabin water.
            far.bind(true);
            GL11C.glClearDepth(1.0D);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            GL11C.glCullFace(GL11C.GL_FRONT);
            ContinuousCompartmentOcclusionRegion.renderAllRegistered(
                    modelView, projection);
        } finally {
            RenderSystem.depthFunc(GL11C.GL_LEQUAL);
            GL11C.glDepthFunc(GL11C.GL_LEQUAL);
            AdvancedFbo.unbind();
            restoreRenderPassState(state);
        }
    }

    private static RenderPassState captureRenderPassState() {
        final IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        final IntBuffer scissor = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissor);
        final ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMask);
        final FloatBuffer clearColor = BufferUtils.createFloatBuffer(4);
        GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, clearColor);
        final float[] shaderColor = RenderSystem.getShaderColor().clone();

        return new RenderPassState(
                GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3),
                scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3),
                GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE),
                GL11C.glGetInteger(GL11C.GL_FRONT_FACE),
                GL11C.glIsEnabled(DEPTH_CLAMP),
                GL11C.glIsEnabled(CLIP_DISTANCE_ZERO),
                GL11C.glIsEnabled(GL11C.GL_BLEND),
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),
                GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),
                GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA),
                GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
                GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA),
                colorMask.get(0) != 0, colorMask.get(1) != 0,
                colorMask.get(2) != 0, colorMask.get(3) != 0,
                clearColor.get(0), clearColor.get(1),
                clearColor.get(2), clearColor.get(3),
                GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE),
                GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),
                GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),
                GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING),
                GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
                RenderSystem.getShader(), shaderColor);
    }

    private static void restoreRenderPassState(final RenderPassState state) {
        // Restore Iris' draw/read targets before any subsequent terrain command is submitted.
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.drawFramebuffer());
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, state.readFramebuffer());
        RenderSystem.viewport(state.viewportX(), state.viewportY(),
                state.viewportWidth(), state.viewportHeight());

        if (state.scissorEnabled()) {
            RenderSystem.enableScissor(state.scissorX(), state.scissorY(),
                    state.scissorWidth(), state.scissorHeight());
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        } else {
            RenderSystem.disableScissor();
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        }
        GL11C.glScissor(state.scissorX(), state.scissorY(),
                state.scissorWidth(), state.scissorHeight());

        if (state.depthTestEnabled()) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        if (state.depthTestEnabled()) {
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        } else {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        }
        RenderSystem.depthMask(state.depthWriteEnabled());
        GL11C.glDepthMask(state.depthWriteEnabled());
        RenderSystem.depthFunc(state.depthFunction());
        GL11C.glDepthFunc(state.depthFunction());
        if (state.cullEnabled()) {
            RenderSystem.enableCull();
            GL11C.glEnable(GL11C.GL_CULL_FACE);
        } else {
            RenderSystem.disableCull();
            GL11C.glDisable(GL11C.GL_CULL_FACE);
        }
        GL11C.glFrontFace(state.frontFaceMode());
        GL11C.glCullFace(state.cullFaceMode());
        if (state.depthClampEnabled()) {
            GL11C.glEnable(DEPTH_CLAMP);
        } else {
            GL11C.glDisable(DEPTH_CLAMP);
        }
        if (state.clipDistanceZeroEnabled()) {
            GL11C.glEnable(CLIP_DISTANCE_ZERO);
        } else {
            GL11C.glDisable(CLIP_DISTANCE_ZERO);
        }
        if (state.blendEnabled()) {
            RenderSystem.enableBlend();
            GL11C.glEnable(GL11C.GL_BLEND);
        } else {
            RenderSystem.disableBlend();
            GL11C.glDisable(GL11C.GL_BLEND);
        }
        RenderSystem.blendFuncSeparate(
                state.blendSourceRgb(), state.blendDestinationRgb(),
                state.blendSourceAlpha(), state.blendDestinationAlpha());
        GL14C.glBlendFuncSeparate(
                state.blendSourceRgb(), state.blendDestinationRgb(),
                state.blendSourceAlpha(), state.blendDestinationAlpha());
        GL20C.glBlendEquationSeparate(
                state.blendEquationRgb(), state.blendEquationAlpha());
        RenderSystem.colorMask(state.colorRed(), state.colorGreen(),
                state.colorBlue(), state.colorAlpha());
        GL11C.glColorMask(state.colorRed(), state.colorGreen(),
                state.colorBlue(), state.colorAlpha());
        GL11C.glClearColor(state.clearRed(), state.clearGreen(),
                state.clearBlue(), state.clearAlpha());
        GL11C.glClearDepth(state.clearDepth());
        final float[] color = state.shaderColor();
        RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);

        /*
         * Veil may lazily allocate or resize the close/far depth attachments during update().
         * That code binds a 2D texture on the currently active unit. Restore both pieces of
         * state together; otherwise Iris can sample one of Nautilus' depth textures as a pack
         * input on the first affected frame, producing white/black water panels.
         */
        GL13C.glActiveTexture(state.activeTexture());
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.texture2d());

        // VertexBuffer.unbind() leaves VAO 0 (and first-time uploads may leave another VBO).
        // Sodium expects the bindings it established before entering this bridge.
        GL30C.glBindVertexArray(state.vertexArray());
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, state.arrayBuffer());

        /*
         * Keep RenderSystem's cached ShaderInstance coherent as well as restoring the raw GL
         * program. Sodium immediately replaces this with its terrain program, but later vanilla
         * draws must not believe Sable's position shader is still current.
         */
        // The saved value can legitimately be null. Restore it unconditionally so the Java-side
        // RenderSystem cache never keeps Sable's PositionShader after this helper pass.
        RenderSystem.setShader(() -> state.shader());
        // setShader restores RenderSystem's cache but can bind a vanilla program. The program
        // active on entry can instead be Iris' transformed terrain program, so calibrate raw GL
        // last without disturbing the restored cache.
        GL20C.glUseProgram(state.program());
    }

    private static FramebufferAccess findFramebufferAccess() {
        try {
            final Field close = WaterOcclusionRenderer.class.getDeclaredField("closeBuffer");
            final Field far = WaterOcclusionRenderer.class.getDeclaredField("farBuffer");
            close.setAccessible(true);
            far.setAccessible(true);
            return new FramebufferAccess(close, far);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            logFailureOnce("Unable to access Sable water-occlusion framebuffers", exception);
            return null;
        }
    }

    /** Iris reuses Sodium chunk programs for shadow maps, where Sable's camera-depth FBOs do not apply. */
    private static boolean isIrisShadowPass() {
        if (IRIS_SHADOW_QUERY == null) {
            return false;
        }
        try {
            return (boolean) IRIS_SHADOW_QUERY.invoke(null);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static Method findIrisShadowQuery() {
        try {
            final Class<?> state = Class.forName(
                    "net.irisshaders.iris.shadows.ShadowRenderingState");
            return state.getMethod("areShadowsCurrentlyBeingRendered");
        } catch (final ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static void logFailureOnce(final String message, final Throwable exception) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        if (exception == null) {
            CreateNautilus.LOGGER.warn(message);
        } else {
            CreateNautilus.LOGGER.warn(message, exception);
        }
    }

    private record FramebufferAccess(Field close, Field far) {
    }

    private record BindingState(int program, int enabledUniform,
                                int closeUnit, int farUnit, int previousActiveTexture,
                                int previousCloseTexture, int previousFarTexture,
                                int previousCloseSampler, int previousFarSampler) {
    }

    private record VoxelBindingState(int program, int enabledUniform) {
    }

    private record TextureUnitCacheKey(int program, int pipelineGeneration) {
    }

    private record RenderPassState(int drawFramebuffer, int readFramebuffer,
                                   int viewportX, int viewportY,
                                   int viewportWidth, int viewportHeight,
                                   int scissorX, int scissorY,
                                   int scissorWidth, int scissorHeight,
                                   boolean scissorEnabled,
                                   boolean depthTestEnabled, boolean depthWriteEnabled,
                                   int depthFunction,
                                   boolean cullEnabled, int cullFaceMode, int frontFaceMode,
                                   boolean depthClampEnabled, boolean clipDistanceZeroEnabled,
                                   boolean blendEnabled,
                                   int blendSourceRgb, int blendDestinationRgb,
                                   int blendSourceAlpha, int blendDestinationAlpha,
                                   int blendEquationRgb, int blendEquationAlpha,
                                   boolean colorRed, boolean colorGreen,
                                   boolean colorBlue, boolean colorAlpha,
                                   float clearRed, float clearGreen,
                                   float clearBlue, float clearAlpha, float clearDepth,
                                   int activeTexture, int texture2d,
                                   int vertexArray, int arrayBuffer,
                                   int program, ShaderInstance shader, float[] shaderColor) {
    }
}
