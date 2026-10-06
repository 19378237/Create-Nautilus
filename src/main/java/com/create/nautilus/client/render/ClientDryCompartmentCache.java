package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.network.DryCompartmentSnapshotPayload;
import dev.ryanhcode.sable.SableClient;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import dev.ryanhcode.sable.util.BoundedBitVolume3i;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Client-only, fail-open cache consulted while chunk meshes are built.
 *
 * <p>Sable may compile carrier sections on worker threads. Map publication and
 * immutable sorted arrays therefore replace mutable attachment views. Missing
 * or stale-unavailable data always means "render the water".</p>
 */
public final class ClientDryCompartmentCache {
    private static final double CAMERA_BOUNDARY_MARGIN = 1.0D / 1024.0D;
    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, OcclusionRegistration> OCCLUSION_REGIONS =
            new ConcurrentHashMap<>();
    /*
     * Parent-world ocean is suppressed at the chunk-mesh level: Sodium's fluid renderer skips
     * water quads whose world position falls inside a tracked dry compartment. The mesh workers
     * run on background threads while Sable's render pose mutates on the render thread, so the
     * pose is frozen into an immutable matrix array once per client tick.
     */
    private static final WorldDrySnapshot[] EMPTY_WORLD_DRY = new WorldDrySnapshot[0];
    private static volatile WorldDrySnapshot[] WORLD_DRY = EMPTY_WORLD_DRY;
    private static final Map<UUID, PackedTopology> PACKED_TOPOLOGY_CACHE =
            new ConcurrentHashMap<>();
    private static volatile Map<UUID, PackedTopology> PACKED_TOPOLOGIES = Map.of();
    private static final Map<UUID, DirtyState> PARENT_DIRTY_STATE = new ConcurrentHashMap<>();
    private static long lastParentDirtyLogNanos;
    /** Rebuild only after the hull actually left the previous rebuild position. */
    private static final double REBUILD_TRANSLATION_HYSTERESIS = 1.0D;
    /*
     * When the fragment depth-mask pipeline is active (vanilla/Sodium without a shader pack) it
     * trims boundary water at the exact hull faces. Whole-block mesh suppression would delete the
     * very blocks the mask needs to smooth, leaving a block-stepped cutout, so it is switched off
     * in that mode. Colorwheel replaces the water program and disables the fragment mask, which
     * turns this suppression back on.
     */
    private static volatile boolean MESH_SUPPRESSION_ENABLED = true;
    private static boolean worldDryLogged;
    private static boolean suppressionHitLogged;
    private static long suppressionHitCount;
    /**
     * Snapshots can arrive before Sable has created the ClientSubLevel render data. Keep those
     * carrier ids here and retry instead of relying on a later block update to rebuild the mesh.
     */
    private static final java.util.Set<UUID> PENDING_RENDER_REBUILDS =
            ConcurrentHashMap.newKeySet();
    private ClientDryCompartmentCache() {
    }

    /** Returns whether Sable currently owns at least one client-side dry-volume mesh. */
    public static boolean hasOcclusionRegions() {
        return !OCCLUSION_REGIONS.isEmpty();
    }

    /** Called by the registered clientbound payload handler on the client thread. */
    public static void accept(final DryCompartmentSnapshotPayload payload) {
        final Snapshot next = new Snapshot(payload.revision(), payload.dryCells());
        final Snapshot previous = SNAPSHOTS.get(payload.carrierId());

        // TCP preserves order, while the revision also protects against a
        // delayed/replayed snapshot supplied by another integration layer.
        if (previous != null && previous.revision() > next.revision()) {
            return;
        }
        final boolean sameContents = previous != null && previous.sameContents(next);
        final boolean registrationReady = next.cells().length == 0
                || OCCLUSION_REGIONS.containsKey(payload.carrierId());
        if (sameContents && registrationReady
                && !PENDING_RENDER_REBUILDS.contains(payload.carrierId())) {
            return;
        }

        SNAPSHOTS.put(payload.carrierId(), next);
        /*
         * The Sable region is a render-mesh container only: ContinuousCompartmentOcclusionRegion
         * rebuilds its geometry every frame from the visual topology cache (room cells), so the
         * dry-cell payload does not change what the mask looks like. Re-registering on every
         * per-tick dry-cell delta (which happens continuously while a room floods or drains)
         * churned the renderer's region set many times per second. Register once, and only
         * remove when the carrier's dry volume disappears.
         */
        if (next.cells().length == 0) {
            removeClientOcclusion(payload.carrierId());
        } else if (!OCCLUSION_REGIONS.containsKey(payload.carrierId())) {
            replaceClientOcclusion(payload.carrierId(), next.cells());
        }
        final Snapshot dirtyPrevious = sameContents ? null : previous;
        final boolean occlusionReady = next.cells().length == 0
                || OCCLUSION_REGIONS.containsKey(payload.carrierId());
        if (markChangedSectionsDirty(payload.carrierId(), dirtyPrevious, next)
                && occlusionReady) {
            PENDING_RENDER_REBUILDS.remove(payload.carrierId());
        } else {
            PENDING_RENDER_REBUILDS.add(payload.carrierId());
        }
    }

    /** Retries packets that raced Sable's client-side sublevel/render-data creation. */
    public static void onClientTick(final ClientTickEvent.Post event) {
        // Decide the active renderer mode before doing any topology work. In the exact fragment
        // path the worker snapshot is unused, so packing every room on every tick was pure cost.
        updateParentSectionDirtying();
        if (MESH_SUPPRESSION_ENABLED) {
            freezeWorldDrySnapshot();
        } else {
            WORLD_DRY = EMPTY_WORLD_DRY;
        }
        for (final UUID carrierId : List.copyOf(PENDING_RENDER_REBUILDS)) {
            final Snapshot snapshot = SNAPSHOTS.get(carrierId);
            if (snapshot == null) {
                PENDING_RENDER_REBUILDS.remove(carrierId);
                continue;
            }
            if (snapshot.cells().length > 0
                    && !OCCLUSION_REGIONS.containsKey(carrierId)) {
                replaceClientOcclusion(carrierId, snapshot.cells());
            }
            final boolean occlusionReady = snapshot.cells().length == 0
                    || OCCLUSION_REGIONS.containsKey(carrierId);
            if (markChangedSectionsDirty(carrierId, null, snapshot)
                    && occlusionReady) {
                PENDING_RENDER_REBUILDS.remove(carrierId);
            }
        }
    }

    /**
     * Resolves a fluid's reserved plot position back to its ClientSubLevel.
     *
     * <p>Sable 2.0.3 adds {@link SubLevelContainerHolder} to
     * {@code RenderChunkRegion}. The supplied {@code pos} is already an
     * absolute plotyard coordinate; applying {@code renderPose()} here would be
     * incorrect.</p>
     */
    public static boolean shouldSuppressWater(final BlockAndTintGetter level,
                                              final BlockPos pos) {
        if (level instanceof final SubLevelContainerHolder holder) {
            final SubLevelContainer container = holder.sable$getPlotContainer();
            if (container != null) {
                final LevelPlot plot = container.getPlot(
                        SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()));
                if (plot != null && plot.getSubLevel() instanceof final ClientSubLevel subLevel) {
                    final Snapshot snapshot = SNAPSHOTS.get(subLevel.getUniqueId());
                    if (snapshot != null && snapshot.contains(pos.asLong())) {
                        return true;
                    }
                }
            }
        }
        /*
         * Parent-world suppression is retained only for the legacy no-shader fallback.  With an
         * Iris pack the exact atlas needs every source water fragment and this query is disabled.
         */
        return isParentWaterBlockSuppressed(pos);
    }

    /**
     * Worker-thread-safe world-space query against the frozen render poses.
     *
     * <p>This path runs only when the fragment depth mask is inactive (legacy mode), where the
     * block-level suppression is the only cutout. The frozen cells therefore contain the complete
     * sealed room volume (not just the dry subset), and a block is removed when its centre maps
     * into that volume -- otherwise a mostly-floating hull leaves only a handful of fully interior
     * blocks and the cutout is invisible.</p>
     */
    public static boolean isParentWaterBlockSuppressed(final BlockPos pos) {
        if (!MESH_SUPPRESSION_ENABLED) {
            return false;
        }
        final WorldDrySnapshot[] snapshots = WORLD_DRY;
        if (snapshots.length == 0) {
            return false;
        }
        final Vector3d world = new Vector3d();
        final Vector3d local = new Vector3d();
        world.set(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        for (final WorldDrySnapshot snapshot : snapshots) {
            snapshot.inversePose().transformPosition(world, local);
            if (Arrays.binarySearch(snapshot.cells(),
                    BlockPos.containing(local.x, local.y, local.z).asLong()) >= 0) {
                final long hit = ++suppressionHitCount;
                if (!suppressionHitLogged) {
                    suppressionHitLogged = true;
                    CreateNautilus.LOGGER.info(
                            "[occlusion] first suppressed parent water block at {}",
                            pos);
                } else if ((hit & 0x3FF) == 0L) {
                    CreateNautilus.LOGGER.info(
                            "[occlusion] suppressed parent water blocks so far: {} (last at {})",
                            hit, pos);
                }
                return true;
            }
        }
        return false;
    }

    /** Rebuilds the immutable worker snapshot from the current client render poses. */
    private static void freezeWorldDrySnapshot() {
        final Minecraft minecraft = Minecraft.getInstance();
        final Map<UUID, PackedTopology> topologies = PACKED_TOPOLOGIES;
        if (!MESH_SUPPRESSION_ENABLED || minecraft.level == null || topologies.isEmpty()) {
            WORLD_DRY = EMPTY_WORLD_DRY;
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            WORLD_DRY = EMPTY_WORLD_DRY;
            return;
        }
        /*
         * Use the complete sealed room topology for suppression. The per-tick dry-cell set
         * deliberately excludes the waterline-crossed cells (for the smooth fragment mask), which
         * would leave the shader-mode cutout with almost nothing to remove.
         */
        final List<WorldDrySnapshot> list = new java.util.ArrayList<>();
        for (final Map.Entry<UUID, PackedTopology> carrierEntry : topologies.entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel subLevel)) {
                continue;
            }
            final Matrix4d pose = subLevel.renderPose().bakeIntoMatrix(new Matrix4d());
            final long[] packed = carrierEntry.getValue().cells();
            if (packed.length == 0) {
                continue;
            }
            list.add(new WorldDrySnapshot(carrierEntry.getKey(),
                    pose.invert(new Matrix4d()), packed));
            if (!worldDryLogged) {
                CreateNautilus.LOGGER.info(
                        "[occlusion] carrier pose translation=({}, {}, {}) sampleCell=({}, {}, {})",
                        pose.m30(), pose.m31(), pose.m32(),
                        BlockPos.getX(packed[0]),
                        BlockPos.getY(packed[0]),
                        BlockPos.getZ(packed[0]));
            }
        }
        WORLD_DRY = list.toArray(new WorldDrySnapshot[0]);
        if (!worldDryLogged && WORLD_DRY.length > 0) {
            worldDryLogged = true;
            long cells = 0L;
            for (final WorldDrySnapshot snapshot : WORLD_DRY) {
                cells += snapshot.cells().length;
            }
            CreateNautilus.LOGGER.info(
                    "[occlusion] world dry snapshot carriers={} totalCells={}",
                    WORLD_DRY.length, cells);
        }
    }

    /** Packs complete visual topology once per carrier revision, never once per render pose. */
    private static Map<UUID, PackedTopology> refreshPackedTopologies() {
        final Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> snapshots =
                ClientCompartmentVisualCache.shaderAtlasSnapshots();
        if (snapshots.isEmpty()) {
            PACKED_TOPOLOGIES = Map.of();
            return PACKED_TOPOLOGIES;
        }
        final Map<UUID, PackedTopology> current = new java.util.LinkedHashMap<>();
        for (final Map.Entry<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> entry
                : snapshots.entrySet()) {
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms = entry.getValue();
            if (rooms.isEmpty()) {
                continue;
            }
            final long revision = rooms.get(0).topologyRevision();
            if (rooms.stream().anyMatch(room -> room.topologyRevision() != revision)) {
                // A mixed revision cannot describe one safe worker snapshot. Fail open for only
                // that carrier until the visual cache publishes a coherent topology.
                continue;
            }
            PackedTopology packed = PACKED_TOPOLOGY_CACHE.get(entry.getKey());
            if (packed == null || packed.revision() != revision) {
                int cellCount = 0;
                for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
                    cellCount = Math.addExact(cellCount, room.cells().size());
                }
                final long[] cells = new long[cellCount];
                int cursor = 0;
                for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
                    for (final BlockPos cell : room.cells()) {
                        cells[cursor++] = cell.asLong();
                    }
                }
                Arrays.sort(cells);
                packed = new PackedTopology(revision, cells);
                PACKED_TOPOLOGY_CACHE.put(entry.getKey(), packed);
            }
            current.put(entry.getKey(), packed);
        }
        PACKED_TOPOLOGY_CACHE.keySet().removeIf(id -> !snapshots.containsKey(id));
        PACKED_TOPOLOGIES = Map.copyOf(current);
        return PACKED_TOPOLOGIES;
    }

    /**
     * Marks parent-world sections dirty exactly when their water mesh must change.
     *
     * <p>Two triggers rebuild the mesh: the carrier's pose moved (union of old/new hull sections),
     * or its complete room topology changed. This is deliberately the same packed topology used
     * by {@link #isParentWaterBlockSuppressed(BlockPos)}; using physical dry cells here missed
     * waterline-crossed and fully flooded room sections.</p>
     */
    private static void updateParentSectionDirtying() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            PARENT_DIRTY_STATE.clear();
            PACKED_TOPOLOGIES = Map.of();
            return;
        }
        final LongSet sectionsToDirty = new LongOpenHashSet();
        final boolean fragmentMaskActive = WaterOcclusionPassBridge.isSupportedAndEnabled()
                || WaterOcclusionPassBridge.isShaderPackActive();
        final boolean meshSuppression = !fragmentMaskActive;
        if (meshSuppression != MESH_SUPPRESSION_ENABLED) {
            if (!meshSuppression) {
                // Restore every parent-water section whose mesh was built with whole-block
                // suppression before discarding the state needed to find it.
                PARENT_DIRTY_STATE.values().forEach(state ->
                        addAllSections(sectionsToDirty, state.sections()));
                PARENT_DIRTY_STATE.clear();
            }
            MESH_SUPPRESSION_ENABLED = meshSuppression;
            CreateNautilus.LOGGER.info(
                    "[occlusion] parent-water mesh suppression {} (fragment mask {})",
                    MESH_SUPPRESSION_ENABLED ? "enabled" : "disabled",
                    fragmentMaskActive ? "active" : "inactive");
        }
        if (!MESH_SUPPRESSION_ENABLED) {
            // Fragment classification follows the moving pose every frame; no chunk remesh is
            // needed and the old coarse pose thresholds would only add visible stutter/work.
            PACKED_TOPOLOGIES = Map.of();
            markParentSectionsDirty(minecraft, sectionsToDirty);
            return;
        }
        final Map<UUID, PackedTopology> topologies = refreshPackedTopologies();
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            PARENT_DIRTY_STATE.values().forEach(state ->
                    addAllSections(sectionsToDirty, state.sections()));
            PARENT_DIRTY_STATE.clear();
            markParentSectionsDirty(minecraft, sectionsToDirty);
            return;
        }

        final Set<UUID> seen = new HashSet<>();
        for (final Map.Entry<UUID, PackedTopology> entry : topologies.entrySet()) {
            final UUID carrierId = entry.getKey();
            final long[] cells = entry.getValue().cells();
            seen.add(carrierId);
            final DirtyState previous = PARENT_DIRTY_STATE.get(carrierId);
            if (!(container.getSubLevel(carrierId) instanceof final ClientSubLevel subLevel)) {
                continue;
            }
            final Matrix4d pose = subLevel.renderPose().bakeIntoMatrix(new Matrix4d());
            final long[] currentSections = worldSections(pose, cells);
            final boolean moved = previous == null || poseChangedEnough(previous, pose);
            if (previous == null || moved || !previous.sameCells(cells)) {
                if (previous != null) {
                    // Restore water where the hull used to be, remove it where it is now.
                    if (moved) {
                        addAllSections(sectionsToDirty, previous.sections());
                        addAllSections(sectionsToDirty, currentSections);
                    } else {
                        // Same pose: only cells that left/entered the dry set changed.
                        addChangedCellSections(sectionsToDirty,
                                previous.cells(), cells);
                    }
                } else {
                    addAllSections(sectionsToDirty, currentSections);
                }
                PARENT_DIRTY_STATE.put(carrierId,
                        new DirtyState(pose.m30(), pose.m31(), pose.m32(),
                                rotationKey(pose), cells, currentSections));
            }
        }
        PARENT_DIRTY_STATE.keySet().removeIf(carrierId -> {
            if (seen.contains(carrierId)) {
                return false;
            }
            final DirtyState removed = PARENT_DIRTY_STATE.remove(carrierId);
            if (removed != null) {
                addAllSections(sectionsToDirty, removed.sections());
            }
            return true;
        });

        markParentSectionsDirty(minecraft, sectionsToDirty);
        if (!sectionsToDirty.isEmpty()) {
            final long now = System.nanoTime();
            if (now - lastParentDirtyLogNanos > 5_000_000_000L) {
                lastParentDirtyLogNanos = now;
                CreateNautilus.LOGGER.info(
                        "[occlusion] parent water sections dirtied: {} "
                                + "(meshSuppression={})",
                        sectionsToDirty.size(), MESH_SUPPRESSION_ENABLED);
            }
        }
    }

    private static void markParentSectionsDirty(final Minecraft minecraft,
                                                final LongSet sections) {
        for (final long section : sections) {
            minecraft.levelRenderer.setSectionDirty(
                    SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
    }

    /** Sections whose dry membership changed between two snapshots. */
    private static void addChangedCellSections(final LongSet output,
                                               final long[] previousCells,
                                               final long[] currentCells) {
        for (final long cell : previousCells) {
            if (Arrays.binarySearch(currentCells, cell) < 0) {
                addSection(output, cell);
            }
        }
        for (final long cell : currentCells) {
            if (Arrays.binarySearch(previousCells, cell) < 0) {
                addSection(output, cell);
            }
        }
    }

    /**
     * Hysteresis against the last rebuild pose. A floating hull continuously crosses block
     * boundaries while bobbing; without a dead zone each crossing rebuilt the parent sections and
     * made the cutout jump back and forth. Rebuilding only once the hull moved a whole block (or
     * rotated roughly seven degrees) from the reference keeps the suppression stable at rest.
     */
    private static boolean poseChangedEnough(final DirtyState previous,
                                             final Matrix4d pose) {
        if (Math.abs(pose.m30() - previous.refX()) >= REBUILD_TRANSLATION_HYSTERESIS
                || Math.abs(pose.m31() - previous.refY()) >= REBUILD_TRANSLATION_HYSTERESIS
                || Math.abs(pose.m32() - previous.refZ()) >= REBUILD_TRANSLATION_HYSTERESIS) {
            return true;
        }
        return previous.rotationKey() != rotationKey(pose);
    }

    private static long rotationKey(final Matrix4d pose) {
        /*
         * Coarse quantization (1/8 of a basis component, about 7 degrees) so small settling
         * oscillations of the floating hull do not churn the parent sections.
         */
        final Vector3d axisX = pose.transformDirection(new Vector3d(1.0D, 0.0D, 0.0D),
                new Vector3d());
        final Vector3d axisY = pose.transformDirection(new Vector3d(0.0D, 1.0D, 0.0D),
                new Vector3d());
        return coarseQuantize(axisX.x) + coarseQuantize(axisX.y) * 17L
                + coarseQuantize(axisX.z) * 289L
                + coarseQuantize(axisY.x) * 4_913L
                + coarseQuantize(axisY.y) * 83_521L
                + coarseQuantize(axisY.z) * 1_419_857L;
    }

    private static long coarseQuantize(final double value) {
        return Math.round(value * 8.0D);
    }

    /** Sections overlapped by the world-space AABB of the dry cells (1-block margin). */
    private static long[] worldSections(final Matrix4d pose, final long[] cells) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final long cell : cells) {
            minX = Math.min(minX, BlockPos.getX(cell));
            minY = Math.min(minY, BlockPos.getY(cell));
            minZ = Math.min(minZ, BlockPos.getZ(cell));
            maxX = Math.max(maxX, BlockPos.getX(cell));
            maxY = Math.max(maxY, BlockPos.getY(cell));
            maxZ = Math.max(maxZ, BlockPos.getZ(cell));
        }
        double worldMinX = Double.POSITIVE_INFINITY;
        double worldMinY = Double.POSITIVE_INFINITY;
        double worldMinZ = Double.POSITIVE_INFINITY;
        double worldMaxX = Double.NEGATIVE_INFINITY;
        double worldMaxY = Double.NEGATIVE_INFINITY;
        double worldMaxZ = Double.NEGATIVE_INFINITY;
        final Vector3d corner = new Vector3d();
        final Vector3d transformed = new Vector3d();
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    pose.transformPosition(corner.set(
                            x == 0 ? minX : maxX + 1,
                            y == 0 ? minY : maxY + 1,
                            z == 0 ? minZ : maxZ + 1), transformed);
                    worldMinX = Math.min(worldMinX, transformed.x);
                    worldMinY = Math.min(worldMinY, transformed.y);
                    worldMinZ = Math.min(worldMinZ, transformed.z);
                    worldMaxX = Math.max(worldMaxX, transformed.x);
                    worldMaxY = Math.max(worldMaxY, transformed.y);
                    worldMaxZ = Math.max(worldMaxZ, transformed.z);
                }
            }
        }
        final LongSet sections = new LongOpenHashSet();
        final int sectionMinX = Math.floorDiv((int) Math.floor(worldMinX - 1.0D), 16);
        final int sectionMinY = Math.floorDiv((int) Math.floor(worldMinY - 1.0D), 16);
        final int sectionMinZ = Math.floorDiv((int) Math.floor(worldMinZ - 1.0D), 16);
        final int sectionMaxX = Math.floorDiv((int) Math.floor(worldMaxX + 1.0D), 16);
        final int sectionMaxY = Math.floorDiv((int) Math.floor(worldMaxY + 1.0D), 16);
        final int sectionMaxZ = Math.floorDiv((int) Math.floor(worldMaxZ + 1.0D), 16);
        for (int x = sectionMinX; x <= sectionMaxX; x++) {
            for (int y = sectionMinY; y <= sectionMaxY; y++) {
                for (int z = sectionMinZ; z <= sectionMaxZ; z++) {
                    sections.add(SectionPos.asLong(x, y, z));
                }
            }
        }
        return sections.toLongArray();
    }

    private static void addAllSections(final LongSet output, final long[] sections) {
        for (final long section : sections) {
            output.add(section);
        }
    }

    /**
     * Tests the camera against the exact client render pose used by Sable's occlusion mesh.
     *
     * <p>Sable 2.0.3 performs its own camera-inside test with {@code logicalPose()}, while its
     * depth volume is drawn with the interpolated {@code renderPose()}.  On a moving or tilted
     * carrier the two poses can straddle a voxel boundary for several frames.  Sable then clears
     * the close-depth texture to one even though the camera is visibly inside a dry cell, making
     * every water fragment fail {@code waterDepth > closeDepth}.  Only carriers whose client
     * occlusion region was actually registered are considered here, so missing packet state
     * continues to fail open instead of hiding unrelated ocean water.</p>
     */
    public static boolean isCameraInsideRegisteredDryRegion(final net.minecraft.world.phys.Vec3
                                                                     worldPosition) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || OCCLUSION_REGIONS.isEmpty()) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }

        for (final UUID carrierId : OCCLUSION_REGIONS.keySet()) {
            final Snapshot snapshot = SNAPSHOTS.get(carrierId);
            if (snapshot == null || !(container.getSubLevel(carrierId)
                    instanceof final ClientSubLevel subLevel)) {
                continue;
            }
            final Vector3d local = subLevel.renderPose().transformPositionInverse(new Vector3d(
                    worldPosition.x, worldPosition.y, worldPosition.z));
            if (containsCameraWithHullMargin(snapshot, local)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Avoids clearing the entire close-depth texture when the interpolated camera lies within
     * floating-point noise of an actual dry-volume boundary. Internal voxel seams remain valid:
     * a near face is accepted whenever the adjacent voxel is also part of the dry compartment.
     */
    private static boolean containsCameraWithHullMargin(final Snapshot snapshot,
                                                        final Vector3d local) {
        final BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
        if (!snapshot.contains(cell.asLong())) {
            return false;
        }

        final double fractionX = local.x - Math.floor(local.x);
        final double fractionY = local.y - Math.floor(local.y);
        final double fractionZ = local.z - Math.floor(local.z);
        return boundaryIsInternal(snapshot, cell, fractionX,
                net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST)
                && boundaryIsInternal(snapshot, cell, fractionY,
                net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.UP)
                && boundaryIsInternal(snapshot, cell, fractionZ,
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH);
    }

    private static boolean boundaryIsInternal(final Snapshot snapshot, final BlockPos cell,
                                              final double fraction,
                                              final net.minecraft.core.Direction negative,
                                              final net.minecraft.core.Direction positive) {
        if (fraction <= CAMERA_BOUNDARY_MARGIN
                && !snapshot.contains(cell.relative(negative).asLong())) {
            return false;
        }
        return fraction < 1.0D - CAMERA_BOUNDARY_MARGIN
                || snapshot.contains(cell.relative(positive).asLong());
    }

    public static void onClientLogout(final ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /**
     * Sable tracking removal does not unload the parent ClientLevel. Drop the old loading
     * lifetime here so a reloaded carrier may restart its network revisions at one. Do not
     * prune missing carriers on tick: their payloads can legitimately precede Sable creation.
     */
    public static void forget(final ClientLevel level, final UUID carrierId) {
        SNAPSHOTS.remove(carrierId);
        removeClientOcclusion(carrierId);
        PENDING_RENDER_REBUILDS.remove(carrierId);
        PACKED_TOPOLOGY_CACHE.remove(carrierId);
        final Map<UUID, PackedTopology> retained = new java.util.LinkedHashMap<>(PACKED_TOPOLOGIES);
        retained.remove(carrierId);
        PACKED_TOPOLOGIES = Map.copyOf(retained);
        WORLD_DRY = Arrays.stream(WORLD_DRY)
                .filter(snapshot -> !snapshot.carrierId().equals(carrierId))
                .toArray(WorldDrySnapshot[]::new);
        ClientCompartmentVisualCache.forget(carrierId);
        ApertureMediumMaskRenderer.forgetCarrier(level, carrierId);

        final DirtyState dirty = PARENT_DIRTY_STATE.remove(carrierId);
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) {
            final LongSet sections = new LongOpenHashSet();
            if (dirty != null) addAllSections(sections, dirty.sections());
            markParentSectionsDirty(minecraft, sections);
        }
    }

    public static void onClientLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            clear();
        }
    }

    public static void clear() {
        ShaderVoxelMaskAtlas.release();
        OCCLUSION_REGIONS.values().forEach(registration ->
                registration.container().removeRegion(registration.region()));
        OCCLUSION_REGIONS.clear();
        SNAPSHOTS.clear();
        WORLD_DRY = EMPTY_WORLD_DRY;
        PACKED_TOPOLOGIES = Map.of();
        PACKED_TOPOLOGY_CACHE.clear();
        PARENT_DIRTY_STATE.clear();
        PENDING_RENDER_REBUILDS.clear();
        ClientCompartmentVisualCache.clear();
    }

    /** Mirrors the server's physical dry volume into Sable's client container. */
    private static void replaceClientOcclusion(final UUID carrierId, final long[] cells) {
        final OcclusionRegistration old = OCCLUSION_REGIONS.remove(carrierId);
        if (old != null) {
            old.container().removeRegion(old.region());
        }

        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || cells.length == 0) {
            return;
        }

        /*
         * Sable 2.0.3 aligns WaterOcclusionRenderer with the current ClientLevel at the start of
         * GameRenderer.render().  A Nautilus snapshot can arrive before that first frame.  If we
         * add the region immediately, Sable's first update subsequently frees the render mesh but
         * leaves both ClientWaterOcclusionContainer and OCCLUSION_REGIONS believing it still
         * exists.  Periodic identical snapshots then never repair the lost mesh.
         *
         * update() is public and idempotent once the level matches.  Calling it before addRegion
         * completes the world-generation hand-off first, so the new region cannot be erased on
         * the following frame.  This is a lifecycle repair only; compartment cells and physics
         * are unchanged.
         */
        SableClient.WATER_OCCLUSION_RENDERER.update();
        final WaterOcclusionContainer<?> container = WaterOcclusionContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final List<BlockPos> positions = Arrays.stream(cells)
                .mapToObj(BlockPos::of)
                .toList();
        final BoundedBitVolume3i volume = BoundedBitVolume3i.fromBlocks(positions);
        if (volume != null) {
            final WaterOcclusionRegion region = container.addRegion(volume);
            /*
             * Keep Sable's logical integer volume for lifecycle/camera queries, but replace its
             * render-only cube mesh with Nautilus' continuously clipped dry volume. This removes
             * the one-block transparent band where an Attachment water surface crosses a voxel.
             */
            ContinuousCompartmentOcclusionRegion.install(carrierId, region);
            OCCLUSION_REGIONS.put(carrierId, new OcclusionRegistration(container, region));
            CreateNautilus.LOGGER.info(
                    "Registered client dry-compartment occlusion for {} "
                            + "(cells={}, clientRegions={})",
                    carrierId, cells.length, container.getRegions().size());
        }
    }

    private static boolean markChangedSectionsDirty(final UUID carrierId,
                                                    final Snapshot previous,
                                                    final Snapshot next) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }

        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null
                || !(container.getSubLevel(carrierId) instanceof final ClientSubLevel subLevel)
                || subLevel.getRenderData() == null) {
            // If Sable's start-tracking packet has not arrived yet, its initial
            // render-data build will consult the already-published cache.
            return false;
        }

        /*
         * Only sections whose dry membership actually changed need a mesh rebuild. The previous
         * union-based marking rebuilt every section of the hull on every dry-cell sync, which
         * happened several times per second while a room was flooding and visibly churned the
         * carrier's own meshes. A symmetric difference touches only the waterline band.
         */
        final LongSet changedSections = new LongOpenHashSet();
        if (previous != null) {
            for (final long cell : previous.cells()) {
                if (!next.contains(cell)) {
                    addSection(changedSections, cell);
                }
            }
        }
        for (final long cell : next.cells()) {
            if (previous == null || !previous.contains(cell)) {
                addSection(changedSections, cell);
            }
        }

        for (final long packedSection : changedSections) {
            subLevel.getRenderData().setDirty(
                    SectionPos.x(packedSection),
                    SectionPos.y(packedSection),
                    SectionPos.z(packedSection),
                    false);
        }
        return true;
    }

    private static void removeClientOcclusion(final UUID carrierId) {
        final OcclusionRegistration old = OCCLUSION_REGIONS.remove(carrierId);
        if (old != null) {
            old.container().removeRegion(old.region());
        }
    }

    private static void addSections(final LongSet output, final long[] cells) {
        for (final long cell : cells) {
            addSection(output, cell);
        }
    }

    private static void addSection(final LongSet output, final long cell) {
        output.add(SectionPos.asLong(
                SectionPos.blockToSectionCoord(BlockPos.getX(cell)),
                SectionPos.blockToSectionCoord(BlockPos.getY(cell)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(cell))));
    }

    private record Snapshot(long revision, long[] cells) {
        private Snapshot {
            cells = cells.clone();
            // Payload construction already sorts, but preserving this invariant
            // locally makes binary-search safety independent of packet callers.
            Arrays.sort(cells);
        }

        private boolean contains(final long packedPos) {
            return Arrays.binarySearch(cells, packedPos) >= 0;
        }

        private boolean sameContents(final Snapshot other) {
            /*
             * The Sable region contains only dry-cell geometry. A newer server revision with the
             * same cells changes water fraction/pressure metadata, not this mesh. Replacing the
             * region in that case leaves one frame where close/far depth textures describe
             * different generations of the room and appears as striped ocean cut-outs.
             */
            return Arrays.equals(cells, other.cells);
        }
    }


    private record OcclusionRegistration(WaterOcclusionContainer<?> container,
                                         WaterOcclusionRegion region) {
    }

    /** Immutable pose + cached topology array handed to chunk-mesh worker threads. */
    private record WorldDrySnapshot(UUID carrierId, Matrix4d inversePose, long[] cells) {
        private WorldDrySnapshot {
            inversePose = new Matrix4d(inversePose);
        }
    }

    /** Array ownership stays private and immutable after its one revision-time sort. */
    private record PackedTopology(long revision, long[] cells) {
    }

    private record DirtyState(double refX, double refY, double refZ,
                              long rotationKey, long[] cells, long[] sections) {
        private DirtyState {
            cells = cells.clone();
            sections = sections.clone();
        }

        private boolean sameCells(final long[] other) {
            return Arrays.equals(cells, other);
        }
    }
}
