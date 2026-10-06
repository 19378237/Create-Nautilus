package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

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
 * Renders a per-pixel "glass entry depth" mask for dry compartments.
 *
 * <p>For every glass-like block inside a dry room, the block faces whose outward neighbour is
 * NOT an interior cell of any compartment are depth-rendered into a private FBO with the
 * frame's water projection. This produces a pixel-exact silhouette of the actual glass hull
 * geometry (Sable renders the vehicle through the vanilla chunk path, so the mask faces sit
 * exactly on the baked glass block boundary). The composite then compares that entry depth
 * against its own front depth: pixels whose front surface is farther than the entry (i.e.
 * looking out through the glass) trigger the pack's underwater post-processing, while
 * solid-wall pixels do not. The water-line itself is clipped per pixel in the composite by
 * {@code NautilusWaterSurfaceY}, so the mask does not need to know where the water is.</p>
 */
public final class WaterEntryMaskRenderer {
    public static final int TEXTURE_UNIT = 15;

    private static int fbo = -1;
    private static int depthTexture = -1;
    private static int width = -1;
    private static int height = -1;
    private static long lastLogNanos;
    private static long lastReadbackNanos;
    private static long lastFirstSampleLogNanos;
    private static boolean matrixSourceLogged;
    private static long lastFaceLogNanos;
    private static final Map<FaceCacheKey, FaceCacheEntry> FACE_CACHE = new HashMap<>();
    private static final int FACE_CACHE_LIMIT = 128;
    private static final long FACE_REBUILD_NANOS = 3_000_000_000L;

    private record AlignSample(String label, float screenX, float screenY, float distance) {
    }

    private record FaceCacheKey(UUID carrierId, long topologyRevision) {
    }

    private record FaceCacheEntry(float[] faces, long rebuiltNanos) {
    }

    private WaterEntryMaskRenderer() {
    }

    public static void onRenderLevelStart(final RenderLevelStageEvent event) {
        /*
         * The porthole underwater-fog feature is currently disabled (see
         * IrisCompositeShaderSource.PORTHOLE_ENABLED). Without this gate the mask renderer still
         * bound/cleared its FBO and touched the reserved texture unit every frame, adding raw GL
         * state churn to the AFTER_SKY stage for a feature that cannot render.
         */
        if (!IrisCompositeShaderSource.PORTHOLE_ENABLED) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.getMainRenderTarget() == null) {
            return;
        }
        final var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        final boolean dryCompartment = ClientCompartmentVisualCache.cameraWaterState(camera)
                == ClientCompartmentVisualCache.CameraWaterState.DRY;
        if (!ensureFbo(minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height)) {
            return;
        }

        /*
         * The mask must use the same projection as depthtex0 for the composite's entry<front
         * comparison. Sable's water-pass uniforms are preferred, but with Iris/Colorwheel the
         * Sodium program carrying those uniforms never binds (begin() bails out before filling
         * lastWaterMatricesValid), so fall back to the live frame camera: rotation-only model
         * view plus the frame projection, which is exactly what depthtex0 was rendered with.
         */
        final Matrix4f modelView;
        final Matrix4f projection;
        if (SodiumWaterOcclusionShaderBridge.lastWaterMatricesValid()) {
            modelView = SodiumWaterOcclusionShaderBridge.lastWaterModelView();
            projection = SodiumWaterOcclusionShaderBridge.lastWaterProjection();
        } else {
            modelView = new Matrix4f().rotation(
                    minecraft.gameRenderer.getMainCamera().rotation());
            projection = new Matrix4f(minecraft.gameRenderer.getProjectionMatrix(
                    minecraft.getTimer().getGameTimeDeltaPartialTick(false)));
        }
        if (!matrixSourceLogged) {
            matrixSourceLogged = true;
            CreateNautilus.LOGGER.info(
                    "[water-entry] mask matrices source: {}",
                    SodiumWaterOcclusionShaderBridge.lastWaterMatricesValid()
                            ? "water-pass uniforms" : "frame camera");
        }

        final List<Float> positions = new ArrayList<>();
        final List<AlignSample> samples = new ArrayList<>();
        if (dryCompartment) {
            final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
            if (container != null) {
                for (final var carrierEntry : ClientCompartmentVisualCache.snapshots().entrySet()) {
                    if (carrierEntry.getValue().isEmpty()) {
                        continue;
                    }
                    if (!(container.getSubLevel(carrierEntry.getKey())
                            instanceof final ClientSubLevel carrier)) {
                        continue;
                    }
                    final Set<Long> interiorCells = new HashSet<>();
                    for (final ClientCompartmentVisualCache.RoomSnapshot room
                            : carrierEntry.getValue()) {
                        for (final BlockPos cell : room.cells()) {
                            interiorCells.add(cell.asLong());
                        }
                    }
                    final long revision = carrierEntry.getValue().stream()
                            .mapToLong(ClientCompartmentVisualCache.RoomSnapshot
                                    ::topologyRevision)
                            .max()
                            .orElse(0L);
                    addGlassFaces(positions, carrierEntry.getKey(), carrier, camera,
                            interiorCells, revision, samples, modelView, projection);
                }
            }
        }

        final MaskState state = captureState();
        try {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL11C.glViewport(0, 0, width, height);
            GL11C.glDepthMask(true);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_LESS);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glClearDepth(1.0D);
            GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
            if (!positions.isEmpty()) {
                RawDepthRenderer.draw(positions, modelView, projection);
            }
        } finally {
            restoreState(state);
        }
        bindForComposite();
        logMaskReadback(samples);
        final long now = System.nanoTime();
        if (now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            CreateNautilus.LOGGER.info(
                    "[water-entry] mask rendered: dry={} quads={}",
                    dryCompartment, positions.size() / 6);
        }
        if (dryCompartment && positions.isEmpty()
                && now - lastFirstSampleLogNanos > 5_000_000_000L) {
            lastFirstSampleLogNanos = now;
            logFirstSample();
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

    private static void logMaskReadback(final List<AlignSample> samples) {
        final long now = System.nanoTime();
        if (lastReadbackNanos != 0L && now - lastReadbackNanos < 5_000_000_000L) {
            return;
        }
        lastReadbackNanos = now;
        if (depthTexture < 0 || width <= 0 || height <= 0) {
            return;
        }
        final FloatBuffer pixels = BufferUtils.createFloatBuffer(width * height);
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTexture);
            GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0,
                    GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, pixels);
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL13C.glActiveTexture(previousActive);
        }
        final int cx = width / 2;
        final int cy = height / 2;
        CreateNautilus.LOGGER.info(
                "[water-entry] mask readback size={}x{} center={} p25={} p50={} p75={} "
                        + "corner={}",
                width, height,
                pixels.get(cy * width + cx),
                pixels.get((height / 4) * width + width / 2),
                pixels.get((height / 2) * width + width / 4),
                pixels.get((height / 2) * width + width * 3 / 4),
                pixels.get(0));
        for (final AlignSample sample : samples) {
            final int sampleX = (int) sample.screenX();
            final int sampleY = (int) sample.screenY();
            final float depth = pixels.get(sampleY * width + sampleX);
            CreateNautilus.LOGGER.info(
                    "[water-entry] mask align sample={} screen=({},{}) dist={} depth={}",
                    sample.label(), sampleX, sampleY, sample.distance(), depth);
        }
    }

    private static void logFirstSample() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return;
        }
        final var snapshots = ClientCompartmentVisualCache.snapshots();
        CreateNautilus.LOGGER.info(
                "[water-entry] debug: rooms={}", snapshots.size());
        for (final var carrierEntry : snapshots.entrySet()) {
            final Object raw = container.getSubLevel(carrierEntry.getKey());
            CreateNautilus.LOGGER.info(
                    "[water-entry] debug: carrier={} subLevel={} type={}",
                    carrierEntry.getKey(), raw, raw == null ? "null" : raw.getClass().getName());
            if (!(raw instanceof final ClientSubLevel carrier)) {
                continue;
            }
            for (final ClientCompartmentVisualCache.RoomSnapshot room : carrierEntry.getValue()) {
                final AABB bounds = room.bounds();
                final Direction direction = Direction.NORTH;
                final int nz = -1;
                final double plane = bounds.minZ - 0.1D;
                final Vector3d sampleLocal = new Vector3d(
                        bounds.minX + 0.5D, bounds.minY + 0.5D, plane + nz * 1.4D);
                final Vector3d sampleWorldCamRel = carrier.renderPose().transformPosition(
                        new Vector3d(sampleLocal));
                final Matrix4d pose = carrier.renderPose().bakeIntoMatrix(new Matrix4d());
                final Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
                CreateNautilus.LOGGER.info(
                        "[water-entry] debug: bounds=({},{},{})-({},{},{}) "
                                + "poseT=({},{},{}) sampleLocal=({},{},{}) "
                                + "sampleWorldCamRel=({},{},{}) camera=({},{},{}) "
                                + "sampleWorldAbs=({},{},{}) fluid={}",
                        bounds.minX, bounds.minY, bounds.minZ,
                        bounds.maxX, bounds.maxY, bounds.maxZ,
                        pose.m30(), pose.m31(), pose.m32(),
                        sampleLocal.x, sampleLocal.y, sampleLocal.z,
                        sampleWorldCamRel.x, sampleWorldCamRel.y, sampleWorldCamRel.z,
                        cameraPos.x, cameraPos.y, cameraPos.z,
                        sampleWorldCamRel.x + cameraPos.x,
                        sampleWorldCamRel.y + cameraPos.y,
                        sampleWorldCamRel.z + cameraPos.z,
                        minecraft.level.getFluidState(BlockPos.containing(
                                sampleWorldCamRel.x + cameraPos.x,
                                sampleWorldCamRel.y + cameraPos.y,
                                sampleWorldCamRel.z + cameraPos.z)));
                return;
            }
        }
    }

    /** Binds the depth mask to the reserved unit used by the injected composite sampler. */
    public static void bindForComposite() {
        if (depthTexture < 0) {
            return;
        }
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + TEXTURE_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTexture);
        GL13C.glActiveTexture(previousActive);
    }

    /**
     * Draws the cached glass faces of one carrier. The face list is built in vehicle-local
     * space once per topology revision and only transformed per frame, so scanning the plot
     * volume (which only happens on seal changes or the periodic safety-net rebuild) never
     * costs anything while the carrier moves or rotates.
     */
    private static void addGlassFaces(final List<Float> positions,
                                      final UUID carrierId,
                                      final ClientSubLevel carrier,
                                      final Vec3 camera,
                                      final Set<Long> interiorCells,
                                      final long revision,
                                      final List<AlignSample> samples,
                                      final Matrix4f modelView,
                                      final Matrix4f projection) {
        final FaceCacheKey key = new FaceCacheKey(carrierId, revision);
        final long now = System.nanoTime();
        final FaceCacheEntry cached = FACE_CACHE.get(key);
        /*
         * Topology revisions only bump when the seal result changes; adding/removing a glass
         * block inside the hull does not. Rebuild periodically as a safety net so the mask
         * never stays stale while the player edits the vehicle.
         */
        final boolean stale = cached != null
                && now - cached.rebuiltNanos() > FACE_REBUILD_NANOS;
        if (cached == null || stale) {
            final float[] rebuilt = buildCarrierGlassFaces(carrier, interiorCells);
            final long logNow = System.nanoTime();
            if (logNow - lastFaceLogNanos > 5_000_000_000L) {
                lastFaceLogNanos = logNow;
                CreateNautilus.LOGGER.info(
                        "[water-entry] glass mask carrier={} revision={} faces={} cells={}",
                        carrierId, revision, rebuilt.length / 12, interiorCells.size());
            }
            FACE_CACHE.put(key, new FaceCacheEntry(rebuilt, now));
            if (FACE_CACHE.size() > FACE_CACHE_LIMIT) {
                FACE_CACHE.clear();
                FACE_CACHE.put(key, new FaceCacheEntry(rebuilt, now));
            }
            emitTransformed(positions, rebuilt, carrier, camera);
            collectAlignSamples(samples, rebuilt, carrier, camera, modelView, projection);
            return;
        }
        emitTransformed(positions, cached.faces(), carrier, camera);
        collectAlignSamples(samples, cached.faces(), carrier, camera, modelView, projection);
    }

    /**
     * Projects up to three real glass faces onto the mask framebuffer and remembers their
     * screen positions. {@link #logMaskReadback} then reads the mask depth exactly at those
     * pixels: a value below 1.0 proves the mask is written at the glass's own screen position,
     * while 1.0 means the mask is offset from the geometry it should cover.
     */
    private static void collectAlignSamples(final List<AlignSample> samples,
                                            final float[] local,
                                            final ClientSubLevel carrier,
                                            final Vec3 camera,
                                            final Matrix4f modelView,
                                            final Matrix4f projection) {
        if (samples.size() >= 3 || local.length < 12) {
            return;
        }
        final Vector3d center = new Vector3d();
        for (int corner = 0; corner < 4; corner++) {
            final int offset = corner * 3;
            center.add(carrier.renderPose().transformPosition(
                    new Vector3d(local[offset], local[offset + 1], local[offset + 2])));
        }
        center.mul(0.25D);
        final Vector3d cameraRelative = new Vector3d(
                center.x - camera.x, center.y - camera.y, center.z - camera.z);
        final float distance = (float) cameraRelative.length();
        final Vector4f clip = new Vector4f(
                (float) cameraRelative.x, (float) cameraRelative.y,
                (float) cameraRelative.z, 1.0F);
        modelView.transform(clip);
        projection.transform(clip);
        if (clip.w() <= 0.0F) {
            return;
        }
        final float ndcX = clip.x() / clip.w();
        final float ndcY = clip.y() / clip.w();
        final float screenX = (ndcX * 0.5F + 0.5F) * width;
        final float screenY = (0.5F - ndcY * 0.5F) * height;
        if (screenX < 0.0F || screenX >= width || screenY < 0.0F || screenY >= height) {
            return;
        }
        samples.add(new AlignSample(
                "sample" + samples.size(), screenX, screenY, distance));
    }

    private static void emitTransformed(final List<Float> positions,
                                        final float[] local,
                                        final ClientSubLevel carrier,
                                        final Vec3 camera) {
        for (int face = 0; face < local.length; face += 12) {
            final Vector3d[] world = new Vector3d[4];
            for (int corner = 0; corner < 4; corner++) {
                final int offset = face + corner * 3;
                world[corner] = carrier.renderPose().transformPosition(
                        new Vector3d(local[offset], local[offset + 1], local[offset + 2]));
            }
            emitTriangle(positions, world[0], world[1], world[2], camera);
            emitTriangle(positions, world[0], world[2], world[3], camera);
        }
    }

    /**
     * Scans the carrier's whole plot (the sealed hull walls are part of the plot, while room
     * bounds only cover interior air cells) for glass-like blocks and emits, in local space,
     * every block face whose outward neighbour is not an interior cell of any compartment.
     * Interior partitions and faces looking into other (or flooded) rooms are therefore
     * excluded: the mask only contains actual hull glass. Faces embedded inside opaque walls
     * are harmless: the composite's {@code entry < front} test rejects pixels where an opaque
     * wall is closer than the glass.
     */
    private static float[] buildCarrierGlassFaces(final ClientSubLevel carrier,
                                                  final Set<Long> interiorCells) {
        final var plotBounds = carrier.getPlot().getBoundingBox();
        if (plotBounds == null) {
            return new float[0];
        }
        final int minX = plotBounds.minX() - 1;
        final int minY = plotBounds.minY() - 1;
        final int minZ = plotBounds.minZ() - 1;
        final int maxX = plotBounds.maxX() + 1;
        final int maxY = plotBounds.maxY() + 1;
        final int maxZ = plotBounds.maxZ() + 1;
        final List<Float> out = new ArrayList<>();
        final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (final BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            final BlockState state = carrier.getLevel().getBlockState(pos);
            if (!isGlassLike(state)) {
                continue;
            }
            for (final Direction direction : Direction.values()) {
                neighbor.setWithOffset(pos, direction);
                if (interiorCells.contains(neighbor.asLong())) {
                    continue;
                }
                emitLocalFace(out, pos, direction);
            }
        }
        final float[] array = new float[out.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = out.get(i);
        }
        return array;
    }

    /**
     * A face belongs to the mask when it is a transparent solid that Sable actually renders in
     * the translucent layer (Create glass and every vanilla GlassBlock subclass are transparent
     * solids; ice/slime/honey resolve through the model render types). Plain vanilla glass is
     * a cutout solid: it is still emitted here (its silhouette is real glass geometry) and the
     * composite depth test rejects it because cutout glass writes depth.
     */
    private static boolean isGlassLike(final BlockState state) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        if (state.getBlock() instanceof TransparentBlock) {
            return true;
        }
        final BakedModel model = Minecraft.getInstance().getBlockRenderer()
                .getBlockModel(state);
        return model.getRenderTypes(state, RandomSource.create(), ModelData.EMPTY)
                .contains(RenderType.translucent());
    }

    /** Emits the four corners of one block face in vehicle-local coordinates. */
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

    private static void addLocalVertex(final List<Float> out,
                                       final int axis,
                                       final double plane,
                                       final double minA,
                                       final double minB,
                                       final int da,
                                       final int db) {
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

    private static void emitTriangle(final List<Float> positions,
                                     final Vector3d first,
                                     final Vector3d second,
                                     final Vector3d third,
                                     final Vec3 camera) {
        positions.add((float) (first.x - camera.x));
        positions.add((float) (first.y - camera.y));
        positions.add((float) (first.z - camera.z));
        positions.add((float) (second.x - camera.x));
        positions.add((float) (second.y - camera.y));
        positions.add((float) (second.z - camera.z));
        positions.add((float) (third.x - camera.x));
        positions.add((float) (third.y - camera.y));
        positions.add((float) (third.z - camera.z));
    }

    private static boolean ensureFbo(final int targetWidth, final int targetHeight) {
        if (fbo >= 0 && width == targetWidth && height == targetHeight) {
            return true;
        }
        destroy();
        depthTexture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTexture);
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
        fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11C.GL_TEXTURE_2D, depthTexture, 0);
        GL20C.glDrawBuffer(GL11C.GL_NONE);
        GL20C.glReadBuffer(GL11C.GL_NONE);
        final int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            destroy();
            return false;
        }
        width = targetWidth;
        height = targetHeight;
        return true;
    }

    public static void destroy() {
        FACE_CACHE.clear();
        if (fbo >= 0) {
            GL30C.glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (depthTexture >= 0) {
            GL11C.glDeleteTextures(depthTexture);
            depthTexture = -1;
        }
        width = -1;
        height = -1;
    }
}
