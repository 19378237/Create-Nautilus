package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.physics.CompartmentVoxelSemantics;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact compartment occupancy for Iris water programs, flattened into one ordinary 2D texture.
 *
 * <p>The texture contains a small descriptor/room prefix, sparse 8-cubed brick page tables and
 * one texel per occupied voxel.  It deliberately uses {@code GL_TEXTURE_2D + RGBA32F}; the 3D
 * texture allocation path is unstable on the target NVIDIA driver.  All integer-like values are
 * kept below 2^24 so float32 stores them exactly.</p>
 */
public final class ShaderVoxelMaskAtlas {
    private static final int HEADER_TEXELS =
            SodiumOcclusionShaderSource.VOXEL_ATLAS_HEADER_TEXELS;
    private static final int DESCRIPTOR_TEXELS =
            SodiumOcclusionShaderSource.VOXEL_CARRIER_DESCRIPTOR_TEXELS;
    private static final int BRICK = SodiumOcclusionShaderSource.VOXEL_BRICK_SIZE;
    private static final int BRICK_TEXELS = SodiumOcclusionShaderSource.VOXEL_BRICK_TEXELS;
    /** Negative/positive medium planes plus axis/valid metadata for one optical event. */
    private static final int OPTICAL_EVENT_TEXELS = 3;
    /** Full optical occupancy without a fog event; exact float, outside the <= -1.5 slot range. */
    static final float EVENTLESS_OPTICAL_VOLUME = -1.25F;
    private static final int MAX_EXACT_TEXELS =
            SodiumOcclusionShaderSource.MAX_EXACT_FLOAT_TEXELS - 1;
    private static final long MAX_ATLAS_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_CARRIER_ATLAS_BYTES = 32L * 1024L * 1024L;
    private static final long BYTES_PER_TEXEL = 4L * Float.BYTES;
    private static final long MAX_CARRIER_TEXELS =
            MAX_CARRIER_ATLAS_BYTES / BYTES_PER_TEXEL;
    private static final long MAX_GLOBAL_ATLAS_TEXELS = Math.min(
            MAX_EXACT_TEXELS - 1L, MAX_ATLAS_BYTES / BYTES_PER_TEXEL);
    /** Bounds topology work even when many overlapping room packets repeat the same voxel. */
    private static final long MAX_CARRIER_SEMANTIC_VISITS = MAX_CARRIER_TEXELS * 2L;
    private static final double FULL_EPSILON = 1.0E-6D;
    /**
     * The three recognized Iris water adapters displace vertices inside a much smaller envelope.
     * Expanding by a complete atlas brick keeps the screen gate conservative without introducing
     * a pack-specific uniform. Unknown vertex layouts never publish the ready uniform and thus
     * fail open before this gate is consumed.
     */
    private static final float SCREEN_RECT_LOCAL_MARGIN = BRICK;
    /** Covers sub-pixel TAA offsets which are applied after the supplied terrain projection. */
    private static final float SCREEN_RECT_PIXEL_MARGIN = 4.0F;
    /** Outward quantisation prevents sub-pixel TAA from changing the descriptor every frame. */
    private static final float SCREEN_RECT_PIXEL_QUANTUM = 16.0F;
    private static final float SCREEN_RECT_CLIP_EPSILON = 1.0E-4F;
    private static final float SCREEN_RECT_UNBOUNDED = 1.0E20F;
    /**
     * Some NVIDIA upload paths copy a cache-line sized tail past the logical client range. Keep
     * that tail inside GL-owned PBO storage instead of exposing a page-ended Java direct buffer.
     */
    private static final int PIXEL_UNPACK_GUARD_BYTES = 4096;
    private static int texture = -1;
    private static int pixelUnpackBuffer = -1;
    private static int width;
    private static int height;
    private static AtlasLayout layout;
    private static long topologyKey = Long.MIN_VALUE;
    private static long voxelStateKey = Long.MIN_VALUE;
    private static BoundTexture activeBinding;
    private static volatile FrameSnapshot preparedFrame;
    private static PreparedOpticalFrame preparedOpticalFrame;
    private static CompositeAtlasView publishedOpticalView;
    /** One early fog snapshot which the later water pass must consume instead of replacing. */
    private static long earlyFogPreparedToken = Long.MIN_VALUE;
    private static FrameSnapshot pausedFrame;
    private static float[] uploadedPrefix;
    private static volatile Set<Long> watchedCellKeys = Set.of();
    /**
     * Plot-coordinate bounds are required in addition to level identity: Sable stores plot blocks
     * in the parent {@link ClientLevel}, so an ordinary parent-world edit has the same level
     * identity as every carrier.  Keeping one conservative bound per input carrier prevents an
     * unrelated world edit from waking a skipped/over-budget carrier and forcing a full rebuild.
     */
    private static volatile List<WatchedPlotBounds> watchedPlotBounds = List.of();
    /** Render-thread residency with a one-carrier-range hysteresis band. */
    private static final Set<UUID> RESIDENT_CARRIERS = new HashSet<>();
    private static boolean capacityWarningLogged;
    private static final Set<UUID> CARRIER_CAPACITY_WARNINGS =
            ConcurrentHashMap.newKeySet();
    /**
     * The exact surface solve is O(room voxels * bisection steps).  Its result is independent of
     * camera position and carrier translation; only the carrier's world-Y basis, topology and
     * water fraction can change the surface relative to the carrier translation.  Cache precisely
     * those inputs so a stationary/translated/yawing carrier performs no full-cell solve on steady
     * render frames (the previous behaviour was a major 1% LOW spike).
     */
    private static final Map<RoomSurfaceCacheKey, RoomSurfaceCacheValue> ROOM_SURFACE_CACHE =
            new HashMap<>();
    private static boolean uploadWarningLogged;
    private static boolean layoutBuildWarningLogged;
    private static long lastSummaryNanos;
    /** Cross-thread semantic-layout invalidation, consumed atomically by the render thread. */
    private static final AtomicBoolean OPTICAL_LAYOUT_REBUILD_REQUESTED = new AtomicBoolean();
    /** Plot-space candidate cells dirtied by committed block/fluid events. */
    private static final ConcurrentLinkedQueue<Long> dirtyCellKeys =
            new ConcurrentLinkedQueue<>();

    private ShaderVoxelMaskAtlas() {
    }

    /** Runs on the render thread at the parent-world translucent-pass boundary. */
    public static void update(final double cameraX, final double cameraY,
                              final double cameraZ) {
        if (retainEarlyFogFrameForWater(cameraX, cameraY, cameraZ)) return;
        preparedFrame = null;
        preparedOpticalFrame = null;
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return;
        }
        if (OPTICAL_LAYOUT_REBUILD_REQUESTED.getAndSet(false)) {
            /*
             * Do not destroy the current GL texture here.  Invalidating the CPU generation makes
             * the next block below build and upload a complete candidate texture first; install()
             * swaps the texture name only after that upload succeeds.  During a failed build the
             * old object remains allocated but unpublished, which is the required fail-open
             * behaviour and avoids the old post-program activation hitch.
             */
            publishedOpticalView = null;
            pausedFrame = null;
            uploadedPrefix = null;
            topologyKey = Long.MIN_VALUE;
            voxelStateKey = Long.MIN_VALUE;
        }
        // Do not consume committed block/fluid events until a live plot container exists.
        // Dimension changes and first-world startup can expose a level for a frame before its
        // container; draining earlier would permanently lose the invalidation while retaining
        // an atlas built from the old block state.
        final Set<Long> dirtyCells = drainDirtyCells();
        final Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> snapshots =
                ClientCompartmentVisualCache.shaderAtlasSnapshots();
        final List<CarrierInput> inputs = collectInputs(
                container, snapshots, cameraX, cameraY, cameraZ);
        watchedPlotBounds = watchedPlotBounds(inputs);
        final long topologyOnlyKey = topologyKey(inputs);
        final long now = System.nanoTime();
        // The expensive full plot-state fingerprint belongs only to a new atomic generation.
        // Committed block/fluid events use the local brick path below; no timed scan runs here.
        if (voxelStateKey == Long.MIN_VALUE || topologyKey == Long.MIN_VALUE
                || topologyOnlyKey != (topologyKey ^ voxelStateKey)) {
            voxelStateKey = voxelStateKey(inputs);
        }
        long nextTopologyKey = topologyOnlyKey ^ voxelStateKey;
        if (inputs.isEmpty()) {
            releaseOnRenderThread();
            topologyKey = nextTopologyKey;
            return;
        }
        // A topology which produced no packable carrier is a stable fail-open result. Do not
        // rescan and rematerialise it every water pass; an exact plot block/fluid event or a new
        // topology key below is the only reason to retry.
        if (layout == null && topologyKey == nextTopologyKey && dirtyCells.isEmpty()) {
            return;
        }
        if (layout == null || topologyKey != nextTopologyKey) {
            final AtlasLayout replacement = buildLayoutSafely(inputs);
            if (replacement == null) {
                layout = null;
                watchedCellKeys = Set.of();
                topologyKey = nextTopologyKey;
                return;
            }
            if (!install(replacement)) {
                // A stale atlas must never classify a new topology. Keep its GL object for a
                // possible later retry, but publish no descriptors until replacement succeeds.
                layout = null;
                watchedCellKeys = Set.of();
                topologyKey = Long.MIN_VALUE;
                return;
            }
            layout = replacement;
            ROOM_SURFACE_CACHE.clear();
            topologyKey = nextTopologyKey;
            watchedCellKeys = replacement.watchedCellKeys();
            capacityWarningLogged = false;
            layoutBuildWarningLogged = false;
        } else if (!dirtyCells.isEmpty()) {
            final LocalUpdateResult result;
            try {
                result = updateDirtyBricks(layout, inputs, dirtyCells);
            } catch (final RuntimeException | LinkageError exception) {
                if (!uploadWarningLogged) {
                    uploadWarningLogged = true;
                    CreateNautilus.LOGGER.warn(
                            "[occlusion] exact voxel atlas local rebuild failed; "
                                    + "water is left intact",
                            exception);
                }
                disableStaleLayoutForRetry();
                return;
            }
            if (result == LocalUpdateResult.REBUILD_REQUIRED) {
                // A newly non-empty brick needs a new page-directory entry. Preserve the old
                // texture until the complete replacement has uploaded successfully.
                voxelStateKey = voxelStateKey(inputs);
                nextTopologyKey = topologyOnlyKey ^ voxelStateKey;
                final AtlasLayout replacement = buildLayoutSafely(inputs);
                if (replacement == null) {
                    layout = null;
                    watchedCellKeys = Set.of();
                    topologyKey = nextTopologyKey;
                    return;
                }
                if (!install(replacement)) {
                    disableStaleLayoutForRetry();
                    return;
                }
                layout = replacement;
                topologyKey = nextTopologyKey;
                watchedCellKeys = replacement.watchedCellKeys();
                capacityWarningLogged = false;
                layoutBuildWarningLogged = false;
            } else if (result == LocalUpdateResult.FAILED) {
                // A sub-image upload may have changed only part of the old texture. Publish no
                // descriptors until a complete atomic replacement succeeds on a later frame.
                disableStaleLayoutForRetry();
                return;
            }
        }
        final FrameSnapshot frozen = pausedFrame;
        if (minecraft.isPaused() && compatibleFrame(frozen, layout, cameraX, cameraY, cameraZ)) {
            preparedFrame = frozen;
        } else {
            preparedFrame = captureFrame(layout, container, snapshots,
                    cameraX, cameraY, cameraZ);
            pausedFrame = minecraft.isPaused() ? preparedFrame : null;
        }
        if (layout != null && now - lastSummaryNanos > 5_000_000_000L) {
            lastSummaryNanos = now;
            CreateNautilus.LOGGER.info(
                    "[occlusion] exact voxel atlas ready carriers={} rooms={} bricks={} "
                            + "interiorCells={} shellCells={} eventlessOpticalCells={} texture={}x{}",
                    layout.carriers().size(), layout.roomCount(), layout.brickCount(),
                    layout.interiorCellCount(), layout.shellCellCount(),
                    layout.eventlessOpticalCellCount(), width, height);
        }
    }

    /** Starts a new early-fog snapshot; a previous frame's retention marker is never reusable. */
    static void updateForEarlyFog(final double cameraX, final double cameraY,
                                  final double cameraZ) {
        earlyFogPreparedToken = Long.MIN_VALUE;
        update(cameraX, cameraY, cameraZ);
    }

    /** Ends any unconsumed previous-frame retention without touching atlas or snapshot data. */
    static void clearEarlyFogRetention() {
        earlyFogPreparedToken = Long.MIN_VALUE;
    }

    private static boolean retainEarlyFogFrameForWater(
            final double cameraX, final double cameraY, final double cameraZ) {
        final FrameSnapshot frame = preparedFrame;
        final PreparedOpticalFrame optical = preparedOpticalFrame;
        if (earlyFogPreparedToken == Long.MIN_VALUE || frame == null || optical == null
                || optical.frameToken() != earlyFogPreparedToken || frame.layout() != layout
                || !sameDouble(frame.cameraX(), cameraX)
                || !sameDouble(frame.cameraY(), cameraY)
                || !sameDouble(frame.cameraZ(), cameraZ)) {
            return false;
        }
        // Exactly one ordinary update follows the early pass. Keeping both snapshots makes its
        // Sodium upload rewrite only the dynamic prefix/screen rectangle, never the layout or
        // texture name already consumed by deferred fog.
        earlyFogPreparedToken = Long.MIN_VALUE;
        return true;
    }

    /**
     * Updates camera-relative descriptors and room surfaces, binds the texture, and uploads the
     * three public atlas uniforms. The caller owns the selected texture unit.
     */
    public static boolean upload(final int program, final int textureUnit,
                                 final double cameraX, final double cameraY,
                                 final double cameraZ,
                                 final Matrix4f modelView, final Matrix4f projection,
                                 final int viewportX, final int viewportY,
                                 final int viewportWidth, final int viewportHeight,
                                 final boolean waveLayoutKnown) {
        restoreBinding();
        final AtlasLayout current = layout;
        final FrameSnapshot frame = preparedFrame;
        preparedFrame = null;
        final PreparedOpticalFrame opticalPrepared = preparedOpticalFrame;
        preparedOpticalFrame = null;
        publishedOpticalView = null;
        if (current == null || texture < 0 || current.carriers().isEmpty()
                || frame == null || frame.layout() != current
                || !sameDouble(frame.cameraX(), cameraX)
                || !sameDouble(frame.cameraY(), cameraY)
                || !sameDouble(frame.cameraZ(), cameraZ)) {
            return false;
        }
        final EncodedPrefix encoded = encodePrefix(current, frame, opticalPrepared,
                modelView, projection, viewportX, viewportY,
                viewportWidth, viewportHeight, waveLayoutKnown);
        if (encoded == null) return false;

        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + textureUnit);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int previousSampler = GL.getCapabilities().OpenGL33
                ? GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, textureUnit) : -1;
        try {
            clearGlErrors();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            if (previousSampler >= 0) {
                // Texture-object NEAREST parameters are ignored while a sampler object is bound.
                GL33C.glBindSampler(textureUnit, 0);
            }
            uploadPrefixIfChanged(encoded.data(), current.prefixTexels());
            setUniform1i(program, SodiumOcclusionShaderSource.VOXEL_ATLAS_UNIFORM, textureUnit);
            setUniform2f(program, SodiumOcclusionShaderSource.VOXEL_ATLAS_SIZE_UNIFORM,
                    width, height);
            setUniform1i(program, SodiumOcclusionShaderSource.VOXEL_CARRIER_COUNT_UNIFORM,
                    current.carriers().size());
            requireNoGlError("frame upload");
            publishedOpticalView = encoded.opticalView();
            activeBinding = new BoundTexture(textureUnit, previousActive, previousTexture,
                    previousSampler);
            GL13C.glActiveTexture(previousActive);
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            if (previousSampler >= 0) {
                GL33C.glBindSampler(textureUnit, previousSampler);
            }
            GL13C.glActiveTexture(previousActive);
            logUploadFailure(exception);
            publishedOpticalView = null;
            return false;
        }
    }

    /**
     * Publishes the current optical-event prefix before Iris' deferred programs run. Unlike
     * {@link #upload}, this does not consume either prepared snapshot, bind a Sodium sampler, or
     * retain any texture-unit state. The later parent-water draw therefore follows its unchanged
     * upload and clipping path using the same atlas object.
     */
    static boolean publishOpticalForEarlyFog(final double cameraX,
                                             final double cameraY,
                                             final double cameraZ,
                                             final Matrix4f modelView,
                                             final Matrix4f projection,
                                             final int viewportX,
                                             final int viewportY,
                                             final int viewportWidth,
                                             final int viewportHeight) {
        restoreBinding();
        publishedOpticalView = null;
        final AtlasLayout current = layout;
        final FrameSnapshot frame = preparedFrame;
        final PreparedOpticalFrame opticalPrepared = preparedOpticalFrame;
        if (current == null || texture < 0 || current.carriers().isEmpty()
                || frame == null || frame.layout() != current
                || !sameDouble(frame.cameraX(), cameraX)
                || !sameDouble(frame.cameraY(), cameraY)
                || !sameDouble(frame.cameraZ(), cameraZ)) {
            return false;
        }
        // No water program has run yet, so its pack-specific wave varying is unavailable. A full
        // viewport descriptor is the existing conservative fallback and does not alter occupancy.
        final EncodedPrefix encoded = encodePrefix(current, frame, opticalPrepared,
                modelView, projection, viewportX, viewportY,
                viewportWidth, viewportHeight, false);
        if (encoded == null || encoded.opticalView() == null) return false;

        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            clearGlErrors();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            uploadPrefixIfChanged(encoded.data(), current.prefixTexels());
            requireNoGlError("early optical frame upload");
            publishedOpticalView = encoded.opticalView();
            earlyFogPreparedToken = encoded.opticalView().frameToken();
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            logUploadFailure(exception);
            publishedOpticalView = null;
            return false;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL13C.glActiveTexture(previousActive);
        }
    }

    private static EncodedPrefix encodePrefix(
            final AtlasLayout current,
            final FrameSnapshot frame,
            final PreparedOpticalFrame opticalPrepared,
            final Matrix4f modelView,
            final Matrix4f projection,
            final int viewportX,
            final int viewportY,
            final int viewportWidth,
            final int viewportHeight,
            final boolean waveLayoutKnown) {
        final float[] prefixData = new float[current.prefixTexels() * 4];
        final FloatBuffer prefix = FloatBuffer.wrap(prefixData);
        put(prefix, current.carriers().size(), HEADER_TEXELS, width, height);
        final Map<UUID, ApertureEventCache.CarrierFrame> opticalById = new HashMap<>();
        final boolean opticalFrameCoherent = opticalPrepared != null
                && opticalPrepared.frameToken() > 0L
                && opticalPrepared.frame().level() == Minecraft.getInstance().level;
        final Set<UUID> ambiguousOpticalIds = new HashSet<>();
        if (opticalFrameCoherent) {
            for (final ApertureEventCache.CarrierFrame carrier
                    : opticalPrepared.frame().carriers()) {
                if (ambiguousOpticalIds.contains(carrier.id())) continue;
                if (opticalById.put(carrier.id(), carrier) != null) {
                    // Duplicate carrier ids make only that carrier ambiguous. Removing it keeps
                    // every unrelated carrier usable without letting an arbitrary duplicate win.
                    opticalById.remove(carrier.id());
                    ambiguousOpticalIds.add(carrier.id());
                }
            }
        }
        final Map<UUID, CompositeCarrierView> opticalCarriers = new HashMap<>();
        int roomCount = 0;
        for (final CarrierFrame carrier : frame.carriers()) {
            final Matrix4f transform = carrier.transform();
            // JOML stores matrices column-major, exactly matching GLSL mat4(d0,d1,d2,d3).
            put(prefix, transform.m00(), transform.m01(), transform.m02(), transform.m03());
            put(prefix, transform.m10(), transform.m11(), transform.m12(), transform.m13());
            put(prefix, transform.m20(), transform.m21(), transform.m22(), transform.m23());
            put(prefix, transform.m30(), transform.m31(), transform.m32(), transform.m33());
            // The transform already subtracts the brick-aligned atlas origin. d4.xy remain the
            // relative origin; d4.z carries the missing Z coarse bound. d6 is reserved for the
            // conservative screen rectangle used before any matrix or page-directory lookup.
            put(prefix, 0.0F, 0.0F, carrier.layout().brickDimZ(),
                    carrier.layout().pageBase());
            put(prefix, carrier.layout().brickDimX(), carrier.layout().brickDimY(),
                    carrier.layout().directoryCount(), carrier.layout().roomBase());
            putConservativeScreenRect(prefix, carrier, modelView, projection,
                    viewportX, viewportY, viewportWidth, viewportHeight, waveLayoutKnown);
            put(prefix, carrier.layout().eventBase(), carrier.layout().eventCount(),
                    generationFloat(carrier.layout().revision()), 0.0F);
        }
        for (final CarrierFrame carrier : frame.carriers()) {
            for (final RoomFrame room : carrier.rooms()) {
                put(prefix, room.surfaceY(), room.clipEnabled(), room.generation(), 0.0F);
                roomCount++;
            }
        }
        for (final CarrierFrame carrier : frame.carriers()) {
            final CarrierLayout carrierLayout = carrier.layout();
            final ApertureEventCache.CarrierFrame optical = opticalById.get(carrierLayout.id());
            final boolean opticalCarrierValid = optical != null
                    && optical.topologyRevision() == carrierLayout.revision()
                    && optical.geometryGeneration()
                    == carrierLayout.opticalGeometryGeneration()
                    && optical.events().size() == carrierLayout.eventCount();
            if (opticalCarrierValid) {
                // A carrier-level topology/geometry mismatch must not make another carrier's
                // windows disappear. Only aligned carriers are drawable by the D24S8 gate pass;
                // mismatched descriptors remain available to ordinary water clipping and their
                // optical event texels are written invalid below.
                opticalCarriers.put(carrierLayout.id(), new CompositeCarrierView(
                        carrierLayout.eventBase(), carrierLayout.voxelOriginX(),
                        carrierLayout.voxelOriginY(), carrierLayout.voxelOriginZ(),
                        carrierLayout.revision()));
            }
            final int originDeltaX = opticalCarrierValid
                    ? carrierLayout.voxelOriginX() - optical.voxelOriginX() : 0;
            final int originDeltaY = opticalCarrierValid
                    ? carrierLayout.voxelOriginY() - optical.voxelOriginY() : 0;
            final int originDeltaZ = opticalCarrierValid
                    ? carrierLayout.voxelOriginZ() - optical.voxelOriginZ() : 0;
            for (int slot = 0; slot < carrierLayout.eventCount(); slot++) {
                final ApertureEventCache.Event event = optical != null
                        && slot < optical.events().size() ? optical.events().get(slot) : null;
                final boolean valid = opticalCarrierValid && event != null
                        && event.valid() && event.slot() == slot;
                // Invalid/unsupported medium semantics are a per-slot fail-open. Preserve the
                // slot and geometry alignment, but publish invalid metadata so both the private
                // gate shader and the Photon resolver reject only this window.
                putOpticalEvent(prefix, event, valid, carrierLayout.revision(),
                        originDeltaX, originDeltaY, originDeltaZ);
            }
        }
        if (roomCount != current.roomCount() || prefix.position() != current.prefixTexels() * 4) {
            return null;
        }
        final CompositeAtlasView opticalView = opticalFrameCoherent
                ? new CompositeAtlasView(texture, width, height,
                current.carriers().size(), opticalPrepared.frameToken(), opticalCarriers)
                : null;
        return new EncodedPrefix(prefixData, opticalView);
    }

    private static void uploadPrefixIfChanged(final float[] prefixData,
                                              final int prefixTexels) {
        if (Arrays.equals(prefixData, uploadedPrefix)) return;
        uploadLinearPrefix(prefixData, prefixTexels);
        uploadedPrefix = prefixData;
    }

    private static void logUploadFailure(final Throwable exception) {
        if (uploadWarningLogged) return;
        uploadWarningLogged = true;
        CreateNautilus.LOGGER.warn(
                "[occlusion] exact voxel atlas frame upload failed; water is left intact",
                exception);
    }

    /** Restores the texture/sampler state captured by {@link #upload}. */
    public static void restoreBinding() {
        final BoundTexture binding = activeBinding;
        activeBinding = null;
        if (binding == null) {
            return;
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + binding.unit());
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding.previousTexture());
        if (binding.previousSampler() >= 0) {
            GL33C.glBindSampler(binding.unit(), binding.previousSampler());
        }
        GL13C.glActiveTexture(binding.previousActive());
    }

    /** Render-thread resource release, used on level unload/logout. */
    public static void release() {
        if (RenderSystem.isOnRenderThread()) {
            restoreBinding();
        } else {
            activeBinding = null;
        }
        final int doomed = texture;
        final int doomedPixelUnpackBuffer = pixelUnpackBuffer;
        texture = -1;
        pixelUnpackBuffer = -1;
        width = 0;
        height = 0;
        layout = null;
        preparedFrame = null;
        pausedFrame = null;
        preparedOpticalFrame = null;
        publishedOpticalView = null;
        earlyFogPreparedToken = Long.MIN_VALUE;
        uploadedPrefix = null;
        watchedCellKeys = Set.of();
        watchedPlotBounds = List.of();
        RESIDENT_CARRIERS.clear();
        ROOM_SURFACE_CACHE.clear();
        CARRIER_CAPACITY_WARNINGS.clear();
        layoutBuildWarningLogged = false;
        topologyKey = Long.MIN_VALUE;
        voxelStateKey = Long.MIN_VALUE;
        OPTICAL_LAYOUT_REBUILD_REQUESTED.set(false);
        dirtyCellKeys.clear();
        if (doomed >= 0) {
            if (RenderSystem.isOnRenderThread()) {
                GL11C.glDeleteTextures(doomed);
            } else {
                RenderSystem.recordRenderCall(() -> GL11C.glDeleteTextures(doomed));
            }
        }
        if (doomedPixelUnpackBuffer >= 0) {
            if (RenderSystem.isOnRenderThread()) {
                GL15C.glDeleteBuffers(doomedPixelUnpackBuffer);
            } else {
                RenderSystem.recordRenderCall(
                        () -> GL15C.glDeleteBuffers(doomedPixelUnpackBuffer));
            }
        }
    }

    /**
     * Requests an atomic rebuild of optical event slots and shell encodings.
     *
     * <p>This method is safe from resource/shader callback threads and deliberately performs no
     * GL work.  The render thread keeps the old texture object alive while building a complete
     * replacement and swaps it only after a successful upload.</p>
     */
    public static void requestOpticalLayoutRebuild() {
        OPTICAL_LAYOUT_REBUILD_REQUESTED.set(true);
    }

    /** Compatibility alias for older internal callers. */
    public static void requestRebuild() {
        requestOpticalLayoutRebuild();
    }

    /**
     * Invalidates shell/fluid semantics when a committed plot block near a tracked room changes.
     * Neighbour probing also catches connected panes and hull pieces whose shape changed because
     * an immediately adjacent block was edited.
     */
    public static void notifyBlockChanged(final ClientLevel level, final BlockPos position) {
        if (level == null || position == null || !isWatchedPlotCell(level, position)) {
            return;
        }
        // Retain the edited cell and its connected-shape neighbours even when this carrier was
        // empty or over budget in the previous generation; that exact in-range event is what
        // permits a stable fail-open generation to be reconsidered without a periodic full scan.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    final long key = BlockPos.asLong(position.getX() + dx,
                            position.getY() + dy, position.getZ() + dz);
                    dirtyCellKeys.add(key);
                }
            }
        }
    }

    /** Compatibility overload for callers outside the level-aware block-event mixin. */
    public static void notifyBlockChanged(final BlockPos position) {
        notifyBlockChanged(Minecraft.getInstance().level, position);
    }

    /** Drops a prepared per-pass snapshot without touching the persistent GPU atlas. */
    public static void discardPreparedFrame() {
        preparedFrame = null;
    }

    private static void releaseOnRenderThread() {
        restoreBinding();
        final int doomed = texture;
        final int doomedPixelUnpackBuffer = pixelUnpackBuffer;
        texture = -1;
        pixelUnpackBuffer = -1;
        width = 0;
        height = 0;
        layout = null;
        preparedFrame = null;
        pausedFrame = null;
        preparedOpticalFrame = null;
        publishedOpticalView = null;
        earlyFogPreparedToken = Long.MIN_VALUE;
        uploadedPrefix = null;
        watchedCellKeys = Set.of();
        watchedPlotBounds = List.of();
        RESIDENT_CARRIERS.clear();
        ROOM_SURFACE_CACHE.clear();
        layoutBuildWarningLogged = false;
        topologyKey = Long.MIN_VALUE;
        voxelStateKey = Long.MIN_VALUE;
        OPTICAL_LAYOUT_REBUILD_REQUESTED.set(false);
        dirtyCellKeys.clear();
        if (doomed >= 0) {
            GL11C.glDeleteTextures(doomed);
        }
        if (doomedPixelUnpackBuffer >= 0) {
            GL15C.glDeleteBuffers(doomedPixelUnpackBuffer);
        }
    }

    public static boolean isReady() {
        return layout != null && texture >= 0;
    }

    public static int textureWidth() {
        return width;
    }

    public static int textureHeight() {
        return height;
    }

    /** Reuses this pass' authoritative surface solve before the water program consumes it. */
    static PreparedRoomWater preparedRoomWater(final UUID carrierId, final UUID roomId,
                                               final long topologyRevision) {
        final FrameSnapshot frame = preparedFrame;
        if (frame == null || carrierId == null || roomId == null) return null;
        for (final CarrierFrame carrier : frame.carriers()) {
            if (!carrier.layout().id().equals(carrierId)
                    || carrier.layout().revision() != topologyRevision) continue;
            for (final RoomFrame room : carrier.rooms()) {
                if (room.id().equals(roomId) && Float.isFinite(room.surfaceY())) {
                    return new PreparedRoomWater(
                            frame.cameraY() + room.surfaceY(), room.fraction());
                }
            }
            return null;
        }
        return null;
    }

    /** Supplies this exact render frame's dynamic optical planes before the atlas prefix upload. */
    static void prepareOpticalEvents(final ApertureEventCache.Frame frame,
                                     final long frameToken) {
        preparedOpticalFrame = frame == null || frameToken <= 0L
                ? null : new PreparedOpticalFrame(frame, frameToken);
    }

    /**
     * Returns an immutable view only when the water program uploaded optical slots and planes from
     * the exact frame the D24S8 renderer is about to consume.
     */
    static CompositeAtlasView compositeView(final long frameToken) {
        final CompositeAtlasView view = publishedOpticalView;
        return view != null && view.frameToken() == frameToken
                && view.texture() == texture && view.width() == width && view.height() == height
                ? view : null;
    }

    private static List<CarrierInput> collectInputs(
            final SubLevelContainer container,
            final Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> snapshots,
            final double cameraX,
            final double cameraY,
            final double cameraZ) {
        final List<DistanceCarrierInput> candidates = new ArrayList<>();
        final Minecraft minecraft = Minecraft.getInstance();
        final double possibleHitDistance = Math.max(64.0D,
                (minecraft.options.getEffectiveRenderDistance() + 2.0D) * 16.0D);
        final double residentExitDistance = possibleHitDistance + 64.0D;
        for (final Map.Entry<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> entry
                : snapshots.entrySet()) {
            if (!(container.getSubLevel(entry.getKey()) instanceof final ClientSubLevel carrier)
                    || entry.getValue().isEmpty()) {
                continue;
            }
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms =
                    meaningfulRooms(entry.getValue());
            if (rooms.isEmpty()) {
                continue;
            }
            final double distanceSquared = carrierDistanceSquared(
                    carrier, rooms, cameraX, cameraY, cameraZ);
            final double limit = RESIDENT_CARRIERS.contains(entry.getKey())
                    ? residentExitDistance : possibleHitDistance;
            if (Double.isFinite(distanceSquared) && distanceSquared <= limit * limit) {
                candidates.add(new DistanceCarrierInput(
                        new CarrierInput(entry.getKey(), carrier, revisionOf(rooms), rooms),
                        distanceSquared));
            }
        }
        candidates.sort(Comparator.comparingDouble(DistanceCarrierInput::distanceSquared)
                .thenComparing(candidate -> candidate.input().id()));
        RESIDENT_CARRIERS.clear();
        final List<CarrierInput> inputs = new ArrayList<>(candidates.size());
        for (final DistanceCarrierInput candidate : candidates) {
            inputs.add(candidate.input());
            RESIDENT_CARRIERS.add(candidate.input().id());
        }
        return List.copyOf(inputs);
    }

    private static List<WatchedPlotBounds> watchedPlotBounds(
            final List<CarrierInput> inputs) {
        final List<WatchedPlotBounds> bounds = new ArrayList<>(inputs.size());
        for (final CarrierInput input : inputs) {
            final AABB inputBounds = inputWatchBounds(input);
            if (inputBounds != null) {
                bounds.add(new WatchedPlotBounds(input.carrier().getLevel(), inputBounds));
            }
        }
        return List.copyOf(bounds);
    }

    /**
     * Includes the semantic shell, its one-step full-glass occupancy extension, and the one-cell
     * model-neighbour expansion queued by the block listener. This envelope may wake the local brick
     * path, but it can never admit an arbitrary parent-world edit far from every plot room.
     */
    private static AABB inputWatchBounds(final CarrierInput input) {
        if (input == null || input.rooms().isEmpty()) return null;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (final ClientCompartmentVisualCache.RoomSnapshot room : input.rooms()) {
            final AABB roomBounds = room.bounds();
            minX = Math.min(minX, roomBounds.minX);
            minY = Math.min(minY, roomBounds.minY);
            minZ = Math.min(minZ, roomBounds.minZ);
            maxX = Math.max(maxX, roomBounds.maxX);
            maxY = Math.max(maxY, roomBounds.maxY);
            maxZ = Math.max(maxZ, roomBounds.maxZ);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                || !Double.isFinite(maxZ)) {
            return null;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(3.0D);
    }

    private static boolean isWatchedPlotCell(final ClientLevel level,
                                             final BlockPos position) {
        final double x = position.getX() + 0.5D;
        final double y = position.getY() + 0.5D;
        final double z = position.getZ() + 0.5D;
        for (final WatchedPlotBounds watched : watchedPlotBounds) {
            if (watched.level() == level && watched.bounds().contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** Conservative world-AABB distance; no frustum test may remove a possible water hit. */
    private static double carrierDistanceSquared(
            final ClientSubLevel carrier,
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms,
            final double cameraX,
            final double cameraY,
            final double cameraZ) {
        final Pose3d pose = new Pose3d(carrier.renderPose());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            final AABB bounds = room.bounds().inflate(1.0D);
            for (final double x : new double[]{bounds.minX, bounds.maxX}) {
                for (final double y : new double[]{bounds.minY, bounds.maxY}) {
                    for (final double z : new double[]{bounds.minZ, bounds.maxZ}) {
                        final Vector3d world = pose.transformPosition(
                                new Vector3d(x, y, z), new Vector3d());
                        if (!world.isFinite()) {
                            return Double.POSITIVE_INFINITY;
                        }
                        minX = Math.min(minX, world.x);
                        minY = Math.min(minY, world.y);
                        minZ = Math.min(minZ, world.z);
                        maxX = Math.max(maxX, world.x);
                        maxY = Math.max(maxY, world.y);
                        maxZ = Math.max(maxZ, world.z);
                    }
                }
            }
        }
        if (!Double.isFinite(minX)) {
            return Double.POSITIVE_INFINITY;
        }
        final double dx = cameraX < minX ? minX - cameraX
                : cameraX > maxX ? cameraX - maxX : 0.0D;
        final double dy = cameraY < minY ? minY - cameraY
                : cameraY > maxY ? cameraY - maxY : 0.0D;
        final double dz = cameraZ < minZ ? minZ - cameraZ
                : cameraZ > maxZ ? cameraZ - maxZ : 0.0D;
        return dx * dx + dy * dy + dz * dz;
    }

    private static List<ClientCompartmentVisualCache.RoomSnapshot> meaningfulRooms(
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms) {
        if (rooms == null) {
            return List.of();
        }
        return rooms.stream().filter(room -> !room.cells().isEmpty())
                .sorted(java.util.Comparator.comparing(
                        ClientCompartmentVisualCache.RoomSnapshot::id))
                .toList();
    }

    /**
     * Freezes every pose and sampled water level used by one parent-water draw.
     *
     * <p>{@link ClientSubLevel#renderPose()} returns a mutable cached object. Copying it once here
     * prevents the descriptor transform and room surface from observing different partial-tick
     * states when the game is paused or a GUI continues rendering TAA frames.</p>
     */
    private static FrameSnapshot captureFrame(
            final AtlasLayout current, final SubLevelContainer container,
            final Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> snapshots,
            final double cameraX, final double cameraY, final double cameraZ) {
        if (current == null) {
            return null;
        }
        final List<CarrierFrame> carriers = new ArrayList<>(current.carriers().size());
        for (final CarrierLayout carrier : current.carriers()) {
            if (!(container.getSubLevel(carrier.id()) instanceof final ClientSubLevel subLevel)) {
                return null;
            }
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms =
                    meaningfulRooms(snapshots.get(carrier.id()));
            if (rooms.size() != carrier.rooms().size()
                    || revisionOf(rooms) != carrier.revision()) {
                return null;
            }
            final Pose3d pose = new Pose3d(subLevel.renderPose());
            final Matrix4d poseMatrix = pose.bakeIntoMatrix(new Matrix4d());
            if (!poseMatrix.isFinite()) {
                return null;
            }
            final Matrix4f transform = cameraRelativeToAtlasLocal(
                    pose, carrier.voxelOriginX(), carrier.voxelOriginY(),
                    carrier.voxelOriginZ(), cameraX, cameraY, cameraZ);
            if (!finite(transform)) {
                return null;
            }
            final Map<UUID, ClientCompartmentVisualCache.RoomSnapshot> live = new HashMap<>();
            for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
                live.put(room.id(), room);
            }
            final List<RoomFrame> roomFrames = new ArrayList<>(carrier.rooms().size());
            for (final RoomLayout room : carrier.rooms()) {
                final ClientCompartmentVisualCache.RoomSnapshot snapshot = live.get(room.id());
                if (snapshot == null) {
                    return null;
                }
                final double fraction = Math.clamp(snapshot.waterFraction(), 0.0D, 1.0D);
                // Dry rooms only need a conservative lower plane and full rooms disable clipping.
                // Avoid the exact per-cell volume solve for both constant-medium endpoints.
                final double surfaceWorld = fraction >= 1.0D - FULL_EPSILON
                        ? cameraY
                        : cachedRoomSurfaceWorld(carrier.id(), snapshot, pose, poseMatrix,
                        fraction);
                final float surface = (float) (surfaceWorld - cameraY);
                if (!Float.isFinite(surface)) {
                    return null;
                }
                roomFrames.add(new RoomFrame(room.id(), surface, (float) fraction,
                        fraction < 1.0D - FULL_EPSILON ? 1.0F : 0.0F,
                        generationFloat(carrier.revision())));
            }
            carriers.add(new CarrierFrame(carrier, new Matrix4f(transform),
                    List.copyOf(roomFrames)));
        }
        return new FrameSnapshot(current, cameraX, cameraY, cameraZ, List.copyOf(carriers));
    }

    private static double cachedRoomSurfaceWorld(
            final UUID carrierId,
            final ClientCompartmentVisualCache.RoomSnapshot room,
            final Pose3dc pose,
            final Matrix4d poseMatrix,
            final double fraction) {
        final RoomSurfaceCacheKey key = new RoomSurfaceCacheKey(carrierId, room.id());
        final RoomSurfaceCacheValue previous = ROOM_SURFACE_CACHE.get(key);
        final long fractionBits = Double.doubleToLongBits(fraction);
        final long basisXBits = Double.doubleToLongBits(poseMatrix.m01());
        final long basisYBits = Double.doubleToLongBits(poseMatrix.m11());
        final long basisZBits = Double.doubleToLongBits(poseMatrix.m21());
        if (previous != null
                && previous.topologyRevision() == room.topologyRevision()
                && previous.fractionBits() == fractionBits
                && previous.basisXBits() == basisXBits
                && previous.basisYBits() == basisYBits
                && previous.basisZBits() == basisZBits) {
            return poseMatrix.m31() + previous.surfaceRelativeToTranslation();
        }
        final double solved = fraction <= FULL_EPSILON
                ? com.create.nautilus.physics.CompartmentWaterGeometry.surfaceY(
                pose, List.of(), room.bounds(), 0.0D)
                : com.create.nautilus.physics.CompartmentWaterGeometry.surfaceY(
                pose, room.cells(), room.bounds(), fraction);
        if (Double.isFinite(solved)) {
            ROOM_SURFACE_CACHE.put(key, new RoomSurfaceCacheValue(
                    room.topologyRevision(), fractionBits,
                    basisXBits, basisYBits, basisZBits,
                    solved - poseMatrix.m31()));
        } else {
            ROOM_SURFACE_CACHE.remove(key);
        }
        return solved;
    }

    private static boolean compatibleFrame(final FrameSnapshot frame, final AtlasLayout current,
                                           final double cameraX, final double cameraY,
                                           final double cameraZ) {
        return frame != null && frame.layout() == current
                && sameDouble(frame.cameraX(), cameraX)
                && sameDouble(frame.cameraY(), cameraY)
                && sameDouble(frame.cameraZ(), cameraZ);
    }

    private static Set<Long> drainDirtyCells() {
        final Set<Long> dirty = new HashSet<>();
        Long key;
        while ((key = dirtyCellKeys.poll()) != null) {
            dirty.add(key);
        }
        return dirty;
    }

    /** Stops publishing a possibly partially updated texture until an atomic rebuild succeeds. */
    private static void disableStaleLayoutForRetry() {
        layout = null;
        preparedFrame = null;
        pausedFrame = null;
        uploadedPrefix = null;
        topologyKey = Long.MIN_VALUE;
        voxelStateKey = Long.MIN_VALUE;
    }

    private static BrickKey brickKey(final BlockPos cell) {
        return new BrickKey(Math.floorDiv(cell.getX(), BRICK),
                Math.floorDiv(cell.getY(), BRICK), Math.floorDiv(cell.getZ(), BRICK));
    }

    private static SemanticCells semanticCells(final CarrierInput input) {
        final Map<Long, Float> roomOwners = new Long2FloatOpenHashMap();
        for (int roomSlot = 0; roomSlot < input.rooms().size(); roomSlot++) {
            for (final BlockPos cell : input.rooms().get(roomSlot).cells()) {
                // Later room slots retain the existing deterministic overlap precedence.
                roomOwners.put(cell.asLong(), roomSlot + 1.0F);
            }
        }
        final ApertureEventCache.OpticalSlots optical = opticalSlots(input);
        final Set<Long> candidates = atlasCandidates(
                input, roomOwners.keySet(), optical);
        return new SemanticCells(Collections.unmodifiableMap(roomOwners),
                Collections.unmodifiableSet(candidates), optical.voxelSlots(),
                optical.eventCount(), optical.geometryGeneration());
    }

    private static ApertureEventCache.OpticalSlots opticalSlots(final CarrierInput input) {
        return ApertureEventCache.opticalSlots(Minecraft.getInstance().level,
                input.id(), input.carrier(), input.revision(), input.rooms());
    }

    private static LocalUpdateResult updateDirtyBricks(
            final AtlasLayout current, final List<CarrierInput> inputs,
            final Set<Long> dirtyCells) {
        if (current == null || texture < 0 || dirtyCells.isEmpty()) {
            return LocalUpdateResult.NO_CHANGE;
        }
        final Map<UUID, CarrierLayout> packedById = new HashMap<>();
        for (final CarrierLayout carrier : current.carriers()) {
            packedById.put(carrier.id(), carrier);
        }
        final List<BrickPatch> patches = new ArrayList<>();
        int interiorDelta = 0;
        int shellDelta = 0;
        int eventlessOpticalDelta = 0;
        for (final CarrierInput input : inputs) {
            if (!dirtyAffectsInput(input, dirtyCells)) {
                continue;
            }
            final CarrierLayout packed = packedById.get(input.id());
            final Map<Long, Float> roomOwners;
            final Set<Long> candidates;
            final Map<BrickKey, Integer> payloadBases;
            if (packed != null) {
                boolean candidateAffected = false;
                for (final long key : dirtyCells) {
                    if (packed.candidates().contains(key)) {
                        candidateAffected = true;
                        break;
                    }
                }
                final ApertureEventCache.OpticalSlots liveOptical = opticalSlots(input);
                if (liveOptical.geometryGeneration() != packed.opticalGeometryGeneration()
                        || liveOptical.eventCount() != packed.eventCount()
                        || !liveOptical.voxelSlots().equals(packed.opticalSlots())) {
                    return LocalUpdateResult.REBUILD_REQUIRED;
                }
                // A full glass block can enter/leave the extra occupancy ring without creating
                // any optical event. Reconsider membership only on committed nearby block edits.
                if (!atlasCandidates(input, packed.roomOwners().keySet(), liveOptical)
                        .equals(packed.candidates())) {
                    return LocalUpdateResult.REBUILD_REQUIRED;
                }
                if (!candidateAffected) {
                    continue;
                }
                roomOwners = packed.roomOwners();
                candidates = packed.candidates();
                payloadBases = packed.payloadBases();
            } else {
                // A visible carrier skipped by the last atomic build is still level-watched.
                // Rebuild once on its exact block/fluid event; never rematerialise its complete
                // 26-neighbour semantics inside the local-patch path merely to rediscover that it
                // is empty or over budget.
                return LocalUpdateResult.REBUILD_REQUIRED;
            }
            final Set<BrickKey> affected = new HashSet<>();
            for (final long key : dirtyCells) {
                if (candidates.contains(key)) {
                    affected.add(brickKey(BlockPos.of(key)));
                }
            }
            for (final BrickKey brick : affected) {
                final float[] replacement = buildBrickPayload(
                        input.carrier(), roomOwners, candidates, packed.opticalSlots(), brick);
                final Integer payloadBase = payloadBases.get(brick);
                if (payloadBase == null) {
                    if (containsOccupiedVoxel(replacement)) {
                        return LocalUpdateResult.REBUILD_REQUIRED;
                    }
                    continue;
                }
                if (payloadBase < 0 || payloadBase + BRICK_TEXELS > current.data().length / 4) {
                    return LocalUpdateResult.FAILED;
                }
                final float[] previous = payloadAt(current.data(), payloadBase);
                if (Arrays.equals(previous, replacement)) {
                    continue;
                }
                interiorDelta += countPositive(replacement) - countPositive(previous);
                shellDelta += countNegative(replacement) - countNegative(previous);
                eventlessOpticalDelta += countEventlessOptical(replacement)
                        - countEventlessOptical(previous);
                patches.add(new BrickPatch(payloadBase, replacement));
            }
        }
        if (patches.isEmpty()) {
            return LocalUpdateResult.NO_CHANGE;
        }
        if (!uploadBrickPatches(patches)) {
            return LocalUpdateResult.FAILED;
        }
        for (final BrickPatch patch : patches) {
            for (int index = 0; index < BRICK_TEXELS; index++) {
                final int offset = (patch.payloadBase() + index) * 4;
                current.data()[offset] = patch.values()[index];
                current.data()[offset + 1] = 0.0F;
                current.data()[offset + 2] = 0.0F;
                current.data()[offset + 3] = 0.0F;
            }
        }
        layout = new AtlasLayout(current.carriers(), current.prefixTexels(),
                current.roomCount(), current.brickCount(),
                current.interiorCellCount() + interiorDelta,
                current.shellCellCount() + shellDelta,
                current.eventlessOpticalCellCount() + eventlessOpticalDelta,
                current.watchedCellKeys(),
                current.width(), current.height(), current.data());
        preparedFrame = null;
        pausedFrame = null;
        return LocalUpdateResult.PATCHED;
    }

    private static boolean dirtyAffectsInput(final CarrierInput input,
                                             final Set<Long> dirtyCells) {
        final AABB bounds = inputWatchBounds(input);
        if (bounds == null) return false;
        for (final long key : dirtyCells) {
            final BlockPos cell = BlockPos.of(key);
            if (bounds.contains(cell.getX() + 0.5D,
                    cell.getY() + 0.5D, cell.getZ() + 0.5D)) {
                return true;
            }
        }
        return false;
    }

    private static float[] buildBrickPayload(
            final ClientSubLevel carrier, final Map<Long, Float> roomOwners,
            final Set<Long> candidates, final Map<Long, Integer> opticalSlots,
            final BrickKey brick) {
        final float[] payload = new float[BRICK_TEXELS];
        final int baseX = brick.x() * BRICK;
        final int baseY = brick.y() * BRICK;
        final int baseZ = brick.z() * BRICK;
        for (int iy = 0; iy < BRICK; iy++) {
            for (int iz = 0; iz < BRICK; iz++) {
                for (int ix = 0; ix < BRICK; ix++) {
                    final int x = baseX + ix;
                    final int y = baseY + iy;
                    final int z = baseZ + iz;
                    final long key = BlockPos.asLong(x, y, z);
                    final Float owner = roomOwners.get(key);
                    if (owner == null && !candidates.contains(key)) {
                        continue;
                    }
                    final BlockPos cell = new BlockPos(x, y, z);
                    if (isRealPlotWater(carrier, cell)) {
                        continue;
                    }
                    final int index = ix + BRICK * (iz + BRICK * iy);
                    if (owner != null) {
                        // Positive ownership is evaluated first and always wins over shell state.
                        payload[index] = owner;
                    } else if (isSolidMembrane(carrier, cell)) {
                        payload[index] = encodedShell(carrier, cell, opticalSlots.get(key));
                    }
                }
            }
        }
        return payload;
    }

    private static float encodedShell(final ClientSubLevel carrier, final BlockPos cell,
                                      final Integer directionSlotGroup) {
        final boolean fullOpticalVolume = directionSlotGroup == null
                && OpticalApertureProvider.isFullOpticalVolume(carrier, cell);
        return encodedOpticalShell(directionSlotGroup, fullOpticalVolume);
    }

    private static float encodedOpticalShell(final Integer directionSlotGroup,
                                            final boolean fullOpticalVolume) {
        // The integer is firstSlot * 64 + X-/X+/Y-/Y+/Z-/Z+ presence bits. Values stay exactly
        // representable in float at the carrier event cap. The -1.25 occupancy sentinel cuts
        // water in proven full glass without inventing an event for the fog/composite resolver.
        // Opaque/unknown or partial eventless membranes retain the -1 fail-open sentinel.
        return directionSlotGroup != null && directionSlotGroup >= 0
                ? -(directionSlotGroup + 2.0F)
                : fullOpticalVolume ? EVENTLESS_OPTICAL_VOLUME : -1.0F;
    }

    private static int countEventlessOptical(final float[] payload) {
        int count = 0;
        for (final float value : payload) {
            if (value == EVENTLESS_OPTICAL_VOLUME) count++;
        }
        return count;
    }

    private static float[] payloadAt(final float[] atlasData, final int payloadBase) {
        final float[] payload = new float[BRICK_TEXELS];
        for (int index = 0; index < BRICK_TEXELS; index++) {
            payload[index] = atlasData[(payloadBase + index) * 4];
        }
        return payload;
    }

    private static boolean containsOccupiedVoxel(final float[] payload) {
        for (final float value : payload) {
            if (value != 0.0F) {
                return true;
            }
        }
        return false;
    }

    private static int countPositive(final float[] payload) {
        int count = 0;
        for (final float value : payload) {
            if (value > 0.0F) {
                count++;
            }
        }
        return count;
    }

    private static int countNegative(final float[] payload) {
        int count = 0;
        for (final float value : payload) {
            if (value < 0.0F) {
                count++;
            }
        }
        return count;
    }

    private static boolean uploadBrickPatches(final List<BrickPatch> patches) {
        restoreBinding();
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            clearGlErrors();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            for (final BrickPatch patch : patches) {
                uploadPayloadRange(patch.payloadBase(), patch.values());
            }
            requireNoGlError("local brick upload");
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            if (!uploadWarningLogged) {
                uploadWarningLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[occlusion] exact voxel atlas local update failed; water is left intact",
                        exception);
            }
            return false;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL13C.glActiveTexture(previousActive);
        }
    }

    /** Uploads one 512-texel payload even when its linear range crosses atlas rows. */
    private static void uploadPayloadRange(final int payloadBase, final float[] payload) {
        final float[] rgba = new float[BRICK_TEXELS * 4];
        for (int index = 0; index < BRICK_TEXELS; index++) {
            rgba[index * 4] = payload[index];
        }
        final PixelUnpackState unpack = stagePixelUnpack(rgba);
        int sourceTexel = 0;
        int destination = payloadBase;
        int remaining = BRICK_TEXELS;
        try {
            final int startX = destination % width;
            if (startX != 0) {
                final int count = Math.min(remaining, width - startX);
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, startX,
                        destination / width, count, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT,
                        byteOffset(sourceTexel));
                sourceTexel += count;
                destination += count;
                remaining -= count;
            }
            final int fullRows = remaining / width;
            if (fullRows > 0) {
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0,
                        destination / width, width, fullRows, GL11C.GL_RGBA, GL11C.GL_FLOAT,
                        byteOffset(sourceTexel));
                final int count = fullRows * width;
                sourceTexel += count;
                destination += count;
                remaining -= count;
            }
            if (remaining > 0) {
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0,
                        destination / width, remaining, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT,
                        byteOffset(sourceTexel));
            }
        } finally {
            unpack.restore();
        }
    }

    private static long byteOffset(final int texels) {
        return (long) texels * 4L * Float.BYTES;
    }

    private static AtlasLayout buildLayoutSafely(final List<CarrierInput> inputs) {
        try {
            return buildLayout(inputs);
        } catch (final OutOfMemoryError error) {
            if (!capacityWarningLogged) {
                capacityWarningLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[occlusion] exact voxel atlas CPU budget exhausted; water is left intact");
            }
            return null;
        } catch (final RuntimeException | LinkageError exception) {
            if (!layoutBuildWarningLogged) {
                layoutBuildWarningLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[occlusion] exact voxel atlas semantic build failed; "
                                + "water is left intact until a new invalidation",
                        exception);
            }
            return null;
        }
    }

    private static AtlasLayout buildLayout(final List<CarrierInput> inputs) {
        int roomCount = 0;
        int prefix = HEADER_TEXELS;
        final List<CarrierBuild> builds = new ArrayList<>();
        final Set<Long> watchedCells = new LongOpenHashSet();
        long total = HEADER_TEXELS;
        for (final CarrierInput input : inputs) {
            if (!carrierFitsAtlasBudget(input)) {
                warnCarrierCapacityOnce(input.id(), MAX_CARRIER_TEXELS + 1L,
                        "preflight brick/semantic budget");
                continue;
            }
            final Map<BrickKey, float[]> bricks = new LinkedHashMap<>();
            final SemanticCells semantic = semanticCells(input);
            final Map<Long, Float> roomOwners = semantic.roomOwners();
            final Set<Long> interior = roomOwners.keySet();
            final Set<Long> candidates = semantic.candidates();
            int interiorCells = 0;
            for (final Map.Entry<Long, Float> owner : roomOwners.entrySet()) {
                final BlockPos cell = BlockPos.of(owner.getKey());
                if (isRealPlotWater(input.carrier(), cell)) {
                    continue;
                }
                if (putVoxel(bricks, cell, owner.getValue()) == 0.0F) {
                    interiorCells++;
                }
            }
            int shellCells = 0;
            for (final long key : candidates) {
                if (interior.contains(key)) {
                    continue;
                }
                final BlockPos cell = BlockPos.of(key);
                if (isRealPlotWater(input.carrier(), cell)
                        || !isSolidMembrane(input.carrier(), cell)) {
                    continue;
                }
                if (putVoxel(bricks, cell,
                        encodedShell(input.carrier(), cell,
                                semantic.opticalSlots().get(key))) == 0.0F) {
                    shellCells++;
                }
            }
            if (bricks.isEmpty()) {
                continue;
            }
            int minBx = Integer.MAX_VALUE;
            int minBy = Integer.MAX_VALUE;
            int minBz = Integer.MAX_VALUE;
            int maxBx = Integer.MIN_VALUE;
            int maxBy = Integer.MIN_VALUE;
            int maxBz = Integer.MIN_VALUE;
            for (final BrickKey brick : bricks.keySet()) {
                minBx = Math.min(minBx, brick.x());
                minBy = Math.min(minBy, brick.y());
                minBz = Math.min(minBz, brick.z());
                maxBx = Math.max(maxBx, brick.x());
                maxBy = Math.max(maxBy, brick.y());
                maxBz = Math.max(maxBz, brick.z());
            }
            final int dimX = maxBx - minBx + 1;
            final int dimY = maxBy - minBy + 1;
            final int dimZ = maxBz - minBz + 1;
            // The page directory contains one sorted entry per allocated brick. Each entry is
            // two texels: relative brick XYZ + payload base. Empty spans allocate nothing.
            final long directoryTexels = (long) bricks.size() * 2L;
            final long carrierTexels = DESCRIPTOR_TEXELS + input.rooms().size()
                    + (long) semantic.eventCount() * OPTICAL_EVENT_TEXELS
                    + directoryTexels + (long) bricks.size() * BRICK_TEXELS;
            if (carrierTexels > MAX_CARRIER_TEXELS) {
                warnCarrierCapacityOnce(input.id(), carrierTexels,
                        "per-carrier atlas budget");
                continue;
            }
            if (total + carrierTexels > MAX_GLOBAL_ATLAS_TEXELS) {
                warnCarrierCapacityOnce(input.id(), carrierTexels,
                        "remaining global atlas budget");
                continue;
            }
            builds.add(new CarrierBuild(input, minBx, minBy, minBz,
                    dimX, dimY, dimZ, bricks, roomOwners,
                    candidates, semantic.opticalSlots(), semantic.eventCount(),
                    semantic.opticalGeometryGeneration(), interiorCells, shellCells));
            watchedCells.addAll(candidates);
            total += carrierTexels;
        }
        if (builds.isEmpty()) {
            return null;
        }
        // Empty carriers were removed; recompute the packed prefix and all absolute bases.
        roomCount = builds.stream().mapToInt(build -> build.input().rooms().size()).sum();
        final int opticalEventTexels = builds.stream()
                .mapToInt(build -> build.eventCount() * OPTICAL_EVENT_TEXELS).sum();
        prefix = HEADER_TEXELS + builds.size() * DESCRIPTOR_TEXELS
                + roomCount + opticalEventTexels;
        int cursor = prefix;
        int roomCursor = HEADER_TEXELS + builds.size() * DESCRIPTOR_TEXELS;
        int eventCursor = roomCursor + roomCount;
        final List<CarrierLayout> carriers = new ArrayList<>();
        final List<PageWrite> pageWrites = new ArrayList<>();
        final List<PayloadWrite> payloadWrites = new ArrayList<>();
        int brickCount = 0;
        int interiorCellCount = 0;
        int shellCellCount = 0;
        int eventlessOpticalCellCount = 0;
        for (final CarrierBuild build : builds) {
            final int pageBase = cursor;
            final int pageCount = Math.multiplyExact(build.bricks().size(), 2);
            cursor += pageCount;
            final int roomBase = roomCursor;
            roomCursor += build.input().rooms().size();
            final int eventBase = eventCursor;
            eventCursor += build.eventCount() * OPTICAL_EVENT_TEXELS;
            final float[] pages = new float[pageCount * 4];
            final List<Map.Entry<BrickKey, float[]>> sortedBricks = build.bricks().entrySet()
                    .stream().sorted(Map.Entry.comparingByKey()).toList();
            final Map<BrickKey, Integer> payloadBases = new HashMap<>();
            for (int directoryIndex = 0; directoryIndex < sortedBricks.size();
                 directoryIndex++) {
                final Map.Entry<BrickKey, float[]> entry = sortedBricks.get(directoryIndex);
                final BrickKey key = entry.getKey();
                final int pageOffset = directoryIndex * 8;
                pages[pageOffset] = key.x() - build.minBx();
                pages[pageOffset + 1] = key.y() - build.minBy();
                pages[pageOffset + 2] = key.z() - build.minBz();
                pages[pageOffset + 4] = cursor + 1.0F;
                payloadBases.put(key, cursor);
                payloadWrites.add(new PayloadWrite(cursor, entry.getValue()));
                eventlessOpticalCellCount += countEventlessOptical(entry.getValue());
                cursor += BRICK_TEXELS;
                brickCount++;
            }
            pageWrites.add(new PageWrite(pageBase, pages));
            final List<RoomLayout> rooms = build.input().rooms().stream()
                    .map(room -> new RoomLayout(room.id())).toList();
            carriers.add(new CarrierLayout(build.input().id(), build.input().revision(),
                    build.minBx() * BRICK, build.minBy() * BRICK, build.minBz() * BRICK,
                    build.dimX(), build.dimY(), build.dimZ(), build.bricks().size(),
                    pageBase, roomBase, eventBase, build.eventCount(), rooms,
                    Map.copyOf(payloadBases), build.roomOwners(), build.candidates(),
                    build.opticalSlots(), build.opticalGeometryGeneration()));
            interiorCellCount += build.interiorCells();
            shellCellCount += build.shellCells();
        }
        if (cursor >= MAX_EXACT_TEXELS) {
            warnCapacityOnce(cursor);
            return null;
        }
        final int maxTexture = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
        int atlasWidth = Math.min(maxTexture, nextPowerOfTwo((int) Math.ceil(Math.sqrt(cursor))));
        atlasWidth = Math.max(1, atlasWidth);
        final int atlasHeight = (cursor + atlasWidth - 1) / atlasWidth;
        if (atlasHeight > maxTexture) {
            warnCapacityOnce(cursor);
            return null;
        }
        final long floatCount = (long) atlasWidth * atlasHeight * 4L;
        if (floatCount > Integer.MAX_VALUE || floatCount * Float.BYTES > MAX_ATLAS_BYTES) {
            warnCapacityOnce(cursor);
            return null;
        }
        final float[] data;
        try {
            data = new float[(int) floatCount];
        } catch (final OutOfMemoryError error) {
            warnCapacityOnce(cursor);
            return null;
        }
        put(data, 0, carriers.size(), HEADER_TEXELS, atlasWidth, atlasHeight);
        for (final PageWrite page : pageWrites) {
            System.arraycopy(page.data(), 0, data, page.base() * 4, page.data().length);
        }
        for (final PayloadWrite payload : payloadWrites) {
            final float[] values = payload.data();
            for (int index = 0; index < values.length; index++) {
                data[(payload.base() + index) * 4] = values[index];
            }
        }
        return new AtlasLayout(List.copyOf(carriers), prefix, roomCount, brickCount,
                interiorCellCount, shellCellCount, eventlessOpticalCellCount,
                Collections.unmodifiableSet(watchedCells),
                atlasWidth, atlasHeight, data);
    }

    private static boolean install(final AtlasLayout replacement) {
        final int previousActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int oldTexture = texture;
        int candidate = -1;
        boolean installed = false;
        try {
            // Errors left by another renderer must not make this allocation appear to fail.
            clearGlErrors();
            candidate = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, candidate);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER,
                    GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER,
                    GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S,
                    GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T,
                    GL12C.GL_CLAMP_TO_EDGE);
            final PixelUnpackState unpack = stagePixelUnpack(replacement.data());
            try {
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F,
                        replacement.width(), replacement.height(), 0,
                        GL11C.GL_RGBA, GL11C.GL_FLOAT, 0L);
            } finally {
                unpack.restore();
            }
            requireNoGlError("allocation");
            texture = candidate;
            width = replacement.width();
            height = replacement.height();
            uploadedPrefix = null;
            candidate = -1;
            installed = true;
            return true;
        } catch (final RuntimeException | LinkageError exception) {
            if (!uploadWarningLogged) {
                uploadWarningLogged = true;
                CreateNautilus.LOGGER.warn(
                        "[occlusion] exact voxel atlas allocation failed; water is left intact",
                        exception);
            }
            return false;
        } finally {
            if (candidate >= 0) {
                GL11C.glDeleteTextures(candidate);
            }
            // If the caller happened to have the old atlas bound, preserve that logical binding
            // by replacing it with the newly installed atlas before deleting the old name.
            final int restoreTexture = installed && previousTexture == oldTexture
                    ? texture : previousTexture;
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, restoreTexture);
            if (installed && oldTexture >= 0) {
                GL11C.glDeleteTextures(oldTexture);
            }
            GL13C.glActiveTexture(previousActive);
        }
    }

    private static void clearGlErrors() {
        for (int index = 0; index < 32 && GL11C.glGetError() != GL11C.GL_NO_ERROR; index++) {
            // Drain unrelated errors before attributing a new one to the atlas operation.
        }
    }

    private static void requireNoGlError(final String operation) {
        final int error = GL11C.glGetError();
        if (error != GL11C.GL_NO_ERROR) {
            throw new IllegalStateException("OpenGL " + operation + " error 0x"
                    + Integer.toHexString(error));
        }
    }

    private static void uploadLinearPrefix(final float[] data, final int texels) {
        final PixelUnpackState unpack = stagePixelUnpack(data);
        final int fullRows = texels / width;
        final int remainder = texels % width;
        try {
            if (fullRows > 0) {
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0,
                        width, fullRows, GL11C.GL_RGBA, GL11C.GL_FLOAT, 0L);
            }
            if (remainder > 0) {
                final long byteOffset = (long) fullRows * width * 4L * Float.BYTES;
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, fullRows,
                        remainder, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, byteOffset);
            }
        } finally {
            unpack.restore();
        }
    }

    /**
     * Copies client data into GL-owned pixel-unpack storage before any texture call. This avoids
     * passing a short-lived Java direct-buffer pointer to the NVIDIA driver and fully sandboxes
     * row/skip state left by surrounding renderers.
     */
    private static PixelUnpackState stagePixelUnpack(final float[] data) {
        final int previousBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int previousAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        final int previousRowLength = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
        final int previousSkipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
        final int previousSkipPixels = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
        boolean mapped = false;
        try {
            if (pixelUnpackBuffer < 0) {
                pixelUnpackBuffer = GL15C.glGenBuffers();
                if (pixelUnpackBuffer <= 0) {
                    throw new IllegalStateException("Unable to create pixel-unpack buffer");
                }
            }
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
            final long logicalBytes = Math.multiplyExact((long) data.length, Float.BYTES);
            final long stagingBytes = Math.addExact(logicalBytes, PIXEL_UNPACK_GUARD_BYTES);
            GL15C.glBufferData(GL21C.GL_PIXEL_UNPACK_BUFFER, stagingBytes,
                    GL15C.GL_STREAM_DRAW);
            final ByteBuffer bytes = GL30C.glMapBufferRange(GL21C.GL_PIXEL_UNPACK_BUFFER,
                    0L, stagingBytes,
                    GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_INVALIDATE_BUFFER_BIT);
            if (bytes == null) {
                throw new IllegalStateException("Unable to map pixel-unpack buffer");
            }
            mapped = true;
            bytes.order(ByteOrder.nativeOrder()).asFloatBuffer().put(data);
            if (!GL15C.glUnmapBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER)) {
                mapped = false;
                throw new IllegalStateException("Pixel-unpack buffer contents became invalid");
            }
            mapped = false;
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            return new PixelUnpackState(previousBuffer, previousAlignment, previousRowLength,
                    previousSkipRows, previousSkipPixels);
        } catch (final RuntimeException | LinkageError exception) {
            if (mapped) {
                GL15C.glUnmapBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER);
            }
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousAlignment);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, previousRowLength);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, previousSkipRows);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, previousBuffer);
            throw exception;
        }
    }

    /**
     * Writes a conservative half-open {@code gl_FragCoord} rectangle for one carrier. The
     * existing inverse descriptor is inverted here so the projected volume exactly matches the
     * coordinate domain which the fragment lookup can classify. Any uncertain projection disables
     * only this early-out by publishing the full viewport; voxel clipping itself remains active.
     */
    private static void putConservativeScreenRect(
            final FloatBuffer target, final CarrierFrame carrier,
            final Matrix4f modelView, final Matrix4f projection,
            final int viewportX, final int viewportY,
            final int viewportWidth, final int viewportHeight,
            final boolean waveLayoutKnown) {
        if (!waveLayoutKnown || modelView == null || projection == null
                || viewportWidth <= 0 || viewportHeight <= 0) {
            putFullScreenRect(target, viewportX, viewportY, viewportWidth, viewportHeight);
            return;
        }

        final Matrix4f atlasLocalToCamera = new Matrix4f(carrier.transform());
        final float determinant = atlasLocalToCamera.determinant();
        if (!Float.isFinite(determinant)
                || Math.abs(determinant) <= SCREEN_RECT_CLIP_EPSILON) {
            putFullScreenRect(target, viewportX, viewportY, viewportWidth, viewportHeight);
            return;
        }
        atlasLocalToCamera.invert();
        final Matrix4f localToClip = new Matrix4f(projection)
                .mul(modelView)
                .mul(atlasLocalToCamera);
        if (!finite(localToClip)) {
            putFullScreenRect(target, viewportX, viewportY, viewportWidth, viewportHeight);
            return;
        }

        // The atlas transform subtracts its brick-aligned origin, so its complete coarse domain
        // is [0, brickDimensions * BRICK]. One additional brick covers every supported pack's
        // wave displacement and the one-cell membrane shell without a false-negative screen gate.
        final float minX = -SCREEN_RECT_LOCAL_MARGIN;
        final float minY = -SCREEN_RECT_LOCAL_MARGIN;
        final float minZ = -SCREEN_RECT_LOCAL_MARGIN;
        final float maxX = carrier.layout().brickDimX() * (float) BRICK
                + SCREEN_RECT_LOCAL_MARGIN;
        final float maxY = carrier.layout().brickDimY() * (float) BRICK
                + SCREEN_RECT_LOCAL_MARGIN;
        final float maxZ = carrier.layout().brickDimZ() * (float) BRICK
                + SCREEN_RECT_LOCAL_MARGIN;
        float minNdcX = Float.POSITIVE_INFINITY;
        float minNdcY = Float.POSITIVE_INFINITY;
        float maxNdcX = Float.NEGATIVE_INFINITY;
        float maxNdcY = Float.NEGATIVE_INFINITY;
        final Vector4f clip = new Vector4f();
        for (int corner = 0; corner < 8; corner++) {
            clip.set((corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ, 1.0F);
            localToClip.transform(clip);
            if (!Float.isFinite(clip.x) || !Float.isFinite(clip.y)
                    || !Float.isFinite(clip.z) || !Float.isFinite(clip.w)
                    || clip.w <= SCREEN_RECT_CLIP_EPSILON
                    || clip.z <= -clip.w + SCREEN_RECT_CLIP_EPSILON) {
                // Projecting only the surviving corners of a near-plane crossing volume can make
                // the rectangle too small. Full-screen is the required conservative fallback.
                putFullScreenRect(target, viewportX, viewportY,
                        viewportWidth, viewportHeight);
                return;
            }
            final float inverseW = 1.0F / clip.w;
            final float ndcX = clip.x * inverseW;
            final float ndcY = clip.y * inverseW;
            if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY)) {
                putFullScreenRect(target, viewportX, viewportY,
                        viewportWidth, viewportHeight);
                return;
            }
            minNdcX = Math.min(minNdcX, ndcX);
            minNdcY = Math.min(minNdcY, ndcY);
            maxNdcX = Math.max(maxNdcX, ndcX);
            maxNdcY = Math.max(maxNdcY, ndcY);
        }

        if (maxNdcX < -1.0F || minNdcX > 1.0F
                || maxNdcY < -1.0F || minNdcY > 1.0F) {
            // A reversed interval is the explicit known-empty sentinel consumed by GLSL.
            put(target, 1.0F, 1.0F, 0.0F, 0.0F);
            return;
        }

        minNdcX = Math.clamp(minNdcX, -1.0F, 1.0F);
        minNdcY = Math.clamp(minNdcY, -1.0F, 1.0F);
        maxNdcX = Math.clamp(maxNdcX, -1.0F, 1.0F);
        maxNdcY = Math.clamp(maxNdcY, -1.0F, 1.0F);
        final double viewportMaxX = (double) viewportX + viewportWidth;
        final double viewportMaxY = (double) viewportY + viewportHeight;
        final double projectedMinX = viewportX
                + (minNdcX * 0.5D + 0.5D) * viewportWidth;
        final double projectedMinY = viewportY
                + (minNdcY * 0.5D + 0.5D) * viewportHeight;
        final double projectedMaxX = viewportX
                + (maxNdcX * 0.5D + 0.5D) * viewportWidth;
        final double projectedMaxY = viewportY
                + (maxNdcY * 0.5D + 0.5D) * viewportHeight;
        final double quantizedMinX = Math.floor(
                (projectedMinX - SCREEN_RECT_PIXEL_MARGIN) / SCREEN_RECT_PIXEL_QUANTUM)
                * SCREEN_RECT_PIXEL_QUANTUM;
        final double quantizedMinY = Math.floor(
                (projectedMinY - SCREEN_RECT_PIXEL_MARGIN) / SCREEN_RECT_PIXEL_QUANTUM)
                * SCREEN_RECT_PIXEL_QUANTUM;
        final double quantizedMaxX = Math.ceil(
                (projectedMaxX + SCREEN_RECT_PIXEL_MARGIN) / SCREEN_RECT_PIXEL_QUANTUM)
                * SCREEN_RECT_PIXEL_QUANTUM;
        final double quantizedMaxY = Math.ceil(
                (projectedMaxY + SCREEN_RECT_PIXEL_MARGIN) / SCREEN_RECT_PIXEL_QUANTUM)
                * SCREEN_RECT_PIXEL_QUANTUM;
        final float rectangleMinX = (float) Math.max(viewportX, quantizedMinX);
        final float rectangleMinY = (float) Math.max(viewportY, quantizedMinY);
        final float rectangleMaxX = (float) Math.min(viewportMaxX, quantizedMaxX);
        final float rectangleMaxY = (float) Math.min(viewportMaxY, quantizedMaxY);
        if (rectangleMinX >= rectangleMaxX || rectangleMinY >= rectangleMaxY) {
            put(target, 1.0F, 1.0F, 0.0F, 0.0F);
            return;
        }
        put(target, rectangleMinX, rectangleMinY, rectangleMaxX, rectangleMaxY);
    }

    private static void putFullScreenRect(final FloatBuffer target,
                                          final int viewportX, final int viewportY,
                                          final int viewportWidth, final int viewportHeight) {
        if (viewportWidth > 0 && viewportHeight > 0) {
            put(target, viewportX, viewportY,
                    (float) ((double) viewportX + viewportWidth),
                    (float) ((double) viewportY + viewportHeight));
            return;
        }
        put(target, -SCREEN_RECT_UNBOUNDED, -SCREEN_RECT_UNBOUNDED,
                SCREEN_RECT_UNBOUNDED, SCREEN_RECT_UNBOUNDED);
    }

    private static Matrix4f cameraRelativeToAtlasLocal(
            final Pose3dc pose, final int originX, final int originY, final int originZ,
            final double cameraX, final double cameraY, final double cameraZ) {
        final Matrix4d inverse = new Matrix4d()
                .translate(pose.rotationPoint().x() - originX,
                        pose.rotationPoint().y() - originY,
                        pose.rotationPoint().z() - originZ)
                .scale(1.0D / pose.scale().x(), 1.0D / pose.scale().y(),
                        1.0D / pose.scale().z())
                .rotate(new Quaterniond(pose.orientation()).conjugate())
                // Input is camera-relative, so translate by camera - carrier position.
                .translate(cameraX - pose.position().x(), cameraY - pose.position().y(),
                        cameraZ - pose.position().z());
        return new Matrix4f(inverse);
    }

    /**
     * Performs an allocation-free-by-voxel budget pass. A one-cell shell can enter an adjacent
     * brick only when the source voxel touches that brick face, edge or corner, so at most eight
     * brick keys are considered per room voxel. The set is capped before any room-owner map,
     * 26-neighbour set or 512-float payload is materialised.
     */
    private static boolean carrierFitsAtlasBudget(final CarrierInput input) {
        final long fixedTexels = DESCRIPTOR_TEXELS + (long) input.rooms().size();
        final long available = MAX_CARRIER_TEXELS - fixedTexels;
        if (available <= 0L) {
            return false;
        }
        final long maximumBricks = available / (BRICK_TEXELS + 2L);
        final Set<BrickKey> footprint = new HashSet<>((int) Math.min(maximumBricks + 1L, 8192L));
        final BoundingBox3ic plot = input.carrier().getPlot().getBoundingBox();
        if (plot == null || plot.volume() <= 0) {
            return false;
        }
        long visits = 0L;
        for (final ClientCompartmentVisualCache.RoomSnapshot room : input.rooms()) {
            visits += room.cells().size();
            if (visits > MAX_CARRIER_SEMANTIC_VISITS) {
                return false;
            }
            for (final BlockPos cell : room.cells()) {
                if (cell.getX() < plot.minX() || cell.getX() > plot.maxX()
                        || cell.getY() < plot.minY() || cell.getY() > plot.maxY()
                        || cell.getZ() < plot.minZ() || cell.getZ() > plot.maxZ()) {
                    return false;
                }
                final int bx = Math.floorDiv(cell.getX(), BRICK);
                final int by = Math.floorDiv(cell.getY(), BRICK);
                final int bz = Math.floorDiv(cell.getZ(), BRICK);
                final int localX = Math.floorMod(cell.getX(), BRICK);
                final int localY = Math.floorMod(cell.getY(), BRICK);
                final int localZ = Math.floorMod(cell.getZ(), BRICK);
                final int minDx = localX == 0 ? -1 : 0;
                final int maxDx = localX == BRICK - 1 ? 1 : 0;
                final int minDy = localY == 0 ? -1 : 0;
                final int maxDy = localY == BRICK - 1 ? 1 : 0;
                final int minDz = localZ == 0 ? -1 : 0;
                final int maxDz = localZ == BRICK - 1 ? 1 : 0;
                for (int dx = minDx; dx <= maxDx; dx++) {
                    for (int dy = minDy; dy <= maxDy; dy++) {
                        for (int dz = minDz; dz <= maxDz; dz++) {
                            final BrickKey brick = new BrickKey(bx + dx, by + dy, bz + dz);
                            if (brickIntersectsPlot(brick, plot)
                                    && footprint.add(brick)
                                    && footprint.size() > maximumBricks) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return !footprint.isEmpty();
    }

    private static boolean brickIntersectsPlot(final BrickKey brick, final BoundingBox3ic plot) {
        final int minX = brick.x() * BRICK;
        final int minY = brick.y() * BRICK;
        final int minZ = brick.z() * BRICK;
        return minX <= plot.maxX() && minX + BRICK - 1 >= plot.minX()
                && minY <= plot.maxY() && minY + BRICK - 1 >= plot.minY()
                && minZ <= plot.maxZ() && minZ + BRICK - 1 >= plot.minZ();
    }

    /** Interior cells plus the one-voxel semantic shell, clipped to the real plot bounds. */
    private static Set<Long> shellCandidates(final CarrierInput input,
                                             final Set<Long> interior) {
        final Set<Long> candidates = new LongOpenHashSet(Math.max(16, interior.size()));
        candidates.addAll(interior);
        final BoundingBox3ic bounds = input.carrier().getPlot().getBoundingBox();
        if (bounds == null || bounds.volume() <= 0) {
            return candidates;
        }
        for (final long key : interior) {
            final BlockPos cell = BlockPos.of(key);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        final int x = cell.getX() + dx;
                        final int y = cell.getY() + dy;
                        final int z = cell.getZ() + dz;
                        if (x >= bounds.minX() && x <= bounds.maxX()
                                && y >= bounds.minY() && y <= bounds.maxY()
                                && z >= bounds.minZ() && z <= bounds.maxZ()) {
                            candidates.add(BlockPos.asLong(x, y, z));
                        }
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * Adds full optical occupancy one step beyond the semantic shell (e.g. above a hull footing),
     * independently of event creation. This is a single local ring, not a scan/flood-fill of the
     * whole plot. Existing event-backed wrap cells retain their exact coverage as well.
     */
    private static Set<Long> atlasCandidates(
            final CarrierInput input,
            final Set<Long> interior,
            final ApertureEventCache.OpticalSlots optical) {
        final Set<Long> candidates = shellCandidates(input, interior);
        final BoundingBox3ic bounds = input.carrier().getPlot().getBoundingBox();
        if (bounds != null && bounds.volume() > 0) {
            final Set<Long> examined = new LongOpenHashSet();
            final Set<Long> fullOpticalNeighbours = new LongOpenHashSet();
            final Direction[] directions = Direction.values();
            occupancyRing:
            for (final long key : candidates) {
                if (interior.contains(key)) continue;
                final BlockPos shell = BlockPos.of(key);
                for (final Direction direction : directions) {
                    final BlockPos neighbour = shell.relative(direction);
                    final long neighbourKey = neighbour.asLong();
                    if (neighbour.getX() < bounds.minX() || neighbour.getX() > bounds.maxX()
                            || neighbour.getY() < bounds.minY() || neighbour.getY() > bounds.maxY()
                            || neighbour.getZ() < bounds.minZ() || neighbour.getZ() > bounds.maxZ()
                            || candidates.contains(neighbourKey)) continue;
                    if (examined.size() >= MAX_CARRIER_SEMANTIC_VISITS) break occupancyRing;
                    if (!examined.add(neighbourKey)) continue;
                    if (OpticalApertureProvider.isFullOpticalVolume(input.carrier(), neighbour)) {
                        fullOpticalNeighbours.add(neighbourKey);
                    }
                }
            }
            candidates.addAll(fullOpticalNeighbours);
        }
        candidates.addAll(optical.voxelSlots().keySet());
        return candidates;
    }

    /** Writes one sparse payload voxel and returns the previous value. */
    private static float putVoxel(final Map<BrickKey, float[]> bricks,
                                  final BlockPos cell, final float value) {
        final int bx = Math.floorDiv(cell.getX(), BRICK);
        final int by = Math.floorDiv(cell.getY(), BRICK);
        final int bz = Math.floorDiv(cell.getZ(), BRICK);
        final float[] payload = bricks.computeIfAbsent(
                new BrickKey(bx, by, bz), ignored -> new float[BRICK_TEXELS]);
        final int ix = Math.floorMod(cell.getX(), BRICK);
        final int iy = Math.floorMod(cell.getY(), BRICK);
        final int iz = Math.floorMod(cell.getZ(), BRICK);
        final int index = ix + BRICK * (iz + BRICK * iy);
        final float previous = payload[index];
        if (previous == 0.0F || value > 0.0F) {
            // Positive room ownership always wins over an earlier shell sentinel.
            payload[index] = value;
        }
        return previous;
    }

    private static boolean isSolidMembrane(final ClientSubLevel carrier,
                                           final BlockPos cell) {
        try {
            final var state = carrier.getLevel().getBlockState(cell);
            return CompartmentVoxelSemantics.isSolidMembrane(
                    carrier.getLevel(), cell, state);
        } catch (final RuntimeException | LinkageError ignored) {
            // Unknown block semantics fail open for this shell voxel.
            return false;
        }
    }

    private static boolean isRealPlotWater(final ClientSubLevel carrier, final BlockPos cell) {
        try {
            // Room cells are absolute plot-space positions (around the Sable plot grid), not the
            // EmbeddedPlotLevelAccessor's centre-relative coordinates.
            return carrier.getLevel().getFluidState(cell).is(FluidTags.WATER);
        } catch (final RuntimeException | LinkageError ignored) {
            // Unknown plot state fails open for the individual voxel.
            return true;
        }
    }

    private static long topologyKey(final List<CarrierInput> inputs) {
        long hash = 0xcbf29ce484222325L;
        for (final CarrierInput input : inputs) {
            hash = mix(hash, input.id().getMostSignificantBits());
            hash = mix(hash, input.id().getLeastSignificantBits());
            hash = mix(hash, input.revision());
            hash = mix(hash, input.rooms().size());
            // Optical geometry can be rebuilt without changing the synchronized room revision
            // (carrier instance replacement, one invalid pose frame, or model-resource change).
            // Make that generation part of the atlas key so event slots can never remain
            // permanently mismatched with a cached carrier layout.
            final ApertureEventCache.OpticalSlots optical = opticalSlots(input);
            hash = mix(hash, optical.geometryGeneration());
            hash = mix(hash, optical.eventCount());
            for (final ClientCompartmentVisualCache.RoomSnapshot room : input.rooms()) {
                hash = mix(hash, room.id().getMostSignificantBits());
                hash = mix(hash, room.id().getLeastSignificantBits());
                hash = mix(hash, room.cells().size());
            }
        }
        return hash;
    }

    /** Full shell/fluid fingerprint used only while creating an atomic atlas generation. */
    private static long voxelStateKey(final List<CarrierInput> inputs) {
        long hash = 0x9e3779b97f4a7c15L;
        for (final CarrierInput input : inputs) {
            final Set<Long> interior = new HashSet<>();
            for (final ClientCompartmentVisualCache.RoomSnapshot room : input.rooms()) {
                for (final BlockPos cell : room.cells()) {
                    interior.add(cell.asLong());
                }
            }
            final ApertureEventCache.OpticalSlots optical = opticalSlots(input);
            final Set<Long> candidates = atlasCandidates(input, interior, optical);
            hash = mix(hash, candidates.size());
            for (final long key : candidates.stream().mapToLong(Long::longValue).sorted().toArray()) {
                final BlockPos cell = BlockPos.of(key);
                long semantics = 0L;
                try {
                    final var state = input.carrier().getLevel().getBlockState(cell);
                    if (state.getFluidState().is(FluidTags.WATER)) {
                        semantics |= 1L;
                    }
                    if (CompartmentVoxelSemantics.isSolidMembrane(
                            input.carrier().getLevel(), cell, state)) {
                        semantics |= 2L;
                    }
                } catch (final RuntimeException | LinkageError ignored) {
                    semantics = 4L;
                }
                hash = mix(hash, key);
                hash = mix(hash, semantics);
            }
        }
        return hash;
    }

    private static long revisionOf(final List<ClientCompartmentVisualCache.RoomSnapshot> rooms) {
        return rooms.stream().mapToLong(ClientCompartmentVisualCache.RoomSnapshot::topologyRevision)
                .max().orElse(0L);
    }

    private static long mix(final long hash, final long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static float generationFloat(final long revision) {
        return (float) Math.floorMod(revision, 1L << 20);
    }

    private static int nextPowerOfTwo(final int value) {
        if (value <= 1) {
            return 1;
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static boolean finite(final Matrix4f value) {
        return Float.isFinite(value.m00()) && Float.isFinite(value.m01())
                && Float.isFinite(value.m02()) && Float.isFinite(value.m03())
                && Float.isFinite(value.m10()) && Float.isFinite(value.m11())
                && Float.isFinite(value.m12()) && Float.isFinite(value.m13())
                && Float.isFinite(value.m20()) && Float.isFinite(value.m21())
                && Float.isFinite(value.m22()) && Float.isFinite(value.m23())
                && Float.isFinite(value.m30()) && Float.isFinite(value.m31())
                && Float.isFinite(value.m32()) && Float.isFinite(value.m33());
    }

    private static boolean sameDouble(final double left, final double right) {
        return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }

    private static void setUniform1i(final int program, final String name, final int value) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("Missing uniform " + name);
        }
        GL20C.glUniform1i(location, value);
    }

    private static void setUniform2f(final int program, final String name,
                                     final float x, final float y) {
        final int location = GL20C.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException("Missing uniform " + name);
        }
        GL20C.glUniform2f(location, x, y);
    }

    private static void put(final FloatBuffer target, final float x, final float y,
                            final float z, final float w) {
        target.put(x).put(y).put(z).put(w);
    }

    private static void putOpticalEvent(
            final FloatBuffer target,
            final ApertureEventCache.Event event,
            final boolean valid,
            final long topologyRevision,
            final int originDeltaX,
            final int originDeltaY,
            final int originDeltaZ) {
        if (!valid) {
            put(target, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            put(target, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            put(target, -1.0F, 0.0F, -1.0F, generationFloat(topologyRevision));
            return;
        }
        final ApertureEventCache.Plane negative = event.planes().negative();
        final ApertureEventCache.Plane positive = event.planes().positive();
        // ApertureEventCache stores its planes relative to the minimum room-cell origin, while
        // the sparse atlas inverse transform produces coordinates relative to the brick-aligned
        // CarrierLayout origin. Translate the plane constants once so both the D24S8 gate pass
        // and the Photon resolver evaluate exactly the same local point.
        final double negativeW = negative.w() + negative.x() * originDeltaX
                + negative.y() * originDeltaY + negative.z() * originDeltaZ;
        final double positiveW = positive.w() + positive.x() * originDeltaX
                + positive.y() * originDeltaY + positive.z() * originDeltaZ;
        if (!Double.isFinite(negativeW) || !Double.isFinite(positiveW)) {
            put(target, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            put(target, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            put(target, -1.0F, 0.0F, -1.0F, generationFloat(topologyRevision));
            return;
        }
        put(target, (float) negative.x(), (float) negative.y(),
                (float) negative.z(), (float) negativeW);
        put(target, (float) positive.x(), (float) positive.y(),
                (float) positive.z(), (float) positiveW);
        final float axis = switch (event.axis()) {
            case X -> 0.0F;
            case Y -> 1.0F;
            case Z -> 2.0F;
        };
        final float outwardCode = (event.outwardDirection().getAxisDirection()
                == net.minecraft.core.Direction.AxisDirection.POSITIVE ? 2.0F : 1.0F)
                + (event.floodedExteriorShell() ? 2.0F : 0.0F);
        put(target, axis, outwardCode, event.slot(), generationFloat(topologyRevision));
    }

    private static void put(final float[] target, final int texel, final float x,
                            final float y, final float z, final float w) {
        final int offset = texel * 4;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
        target[offset + 3] = w;
    }

    private static void warnCapacityOnce(final long requested) {
        if (capacityWarningLogged) {
            return;
        }
        capacityWarningLogged = true;
        CreateNautilus.LOGGER.warn(
                "[occlusion] exact voxel atlas needs {} texels (limit {}); water is left intact",
                requested, MAX_EXACT_TEXELS);
    }

    private static void warnCarrierCapacityOnce(final UUID carrier,
                                                final long requestedTexels,
                                                final String budget) {
        if (!CARRIER_CAPACITY_WARNINGS.add(carrier)) {
            return;
        }
        CreateNautilus.LOGGER.warn(
                "[occlusion] skipping exact voxel mask for carrier {}: {} texels exceed {}; "
                        + "other carriers remain active",
                carrier, requestedTexels, budget);
    }

    private record CarrierInput(UUID id, ClientSubLevel carrier, long revision,
                                 List<ClientCompartmentVisualCache.RoomSnapshot> rooms) {
        private CarrierInput {
            Objects.requireNonNull(id);
            Objects.requireNonNull(carrier);
            rooms = List.copyOf(rooms);
        }
    }

    private record WatchedPlotBounds(ClientLevel level, AABB bounds) {
        private WatchedPlotBounds {
            Objects.requireNonNull(level);
            Objects.requireNonNull(bounds);
        }
    }

    private record DistanceCarrierInput(CarrierInput input, double distanceSquared) {
    }

    private record CarrierBuild(CarrierInput input, int minBx, int minBy, int minBz,
                                 int dimX, int dimY, int dimZ,
                                 Map<BrickKey, float[]> bricks,
                                 Map<Long, Float> roomOwners, Set<Long> candidates,
                                 Map<Long, Integer> opticalSlots, int eventCount,
                                 long opticalGeometryGeneration,
                                 int interiorCells, int shellCells) {
    }

    private record CarrierLayout(UUID id, long revision,
                                 int voxelOriginX, int voxelOriginY, int voxelOriginZ,
                                 int brickDimX, int brickDimY, int brickDimZ,
                                 int directoryCount,
                                 int pageBase, int roomBase, int eventBase, int eventCount,
                                 List<RoomLayout> rooms,
                                 Map<BrickKey, Integer> payloadBases,
                                 Map<Long, Float> roomOwners, Set<Long> candidates,
                                 Map<Long, Integer> opticalSlots,
                                 long opticalGeometryGeneration) {
    }

    private record SemanticCells(Map<Long, Float> roomOwners, Set<Long> candidates,
                                 Map<Long, Integer> opticalSlots, int eventCount,
                                 long opticalGeometryGeneration) {
    }

    private record RoomLayout(UUID id) {
    }

    static record PreparedRoomWater(double surfaceY, double fraction) {
    }

    private record RoomSurfaceCacheKey(UUID carrierId, UUID roomId) {
    }

    private record RoomSurfaceCacheValue(long topologyRevision, long fractionBits,
                                         long basisXBits, long basisYBits, long basisZBits,
                                         double surfaceRelativeToTranslation) {
    }

    private record RoomFrame(UUID id, float surfaceY, float fraction, float clipEnabled,
                             float generation) {
    }

    private record CarrierFrame(CarrierLayout layout, Matrix4f transform,
                                List<RoomFrame> rooms) {
    }

    private record FrameSnapshot(AtlasLayout layout,
                                  double cameraX, double cameraY, double cameraZ,
                                  List<CarrierFrame> carriers) {
    }

    private record PreparedOpticalFrame(ApertureEventCache.Frame frame, long frameToken) {
    }

    private record EncodedPrefix(float[] data, CompositeAtlasView opticalView) {
    }

    static record CompositeAtlasView(int texture, int width, int height, int carrierCount,
                                     long frameToken,
                                     Map<UUID, CompositeCarrierView> carriers) {
        CompositeAtlasView {
            carriers = Map.copyOf(carriers);
        }

        CompositeCarrierView carrier(final UUID carrierId) {
            return carriers.get(carrierId);
        }
    }

    static record CompositeCarrierView(int eventBase,
                                       int voxelOriginX,
                                       int voxelOriginY,
                                       int voxelOriginZ,
                                       long topologyRevision) {
    }

    private record BrickKey(int x, int y, int z) implements Comparable<BrickKey> {
        @Override
        public int compareTo(final BrickKey other) {
            int result = Integer.compare(y, other.y);
            if (result == 0) {
                result = Integer.compare(z, other.z);
            }
            return result == 0 ? Integer.compare(x, other.x) : result;
        }
    }

    private record PageWrite(int base, float[] data) {
    }

    private record PayloadWrite(int base, float[] data) {
    }

    private record BrickPatch(int payloadBase, float[] values) {
    }

    private enum LocalUpdateResult {
        NO_CHANGE,
        PATCHED,
        REBUILD_REQUIRED,
        FAILED
    }

    private record AtlasLayout(List<CarrierLayout> carriers, int prefixTexels,
                               int roomCount, int brickCount,
                               int interiorCellCount, int shellCellCount,
                               int eventlessOpticalCellCount,
                               Set<Long> watchedCellKeys,
                               int width, int height, float[] data) {
    }

    private record BoundTexture(int unit, int previousActive, int previousTexture,
                                 int previousSampler) {
    }

    private record PixelUnpackState(int previousBuffer, int previousAlignment,
                                    int previousRowLength, int previousSkipRows,
                                    int previousSkipPixels) {
        private void restore() {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousAlignment);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, previousRowLength);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, previousSkipRows);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, previousBuffer);
        }
    }
}
