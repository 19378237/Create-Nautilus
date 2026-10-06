package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Low-cost, alpha-aware optical transition mask for Photon.
 *
 * <p>Exactly one full-resolution D24S8 texture is allocated.  Real cached glass triangles write
 * the globally nearest direct air/water transition to depth while stencil records its exact
 * X-/X+/Y-/Y+/Z-/Z+ direction. A stencil texture view shares the same storage, so Photon receives
 * exact semantics without another full-resolution allocation or perturbing reconstructed depth.</p>
 */
public final class ApertureMediumMaskRenderer {
    private static final long MAX_FULL_RESOLUTION_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_VBO_BYTES_PER_CARRIER = 8L * 1024L * 1024L;
    private static final long MAX_TOTAL_VBO_BYTES = 24L * 1024L * 1024L;
    private static final int BYTES_PER_DEPTH_STENCIL_PIXEL = 4;
    private static final int RELEASE_AFTER_EMPTY_FRAMES = 120;
    private static final int SCREEN_EXPANSION_PIXELS = 4;
    private static final IntBuffer VIEWPORT_QUERY = BufferUtils.createIntBuffer(4);
    private static final FloatBuffer DEPTH_RANGE_QUERY = BufferUtils.createFloatBuffer(2);

    private static int framebuffer = -1;
    private static int entryDepthStencil = -1;
    private static int entryDirectionView = -1;
    private static int width;
    private static int height;
    private static int maximumTextureSize;
    private static int validatedSceneDepthTexture = -1;
    private static int validatedSceneDepthWidth;
    private static int validatedSceneDepthHeight;
    private static final Map<UUID, CarrierGpu> GPU_CARRIERS = new HashMap<>();

    private static volatile boolean ready;
    private static volatile int generation = 1;
    private static volatile int selectedAdapterGeneration;
    private static volatile boolean publishedDepthRangeReversed;
    private static volatile boolean publishedClearDepthReversed;
    private static volatile boolean publishedCameraWater;
    private static volatile float publishedMinU;
    private static volatile float publishedMinV;
    private static volatile float publishedMaxU;
    private static volatile float publishedMaxV;
    private static volatile int publishedAtlasTexture = -1;
    private static volatile int publishedAtlasWidth;
    private static volatile int publishedAtlasHeight;
    private static volatile int publishedAtlasCarrierCount;
    private static volatile int publishedAdapterGeneration;
    private static boolean firstMaskGeometryLogged;
    private static boolean firstMaskPixelsLogged;
    private static boolean shaderPackWasActive;
    private static boolean failedUntilInvalidation;
    private static boolean failureLogged;
    private static boolean pendingDestroy;
    private static int emptyFrames;
    private static long frameSerial;
    private static long preparedSerial = Long.MIN_VALUE;
    private static long finalizedSerial = Long.MIN_VALUE;
    private static PreparedFrame preparedFrame;
    private static ScreenBounds previousBounds = ScreenBounds.none();

    private ApertureMediumMaskRenderer() {
    }

    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        processPendingDestroy();
        // A frame with no parent translucent draw never consumes its early atlas snapshot. Do not
        // let a same-camera update in the next frame mistake that snapshot for current data.
        ShaderVoxelMaskAtlas.clearEarlyFogRetention();
        ready = false;
        frameSerial++;
        preparedFrame = null;
        preparedSerial = Long.MIN_VALUE;
        final boolean shaderPackActive = WaterOcclusionPassBridge.isShaderPackActive();
        if (!shaderPackActive) {
            if (shaderPackWasActive) {
                shaderPackWasActive = false;
                selectedAdapterGeneration = 0;
                destroyGlNow();
                failedUntilInvalidation = false;
                failureLogged = false;
                generation++;
            }
            return;
        }
        shaderPackWasActive = true;
        // Semantic/raster work is deliberately deferred to the real Sodium parent translucent
        // hook. AFTER_SKY matrices precede Photon's gbuffer jitter contract and used to steal the
        // frame serial, causing both extra CPU work and a view-dependent mask.
    }

    /** Freezes semantic data and carrier poses; no full-resolution resource is touched here. */
    public static void prepareParentTranslucent(final Matrix4f modelView,
                                                final Matrix4f projection,
                                                final double cameraX,
                                                final double cameraY,
                                                final double cameraZ) {
        processPendingDestroy();
        final int adapterGeneration = selectedAdapterGeneration;
        if (preparedSerial == frameSerial) {
            final PreparedFrame existing = preparedFrame;
            if (existing != null && modelView != null && projection != null
                    && modelView.isFinite() && projection.isFinite()
                    && sameCamera(existing, cameraX, cameraY, cameraZ)) {
                if (ready()) {
                    // deferred1 already consumed this exact raster/semantic pair. Keep it stable
                    // through composite1; the Sodium upload below needs only the same semantics.
                    ShaderVoxelMaskAtlas.prepareOpticalEvents(
                            existing.semantic(), frameSerial);
                    return;
                }
                // Without a published early mask, the actual Sodium translucent hook may replace
                // only the raster half with Iris' exact water-pass matrices.
                final RasterContract raster = captureRasterContract();
                if (raster == null) {
                    preparedFrame = null;
                    return;
                }
                preparedFrame = new PreparedFrame(
                        existing.level(), cameraX, cameraY, cameraZ,
                        new Matrix4f(modelView),
                        new Matrix4f(projection), raster.viewportX(), raster.viewportY(),
                        raster.viewportWidth(), raster.viewportHeight(),
                        raster.depthRangeNear(), raster.depthRangeFar(),
                        existing.adapterGeneration(),
                        existing.semantic());
                ShaderVoxelMaskAtlas.prepareOpticalEvents(existing.semantic(), frameSerial);
            } else {
                // A different camera or invalid matrix input cannot share the early snapshot.
                // Fail open instead of pairing its mask with a later atlas publication.
                ready = false;
                preparedFrame = null;
            }
            return;
        }
        preparedSerial = frameSerial;
        ready = false;
        preparedFrame = null;
        if (failedUntilInvalidation || !WaterOcclusionPassBridge.isShaderPackActive()
                || !IrisCompositeShaderSource.adapterReady(adapterGeneration)
                || !IrisCompositeShaderSource.linkedConsumerReady(adapterGeneration)
                || modelView == null || projection == null
                || !modelView.isFinite() || !projection.isFinite()
                || !Double.isFinite(cameraX) || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ)) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof final ClientLevel level)) return;
        try {
            final RasterContract raster = captureRasterContract();
            if (raster == null) return;
            final ApertureEventCache.Frame semantic = ApertureEventCache.prepare(
                    level, cameraX, cameraY, cameraZ);
            if (semantic != null && semantic.opticalLayoutChanged()) {
                // The cache had to renumber/remove optical slots after the voxel atlas had already
                // frozen this water pass.  Publish neither side of that mixed generation: request
                // an atomic atlas replacement and let the next coherent frame retry.
                ShaderVoxelMaskAtlas.requestOpticalLayoutRebuild();
                return;
            }
            if (semantic == null || semantic.carriers().isEmpty()) {
                handleEmptyFrame();
                return;
            }
            ShaderVoxelMaskAtlas.prepareOpticalEvents(semantic, frameSerial);
            preparedFrame = new PreparedFrame(
                    level, cameraX, cameraY, cameraZ,
                    new Matrix4f(modelView),
                    new Matrix4f(projection), raster.viewportX(), raster.viewportY(),
                    raster.viewportWidth(), raster.viewportHeight(),
                    raster.depthRangeNear(), raster.depthRangeFar(),
                    adapterGeneration, semantic);
            emptyFrames = 0;
        } catch (final RuntimeException | LinkageError exception) {
            failCurrentGeneration("semantic aperture cache failed", exception);
        }
    }

    /**
     * Builds and publishes only the data needed by a pre-translucent fog consumer. The ordinary
     * Sodium water pass later consumes the retained atlas snapshots and performs its unchanged
     * uniform binding/clipping setup.
     */
    public static void prepareAndFinalizeEarlyFog(final Matrix4f modelView,
                                                  final Matrix4f projection,
                                                  final double cameraX,
                                                  final double cameraY,
                                                  final double cameraZ,
                                                  final int sceneDepthTexture,
                                                  final int targetWidth,
                                                  final int targetHeight,
                                                  final int irisFrameCounter,
                                                  final int adapterGeneration) {
        if (!RenderSystem.isOnRenderThread()
                || selectedAdapterGeneration != adapterGeneration
                || !IrisCompositeShaderSource.adapterReady(adapterGeneration)
                || !IrisCompositeShaderSource.linkedConsumerReady(adapterGeneration)) {
            return;
        }
        final double effectiveCameraX = WaterOcclusionPassBridge.sodiumEffectiveCamera(cameraX);
        final double effectiveCameraY = WaterOcclusionPassBridge.sodiumEffectiveCamera(cameraY);
        final double effectiveCameraZ = WaterOcclusionPassBridge.sodiumEffectiveCamera(cameraZ);
        try {
            ShaderVoxelMaskAtlas.updateForEarlyFog(
                    effectiveCameraX, effectiveCameraY, effectiveCameraZ);
            prepareParentTranslucent(modelView, projection,
                    effectiveCameraX, effectiveCameraY, effectiveCameraZ);
            final PreparedFrame frame = preparedFrame;
            if (frame == null || preparedSerial != frameSerial
                    || frame.adapterGeneration() != adapterGeneration) {
                return;
            }
            if (!ShaderVoxelMaskAtlas.publishOpticalForEarlyFog(
                    effectiveCameraX, effectiveCameraY, effectiveCameraZ,
                    frame.rotationOnlyView(), frame.projection(),
                    frame.viewportX(), frame.viewportY(),
                    frame.viewportWidth(), frame.viewportHeight())) {
                return;
            }
            finalizeBeforeDeferred(sceneDepthTexture, targetWidth, targetHeight,
                    irisFrameCounter, adapterGeneration);
        } catch (final RuntimeException | LinkageError unavailable) {
            // Early fog is optional and retryable. The later water pass must remain fully native.
            ready = false;
        }
    }

    /** Rasterizes against Iris' copied opaque depth immediately before deferred/composite. */
    public static void finalizeBeforeDeferred(final int sceneDepthTexture,
                                              final int targetWidth,
                                              final int targetHeight,
                                              final int irisFrameCounter,
                                              final int adapterGeneration) {
        processPendingDestroy();
        if (!RenderSystem.isOnRenderThread() || finalizedSerial == frameSerial) return;
        ready = false;
        final PreparedFrame frame = preparedFrame;
        if (failedUntilInvalidation || frame == null
                || preparedSerial != frameSerial
                || frame.adapterGeneration() != adapterGeneration
                || selectedAdapterGeneration != adapterGeneration
                || !IrisCompositeShaderSource.adapterReady(adapterGeneration)
                || !IrisCompositeShaderSource.linkedConsumerReady(adapterGeneration)
                || sceneDepthTexture <= 0 || targetWidth <= 0 || targetHeight <= 0
                || !textureLevelMatches(sceneDepthTexture, targetWidth, targetHeight)) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != frame.level()) return;
        final Vec3 liveCamera = minecraft.gameRenderer.getMainCamera().getPosition();
        if (!sameCamera(frame,
                WaterOcclusionPassBridge.sodiumEffectiveCamera(liveCamera.x),
                WaterOcclusionPassBridge.sodiumEffectiveCamera(liveCamera.y),
                WaterOcclusionPassBridge.sodiumEffectiveCamera(liveCamera.z))) {
            return;
        }

        final int viewportX = frame.viewportX();
        final int viewportY = frame.viewportY();
        final int viewportWidth = frame.viewportWidth();
        final int viewportHeight = frame.viewportHeight();
        if (viewportX < 0 || viewportY < 0 || viewportWidth <= 0 || viewportHeight <= 0
                || (long) viewportX + viewportWidth > targetWidth
                || (long) viewportY + viewportHeight > targetHeight) return;

        final int projectionOrdering = projectionDepthOrdering(frame.projection());
        if (projectionOrdering < 0) return;
        final IrisCompositeShaderSource.SceneDepthTransform sceneDepthTransform =
                IrisCompositeShaderSource.sceneDepthTransform(
                        adapterGeneration, irisFrameCounter, targetWidth, targetHeight);
        if (sceneDepthTransform == null) return;
        if ((long) targetWidth * targetHeight * BYTES_PER_DEPTH_STENCIL_PIXEL
                > MAX_FULL_RESOLUTION_BYTES
                || targetWidth > maximumTextureSize()
                || targetHeight > maximumTextureSize()) {
            failCurrentGeneration("D24S8 target exceeds the 16 MiB/GL size budget", null);
            return;
        }
        // Only consume this frame after the caller supplied a coherent Iris target.  Several
        // Iris paths may reach this hook while attachments are being exchanged; an early invalid
        // target must not prevent the later, correct deferred target from retrying in the same
        // frame.
        finalizedSerial = frameSerial;
        try {
            syncGpuCarriers(frame.semantic());
            final ScreenBounds bounds = screenBounds(frame, viewportX, viewportY,
                    viewportWidth, viewportHeight, targetWidth, targetHeight);
            if (bounds.empty()) {
                handleEmptyFrame();
                return;
            }
            final ShaderVoxelMaskAtlas.CompositeAtlasView opticalAtlas =
                    ShaderVoxelMaskAtlas.compositeView(frameSerial);
            if (opticalAtlas == null) {
                failCurrentGeneration(
                        "optical atlas missing or stale for the current frame", null);
                return;
            }
            if (!ensureFramebuffer(targetWidth, targetHeight)) {
                failCurrentGeneration("private aperture resources could not be allocated", null);
                return;
            }

            final ScreenBounds clearBounds = previousBounds.union(bounds);
            previousBounds = clearBounds;
            final int drawnBatches = renderMask(frame, sceneDepthTexture,
                    viewportX, viewportY, viewportWidth, viewportHeight,
                     projectionOrdering > 0, bounds, clearBounds,
                    sceneDepthTransform, opticalAtlas);
            if (drawnBatches < 0) {
                failCurrentGeneration("single D24S8 aperture draw failed", null);
                return;
            }
            if (!firstMaskGeometryLogged) {
                firstMaskGeometryLogged = true;
                CreateNautilus.LOGGER.info(
                        "[water-entry] first mask geometry batches={} semanticCarriers={} "
                                + "opticalCarriers={} gpuCarriers={}",
                        drawnBatches, frame.semantic().carriers().size(),
                        opticalAtlas.carriers().size(), GPU_CARRIERS.size());
            }
            if (drawnBatches == 0) {
                handleEmptyFrame();
                return;
            }
            previousBounds = bounds;
            publishedMinU = (float) bounds.x() / width;
            publishedMinV = (float) bounds.y() / height;
            publishedMaxU = (float) (bounds.x() + bounds.width()) / width;
            publishedMaxV = (float) (bounds.y() + bounds.height()) / height;
            publishedCameraWater = frame.semantic().cameraStartsInWater();
            publishedAdapterGeneration = frame.adapterGeneration();
            publishedAtlasTexture = opticalAtlas.texture();
            publishedAtlasWidth = opticalAtlas.width();
            publishedAtlasHeight = opticalAtlas.height();
            publishedAtlasCarrierCount = opticalAtlas.carrierCount();
            ready = true;
        } catch (final RuntimeException | LinkageError exception) {
            failCurrentGeneration("aperture mask finalization failed", exception);
        }
    }

    private static int renderMask(final PreparedFrame frame,
                                      final int sceneDepthTexture,
                                      final int viewportX,
                                      final int viewportY,
                                      final int viewportWidth,
                                      final int viewportHeight,
                                      final boolean projectionReversed,
                                      final ScreenBounds bounds,
                                       final ScreenBounds clearBounds,
                                       final IrisCompositeShaderSource.SceneDepthTransform
                                               sceneDepthTransform,
                                       final ShaderVoxelMaskAtlas.CompositeAtlasView opticalAtlas) {
        final GlState state = GlState.capture();
        try {
            final boolean standardRange = Math.abs(frame.depthRangeNear()) <= 1.0E-7D
                    && Math.abs(frame.depthRangeFar() - 1.0D) <= 1.0E-7D;
            final boolean inverseRange = Math.abs(frame.depthRangeNear() - 1.0D) <= 1.0E-7D
                    && Math.abs(frame.depthRangeFar()) <= 1.0E-7D;
            if (!standardRange && !inverseRange) return -1;
            final boolean rangeReversed = frame.depthRangeNear() > frame.depthRangeFar();
            final boolean reversed = projectionReversed ^ rangeReversed;
            final float clearDepth = reversed ? 0.0F : 1.0F;

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            GL11C.glReadBuffer(GL11C.GL_NONE);
            GL11C.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL11C.glScissor(clearBounds.x(), clearBounds.y(),
                    clearBounds.width(), clearBounds.height());
            GL11C.glDepthMask(true);
            GL11C.glStencilMask(0xFF);
            GL11C.glClearDepth(clearDepth);
            GL11C.glClearStencil(0);
            GL30C.glClearBufferfi(GL30C.GL_DEPTH_STENCIL, 0, clearDepth, 0);

            GL11C.glScissor(bounds.x(), bounds.y(), bounds.width(), bounds.height());
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthRange(frame.depthRangeNear(), frame.depthRangeFar());
            GL11C.glDepthFunc(reversed ? GL11C.GL_GREATER : GL11C.GL_LESS);
            GL11C.glEnable(GL11C.GL_STENCIL_TEST);
            GL11C.glStencilFunc(GL11C.GL_ALWAYS, 1, 0xFF);
            // One shared depth attachment selects the globally nearest valid entry across every
            // carrier and batch. Coplanar overlays and deeper windows fail depth after the first
            // fragment, so neither can erase the water segment that has already been established.
            GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_REPLACE);
            final ApertureMaskMeshRenderer.DrawSession drawSession =
                    ApertureMaskMeshRenderer.begin(
                            frame.projection(), opticalAtlas.texture(),
                            opticalAtlas.width(), opticalAtlas.height(), sceneDepthTexture,
                            width, height, sceneDepthTransform.scale(),
                            sceneDepthTransform.offsetU(), sceneDepthTransform.offsetV(),
                            reversed);
            if (drawSession == null) return -1;
            final int drawnBatches;
            try {
                drawnBatches = drawMaskGeometry(drawSession, frame, opticalAtlas);
                if (drawnBatches < 0) return -1;
            } finally {
                ApertureMaskMeshRenderer.end(drawSession);
            }
            logFirstMaskPixels(bounds, frame, opticalAtlas);

            publishedDepthRangeReversed = rangeReversed;
            publishedClearDepthReversed = reversed;
            return drawnBatches;
        } finally {
            state.restore();
        }
    }

    /** One-shot runtime proof that submitted aperture geometry survived the fragment/stencil path. */
    private static void logFirstMaskPixels(
            final ScreenBounds bounds,
            final PreparedFrame frame,
            final ShaderVoxelMaskAtlas.CompositeAtlasView opticalAtlas) {
        if (firstMaskPixelsLogged) return;
        firstMaskPixelsLogged = true;
        final long pixelCount = (long) bounds.width() * bounds.height();
        if (pixelCount <= 0L || pixelCount > Integer.MAX_VALUE) return;

        long invalidEvents = 0L;
        long invalidDirectionGroups = 0L;
        long invalidCarriers = 0L;
        long twoAxisCells = 0L;
        long threeAxisCells = 0L;
        long oppositeSideCells = 0L;
        for (final ApertureEventCache.CarrierFrame carrier
                : frame.semantic().carriers()) {
            invalidEvents += carrier.events().stream()
                    .filter(event -> !event.valid()).count();
            invalidDirectionGroups += carrier.voxelSlots().values().stream()
                    .filter(group -> group < 0
                            || Math.floorMod(group,
                            ApertureEventCache.DIRECTION_SLOT_GROUP_SIZE) == 0
                            || Math.floorDiv(group,
                            ApertureEventCache.DIRECTION_SLOT_GROUP_SIZE)
                            + Integer.bitCount(Math.floorMod(group,
                            ApertureEventCache.DIRECTION_SLOT_GROUP_SIZE))
                            > carrier.events().size())
                    .count();
            for (final int group : carrier.voxelSlots().values()) {
                final int directionBits = Math.floorMod(
                        group, ApertureEventCache.DIRECTION_SLOT_GROUP_SIZE);
                int axisBits = 0;
                if ((directionBits & 0b000011) != 0) axisBits |= 0b001;
                if ((directionBits & 0b001100) != 0) axisBits |= 0b010;
                if ((directionBits & 0b110000) != 0) axisBits |= 0b100;
                final int axisCount = Integer.bitCount(axisBits);
                if (axisCount == 2) twoAxisCells++;
                else if (axisCount == 3) threeAxisCells++;
                if ((directionBits & 0b000011) == 0b000011
                        || (directionBits & 0b001100) == 0b001100
                        || (directionBits & 0b110000) == 0b110000) {
                    oppositeSideCells++;
                }
            }
            final ShaderVoxelMaskAtlas.CompositeCarrierView opticalCarrier =
                    opticalAtlas.carrier(carrier.id());
            if (opticalCarrier == null || opticalCarrier.eventBase() < 0
                    || opticalCarrier.topologyRevision()
                    != carrier.topologyRevision()) {
                invalidCarriers++;
            }
        }
        final long invalidData = invalidEvents + invalidDirectionGroups + invalidCarriers;

        final int previousPackBuffer = GL11C.glGetInteger(
                GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        final int previousAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        final int previousRowLength = GL11C.glGetInteger(GL12C.GL_PACK_ROW_LENGTH);
        final int previousSkipPixels = GL11C.glGetInteger(GL12C.GL_PACK_SKIP_PIXELS);
        final int previousSkipRows = GL11C.glGetInteger(GL12C.GL_PACK_SKIP_ROWS);
        try {
            final ByteBuffer pixels = BufferUtils.createByteBuffer((int) pixelCount);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_ROWS, 0);
            GL11C.glReadPixels(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    GL11C.GL_STENCIL_INDEX, GL11C.GL_UNSIGNED_BYTE, pixels);
            int nearestPixels = 0;
            int noEntryPixels = 0;
            int unexpectedStencilPixels = 0;
            final int[] directionPixels = new int[6];
            for (int index = 0; index < pixels.capacity(); index++) {
                final int stencil = Byte.toUnsignedInt(pixels.get(index));
                if (stencil >= 1 && stencil <= 6) {
                    nearestPixels++;
                    directionPixels[stencil - 1]++;
                } else if (stencil == 0) {
                    noEntryPixels++;
                } else {
                    unexpectedStencilPixels++;
                }
            }
            CreateNautilus.LOGGER.info(
                    "[water-entry] first mask pixels nearest={} noEntry={} invalidData={} "
                            + "invalidEvents={} invalidDirectionGroups={} invalidCarriers={} "
                            + "directions=[X-:{},X+:{},Y-:{},Y+:{},Z-:{},Z+:{}] "
                            + "multiAxisCells=[2:{},3:{}] oppositeSideCells={} "
                            + "unexpectedStencil={} bounds={}x{}",
                    nearestPixels, noEntryPixels, invalidData,
                    invalidEvents, invalidDirectionGroups, invalidCarriers,
                    directionPixels[0], directionPixels[1], directionPixels[2],
                    directionPixels[3], directionPixels[4], directionPixels[5],
                    twoAxisCells, threeAxisCells, oppositeSideCells, unexpectedStencilPixels,
                    bounds.width(), bounds.height());
        } catch (final RuntimeException | LinkageError exception) {
            CreateNautilus.LOGGER.warn(
                    "[water-entry] first mask pixel diagnostic failed", exception);
        } finally {
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, previousAlignment);
            GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, previousRowLength);
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_PIXELS, previousSkipPixels);
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_ROWS, previousSkipRows);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
        }
    }

    private static int drawMaskGeometry(
            final ApertureMaskMeshRenderer.DrawSession drawSession,
            final PreparedFrame frame,
            final ShaderVoxelMaskAtlas.CompositeAtlasView opticalAtlas) {
        int drawnBatches = 0;
        for (final ApertureEventCache.CarrierFrame carrier
                : frame.semantic().carriers()) {
            final ShaderVoxelMaskAtlas.CompositeCarrierView opticalCarrier =
                    opticalAtlas.carrier(carrier.id());
            if (opticalCarrier == null || opticalCarrier.eventBase() < 0
                    || opticalCarrier.topologyRevision()
                    != carrier.topologyRevision()) continue;
            if (carrier.events().stream()
                    .noneMatch(ApertureEventCache.Event::valid)) continue;
            final CarrierGpu gpu = GPU_CARRIERS.get(carrier.id());
            if (gpu == null) continue;
            final Matrix4f modelView = carrierModelView(frame, carrier);
            for (final GpuBatch batch : gpu.batches()) {
                GL11C.glStencilFunc(GL11C.GL_ALWAYS, batch.directionCode(), 0xFF);
                final int atlasTexture = Minecraft.getInstance().getTextureManager()
                        .getTexture(batch.atlas()).getId();
                if (!ApertureMaskMeshRenderer.draw(
                        drawSession, batch.gpu(), modelView,
                        atlasTexture, batch.alphaCutoff(),
                        opticalCarrier.eventBase(),
                        opticalCarrier.voxelOriginX() - carrier.voxelOriginX(),
                        opticalCarrier.voxelOriginY() - carrier.voxelOriginY(),
                        opticalCarrier.voxelOriginZ() - carrier.voxelOriginZ())) return -1;
                drawnBatches++;
            }
        }
        return drawnBatches;
    }

    private static Matrix4f carrierModelView(
            final PreparedFrame frame,
            final ApertureEventCache.CarrierFrame carrier) {
        return cameraRelativeCarrierModelView(
                frame.rotationOnlyView(), carrier.pose(),
                carrier.voxelOriginX(), carrier.voxelOriginY(), carrier.voxelOriginZ(),
                frame.cameraX(), frame.cameraY(), frame.cameraZ());
    }

    /** Composes the camera-relative carrier transform with Sodium's full view matrix. */
    static Matrix4f cameraRelativeCarrierModelView(final Matrix4f modelView,
            final Matrix4d carrierPose, final int voxelOriginX,
            final int voxelOriginY, final int voxelOriginZ,
            final double cameraX, final double cameraY, final double cameraZ) {
        // Compose the large plot origin in double before camera subtraction, then convert the
        // already-small camera-relative transform to float. This avoids catastrophic cancellation
        // between million-scale plot vertices and the carrier pose translation in the vertex shader.
        final Matrix4d relative = new Matrix4d(carrierPose).translate(
                voxelOriginX, voxelOriginY, voxelOriginZ);
        relative.m30(relative.m30() - cameraX);
        relative.m31(relative.m31() - cameraY);
        relative.m32(relative.m32() - cameraZ);
        // Sodium's model view already operates on camera-relative vertices. Keep its view-bob
        // and hurt translation, while subtracting the absolute camera position only once above.
        return new Matrix4f(modelView).mul(new Matrix4f(relative));
    }

    private static void syncGpuCarriers(final ApertureEventCache.Frame semantic) {
        final Set<UUID> live = new HashSet<>();
        long retainedBytes = 0L;
        for (final ApertureEventCache.CarrierFrame carrier : semantic.carriers()) {
            live.add(carrier.id());
            final long carrierBytes = carrier.batches().stream().mapToLong(batch ->
                    (long) batch.vertexCount() * ApertureEventCache.VERTEX_STRIDE
                            * Float.BYTES).sum();
            final CarrierGpu previous = GPU_CARRIERS.get(carrier.id());
            if (carrierBytes <= 0L || carrierBytes > MAX_VBO_BYTES_PER_CARRIER
                    || retainedBytes + carrierBytes > MAX_TOTAL_VBO_BYTES) {
                if (previous != null) {
                    GPU_CARRIERS.remove(carrier.id());
                    destroyGpuCarrier(previous);
                }
                continue;
            }
            if (previous != null
                    && previous.geometryGeneration() == carrier.geometryGeneration()
                    && previous.batches().size() == carrier.batches().size()) {
                retainedBytes += carrierBytes;
                continue;
            }
            if (previous != null) {
                GPU_CARRIERS.remove(carrier.id());
                destroyGpuCarrier(previous);
            }
            final List<GpuBatch> batches = new ArrayList<>();
            boolean complete = true;
            for (final ApertureEventCache.Batch source : carrier.batches()) {
                final ApertureMaskMeshRenderer.GpuBatch gpu =
                        ApertureMaskMeshRenderer.upload(source.vertices());
                if (gpu == null) {
                    complete = false;
                    break;
                }
                batches.add(new GpuBatch(source.atlas(), source.alphaCutoff(),
                        source.directionCode(), gpu));
            }
            if (!complete) {
                batches.forEach(batch -> ApertureMaskMeshRenderer.destroy(batch.gpu()));
                continue;
            }
            GPU_CARRIERS.put(carrier.id(), new CarrierGpu(
                    carrier.geometryGeneration(), List.copyOf(batches)));
            retainedBytes += carrierBytes;
        }
        final List<UUID> stale = GPU_CARRIERS.keySet().stream()
                .filter(id -> !live.contains(id)).toList();
        for (final UUID id : stale) destroyGpuCarrier(GPU_CARRIERS.remove(id));
    }

    private static void destroyGpuCarrier(final CarrierGpu carrier) {
        if (carrier == null) return;
        carrier.batches().forEach(batch -> ApertureMaskMeshRenderer.destroy(batch.gpu()));
    }

    private static ScreenBounds screenBounds(final PreparedFrame frame,
                                             final int viewportX,
                                             final int viewportY,
                                             final int viewportWidth,
                                             final int viewportHeight,
                                             final int targetWidth,
                                             final int targetHeight) {
        ScreenBounds result = ScreenBounds.none();
        for (final ApertureEventCache.CarrierFrame carrier : frame.semantic().carriers()) {
            if (carrier.events().stream().noneMatch(ApertureEventCache.Event::valid)) continue;
            final Matrix4f transform = new Matrix4f(frame.projection())
                    .mul(carrierModelView(frame, carrier));
            // Project each optical block independently. A single AABB spanning front/rear
            // windows encloses an interior camera and degenerates to a full-screen scissor even
            // though every real aperture is tiny.
            for (final AABB local : carrier.apertureCellBounds()) {
                result = result.union(projectApertureBounds(local, transform, carrier,
                        viewportX, viewportY, viewportWidth, viewportHeight,
                        targetWidth, targetHeight));
            }
        }
        return result;
    }

    private static ScreenBounds projectApertureBounds(
            final AABB local,
            final Matrix4f transform,
            final ApertureEventCache.CarrierFrame carrier,
            final int viewportX,
            final int viewportY,
            final int viewportWidth,
            final int viewportHeight,
            final int targetWidth,
            final int targetHeight) {
        if (local == null) return ScreenBounds.none();
        float minimumX = Float.POSITIVE_INFINITY;
        float minimumY = Float.POSITIVE_INFINITY;
        float maximumX = Float.NEGATIVE_INFINITY;
        float maximumY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            final Vector4f clip = new Vector4f(
                    (float) (((corner & 1) == 0 ? local.minX : local.maxX)
                            - carrier.voxelOriginX()),
                    (float) (((corner & 2) == 0 ? local.minY : local.maxY)
                            - carrier.voxelOriginY()),
                    (float) (((corner & 4) == 0 ? local.minZ : local.maxZ)
                            - carrier.voxelOriginZ()), 1.0F);
            transform.transform(clip);
            if (!clip.isFinite() || clip.w <= 1.0E-5F) {
                // Only this one block crossed the near plane. A full viewport is conservative,
                // but unlike a carrier-wide box this occurs only while the camera touches glass.
                return new ScreenBounds(viewportX, viewportY,
                        viewportWidth, viewportHeight, false);
            }
            final float nx = clip.x / clip.w;
            final float ny = clip.y / clip.w;
            minimumX = Math.min(minimumX, nx);
            minimumY = Math.min(minimumY, ny);
            maximumX = Math.max(maximumX, nx);
            maximumY = Math.max(maximumY, ny);
        }
        if (maximumX < -1.0F || minimumX > 1.0F
                || maximumY < -1.0F || minimumY > 1.0F) return ScreenBounds.none();
        int x0 = viewportX + (int) Math.floor((Math.max(-1.0F, minimumX) * 0.5F
                + 0.5F) * viewportWidth) - SCREEN_EXPANSION_PIXELS;
        int y0 = viewportY + (int) Math.floor((Math.max(-1.0F, minimumY) * 0.5F
                + 0.5F) * viewportHeight) - SCREEN_EXPANSION_PIXELS;
        int x1 = viewportX + (int) Math.ceil((Math.min(1.0F, maximumX) * 0.5F
                + 0.5F) * viewportWidth) + SCREEN_EXPANSION_PIXELS;
        int y1 = viewportY + (int) Math.ceil((Math.min(1.0F, maximumY) * 0.5F
                + 0.5F) * viewportHeight) + SCREEN_EXPANSION_PIXELS;
        x0 = Math.clamp(x0, 0, targetWidth);
        y0 = Math.clamp(y0, 0, targetHeight);
        x1 = Math.clamp(x1, 0, targetWidth);
        y1 = Math.clamp(y1, 0, targetHeight);
        return new ScreenBounds(x0, y0, Math.max(0, x1 - x0),
                Math.max(0, y1 - y0), x1 <= x0 || y1 <= y0);
    }

    private static boolean ensureFramebuffer(final int targetWidth, final int targetHeight) {
        if ((long) targetWidth * targetHeight * BYTES_PER_DEPTH_STENCIL_PIXEL
                > MAX_FULL_RESOLUTION_BYTES) {
            return false;
        }
        if (framebuffer > 0 && entryDepthStencil > 0 && entryDirectionView > 0
                && width == targetWidth && height == targetHeight) return true;
        if (!GL.getCapabilities().OpenGL43) return false;

        final GlState state = GlState.capture();
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final PixelUnpackState unpack = PixelUnpackState.capture();
        int candidateFramebuffer = -1;
        int candidateTexture = -1;
        int candidateDirectionView = -1;
        try {
            candidateFramebuffer = GL30C.glGenFramebuffers();
            candidateTexture = GL11C.glGenTextures();
            unpack.applyTight();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, candidateTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL43C.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11C.GL_DEPTH_COMPONENT);
            GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, 1,
                    GL30C.GL_DEPTH24_STENCIL8, targetWidth, targetHeight);

            candidateDirectionView = GL11C.glGenTextures();
            GL43C.glTextureView(candidateDirectionView, GL11C.GL_TEXTURE_2D,
                    candidateTexture, GL30C.GL_DEPTH24_STENCIL8, 0, 1, 0, 1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, candidateDirectionView);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,
                    GL43C.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11C.GL_STENCIL_INDEX);

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, candidateFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,
                    GL30C.GL_DEPTH_STENCIL_ATTACHMENT,
                    GL11C.GL_TEXTURE_2D, candidateTexture, 0);
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            GL11C.glReadBuffer(GL11C.GL_NONE);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
                    != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                GL30C.glDeleteFramebuffers(candidateFramebuffer);
                GL11C.glDeleteTextures(candidateTexture);
                GL11C.glDeleteTextures(candidateDirectionView);
                return false;
            }
        } catch (final RuntimeException | LinkageError exception) {
            if (candidateFramebuffer > 0) GL30C.glDeleteFramebuffers(candidateFramebuffer);
            if (candidateTexture > 0) GL11C.glDeleteTextures(candidateTexture);
            if (candidateDirectionView > 0) GL11C.glDeleteTextures(candidateDirectionView);
            throw exception;
        } finally {
            unpack.restore();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL13C.glActiveTexture(previousActive);
            state.restore();
        }
        final int oldFramebuffer = framebuffer;
        final int oldTexture = entryDepthStencil;
        final int oldDirectionView = entryDirectionView;
        framebuffer = candidateFramebuffer;
        entryDepthStencil = candidateTexture;
        entryDirectionView = candidateDirectionView;
        width = targetWidth;
        height = targetHeight;
        previousBounds = ScreenBounds.none();
        if (oldFramebuffer > 0) GL30C.glDeleteFramebuffers(oldFramebuffer);
        if (oldDirectionView > 0) GL11C.glDeleteTextures(oldDirectionView);
        if (oldTexture > 0) GL11C.glDeleteTextures(oldTexture);
        return true;
    }

    private static int projectionDepthOrdering(final Matrix4f projection) {
        final Vector4f near = projection.transform(
                new Vector4f(0.0F, 0.0F, -1.0F, 1.0F));
        final Vector4f far = projection.transform(
                new Vector4f(0.0F, 0.0F, -16_384.0F, 1.0F));
        if (!near.isFinite() || !far.isFinite()
                || Math.abs(near.w) <= 1.0E-7F || Math.abs(far.w) <= 1.0E-7F) return -1;
        final float nearDepth = near.z / near.w;
        final float farDepth = far.z / far.w;
        if (!Float.isFinite(nearDepth) || !Float.isFinite(farDepth)
                || Math.abs(farDepth - nearDepth) <= 1.0E-7F) return -1;
        return farDepth < nearDepth ? 1 : 0;
    }

    private static boolean textureLevelMatches(final int texture,
                                               final int expectedWidth,
                                               final int expectedHeight) {
        if (texture == validatedSceneDepthTexture
                && expectedWidth == validatedSceneDepthWidth
                && expectedHeight == validatedSceneDepthHeight) return true;
        if (texture <= 0 || !GL11C.glIsTexture(texture)) return false;
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            final boolean valid = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0,
                    GL11C.GL_TEXTURE_WIDTH) == expectedWidth
                    && GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0,
                    GL11C.GL_TEXTURE_HEIGHT) == expectedHeight;
            if (valid) {
                validatedSceneDepthTexture = texture;
                validatedSceneDepthWidth = expectedWidth;
                validatedSceneDepthHeight = expectedHeight;
            }
            return valid;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL13C.glActiveTexture(previousActive);
        }
    }

    private static boolean sameCamera(final PreparedFrame frame,
                                      final double x, final double y, final double z) {
        return Math.abs(frame.cameraX() - x) <= 1.0E-9D
                && Math.abs(frame.cameraY() - y) <= 1.0E-9D
                && Math.abs(frame.cameraZ() - z) <= 1.0E-9D;
    }

    /** Captured at the actual Sodium parent-water pass, never reconstructed at composite time. */
    private static RasterContract captureRasterContract() {
        VIEWPORT_QUERY.clear();
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, VIEWPORT_QUERY);
        DEPTH_RANGE_QUERY.clear();
        GL11C.glGetFloatv(GL11C.GL_DEPTH_RANGE, DEPTH_RANGE_QUERY);
        final int x = VIEWPORT_QUERY.get(0);
        final int y = VIEWPORT_QUERY.get(1);
        final int width = VIEWPORT_QUERY.get(2);
        final int height = VIEWPORT_QUERY.get(3);
        final double near = DEPTH_RANGE_QUERY.get(0);
        final double far = DEPTH_RANGE_QUERY.get(1);
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || !Double.isFinite(near) || !Double.isFinite(far)) return null;
        final boolean standard = Math.abs(near) <= 1.0E-7D
                && Math.abs(far - 1.0D) <= 1.0E-7D;
        final boolean inverse = Math.abs(near - 1.0D) <= 1.0E-7D
                && Math.abs(far) <= 1.0E-7D;
        return standard || inverse
                ? new RasterContract(x, y, width, height, near, far) : null;
    }

    private static int maximumTextureSize() {
        if (maximumTextureSize <= 0) {
            maximumTextureSize = Math.max(1, GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE));
        }
        return maximumTextureSize;
    }

    private static void handleEmptyFrame() {
        ready = false;
        preparedFrame = null;
        if (++emptyFrames >= RELEASE_AFTER_EMPTY_FRAMES) {
            releaseFramebufferOnly();
            releaseCachedMaskResources();
            emptyFrames = 0;
        }
    }

    public static int textureId() {
        return entryDepthStencil;
    }

    /** Unsigned stencil view sharing {@link #textureId()} storage; values 1..6 are directions. */
    public static int directionTextureId() {
        return entryDirectionView;
    }

    /** Chooses the exact cached Iris pipeline generation before Sodium submits water. */
    public static void selectAdapterGeneration(final int adapterGeneration) {
        selectedAdapterGeneration = IrisCompositeShaderSource.adapterReady(adapterGeneration)
                && IrisCompositeShaderSource.linkedConsumerReady(adapterGeneration)
                ? adapterGeneration : 0;
    }

    public static boolean ready() {
        return ready && finalizedSerial == frameSerial;
    }

    public static int generation() {
        return generation;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    public static int atlasTextureId() {
        return ready() ? publishedAtlasTexture : -1;
    }

    public static int atlasWidth() {
        return ready() ? publishedAtlasWidth : 0;
    }

    public static int atlasHeight() {
        return ready() ? publishedAtlasHeight : 0;
    }

    public static int atlasCarrierCount() {
        return ready() ? publishedAtlasCarrierCount : 0;
    }

    /** Exact Iris adapter generation used for this immutable mask/atlas frame. */
    public static int adapterGeneration() {
        return ready() ? publishedAdapterGeneration : 0;
    }

    /** True only when glDepthRange itself must be undone before inverse projection. */
    public static boolean depthRangeReversed() {
        return ready() && publishedDepthRangeReversed;
    }

    /** True when the actual clear value is zero after projection and depth-range composition. */
    public static boolean clearDepthReversed() {
        return ready() && publishedClearDepthReversed;
    }

    public static boolean cameraStartsInWater() {
        return ready() && publishedCameraWater;
    }

    /** Normalized half-open minU/minV/maxU/maxV; callers receive a defensive copy. */
    public static float[] bounds() {
        return new float[]{publishedMinU, publishedMinV, publishedMaxU, publishedMaxV};
    }

    public static void notifyBlockChanged(final ClientLevel level, final BlockPos position) {
        applyInvalidation(ApertureEventCache.notifyBlockChanged(level, position));
    }

    static synchronized void forgetCarrier(final ClientLevel level, final UUID carrierId) {
        ApertureEventCache.invalidateCarrier(level, carrierId);
        ShaderVoxelMaskAtlas.requestOpticalLayoutRebuild();
        ShaderVoxelMaskAtlas.discardPreparedFrame();
        invalidateFrameOnly();
    }

    public static void onClientChunkLoad(final ChunkEvent.Load event) {
        applyInvalidation(ApertureEventCache.onClientChunkLoad(event));
    }

    public static void onClientChunkUnload(final ChunkEvent.Unload event) {
        applyInvalidation(ApertureEventCache.onClientChunkUnload(event));
    }

    public static void onClientLogout(final ClientPlayerNetworkEvent.LoggingOut event) {
        ApertureEventCache.onClientLogout(event);
        destroy();
    }

    public static void onClientLevelUnload(final LevelEvent.Unload event) {
        final ApertureEventCache.Invalidation invalidation =
                ApertureEventCache.onClientLevelUnload(event);
        if (invalidation.geometryChanged()) {
            ShaderVoxelMaskAtlas.requestOpticalLayoutRebuild();
        }
        if (event.getLevel().isClientSide()) destroy();
    }

    public static synchronized void invalidateAll() {
        ready = false;
        preparedFrame = null;
        finalizedSerial = Long.MIN_VALUE;
        failedUntilInvalidation = false;
        failureLogged = false;
        generation++;
        ApertureEventCache.invalidateResources();
        ShaderVoxelMaskAtlas.requestOpticalLayoutRebuild();
        ApertureMaskMeshRenderer.resetFailure();
        pendingDestroy = true;
    }

    private static void applyInvalidation(
            final ApertureEventCache.Invalidation invalidation) {
        if (invalidation == null || !invalidation.changed()) return;
        if (invalidation.geometryChanged()) {
            ShaderVoxelMaskAtlas.requestOpticalLayoutRebuild();
        }
        invalidateFrameOnly();
    }

    /**
     * Drops only pipeline-owned GL/publication state. Cached baked aperture semantics survive a
     * shader hot reload, so Iris cannot trigger a full plot/model rescan merely by relinking.
     */
    public static synchronized void invalidatePipeline() {
        ready = false;
        preparedFrame = null;
        preparedSerial = Long.MIN_VALUE;
        finalizedSerial = Long.MIN_VALUE;
        selectedAdapterGeneration = 0;
        failedUntilInvalidation = false;
        failureLogged = false;
        generation++;
        ApertureMaskMeshRenderer.resetFailure();
        pendingDestroy = true;
    }

    private static void invalidateFrameOnly() {
        ready = false;
        preparedFrame = null;
        finalizedSerial = Long.MIN_VALUE;
        failedUntilInvalidation = false;
        generation++;
    }

    public static void destroy() {
        ready = false;
        preparedFrame = null;
        shaderPackWasActive = false;
        selectedAdapterGeneration = 0;
        failedUntilInvalidation = false;
        failureLogged = false;
        generation++;
        pendingDestroy = true;
        if (RenderSystem.isOnRenderThread()) destroyGlNow();
        else RenderSystem.recordRenderCall(ApertureMediumMaskRenderer::destroyGlNow);
    }

    private static void failCurrentGeneration(final String reason, final Throwable cause) {
        ready = false;
        failedUntilInvalidation = true;
        if (failureLogged) return;
        failureLogged = true;
        if (cause == null) {
            CreateNautilus.LOGGER.warn(
                    "[water-entry] disabled for this generation: {}. Photon remains native.",
                    reason);
        } else {
            CreateNautilus.LOGGER.warn(
                    "[water-entry] disabled for this generation: {}. Photon remains native.",
                    reason, cause);
        }
    }

    private static synchronized void processPendingDestroy() {
        if (pendingDestroy && RenderSystem.isOnRenderThread()) destroyGlNow();
    }

    private static synchronized void destroyGlNow() {
        if (!RenderSystem.isOnRenderThread()) {
            pendingDestroy = true;
            return;
        }
        pendingDestroy = false;
        releaseCachedMaskResources();
        releaseFramebufferOnly();
        validatedSceneDepthTexture = -1;
        validatedSceneDepthWidth = validatedSceneDepthHeight = 0;
        ApertureMaskMeshRenderer.destroyPrograms();
        previousBounds = ScreenBounds.none();
        publishedAtlasTexture = -1;
        publishedAtlasWidth = publishedAtlasHeight = publishedAtlasCarrierCount = 0;
    }

    private static void releaseCachedMaskResources() {
        for (final CarrierGpu carrier : GPU_CARRIERS.values()) destroyGpuCarrier(carrier);
        GPU_CARRIERS.clear();
    }

    private static void releaseFramebufferOnly() {
        if (framebuffer > 0) GL30C.glDeleteFramebuffers(framebuffer);
        if (entryDirectionView > 0) GL11C.glDeleteTextures(entryDirectionView);
        if (entryDepthStencil > 0) GL11C.glDeleteTextures(entryDepthStencil);
        framebuffer = entryDepthStencil = entryDirectionView = -1;
        width = height = 0;
        previousBounds = ScreenBounds.none();
    }

    private record PreparedFrame(ClientLevel level,
                                 double cameraX, double cameraY, double cameraZ,
                                 Matrix4f rotationOnlyView, Matrix4f projection,
                                 int viewportX, int viewportY,
                                 int viewportWidth, int viewportHeight,
                                 double depthRangeNear, double depthRangeFar,
                                 int adapterGeneration,
                                 ApertureEventCache.Frame semantic) {
        private PreparedFrame {
            rotationOnlyView = new Matrix4f(rotationOnlyView);
            projection = new Matrix4f(projection);
        }
    }

    private record RasterContract(int viewportX, int viewportY,
                                  int viewportWidth, int viewportHeight,
                                  double depthRangeNear, double depthRangeFar) {
    }

    private record GpuBatch(net.minecraft.resources.ResourceLocation atlas,
                            float alphaCutoff,
                            int directionCode,
                            ApertureMaskMeshRenderer.GpuBatch gpu) {
    }

    private record CarrierGpu(long geometryGeneration,
                               List<GpuBatch> batches) {
    }

    /** Restores all client-unpack state so Iris PBO uploads cannot reinterpret our pointers. */
    private record PixelUnpackState(int buffer, int alignment, int rowLength,
                                    int skipRows, int skipPixels,
                                    int imageHeight, int skipImages) {
        private static PixelUnpackState capture() {
            return new PixelUnpackState(
                    GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING),
                    GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_ROWS),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_PIXELS),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_IMAGE_HEIGHT),
                    GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_IMAGES));
        }

        private void applyTight() {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
        }

        private void restore() {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, buffer);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, skipImages);
        }
    }

    private record ScreenBounds(int x, int y, int width, int height, boolean empty) {
        private static ScreenBounds none() {
            return new ScreenBounds(0, 0, 0, 0, true);
        }

        private ScreenBounds union(final ScreenBounds other) {
            if (empty) return other;
            if (other.empty) return this;
            final int x0 = Math.min(x, other.x);
            final int y0 = Math.min(y, other.y);
            final int x1 = Math.max(x + width, other.x + other.width);
            final int y1 = Math.max(y + height, other.y + other.height);
            return new ScreenBounds(x0, y0, x1 - x0, y1 - y0, false);
        }
    }

    private record GlState(int drawFramebuffer, int readFramebuffer,
                           int depthFunction, boolean depthMask,
                           double clearDepth, int clearStencil,
                           double depthRangeNear, double depthRangeFar,
                           boolean depthTest, boolean cull,
                           boolean stencilTest, boolean polygonOffset,
                           boolean scissorTest, int scissorX, int scissorY,
                           int scissorWidth, int scissorHeight,
                           int viewportX, int viewportY,
                           int viewportWidth, int viewportHeight,
                            int stencilFunction, int stencilReference,
                            int stencilValueMask, int stencilWriteMask,
                            int stencilFail, int stencilDepthFail, int stencilDepthPass,
                            int stencilBackFunction, int stencilBackReference,
                            int stencilBackValueMask, int stencilBackWriteMask,
                            int stencilBackFail, int stencilBackDepthFail,
                            int stencilBackDepthPass) {
        private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(4);
        private static final IntBuffer SCISSOR = BufferUtils.createIntBuffer(4);
        private static final FloatBuffer DEPTH_CLEAR = BufferUtils.createFloatBuffer(1);
        private static final FloatBuffer DEPTH_RANGE = BufferUtils.createFloatBuffer(2);

        private static GlState capture() {
            VIEWPORT.clear();
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, VIEWPORT);
            SCISSOR.clear();
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, SCISSOR);
            DEPTH_CLEAR.clear();
            GL11C.glGetFloatv(GL11C.GL_DEPTH_CLEAR_VALUE, DEPTH_CLEAR);
            DEPTH_RANGE.clear();
            GL11C.glGetFloatv(GL11C.GL_DEPTH_RANGE, DEPTH_RANGE);
            return new GlState(
                    GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC),
                    GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
                    DEPTH_CLEAR.get(0), GL11C.glGetInteger(GL11C.GL_STENCIL_CLEAR_VALUE),
                    DEPTH_RANGE.get(0), DEPTH_RANGE.get(1),
                    GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
                    GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                    GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
                    GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                    SCISSOR.get(0), SCISSOR.get(1), SCISSOR.get(2), SCISSOR.get(3),
                    VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_REF),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL),
                    GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_FUNC),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_REF),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_VALUE_MASK),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_WRITEMASK),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_FAIL),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_PASS_DEPTH_FAIL),
                    GL11C.glGetInteger(GL20C.GL_STENCIL_BACK_PASS_DEPTH_PASS));
        }

        private void restore() {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            // Draw/read-buffer mappings belong to their framebuffer objects. Rebinding the Iris
            // FBO restores its complete MRT configuration; glDrawBuffer here would collapse it
            // to one attachment and can blank later deferred outputs.
            GL11C.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
            GL11C.glScissor(scissorX, scissorY, scissorWidth, scissorHeight);
            GL11C.glDepthFunc(depthFunction);
            GL11C.glDepthMask(depthMask);
            GL11C.glClearDepth(clearDepth);
            GL11C.glClearStencil(clearStencil);
            GL11C.glDepthRange(depthRangeNear, depthRangeFar);
            GL20C.glStencilMaskSeparate(GL11C.GL_FRONT, stencilWriteMask);
            GL20C.glStencilFuncSeparate(GL11C.GL_FRONT,
                    stencilFunction, stencilReference, stencilValueMask);
            GL20C.glStencilOpSeparate(GL11C.GL_FRONT,
                    stencilFail, stencilDepthFail, stencilDepthPass);
            GL20C.glStencilMaskSeparate(GL11C.GL_BACK, stencilBackWriteMask);
            GL20C.glStencilFuncSeparate(GL11C.GL_BACK,
                    stencilBackFunction, stencilBackReference, stencilBackValueMask);
            GL20C.glStencilOpSeparate(GL11C.GL_BACK,
                    stencilBackFail, stencilBackDepthFail, stencilBackDepthPass);
            setEnabled(GL11C.GL_DEPTH_TEST, depthTest);
            setEnabled(GL11C.GL_CULL_FACE, cull);
            setEnabled(GL11C.GL_STENCIL_TEST, stencilTest);
            setEnabled(GL11C.GL_POLYGON_OFFSET_FILL, polygonOffset);
            setEnabled(GL11C.GL_SCISSOR_TEST, scissorTest);
        }

        private static void setEnabled(final int capability, final boolean enabled) {
            if (enabled) GL11C.glEnable(capability);
            else GL11C.glDisable(capability);
        }
    }
}
