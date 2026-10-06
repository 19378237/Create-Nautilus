package com.create.nautilus.client.render;

import com.create.nautilus.physics.CompartmentVoxelSemantics;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, carrier-local optical geometry and its small per-frame water-plane table.
 *
 * <p>This cache performs no GL work. A topology generation owns real baked aperture triangles,
 * except that a provider-confirmed full collision block with complete baked boundary faces may
 * use exact unit faces at those same outer planes. Current room levels, carrier pose and nearby
 * real-fluid planes are sampled afresh by {@link #prepare(ClientLevel, double, double, double)}.
 * Consequently a renderer can upload the cached batches only when topology changes while
 * replacing the event table every frame.</p>
 *
 * <p>A semantic boundary is deliberately strict. The room's six-neighbour search supplies trusted
 * optical seeds; locally connected membranes may complete a bounded two-block room wrap. Full
 * collision blocks can use synthetic unit faces, while partial/custom membranes retain only
 * reliable baked exterior triangles. Every emitted face must point toward a passable exterior
 * cell. An opening, opaque membrane, unloaded state or a membrane beyond that bounded wrap emits
 * no geometry and therefore leaves the shader pack's native result untouched.</p>
 */
final class ApertureEventCache {
    /** Six signed-direction presence bits follow the first dense event slot for one optical voxel. */
    static final int DIRECTION_SLOT_GROUP_SIZE = 64;
    static final int VERTEX_STRIDE = 8;

    private static final double ROOM_EPSILON = WaterMediumClassifier.SURFACE_EPSILON;
    private static final double FLAT_PLANE_EPSILON = 1.0E-6D;
    private static final int CONNECTED_MEMBRANE_RADIUS = 2;
    private static final int MAX_EVENTS_PER_CARRIER = 32_768;
    private static final int MAX_VERTEX_FLOATS_PER_CARRIER = 2_000_000;

    private static final Plane AIR = new Plane(0.0D, 0.0D, 0.0D, 1.0D);
    private static final Plane WATER = new Plane(0.0D, 0.0D, 0.0D, -1.0D);
    private static final Map<CacheKey, CachedCarrier> CACHE = new HashMap<>();
    private static long generation = 1L;
    private static long nextGeometryGeneration = 1L;
    private static long parentFluidEpoch = 1L;

    private ApertureEventCache() {
    }

    /**
     * Supplies topology-only optical slots while the exact voxel atlas is being rebuilt.  This
     * path never samples room water or exterior fluids, so it cannot recurse into the atlas frame
     * solve.  The normal per-frame {@link #prepare} call reuses the same cached geometry later.
     */
    static synchronized OpticalSlots opticalSlots(
            final ClientLevel parentLevel,
            final UUID carrierId,
            final ClientSubLevel carrier,
            final long topologyRevision,
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms) {
        if (parentLevel == null || carrierId == null || carrier == null
                || rooms == null || rooms.isEmpty()) return OpticalSlots.EMPTY;
        final long resourceGeneration = OpticalApertureProvider.resourceGeneration();
        final CacheKey key = new CacheKey(parentLevel, carrierId);
        CachedCarrier cached = CACHE.get(key);
        if (cached == null || cached.carrier() != carrier
                || cached.carrierLevel() != carrier.getLevel()
                || cached.topologyRevision() != topologyRevision
                || cached.resourceGeneration() != resourceGeneration) {
            final RoomIndex roomIndex = indexRooms(rooms);
            if (!roomIndex.complete()) return OpticalSlots.EMPTY;
            final long signature = topologySignature(rooms);
            if (cached != null) OpticalApertureProvider.invalidateCarrier(cached.carrier());
            cached = buildCarrier(carrier, topologyRevision, signature,
                    resourceGeneration, rooms, roomIndex);
            CACHE.put(key, cached);
            bumpGeneration();
        }
        return new OpticalSlots(cached.voxelSlots(), cached.boundaries().size(),
                cached.geometryGeneration());
    }

    /**
     * Freezes one complete frame.  Missing topology, carrier poses or model/fluid semantics fail
     * open per carrier/event; a partially known carrier is never manufactured from room bounds.
     */
    static synchronized Frame prepare(final ClientLevel parentLevel,
                                      final double cameraX,
                                      final double cameraY,
                                      final double cameraZ) {
        Objects.requireNonNull(parentLevel, "parentLevel");
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ)) {
            return new Frame(parentLevel, generation, cameraX, cameraY, cameraZ,
                    false, false, List.of());
        }
        final ClientCompartmentVisualCache.CameraWaterState cameraState =
                ClientCompartmentVisualCache.cameraWaterState(
                        new Vec3(cameraX, cameraY, cameraZ));

        final SubLevelContainer container = SubLevelContainer.getContainer(parentLevel);
        final Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> snapshots =
                ClientCompartmentVisualCache.shaderAtlasSnapshots();
        if (container == null || snapshots.isEmpty()) {
            final boolean removed = removeStale(parentLevel, Set.of());
            if (removed) bumpGeneration();
            return new Frame(parentLevel, generation, cameraX, cameraY, cameraZ,
                    cameraStartsInWater(parentLevel, cameraX, cameraY, cameraZ, cameraState),
                    removed, List.of());
        }

        final List<Map.Entry<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>>> ordered =
                new ArrayList<>(snapshots.entrySet());
        ordered.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        final List<PreparedCarrier> prepared = new ArrayList<>();
        final Set<UUID> currentIds = new HashSet<>();
        boolean rebuilt = false;

        for (final Map.Entry<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>> entry
                : ordered) {
            if (entry.getValue().isEmpty()
                    || !(container.getSubLevel(entry.getKey())
                    instanceof final ClientSubLevel carrier)) {
                continue;
            }
            final Matrix4d pose = carrier.renderPose().bakeIntoMatrix(new Matrix4d());
            if (!pose.isFinite() || Math.abs(pose.determinant()) <= 1.0E-12D) {
                continue;
            }
            final Matrix4d inversePose = new Matrix4d(pose).invert();
            if (!inversePose.isFinite()) {
                continue;
            }

            final long revision = entry.getValue().stream()
                    .mapToLong(ClientCompartmentVisualCache.RoomSnapshot::topologyRevision)
                    .max().orElse(0L);
            final long resourceGeneration = OpticalApertureProvider.resourceGeneration();
            final CacheKey key = new CacheKey(parentLevel, entry.getKey());
            CachedCarrier cached = CACHE.get(key);
            if (cached == null || cached.carrier() != carrier
                    || cached.carrierLevel() != carrier.getLevel()
                    || cached.topologyRevision() != revision
                    || cached.resourceGeneration() != resourceGeneration) {
                // The synchronized topology revision is authoritative.  Only materialize and
                // hash the complete voxel set when that revision (or the model generation)
                // actually changes; doing both on every frame was one of the old LOW-frame
                // spikes on large compartments.
                final RoomIndex rooms = indexRooms(entry.getValue());
                if (!rooms.complete()) continue;
                final long signature = topologySignature(entry.getValue());
                if (cached != null) {
                    OpticalApertureProvider.invalidateCarrier(cached.carrier());
                }
                cached = buildCarrier(carrier, revision, signature,
                        resourceGeneration, entry.getValue(), rooms);
                CACHE.put(key, cached);
                rebuilt = true;
            }
            currentIds.add(entry.getKey());
            if (cached.boundaries().isEmpty() || cached.batches().isEmpty()) {
                continue;
            }

            final Map<UUID, RoomRuntime> runtimeRooms = runtimeRooms(
                    entry.getKey(), entry.getValue(), pose);
            final List<Event> events = new ArrayList<>(cached.boundaries().size());
            for (final CachedBoundary boundary : cached.boundaries()) {
                final MediumPlane negative = mediumPlane(parentLevel, carrier, pose,
                        cached, runtimeRooms, boundary.negative());
                final MediumPlane positive = mediumPlane(parentLevel, carrier, pose,
                        cached, runtimeRooms, boundary.positive());
                final boolean valid = negative.valid() && positive.valid();
                final UUID boundaryRoom = boundaryRoom(boundary);
                final RoomRuntime boundaryRuntime = boundaryRoom == null
                        ? null : runtimeRooms.get(boundaryRoom);
                /*
                 * Once a room contains virtual water, its own water surface is responsible for
                 * the room-side fog. Keep the optical event as an exterior-water shell instead of
                 * letting a room-water/exterior-water equality erase the glass boundary. This is
                 * camera-independent: an inside ray consumes the ocean suffix and an outside ray
                 * consumes the ocean prefix.
                 */
                final boolean floodedExteriorShell = boundaryRuntime != null
                        && Double.isFinite(boundaryRuntime.fraction())
                        && boundaryRuntime.fraction() > ROOM_EPSILON;
                events.add(new Event(boundary.slot(), boundary.opticalCell(), boundary.axis(),
                        boundary.outwardDirection(), floodedExteriorShell,
                        new PlanePair(negative.plane(), positive.plane()), valid));
            }

            prepared.add(new PreparedCarrier(cached, pose, inversePose, events,
                    runtimeRooms));
        }

        final boolean opticalLayoutChanged = removeStale(parentLevel, currentIds) || rebuilt;
        if (opticalLayoutChanged) {
            bumpGeneration();
        }
        final List<CarrierFrame> carriers = prepared.stream()
                .map(value -> new CarrierFrame(
                        value.cached().id(), value.cached().carrier(),
                         value.pose(), value.inversePose(),
                         value.cached().topologyRevision(),
                         value.cached().geometryGeneration(),
                         value.cached().voxelOriginX(), value.cached().voxelOriginY(),
                        value.cached().voxelOriginZ(), value.events(),
                        value.cached().batches(), value.cached().localBounds(),
                        value.cached().apertureBounds(),
                        value.cached().apertureCellBounds(),
                        value.cached().voxelSlots()))
                .toList();
        final boolean cameraStartsInWater = cameraStartsInWater(
                parentLevel, cameraX, cameraY, cameraZ, cameraState);
        return new Frame(parentLevel, generation, cameraX, cameraY, cameraZ,
                cameraStartsInWater, opticalLayoutChanged, carriers);
    }

    /** Invalidates model-dependent geometry around one committed client block/fluid change. */
    static synchronized Invalidation notifyBlockChanged(final ClientLevel changedLevel,
                                                        final BlockPos changedPosition) {
        if (changedLevel == null || changedPosition == null) {
            invalidateAll();
            return Invalidation.GEOMETRY;
        }
        boolean removed = false;
        boolean fluidInvalidated = false;
        final var iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<CacheKey, CachedCarrier> entry = iterator.next();
            if (entry.getKey().level() != changedLevel) {
                continue;
            }
            final CachedCarrier cached = entry.getValue();
            if (!cached.geometryWatchCells().contains(changedPosition.asLong())) {
                fluidInvalidated |= invalidatePlotSamples(cached, changedPosition);
                fluidInvalidated |= invalidateExteriorSamples(cached, changedPosition);
                continue;
            }
            OpticalApertureProvider.invalidateAround(cached.carrier(), changedPosition);
            iterator.remove();
            removed = true;
        }
        // Only an exterior sample whose real 3x3 fluid-height neighbourhood changed is dirtied.
        // Bumping one level-wide epoch for every ordinary block update made all windows resample
        // together and caused avoidable LOW-frame spikes in active worlds.
        if (removed || fluidInvalidated) {
            bumpGeneration();
        }
        return new Invalidation(removed || fluidInvalidated, removed);
    }

    static synchronized Invalidation notifyChunkChanged(final ClientLevel changedLevel,
                                                        final int chunkX,
                                                        final int chunkZ) {
        if (changedLevel == null) return Invalidation.NONE;
        boolean removed = false;
        boolean fluidInvalidated = false;
        final var iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<CacheKey, CachedCarrier> entry = iterator.next();
            if (entry.getKey().level() != changedLevel) {
                continue;
            }
            final CachedCarrier cached = entry.getValue();
            if (!watchCellsIntersectChunk(cached.geometryWatchCells(), chunkX, chunkZ)) {
                fluidInvalidated |= invalidatePlotSamples(cached, chunkX, chunkZ);
                fluidInvalidated |= invalidateExteriorSamples(cached, chunkX, chunkZ);
                continue;
            }
            OpticalApertureProvider.invalidateCarrier(cached.carrier());
            iterator.remove();
            removed = true;
        }
        if (removed || fluidInvalidated) {
            bumpGeneration();
        }
        return new Invalidation(removed || fluidInvalidated, removed);
    }

    static Invalidation onClientChunkLoad(final ChunkEvent.Load event) {
        if (event.getLevel() instanceof final ClientLevel level) {
            return notifyChunkChanged(level, event.getChunk().getPos().x,
                    event.getChunk().getPos().z);
        }
        return Invalidation.NONE;
    }

    static Invalidation onClientChunkUnload(final ChunkEvent.Unload event) {
        if (event.getLevel() instanceof final ClientLevel level) {
            return notifyChunkChanged(level, event.getChunk().getPos().x,
                    event.getChunk().getPos().z);
        }
        return Invalidation.NONE;
    }

    static void onClientLogout(final ClientPlayerNetworkEvent.LoggingOut event) {
        invalidateAll();
    }

    static synchronized Invalidation onClientLevelUnload(final LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof final ClientLevel level)) {
            return Invalidation.NONE;
        }
        boolean removed = false;
        final var iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<CacheKey, CachedCarrier> entry = iterator.next();
            if (entry.getKey().level() != level
                    && entry.getValue().carrierLevel() != level) {
                continue;
            }
            OpticalApertureProvider.invalidateCarrier(entry.getValue().carrier());
            iterator.remove();
            removed = true;
        }
        if (removed) bumpGeneration();
        return removed ? Invalidation.GEOMETRY : Invalidation.NONE;
    }

    static synchronized void invalidateCarrier(final ClientLevel parentLevel,
                                               final UUID carrierId) {
        if (parentLevel == null || carrierId == null) return;
        final CachedCarrier removed = CACHE.remove(new CacheKey(parentLevel, carrierId));
        if (removed != null) {
            OpticalApertureProvider.invalidateCarrier(removed.carrier());
            bumpGeneration();
        }
    }

    static synchronized void invalidateResources() {
        OpticalApertureProvider.invalidateResources();
        CACHE.clear();
        bumpParentFluidEpoch();
        bumpGeneration();
    }

    static synchronized void invalidateAll() {
        for (final CachedCarrier carrier : CACHE.values()) {
            OpticalApertureProvider.invalidateCarrier(carrier.carrier());
        }
        CACHE.clear();
        bumpGeneration();
    }

    private static CachedCarrier buildCarrier(
            final ClientSubLevel carrier,
            final long topologyRevision,
            final long topologySignature,
            final long resourceGeneration,
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms,
            final RoomIndex roomIndex) {
        final var plotBounds = carrier.getPlot().getBoundingBox();
        if (plotBounds == null || plotBounds.volume() <= 0) {
            return CachedCarrier.empty(carrier, topologyRevision,
                    topologySignature, resourceGeneration);
        }
        final ClientLevel level = carrier.getLevel();
        final Map<BoundaryKey, BoundaryCandidate> accepted = new LinkedHashMap<>();
        final Set<BoundaryKey> rejected = new HashSet<>();
        /*
         * Keep provider-confirmed room-adjacent membranes independently of terminal boundary
         * events.  A two-block glass wrap has no direct room-to-exterior event at its inner
         * layer, so deriving BFS seeds from accepted events makes the whole wrap undiscoverable.
         */
        final Map<UUID, Map<Long, Direction>> roomMembraneSeeds = new LinkedHashMap<>();
        final Set<Long> geometryWatchCells = new LongOpenHashSet();
        final Set<Long> plotFluidWatchCells = new LongOpenHashSet();

        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            for (final BlockPos interior : room.cells()) {
                if (roomIndex.ambiguousCells().contains(interior.asLong())) continue;
                for (final Direction direction : Direction.values()) {
                    final BlockPos optical = interior.relative(direction);
                    if (roomIndex.owners().containsKey(optical.asLong())
                            || !insidePlot(plotBounds, optical)) {
                        continue;
                    }
                    // A new pane, a removed pane, connected-texture neighbours and ModelData can
                    // all change the real baked aperture even when no aperture currently exists.
                    // Retain this small topology-derived 3x3x3 watch set so unrelated plot/world
                    // updates never evict the carrier's persistent VBO.
                    addWatchNeighborhood(geometryWatchCells, optical);
                    if (!loaded(level, optical)) {
                        continue;
                    }
                    final BlockState opticalState = safeState(level, optical);
                    if (opticalState == null
                            || !isSolidMembrane(level, optical, opticalState)
                            || opticalState.getFluidState().is(FluidTags.WATER)) {
                        continue;
                    }
                    final OpticalApertureProvider.Extraction extraction;
                    try {
                        extraction = OpticalApertureProvider.extract(
                                carrier, optical, direction, opticalState);
                    } catch (final RuntimeException | LinkageError ignored) {
                        continue;
                    }
                    if (extraction.disposition()
                            != OpticalApertureProvider.Disposition.APERTURE
                            || extraction.batches().isEmpty()) {
                        continue;
                    }

                    final BoundaryKey key = new BoundaryKey(
                            optical.asLong(), direction);
                    if (rejected.contains(key)) continue;
                    final Direction negative = negativeDirection(direction.getAxis());
                    final Direction positive = negative.getOpposite();
                    final MediumRef negativeMedium = mediumFor(
                            roomIndex, optical.relative(negative), negative);
                    final MediumRef positiveMedium = mediumFor(
                            roomIndex, optical.relative(positive), positive);
                    if (negativeMedium == UnknownMedium.INSTANCE
                            || positiveMedium == UnknownMedium.INSTANCE) {
                        accepted.remove(key);
                        rejected.add(key);
                        continue;
                    }
                    // Establish the trusted room/exterior medium pair before the terminal-face
                    // test. The exterior cell may itself be a second glass layer: it blocks a
                    // direct event but must not prevent this inner membrane from seeding BFS.
                    final boolean roomOnNegative = negativeMedium instanceof RoomMedium roomMedium
                            && roomMedium.roomId().equals(room.id())
                            && positiveMedium instanceof ExteriorMedium;
                    final boolean roomOnPositive = positiveMedium instanceof RoomMedium roomMedium
                            && roomMedium.roomId().equals(room.id())
                            && negativeMedium instanceof ExteriorMedium;
                    if (roomOnNegative || roomOnPositive) {
                        roomMembraneSeeds
                                .computeIfAbsent(room.id(), ignored -> new LinkedHashMap<>())
                                .putIfAbsent(optical.asLong(), direction);
                    }

                    // The direct path emits only a terminal room-to-exterior face. The bounded
                    // connected-wrap pass below can still reach a second optical layer from its
                    // neighbouring trusted seeds; retaining this blocked face would create an
                    // internal entry inside the glass wrap.
                    final BlockPos exterior = optical.relative(direction);
                    if (insidePlot(plotBounds, exterior)) {
                        if (!loaded(level, exterior)) continue;
                        final BlockState exteriorState = safeState(level, exterior);
                        if (exteriorState == null
                                || isSolidMembrane(level, exterior, exteriorState)) {
                            continue;
                        }
                    }

                    final BoundaryCandidate candidate = new BoundaryCandidate(
                            optical.immutable(), direction.getAxis(), direction,
                            negativeMedium, positiveMedium, extraction.batches(),
                            extraction.fullCollisionBlock());
                    final BoundaryCandidate previous = accepted.putIfAbsent(key, candidate);
                    if (previous != null && !previous.compatibleWith(candidate)) {
                        accepted.remove(key);
                        rejected.add(key);
                    }
                    // stableWaterAtOrBelow() reads the side cell, the cell below it, the four
                    // horizontal neighbours/diagonals used by the vanilla corner-height rule,
                    // and their above cells.  Watch that complete plot-local dependency set so a
                    // flowing/source transition invalidates only the tiny medium descriptor and
                    // never waits for an unrelated geometry update.
                    addFluidWatchNeighborhood(plotFluidWatchCells,
                            optical.relative(negative));
                    addFluidWatchNeighborhood(plotFluidWatchCells,
                            optical.relative(positive));
                }
            }
        }

        addConnectedMembraneWrapCandidates(carrier, plotBounds, roomIndex,
                roomMembraneSeeds,
                accepted, rejected, geometryWatchCells, plotFluidWatchCells);

        final List<BoundaryCandidate> renderableBoundaries = accepted.values().stream()
                .filter(ApertureEventCache::hasRenderableGeometry)
                .toList();
        if (renderableBoundaries.isEmpty()) {
            return CachedCarrier.empty(carrier, topologyRevision,
                    topologySignature, resourceGeneration, geometryWatchCells,
                    plotFluidWatchCells);
        }
        if (renderableBoundaries.size() > MAX_EVENTS_PER_CARRIER) {
            return CachedCarrier.empty(carrier, topologyRevision,
                    topologySignature, resourceGeneration, geometryWatchCells,
                    plotFluidWatchCells);
        }

        int originX = Integer.MAX_VALUE;
        int originY = Integer.MAX_VALUE;
        int originZ = Integer.MAX_VALUE;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        double apertureMinX = Double.POSITIVE_INFINITY;
        double apertureMinY = Double.POSITIVE_INFINITY;
        double apertureMinZ = Double.POSITIVE_INFINITY;
        double apertureMaxX = Double.NEGATIVE_INFINITY;
        double apertureMaxY = Double.NEGATIVE_INFINITY;
        double apertureMaxZ = Double.NEGATIVE_INFINITY;
        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            for (final BlockPos cell : room.cells()) {
                originX = Math.min(originX, cell.getX());
                originY = Math.min(originY, cell.getY());
                originZ = Math.min(originZ, cell.getZ());
                minX = Math.min(minX, cell.getX());
                minY = Math.min(minY, cell.getY());
                minZ = Math.min(minZ, cell.getZ());
                maxX = Math.max(maxX, cell.getX() + 1.0D);
                maxY = Math.max(maxY, cell.getY() + 1.0D);
                maxZ = Math.max(maxZ, cell.getZ() + 1.0D);
            }
        }

        final Map<BatchKey, FloatMesh> meshes = new LinkedHashMap<>();
        final Map<Long, Integer> voxelSlots = new HashMap<>();
        final List<CachedBoundary> boundaries = new ArrayList<>(renderableBoundaries.size());
        final List<AABB> apertureCellBounds = new ArrayList<>(renderableBoundaries.size());
        /*
         * One optical block can expose both signs of one axis as well as multiple axes at an edge
         * or corner. Keep those events dense and ordered X-/X+/Y-/Y+/Z-/Z+, then store one exact
         * descriptor in the voxel atlas: firstSlot * 64 + directionBits. Collapsing opposite
         * signs into one axis slot removes both faces of one-block-wide beams and dividers.
         */
        final Map<Long, List<BoundaryCandidate>> boundariesByCell = new LinkedHashMap<>();
        for (final BoundaryCandidate boundary : renderableBoundaries) {
            boundariesByCell.computeIfAbsent(boundary.opticalCell().asLong(),
                    ignored -> new ArrayList<>()).add(boundary);
        }
        final List<BoundaryCandidate> orderedBoundaries = new ArrayList<>(
                renderableBoundaries.size());
        int groupedSlot = 0;
        for (final Map.Entry<Long, List<BoundaryCandidate>> entry
                : boundariesByCell.entrySet()) {
            final List<BoundaryCandidate> cellBoundaries = entry.getValue();
            cellBoundaries.sort(Comparator.comparingInt(
                    boundary -> directionCode(
                            boundary.axis(), boundary.outwardDirection())));
            int directionBits = 0;
            for (final BoundaryCandidate boundary : cellBoundaries) {
                directionBits |= 1 << (directionCode(
                        boundary.axis(), boundary.outwardDirection()) - 1);
            }
            voxelSlots.put(entry.getKey(),
                    groupedSlot * DIRECTION_SLOT_GROUP_SIZE + directionBits);
            orderedBoundaries.addAll(cellBoundaries);
            groupedSlot += cellBoundaries.size();
        }
        int slot = 0;
        for (final BoundaryCandidate boundary : orderedBoundaries) {
            final int eventSlot = slot++;
            boundaries.add(new CachedBoundary(eventSlot, boundary.opticalCell(),
                    boundary.axis(), boundary.outwardDirection(),
                    boundary.negative(), boundary.positive()));
            apertureCellBounds.add(new AABB(
                    boundary.opticalCell()).inflate(1.0E-4D));
            /*
             * A full collision optical block has one synthetic unit face on the known exterior
             * side.  Emitting the opposite face would recreate a join inside a connected glass
             * shell. Panes, slopes and custom partial collision shapes retain their baked exterior
             * surface below.
             */
            final boolean outwardPositive = boundary.outwardDirection().getAxisDirection()
                    == Direction.AxisDirection.POSITIVE;
            final OpticalApertureProvider.AtlasBatch fullFaceBatch =
                    boundary.fullCollisionBlock() ? opticalAxisBatch(boundary) : null;
            if (fullFaceBatch != null) {
                appendFullCellFace(meshes, fullFaceBatch, boundary.opticalCell(),
                        boundary.axis(), boundary.outwardDirection(), outwardPositive,
                        eventSlot, originX, originY, originZ);
                final double placedCellX = boundary.opticalCell().getX();
                final double placedCellY = boundary.opticalCell().getY();
                final double placedCellZ = boundary.opticalCell().getZ();
                minX = Math.min(minX, placedCellX);
                minY = Math.min(minY, placedCellY);
                minZ = Math.min(minZ, placedCellZ);
                maxX = Math.max(maxX, placedCellX + 1.0D);
                maxY = Math.max(maxY, placedCellY + 1.0D);
                maxZ = Math.max(maxZ, placedCellZ + 1.0D);
                apertureMinX = Math.min(apertureMinX, placedCellX);
                apertureMinY = Math.min(apertureMinY, placedCellY);
                apertureMinZ = Math.min(apertureMinZ, placedCellZ);
                apertureMaxX = Math.max(apertureMaxX, placedCellX + 1.0D);
                apertureMaxY = Math.max(apertureMaxY, placedCellY + 1.0D);
                apertureMaxZ = Math.max(apertureMaxZ, placedCellZ + 1.0D);
            }
            // Thin/custom optical blocks keep only their outermost baked exterior surface.
            final double outwardFace = extremeFaceCoordinate(boundary, outwardPositive);
            for (final OpticalApertureProvider.AtlasBatch source : boundary.batches()) {
                final FloatMesh mesh = meshes.computeIfAbsent(
                        new BatchKey(source.atlas(), source.alphaCutoff(),
                                directionCode(boundary.axis(), boundary.outwardDirection())),
                        ignored -> new FloatMesh());
                for (final OpticalApertureProvider.TexturedTriangle triangle
                        : source.triangles()) {
                    if (triangle.modelNormal().getAxis() != boundary.axis()) continue;
                    final boolean positiveFaceNormal = triangle.modelNormal()
                            .getAxisDirection() == Direction.AxisDirection.POSITIVE;
                    if (positiveFaceNormal != outwardPositive || fullFaceBatch != null) continue;
                    final double faceCoordinate = triangleCoordinate(triangle, boundary.axis());
                    if (!Double.isFinite(outwardFace) || !Double.isFinite(faceCoordinate)
                            || Math.abs(faceCoordinate - outwardFace) > 1.0E-6D) continue;
                    final float faceSign = positiveFaceNormal ? 1.0F : -1.0F;
                    final float outwardSign = outwardPositive ? 1.0F : -1.0F;
                    for (final OpticalApertureProvider.ApertureVertex vertex
                            : List.of(triangle.first(), triangle.second(), triangle.third())) {
                        // Plot coordinates can be millions of blocks from zero. Store only
                        // small carrier-relative values in the VBO; the double-precision
                        // pose/origin composition is performed once per carrier before draw.
                        final double placedX = vertex.x();
                        final double placedY = vertex.y();
                        final double placedZ = vertex.z();
                        mesh.add((float) (placedX - originX));
                        mesh.add((float) (placedY - originY));
                        mesh.add((float) (placedZ - originZ));
                        mesh.add(vertex.u());
                        mesh.add(vertex.v());
                        mesh.add((float) eventSlot);
                        mesh.add(faceSign);
                        mesh.add(outwardSign);
                        minX = Math.min(minX, placedX);
                        minY = Math.min(minY, placedY);
                        minZ = Math.min(minZ, placedZ);
                        maxX = Math.max(maxX, placedX);
                        maxY = Math.max(maxY, placedY);
                        maxZ = Math.max(maxZ, placedZ);
                        apertureMinX = Math.min(apertureMinX, placedX);
                        apertureMinY = Math.min(apertureMinY, placedY);
                        apertureMinZ = Math.min(apertureMinZ, placedZ);
                        apertureMaxX = Math.max(apertureMaxX, placedX);
                        apertureMaxY = Math.max(apertureMaxY, placedY);
                        apertureMaxZ = Math.max(apertureMaxZ, placedZ);
                    }
                }
            }
        }
        final long totalFloats = meshes.values().stream().mapToLong(FloatMesh::size).sum();
        if (totalFloats <= 0 || totalFloats > MAX_VERTEX_FLOATS_PER_CARRIER) {
            return CachedCarrier.empty(carrier, topologyRevision,
                    topologySignature, resourceGeneration, geometryWatchCells,
                    plotFluidWatchCells);
        }
        final List<Batch> batches = meshes.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 0)
                .map(entry -> new Batch(entry.getKey().atlas(),
                        entry.getKey().alphaCutoff(), entry.getKey().directionCode(),
                        entry.getValue().toArray()))
                .toList();
        if (batches.isEmpty() || !Double.isFinite(minX) || !Double.isFinite(maxX)
                || !Double.isFinite(apertureMinX) || !Double.isFinite(apertureMaxX)) {
            return CachedCarrier.empty(carrier, topologyRevision,
                    topologySignature, resourceGeneration, geometryWatchCells,
                    plotFluidWatchCells);
        }
        return new CachedCarrier(carrier.getUniqueId(), carrier, carrier.getLevel(),
                topologyRevision, topologySignature, resourceGeneration,
                allocateGeometryGeneration(),
                originX, originY, originZ, List.copyOf(boundaries), batches,
                new AABB(minX, minY, minZ, maxX, maxY, maxZ),
                new AABB(apertureMinX, apertureMinY, apertureMinZ,
                        apertureMaxX, apertureMaxY, apertureMaxZ),
                List.copyOf(apertureCellBounds), Map.copyOf(voxelSlots),
                roomIndex.owners(), geometryWatchCells, plotFluidWatchCells,
                new HashMap<>());
    }

    /**
     * Completes a bounded two-block optical wrap around each room. Direct six-neighbour discovery
     * supplies trusted seeds; a Chebyshev-2 room zone admits the added corner and outer 3x3 layer,
     * while the 26-neighbour BFS still advances only through provider-confirmed optical membranes.
     * Full collision shapes may use synthetic unit faces, while partial shapes retain their baked
     * exterior triangles. Only faces toward open exterior cells become events, so joins between
     * adjacent wrap cells never enter the mesh.
     */
    private static void addConnectedMembraneWrapCandidates(
            final ClientSubLevel carrier,
            final BoundingBox3ic plotBounds,
            final RoomIndex roomIndex,
            final Map<UUID, Map<Long, Direction>> roomSeeds,
            final Map<BoundaryKey, BoundaryCandidate> accepted,
            final Set<BoundaryKey> rejected,
            final Set<Long> geometryWatchCells,
            final Set<Long> plotFluidWatchCells) {
        final ClientLevel level = carrier.getLevel();
        if (roomSeeds.isEmpty()) return;

        final Map<UUID, Set<Long>> shellZones = new LinkedHashMap<>();
        for (final UUID roomId : roomSeeds.keySet()) {
            shellZones.put(roomId, new LongOpenHashSet());
        }
        for (final Map.Entry<Long, UUID> owner : roomIndex.owners().entrySet()) {
            final Set<Long> zone = shellZones.get(owner.getValue());
            if (zone == null || roomIndex.ambiguousCells().contains(owner.getKey())) continue;
            final BlockPos interior = BlockPos.of(owner.getKey());
            for (int dx = -CONNECTED_MEMBRANE_RADIUS;
                 dx <= CONNECTED_MEMBRANE_RADIUS; dx++) {
                for (int dy = -CONNECTED_MEMBRANE_RADIUS;
                     dy <= CONNECTED_MEMBRANE_RADIUS; dy++) {
                    for (int dz = -CONNECTED_MEMBRANE_RADIUS;
                         dz <= CONNECTED_MEMBRANE_RADIUS; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        final BlockPos candidate = interior.offset(dx, dy, dz);
                        final long packed = candidate.asLong();
                        if (!insidePlot(plotBounds, candidate)
                                || roomIndex.owners().containsKey(packed)) continue;
                        zone.add(packed);
                        // A future glass edge/corner must invalidate the cached shell as well.
                        geometryWatchCells.add(packed);
                    }
                }
            }
        }

        for (final Map.Entry<UUID, Map<Long, Direction>> roomEntry
                : roomSeeds.entrySet()) {
            final UUID roomId = roomEntry.getKey();
            final Set<Long> zone = shellZones.get(roomId);
            if (zone == null || zone.isEmpty()) continue;

            // Classification is room-local.  The same physical shell cell can be adjacent to
            // more than one room, and reusing a failed/preferred extraction from another room can
            // otherwise suppress a valid direction in this room's bounded wrap.
            final Map<Long, MembraneWrap> classified = new HashMap<>();
            final Set<Long> nonOptical = new HashSet<>();
            final Map<Long, MembraneWrap> shellCells =
                    new LinkedHashMap<>();
            final List<Long> queue = new ArrayList<>();
            for (final Map.Entry<Long, Direction> seed
                    : roomEntry.getValue().entrySet()) {
                MembraneWrap membrane = classified.get(seed.getKey());
                if (membrane == null) {
                    final BlockPos seedPosition = BlockPos.of(seed.getKey());
                    if (!loaded(level, seedPosition)) continue;
                    final BlockState seedState = safeState(level, seedPosition);
                    if (seedState == null
                            || !isSolidMembrane(level, seedPosition, seedState)
                            || seedState.getFluidState().is(FluidTags.WATER)) continue;
                    membrane = membraneWrap(carrier, seedPosition, seedState, seed.getValue());
                    if (membrane == null || membrane.faces().isEmpty()) continue;
                    classified.put(seed.getKey(), membrane);
                }
                if (shellCells.putIfAbsent(seed.getKey(), membrane) == null) {
                    queue.add(seed.getKey());
                }
            }
            final Set<Long> examined = new HashSet<>(shellCells.keySet());
            for (int cursor = 0; cursor < queue.size(); cursor++) {
                final BlockPos current = BlockPos.of(queue.get(cursor));
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            final BlockPos neighbour = current.offset(dx, dy, dz);
                            final long packed = neighbour.asLong();
                            if (!zone.contains(packed) || !examined.add(packed)
                                    || nonOptical.contains(packed)) continue;
                            MembraneWrap membrane = classified.get(packed);
                            if (membrane == null) {
                                if (!loaded(level, neighbour)) {
                                    nonOptical.add(packed);
                                    continue;
                                }
                                final BlockState state = safeState(level, neighbour);
                                if (state == null
                                        || !isSolidMembrane(level, neighbour, state)
                                        || state.getFluidState().is(FluidTags.WATER)) {
                                    nonOptical.add(packed);
                                    continue;
                                }
                                membrane = membraneWrap(
                                        carrier, neighbour, state, Direction.getNearest(
                                                dx, dy, dz));
                                if (membrane == null || membrane.faces().isEmpty()) {
                                    nonOptical.add(packed);
                                    continue;
                                }
                                classified.put(packed, membrane);
                            }
                            shellCells.put(packed, membrane);
                            queue.add(packed);
                            addWatchNeighborhood(geometryWatchCells, neighbour);
                        }
                    }
                }
            }

            final RoomMedium roomMedium = new RoomMedium(roomId);
            for (final Map.Entry<Long, MembraneWrap> shellEntry
                    : shellCells.entrySet()) {
                final BlockPos optical = BlockPos.of(shellEntry.getKey());
                for (final Direction outward : Direction.values()) {
                    final MembraneWrap membrane = shellEntry.getValue();
                    final MembraneFace face = membrane.face(outward);
                    if (face == null || face.batches().isEmpty()) continue;
                    final boolean outwardPositive = outward.getAxisDirection()
                            == Direction.AxisDirection.POSITIVE;
                    // Both synthetic full-cell faces and partial/custom faces require a baked
                    // triangle on this exact axis/sign.  A different side of the same model is
                    // not evidence that this outward shell face can be drawn.
                    if (!hasBakedFace(face.batches(), outward.getAxis(), outwardPositive)) {
                        continue;
                    }
                    final BlockPos exterior = optical.relative(outward);
                    final long exteriorPacked = exterior.asLong();
                    if (shellCells.containsKey(exteriorPacked)
                            || roomIndex.ambiguousCells().contains(exteriorPacked)) continue;
                    if (insidePlot(plotBounds, exterior)) {
                        if (roomIndex.owners().containsKey(exteriorPacked)
                                || !loaded(level, exterior)) continue;
                        final BlockState exteriorState = safeState(level, exterior);
                        if (exteriorState == null
                                || isSolidMembrane(level, exterior, exteriorState)) continue;
                    }

                    final BoundaryKey key = new BoundaryKey(
                            optical.asLong(), outward);
                    if (rejected.contains(key)) continue;
                    final ExteriorMedium exteriorMedium = new ExteriorMedium(
                            exterior.immutable(), outward);
                    final BoundaryCandidate candidate = new BoundaryCandidate(
                            optical.immutable(), outward.getAxis(), outward,
                            outwardPositive ? roomMedium : exteriorMedium,
                            outwardPositive ? exteriorMedium : roomMedium,
                            face.batches(), face.fullCollisionBlock());
                    final BoundaryCandidate previous = accepted.putIfAbsent(key, candidate);
                    if (previous != null && !previous.compatibleWith(candidate)) {
                        accepted.remove(key);
                        rejected.add(key);
                        continue;
                    }
                    addFluidWatchNeighborhood(plotFluidWatchCells, exterior);
                }
            }
        }
    }

    /**
     * Collects reliable baked geometry on every available axis for one membrane. A wrap cell can
     * expose a different axis than the neighbour step that discovered it (especially at a roof
     * edge/corner); retaining only that first extraction created semantic slots with no stencil
     * geometry on the other axes.
     */
    private static MembraneWrap membraneWrap(
            final ClientSubLevel carrier,
            final BlockPos position,
            final BlockState state,
            final Direction preferredDirection) {
        final Map<Direction, MembraneFace> faces = new EnumMap<>(Direction.class);
        final Direction[] directions = Direction.values();
        for (int attempt = -1; attempt < directions.length; attempt++) {
            final Direction direction = attempt < 0
                    ? preferredDirection : directions[attempt];
            if (attempt >= 0 && direction == preferredDirection) continue;
            final OpticalApertureProvider.Extraction extraction;
            try {
                extraction = OpticalApertureProvider.extract(
                        carrier, position, direction, state);
            } catch (final RuntimeException | LinkageError ignored) {
                continue;
            }
            if (extraction.disposition() == OpticalApertureProvider.Disposition.APERTURE
                    && !extraction.batches().isEmpty()) {
                faces.put(direction, new MembraneFace(
                        extraction.batches(), extraction.fullCollisionBlock()));
            }
        }
        return faces.isEmpty() ? null : new MembraneWrap(faces);
    }

    /** A boundary is drawable only when its exact signed model face is finite and available. */
    private static boolean hasBakedFace(
            final List<OpticalApertureProvider.AtlasBatch> batches,
            final Direction.Axis axis,
            final boolean positive) {
        final Direction.AxisDirection expected = positive
                ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE;
        for (final OpticalApertureProvider.AtlasBatch batch : batches) {
            for (final OpticalApertureProvider.TexturedTriangle triangle : batch.triangles()) {
                if (triangle.modelNormal().getAxis() == axis
                        && triangle.modelNormal().getAxisDirection() == expected
                        && Double.isFinite(triangleCoordinate(triangle, axis))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRenderableGeometry(final BoundaryCandidate boundary) {
        final boolean positive = boundary.outwardDirection().getAxisDirection()
                == Direction.AxisDirection.POSITIVE;
        return hasBakedFace(boundary.batches(), boundary.axis(), positive)
                && (!boundary.fullCollisionBlock()
                || opticalAxisBatch(boundary) != null);
    }

    /** Returns one directional batch to carry a synthetic full-cell face. */
    private static OpticalApertureProvider.AtlasBatch opticalAxisBatch(
            final BoundaryCandidate boundary) {
        final Direction.AxisDirection expected = boundary.outwardDirection().getAxisDirection();
        for (final OpticalApertureProvider.AtlasBatch batch : boundary.batches()) {
            for (final OpticalApertureProvider.TexturedTriangle triangle : batch.triangles()) {
                if (triangle.modelNormal().getAxis() == boundary.axis()
                        && triangle.modelNormal().getAxisDirection() == expected
                        && Double.isFinite(triangleCoordinate(triangle, boundary.axis()))) {
                    return batch;
                }
            }
        }
        return null;
    }

    private static void appendFullCellFace(
            final Map<BatchKey, FloatMesh> meshes,
            final OpticalApertureProvider.AtlasBatch source,
            final BlockPos cell,
            final Direction.Axis axis,
            final Direction outwardDirection,
            final boolean positive,
            final int eventSlot,
            final int originX,
            final int originY,
            final int originZ) {
        final OpticalApertureProvider.ApertureVertex sample =
                source.triangles().getFirst().first();
        final FloatMesh mesh = meshes.computeIfAbsent(
                new BatchKey(source.atlas(), source.alphaCutoff(),
                        directionCode(axis, outwardDirection)),
                ignored -> new FloatMesh());
        final double face = switch (axis) {
            case X -> cell.getX();
            case Y -> cell.getY();
            case Z -> cell.getZ();
        } + (positive ? 1.0D : 0.0D);
        final double[][] corners = switch (axis) {
            case X -> new double[][]{
                    {face, cell.getY(), cell.getZ()},
                    {face, cell.getY() + 1.0D, cell.getZ()},
                    {face, cell.getY() + 1.0D, cell.getZ() + 1.0D},
                    {face, cell.getY(), cell.getZ() + 1.0D}
            };
            case Y -> new double[][]{
                    {cell.getX(), face, cell.getZ()},
                    {cell.getX() + 1.0D, face, cell.getZ()},
                    {cell.getX() + 1.0D, face, cell.getZ() + 1.0D},
                    {cell.getX(), face, cell.getZ() + 1.0D}
            };
            case Z -> new double[][]{
                    {cell.getX(), cell.getY(), face},
                    {cell.getX() + 1.0D, cell.getY(), face},
                    {cell.getX() + 1.0D, cell.getY() + 1.0D, face},
                    {cell.getX(), cell.getY() + 1.0D, face}
            };
        };
        final float outwardSign = outwardDirection.getAxisDirection()
                == Direction.AxisDirection.POSITIVE ? 1.0F : -1.0F;
        for (final int corner : new int[]{0, 1, 2, 0, 2, 3}) {
            final double[] vertex = corners[corner];
            mesh.add((float) (vertex[0] - originX));
            mesh.add((float) (vertex[1] - originY));
            mesh.add((float) (vertex[2] - originZ));
            // The mask shader uses geometry and semantic side only. Preserve a validated finite UV
            // so this synthetic face stays compatible with the existing vertex/batch contract.
            mesh.add(sample.u());
            mesh.add(sample.v());
            mesh.add((float) eventSlot);
            mesh.add(positive ? 1.0F : -1.0F);
            mesh.add(outwardSign);
        }
    }

    private static double extremeFaceCoordinate(final BoundaryCandidate boundary,
                                                  final boolean positive) {
        double selected = positive ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        final Direction.AxisDirection expected = positive
                ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE;
        for (final OpticalApertureProvider.AtlasBatch batch : boundary.batches()) {
            for (final OpticalApertureProvider.TexturedTriangle triangle : batch.triangles()) {
                if (triangle.modelNormal().getAxis() != boundary.axis()
                        || triangle.modelNormal().getAxisDirection() != expected) continue;
                final double coordinate = triangleCoordinate(triangle, boundary.axis());
                if (!Double.isFinite(coordinate)) continue;
                selected = positive ? Math.max(selected, coordinate)
                        : Math.min(selected, coordinate);
            }
        }
        return selected;
    }

    private static double triangleCoordinate(
            final OpticalApertureProvider.TexturedTriangle triangle,
            final Direction.Axis axis) {
        return (vertexCoordinate(triangle.first(), axis)
                + vertexCoordinate(triangle.second(), axis)
                + vertexCoordinate(triangle.third(), axis)) / 3.0D;
    }

    private static double vertexCoordinate(
            final OpticalApertureProvider.ApertureVertex vertex,
            final Direction.Axis axis) {
        return switch (axis) {
            case X -> vertex.x();
            case Y -> vertex.y();
            case Z -> vertex.z();
        };
    }

    private static int axisIndex(final Direction.Axis axis) {
        return switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    private static Direction axisDirection(final Direction.Axis axis,
                                           final boolean positive) {
        final Direction negative = negativeDirection(axis);
        return positive ? negative.getOpposite() : negative;
    }

    /** Stable stencil code: X-/X+/Y-/Y+/Z-/Z+ map to 1..6. */
    private static int directionCode(final Direction.Axis axis,
                                     final Direction outwardDirection) {
        final int positive = outwardDirection.getAxisDirection()
                == Direction.AxisDirection.POSITIVE ? 1 : 0;
        return axisIndex(axis) * 2 + positive + 1;
    }

    private static RoomIndex indexRooms(
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms) {
        final Map<Long, UUID> owners = new HashMap<>();
        final Set<Long> ambiguous = new HashSet<>();
        boolean complete = true;
        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            if (room.id() == null || room.cells() == null) {
                complete = false;
                continue;
            }
            for (final BlockPos cell : room.cells()) {
                if (cell == null) {
                    complete = false;
                    continue;
                }
                final UUID previous = owners.putIfAbsent(cell.asLong(), room.id());
                if (previous != null && !previous.equals(room.id())) {
                    ambiguous.add(cell.asLong());
                }
            }
        }
        return new RoomIndex(Map.copyOf(owners), Set.copyOf(ambiguous), complete);
    }

    private static Map<UUID, RoomRuntime> runtimeRooms(
            final UUID carrierId,
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms,
            final Matrix4d pose) {
        final Map<UUID, RoomRuntime> output = new HashMap<>();
        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            final ShaderVoxelMaskAtlas.PreparedRoomWater atlasWater =
                    ShaderVoxelMaskAtlas.preparedRoomWater(
                            carrierId, room.id(), room.topologyRevision());
            final double fraction = atlasWater == null
                    ? Math.clamp(room.waterFraction(), 0.0D, 1.0D)
                    : Math.clamp(atlasWater.fraction(), 0.0D, 1.0D);
            final double surface;
            if (!Double.isFinite(room.waterFraction())) {
                surface = Double.NaN;
            } else if (fraction <= ROOM_EPSILON || fraction >= 1.0D - ROOM_EPSILON) {
                surface = 0.0D;
            } else {
                if (atlasWater != null) {
                    surface = atlasWater.surfaceY();
                } else {
                    double candidate;
                    try {
                        candidate = WaterMediumClassifier.frozenRoomSurface(
                                pose, room.cells(), room.bounds(), fraction);
                    } catch (final RuntimeException | LinkageError ignored) {
                        candidate = Double.NaN;
                    }
                    surface = candidate;
                }
            }
            output.put(room.id(), new RoomRuntime(room, fraction, surface));
        }
        return Map.copyOf(output);
    }

    private static MediumPlane mediumPlane(
            final ClientLevel parentLevel,
            final ClientSubLevel carrier,
            final Matrix4d pose,
            final CachedCarrier cached,
            final Map<UUID, RoomRuntime> rooms,
            final MediumRef medium) {
        if (medium instanceof final RoomMedium roomMedium) {
            final RoomRuntime room = rooms.get(roomMedium.roomId());
            if (room == null || !Double.isFinite(room.fraction())) {
                return MediumPlane.invalid();
            }
            if (room.fraction() <= ROOM_EPSILON) return MediumPlane.air();
            if (room.fraction() >= 1.0D - ROOM_EPSILON) return MediumPlane.water();
            if (!Double.isFinite(room.surfaceY())) return MediumPlane.invalid();
            return MediumPlane.of(worldHorizontalPlane(pose,
                    cached.voxelOriginX(), cached.voxelOriginY(),
                    cached.voxelOriginZ(), room.surfaceY()));
        }
        if (!(medium instanceof final ExteriorMedium exterior)) {
            return MediumPlane.invalid();
        }

        final ExteriorFluid exteriorFluid = exteriorFluid(
                parentLevel, carrier, pose, cached, exterior);
        final FluidPlane fluid = exteriorFluid.plane();
        if (fluid.kind() == FluidKind.INVALID) return MediumPlane.invalid();
        if (fluid.kind() == FluidKind.WATER_CONSTANT) return MediumPlane.water();
        if (fluid.kind() == FluidKind.SURFACE) {
            if (exteriorFluid.plotLocal()) {
                return MediumPlane.of(new Plane(0.0D, 1.0D, 0.0D,
                        cached.voxelOriginY() - fluid.surfaceY()));
            }
            return MediumPlane.of(worldHorizontalPlane(pose,
                    cached.voxelOriginX(), cached.voxelOriginY(),
                    cached.voxelOriginZ(), fluid.surfaceY()));
        }
        return MediumPlane.air();
    }

    private static ExteriorFluid exteriorFluid(
            final ClientLevel parentLevel,
            final ClientSubLevel carrier,
            final Matrix4d pose,
            final CachedCarrier cached,
            final ExteriorMedium exterior) {
        final ExteriorSampleCache sample = cached.exteriorSamples()
                .computeIfAbsent(exterior, ignored -> new ExteriorSampleCache());
        final var plotBounds = carrier.getPlot().getBoundingBox();
        final boolean plotLocal = plotBounds != null
                && insidePlot(plotBounds, exterior.sideCell());
        if (plotLocal) {
            // A side cell can still lie inside Sable's conservative plot bounds while it is
            // merely empty carrier space.  That is not an exterior-air verdict: the real water
            // is often in the parent world at the carrier pose (the common case for a hull whose
            // plot contains a one-cell safety margin).  Keep an actually sampled plot fluid,
            // reject an uncertain/solid local state, and let local AIR fall through to the parent
            // world transform below.
            if (sample.initialized) {
                if (sample.plotLocal) return new ExteriorFluid(sample.plane, true);
                if (!sample.plotAirFallback) sample.initialized = false;
            }
            if (!sample.initialized) {
                final FluidPlane localPlane;
                if (!loaded(carrier.getLevel(), exterior.sideCell())) {
                    localPlane = FluidPlane.invalid();
                } else {
                    final BlockState sideState = safeState(
                            carrier.getLevel(), exterior.sideCell());
                    localPlane = sideState == null
                            || isSolidMembrane(carrier.getLevel(), exterior.sideCell(), sideState)
                            ? FluidPlane.invalid()
                            : stableWaterAtOrBelow(carrier.getLevel(), exterior.sideCell());
                }
                if (localPlane.kind() != FluidKind.AIR) {
                    sample.plane = localPlane;
                    sample.initialized = true;
                    sample.plotLocal = true;
                    sample.plotAirFallback = false;
                    return new ExteriorFluid(sample.plane, true);
                }
                sample.plotAirFallback = true;
            }
            // Local AIR is intentionally not cached as a plot-local medium.  Continue below so
            // the same optical side can resolve the parent-world ocean through renderPose().
        }

        final Vector3d worldSample = pose.transformPosition(new Vector3d(
                exterior.sideCell().getX() + 0.5D,
                exterior.sideCell().getY() + 0.5D,
                exterior.sideCell().getZ() + 0.5D));
        if (!worldSample.isFinite()) return new ExteriorFluid(FluidPlane.invalid(), false);
        final BlockPos worldCell = BlockPos.containing(
                worldSample.x, worldSample.y, worldSample.z);
        final long worldKey = worldCell.asLong();
        if (!sample.initialized || sample.plotLocal || sample.worldCell != worldKey
                || sample.parentEpoch != parentFluidEpoch) {
            sample.plane = stableWaterAtOrBelow(parentLevel, worldCell);
            sample.worldCell = worldKey;
            sample.parentEpoch = parentFluidEpoch;
            sample.initialized = true;
            sample.plotLocal = false;
            sample.plotAirFallback = plotLocal;
        }
        return new ExteriorFluid(sample.plane, false);
    }

    /** Accepts only real source blocks whose rendered four corners form one stable plane. */
    private static FluidPlane stableWaterAtOrBelow(final ClientLevel level,
                                                   final BlockPos position) {
        final FluidPlane current = stableWater(level, position);
        if (current.kind() != FluidKind.AIR) return current;
        final BlockState currentState = safeState(level, position);
        // The supported surface case is literally one air cell above a stable source.  A
        // replaceable/non-solid decoration is still occupied and must not be promoted to AIR.
        if (currentState == null || !currentState.isAir()) return FluidPlane.invalid();
        return stableWater(level, position.below());
    }

    private static FluidPlane stableWater(final ClientLevel level,
                                          final BlockPos position) {
        if (!loaded(level, position)) return FluidPlane.invalid();
        final BlockState state = safeState(level, position);
        if (state == null) return FluidPlane.invalid();
        final FluidState fluid = state.getFluidState();
        if (!fluid.is(FluidTags.WATER)) return FluidPlane.air();
        if (!fluid.isSource() || !(state.getBlock() instanceof LiquidBlock)
                || state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return FluidPlane.invalid();
        }
        // Vanilla corner heights inspect the horizontal 3x3 footprint and each cell above it.
        // Preflight the complete dependency set so an unloaded neighbour cannot masquerade as
        // air and accidentally turn uncertain chunk-edge water into a stable surface.
        if (!fluidNeighborhoodLoaded(level, position)) return FluidPlane.invalid();
        final double[] corners;
        try {
            corners = WaterMediumClassifier.fluidCornerHeights(
                    level, position, fluid.getType());
        } catch (final RuntimeException | LinkageError ignored) {
            return FluidPlane.invalid();
        }
        if (corners.length != 4) return FluidPlane.invalid();
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (final double corner : corners) {
            if (!Double.isFinite(corner)) return FluidPlane.invalid();
            minimum = Math.min(minimum, corner);
            maximum = Math.max(maximum, corner);
        }
        if (minimum <= 0.0D || maximum > 1.0D + FLAT_PLANE_EPSILON
                || maximum - minimum > FLAT_PLANE_EPSILON) {
            return FluidPlane.invalid();
        }
        final BlockState aboveState = safeState(level, position.above());
        if (aboveState == null) return FluidPlane.invalid();
        final FluidState above = aboveState.getFluidState();
        if (above.is(FluidTags.WATER)) {
            return above.isSource() && aboveState.getBlock() instanceof LiquidBlock
                    && !(aboveState.hasProperty(BlockStateProperties.WATERLOGGED)
                    && aboveState.getValue(BlockStateProperties.WATERLOGGED))
                    ? FluidPlane.water() : FluidPlane.invalid();
        }
        return FluidPlane.surface(position.getY() + (minimum + maximum) * 0.5D);
    }

    private static boolean fluidNeighborhoodLoaded(final ClientLevel level,
                                                   final BlockPos position) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final BlockPos neighbor = position.offset(dx, 0, dz);
                if (!loaded(level, neighbor) || !loaded(level, neighbor.above())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Plane worldHorizontalPlane(final Matrix4d pose,
                                              final int originX,
                                              final int originY,
                                              final int originZ,
                                              final double worldSurfaceY) {
        final double x = pose.m01();
        final double y = pose.m11();
        final double z = pose.m21();
        final double w = x * originX + y * originY + z * originZ
                + pose.m31() - worldSurfaceY;
        return new Plane(x, y, z, w);
    }

    private static boolean cameraStartsInWater(
            final ClientLevel parentLevel,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final ClientCompartmentVisualCache.CameraWaterState compartmentState) {
        /*
         * These optical events describe parent-ocean intervals, not the immersive camera state.
         * WET now reports WATER to Iris, while this value remains false for tracked rooms. The
         * decoder's eye/medium mismatch deliberately leaves wet-room fog to the pack's native
         * underwater path instead of replacing it with a bounded glass-only interval. DRY still
         * uses glass-entry compensation; OUTSIDE inherits the actual parent-world medium.
         */
        if (compartmentState != ClientCompartmentVisualCache.CameraWaterState.OUTSIDE) {
            return false;
        }
        final Vector3d cameraWorld = new Vector3d(cameraX, cameraY, cameraZ);
        return WaterMediumClassifier.parentWorldContains(parentLevel, cameraWorld);
    }

    /** One optical exterior boundary has exactly one room side. */
    private static UUID boundaryRoom(final CachedBoundary boundary) {
        if (boundary.negative() instanceof final RoomMedium room
                && boundary.positive() instanceof ExteriorMedium) {
            return room.roomId();
        }
        if (boundary.positive() instanceof final RoomMedium room
                && boundary.negative() instanceof ExteriorMedium) {
            return room.roomId();
        }
        return null;
    }

    private static MediumRef mediumFor(final RoomIndex rooms,
                                       final BlockPos sideCell,
                                       final Direction sideDirection) {
        if (rooms.ambiguousCells().contains(sideCell.asLong())) {
            return UnknownMedium.INSTANCE;
        }
        final UUID room = rooms.owners().get(sideCell.asLong());
        return room == null
                ? new ExteriorMedium(sideCell.immutable(), sideDirection)
                : new RoomMedium(room);
    }

    private static boolean removeStale(final ClientLevel parentLevel,
                                       final Set<UUID> liveIds) {
        boolean removed = false;
        final var iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<CacheKey, CachedCarrier> entry = iterator.next();
            if (entry.getKey().level() != parentLevel
                    || liveIds.contains(entry.getKey().id())) {
                continue;
            }
            OpticalApertureProvider.invalidateCarrier(entry.getValue().carrier());
            iterator.remove();
            removed = true;
        }
        return removed;
    }

    private static void addWatchNeighborhood(final Set<Long> output,
                                             final BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    output.add(BlockPos.asLong(center.getX() + dx,
                            center.getY() + dy, center.getZ() + dz));
                }
            }
        }
    }

    /** Complete local dependency footprint of stableWaterAtOrBelow(). */
    private static void addFluidWatchNeighborhood(final Set<Long> output,
                                                  final BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    output.add(BlockPos.asLong(center.getX() + dx,
                            center.getY() + dy, center.getZ() + dz));
                    output.add(BlockPos.asLong(center.getX() + dx,
                            center.getY() - 1 + dy, center.getZ() + dz));
                }
            }
        }
    }

    private static boolean watchCellsIntersectChunk(final Set<Long> watched,
                                                    final int chunkX,
                                                    final int chunkZ) {
        for (final long packed : watched) {
            final BlockPos cell = BlockPos.of(packed);
            if ((cell.getX() >> 4) == chunkX && (cell.getZ() >> 4) == chunkZ) {
                return true;
            }
        }
        return false;
    }

    private static boolean invalidateExteriorSamples(final CachedCarrier cached,
                                                     final BlockPos changedPosition) {
        boolean invalidated = false;
        for (final ExteriorSampleCache sample : cached.exteriorSamples().values()) {
            if (!sample.initialized || sample.plotLocal) continue;
            final BlockPos sampled = BlockPos.of(sample.worldCell);
            if (Math.abs((long) changedPosition.getX() - sampled.getX()) <= 1L
                    && Math.abs((long) changedPosition.getY() - sampled.getY()) <= 1L
                    && Math.abs((long) changedPosition.getZ() - sampled.getZ()) <= 1L) {
                sample.initialized = false;
                invalidated = true;
            }
        }
        return invalidated;
    }

    private static boolean invalidatePlotSamples(final CachedCarrier cached,
                                                 final BlockPos changedPosition) {
        if (!cached.plotFluidWatchCells().contains(changedPosition.asLong())) return false;
        boolean invalidated = false;
        for (final Map.Entry<ExteriorMedium, ExteriorSampleCache> entry
                : cached.exteriorSamples().entrySet()) {
            final ExteriorSampleCache sample = entry.getValue();
            if (!sample.initialized || !(sample.plotLocal || sample.plotAirFallback)) continue;
            if (!fluidDependencyContains(entry.getKey().sideCell(), changedPosition)) continue;
            sample.initialized = false;
            invalidated = true;
        }
        return invalidated;
    }

    private static boolean invalidatePlotSamples(final CachedCarrier cached,
                                                 final int chunkX,
                                                 final int chunkZ) {
        if (!watchCellsIntersectChunk(cached.plotFluidWatchCells(), chunkX, chunkZ)) {
            return false;
        }
        boolean invalidated = false;
        for (final Map.Entry<ExteriorMedium, ExteriorSampleCache> entry
                : cached.exteriorSamples().entrySet()) {
            final ExteriorSampleCache sample = entry.getValue();
            if (!sample.initialized || !(sample.plotLocal || sample.plotAirFallback)) continue;
            final BlockPos side = entry.getKey().sideCell();
            if ((side.getX() - 1 >> 4) > chunkX || (side.getX() + 1 >> 4) < chunkX
                    || (side.getZ() - 1 >> 4) > chunkZ
                    || (side.getZ() + 1 >> 4) < chunkZ) continue;
            sample.initialized = false;
            invalidated = true;
        }
        return invalidated;
    }

    private static boolean fluidDependencyContains(final BlockPos side,
                                                   final BlockPos changed) {
        return Math.abs((long) side.getX() - changed.getX()) <= 1L
                && changed.getY() >= side.getY() - 2
                && changed.getY() <= side.getY() + 1
                && Math.abs((long) side.getZ() - changed.getZ()) <= 1L;
    }

    private static boolean invalidateExteriorSamples(final CachedCarrier cached,
                                                     final int chunkX,
                                                     final int chunkZ) {
        final long minimumX = ((long) chunkX << 4) - 1L;
        final long maximumX = ((long) chunkX << 4) + 16L;
        final long minimumZ = ((long) chunkZ << 4) - 1L;
        final long maximumZ = ((long) chunkZ << 4) + 16L;
        boolean invalidated = false;
        for (final ExteriorSampleCache sample : cached.exteriorSamples().values()) {
            if (!sample.initialized || sample.plotLocal) continue;
            final BlockPos sampled = BlockPos.of(sample.worldCell);
            if (sampled.getX() >= minimumX && sampled.getX() <= maximumX
                    && sampled.getZ() >= minimumZ && sampled.getZ() <= maximumZ) {
                sample.initialized = false;
                invalidated = true;
            }
        }
        return invalidated;
    }

    private static boolean insidePlot(final BoundingBox3ic bounds,
                                      final BlockPos position) {
        return position.getX() >= bounds.minX() && position.getX() <= bounds.maxX()
                && position.getY() >= bounds.minY() && position.getY() <= bounds.maxY()
                && position.getZ() >= bounds.minZ() && position.getZ() <= bounds.maxZ();
    }

    private static boolean loaded(final ClientLevel level, final BlockPos position) {
        try {
            return level.hasChunkAt(position);
        } catch (final RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static BlockState safeState(final ClientLevel level,
                                        final BlockPos position) {
        try {
            return level.getBlockState(position);
        } catch (final RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isSolidMembrane(final ClientLevel level,
                                           final BlockPos position,
                                           final BlockState state) {
        try {
            return CompartmentVoxelSemantics.isSolidMembrane(level, position, state);
        } catch (final RuntimeException | LinkageError ignored) {
            // Unknown physical semantics are never treated as an aperture/opening.
            return true;
        }
    }

    private static Direction negativeDirection(final Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.WEST;
            case Y -> Direction.DOWN;
            case Z -> Direction.NORTH;
        };
    }

    private static long topologySignature(
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms) {
        long hash = 0xCBF29CE484222325L;
        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            hash = mix(hash, room.id().getMostSignificantBits());
            hash = mix(hash, room.id().getLeastSignificantBits());
            hash = mix(hash, room.topologyRevision());
            hash = mix(hash, room.cells().size());
            for (final BlockPos cell : room.cells()) hash = mix(hash, cell.asLong());
        }
        return hash;
    }

    private static long mix(final long hash, final long value) {
        return (hash ^ value) * 0x100000001B3L;
    }

    private static void bumpGeneration() {
        generation++;
        if (generation <= 0L) generation = 1L;
    }

    private static void bumpParentFluidEpoch() {
        parentFluidEpoch++;
        if (parentFluidEpoch <= 0L) parentFluidEpoch = 1L;
    }

    private static long allocateGeometryGeneration() {
        final long value = nextGeometryGeneration++;
        if (nextGeometryGeneration <= 0L) nextGeometryGeneration = 1L;
        return value;
    }

    /** Immutable render-frame contract. */
    record Frame(ClientLevel level,
                 long generation,
                 double cameraX,
                 double cameraY,
                 double cameraZ,
                 boolean cameraStartsInWater,
                 boolean opticalLayoutChanged,
                 List<CarrierFrame> carriers) {
        Frame {
            Objects.requireNonNull(level, "level");
            carriers = List.copyOf(carriers);
        }
    }

    /** One carrier's cached geometry plus this frame's frozen transform and event table. */
    record CarrierFrame(UUID id,
                        ClientSubLevel carrier,
                        Matrix4d pose,
                        Matrix4d inversePose,
                        long topologyRevision,
                        long geometryGeneration,
                        int voxelOriginX,
                        int voxelOriginY,
                        int voxelOriginZ,
                        List<Event> events,
                        List<Batch> batches,
                        AABB localBounds,
                        AABB apertureBounds,
                        List<AABB> apertureCellBounds,
                        Map<Long, Integer> voxelSlots) {
        CarrierFrame {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(carrier, "carrier");
            pose = new Matrix4d(Objects.requireNonNull(pose, "pose"));
            inversePose = new Matrix4d(Objects.requireNonNull(inversePose, "inversePose"));
            events = List.copyOf(events);
            batches = List.copyOf(batches);
            Objects.requireNonNull(localBounds, "localBounds");
            localBounds = new AABB(localBounds.minX, localBounds.minY, localBounds.minZ,
                    localBounds.maxX, localBounds.maxY, localBounds.maxZ);
            Objects.requireNonNull(apertureBounds, "apertureBounds");
            apertureBounds = new AABB(
                    apertureBounds.minX, apertureBounds.minY, apertureBounds.minZ,
                    apertureBounds.maxX, apertureBounds.maxY, apertureBounds.maxZ);
            apertureCellBounds = apertureCellBounds.stream()
                    .map(bounds -> new AABB(bounds.minX, bounds.minY, bounds.minZ,
                            bounds.maxX, bounds.maxY, bounds.maxZ))
                    .toList();
            voxelSlots = Map.copyOf(voxelSlots);
        }

        @Override
        public Matrix4d pose() {
            return new Matrix4d(pose);
        }

        @Override
        public Matrix4d inversePose() {
            return new Matrix4d(inversePose);
        }
    }

    /** Event slot, semantic face sign and outward sign referenced by each vertex. */
    record Event(int slot, BlockPos opticalCell, Direction.Axis axis,
                 Direction outwardDirection, boolean floodedExteriorShell,
                 PlanePair planes, boolean valid) {
        Event {
            if (slot < 0) throw new IllegalArgumentException("negative event slot");
            opticalCell = Objects.requireNonNull(opticalCell, "opticalCell").immutable();
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(outwardDirection, "outwardDirection");
            if (outwardDirection.getAxis() != axis) {
                throw new IllegalArgumentException("outward direction does not match event axis");
            }
            Objects.requireNonNull(planes, "planes");
        }
    }

    record OpticalSlots(Map<Long, Integer> voxelSlots, int eventCount,
                        long geometryGeneration) {
        private static final OpticalSlots EMPTY = new OpticalSlots(Map.of(), 0, 0L);

        OpticalSlots {
            voxelSlots = Map.copyOf(voxelSlots);
            if (eventCount < 0) throw new IllegalArgumentException("negative event count");
        }
    }

    /** Draw batch: {@code xyzRelative, uv, eventSlot, faceSign, outwardSign}. */
    record Batch(ResourceLocation atlas, float alphaCutoff,
                 int directionCode, float[] vertices) {
        Batch {
            Objects.requireNonNull(atlas, "atlas");
            if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0F
                    || alphaCutoff >= 1.0F) {
                throw new IllegalArgumentException("invalid alpha cutoff " + alphaCutoff);
            }
            if (directionCode < 1 || directionCode > 6) {
                throw new IllegalArgumentException("invalid direction code " + directionCode);
            }
            vertices = Objects.requireNonNull(vertices, "vertices").clone();
            if (vertices.length == 0 || vertices.length % (VERTEX_STRIDE * 3) != 0) {
                throw new IllegalArgumentException("batch is not a complete triangle list");
            }
            for (final float value : vertices) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("non-finite aperture vertex");
                }
            }
        }

        @Override
        public float[] vertices() {
            return vertices.clone();
        }

        int vertexCount() {
            return vertices.length / VERTEX_STRIDE;
        }
    }

    /** Negative-axis and positive-axis medium planes for one optical voxel. */
    record PlanePair(Plane negative, Plane positive) {
        PlanePair {
            Objects.requireNonNull(negative, "negative");
            Objects.requireNonNull(positive, "positive");
        }
    }

    /** Plane in coordinates relative to CarrierFrame's voxel origin. */
    record Plane(double x, double y, double z, double w) {
        Plane {
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z) || !Double.isFinite(w)) {
                throw new IllegalArgumentException("non-finite medium plane");
            }
        }

        boolean containsWater(final double relativeX,
                              final double relativeY,
                              final double relativeZ) {
            return x * relativeX + y * relativeY + z * relativeZ + w <= 0.0D;
        }
    }

    private sealed interface MediumRef permits RoomMedium, ExteriorMedium, UnknownMedium {
    }

    private record RoomMedium(UUID roomId) implements MediumRef {
    }

    private record ExteriorMedium(BlockPos sideCell,
                                  Direction sideDirection) implements MediumRef {
    }

    private enum UnknownMedium implements MediumRef {
        INSTANCE
    }

    private enum FluidKind {
        AIR,
        WATER_CONSTANT,
        SURFACE,
        INVALID
    }

    private record FluidPlane(FluidKind kind, double surfaceY) {
        private static FluidPlane air() {
            return new FluidPlane(FluidKind.AIR, 0.0D);
        }

        private static FluidPlane water() {
            return new FluidPlane(FluidKind.WATER_CONSTANT, 0.0D);
        }

        private static FluidPlane surface(final double surfaceY) {
            return Double.isFinite(surfaceY)
                    ? new FluidPlane(FluidKind.SURFACE, surfaceY) : invalid();
        }

        private static FluidPlane invalid() {
            return new FluidPlane(FluidKind.INVALID, 0.0D);
        }
    }

    private record MediumPlane(Plane plane, boolean valid) {
        private static MediumPlane air() {
            return new MediumPlane(AIR, true);
        }

        private static MediumPlane water() {
            return new MediumPlane(WATER, true);
        }

        private static MediumPlane of(final Plane plane) {
            return new MediumPlane(plane, true);
        }

        private static MediumPlane invalid() {
            return new MediumPlane(AIR, false);
        }
    }

    private record RoomRuntime(ClientCompartmentVisualCache.RoomSnapshot snapshot,
                               double fraction,
                               double surfaceY) {
    }

    private record RoomIndex(Map<Long, UUID> owners,
                             Set<Long> ambiguousCells,
                             boolean complete) {
    }

    private record BoundaryKey(long opticalCell, Direction outwardDirection) {
    }

    private record BoundaryCandidate(
            BlockPos opticalCell,
            Direction.Axis axis,
            Direction outwardDirection,
            MediumRef negative,
            MediumRef positive,
            List<OpticalApertureProvider.AtlasBatch> batches,
            boolean fullCollisionBlock) {
        private BoundaryCandidate {
            opticalCell = opticalCell.immutable();
            Objects.requireNonNull(outwardDirection, "outwardDirection");
            batches = List.copyOf(batches);
        }

        private boolean compatibleWith(final BoundaryCandidate other) {
            return opticalCell.equals(other.opticalCell)
                    && axis == other.axis
                    && outwardDirection == other.outwardDirection
                    && negative.equals(other.negative)
                    && positive.equals(other.positive)
                    && fullCollisionBlock == other.fullCollisionBlock;
        }
    }

    /** One signed-direction extraction and its collision-shape contract. */
    private record MembraneFace(
            List<OpticalApertureProvider.AtlasBatch> batches,
            boolean fullCollisionBlock) {
        private MembraneFace {
            batches = List.copyOf(batches);
        }
    }

    /** Decoded wrap membrane with independent extraction/shape state for each signed direction. */
    private record MembraneWrap(Map<Direction, MembraneFace> faces) {
        private MembraneWrap {
            faces = Map.copyOf(faces);
        }

        private MembraneFace face(final Direction direction) {
            return faces.get(direction);
        }
    }

    private record CachedBoundary(int slot,
                                  BlockPos opticalCell,
                                  Direction.Axis axis,
                                  Direction outwardDirection,
                                  MediumRef negative,
                                  MediumRef positive) {
    }

    private record BatchKey(ResourceLocation atlas, float alphaCutoff, int directionCode) {
    }

    private record CacheKey(ClientLevel level, UUID id) {
    }

    private record CachedCarrier(
            UUID id,
            ClientSubLevel carrier,
            ClientLevel carrierLevel,
            long topologyRevision,
            long topologySignature,
            long resourceGeneration,
            long geometryGeneration,
            int voxelOriginX,
            int voxelOriginY,
            int voxelOriginZ,
            List<CachedBoundary> boundaries,
            List<Batch> batches,
            AABB localBounds,
            AABB apertureBounds,
            List<AABB> apertureCellBounds,
            Map<Long, Integer> voxelSlots,
            Map<Long, UUID> cellOwners,
            Set<Long> geometryWatchCells,
            Set<Long> plotFluidWatchCells,
            Map<ExteriorMedium, ExteriorSampleCache> exteriorSamples) {
        private CachedCarrier {
            boundaries = List.copyOf(boundaries);
            batches = List.copyOf(batches);
            apertureCellBounds = apertureCellBounds.stream()
                    .map(bounds -> new AABB(bounds.minX, bounds.minY, bounds.minZ,
                            bounds.maxX, bounds.maxY, bounds.maxZ))
                    .toList();
            voxelSlots = Map.copyOf(voxelSlots);
            cellOwners = Map.copyOf(cellOwners);
            geometryWatchCells = new LongOpenHashSet(geometryWatchCells);
            plotFluidWatchCells = new LongOpenHashSet(plotFluidWatchCells);
            exteriorSamples = new HashMap<>(exteriorSamples);
        }

        private static CachedCarrier empty(final ClientSubLevel carrier,
                                           final long revision,
                                           final long signature,
                                           final long resourceGeneration) {
            return empty(carrier, revision, signature, resourceGeneration, Set.of());
        }

        private static CachedCarrier empty(final ClientSubLevel carrier,
                                           final long revision,
                                           final long signature,
                                           final long resourceGeneration,
                                           final Set<Long> geometryWatchCells) {
            return empty(carrier, revision, signature, resourceGeneration,
                    geometryWatchCells, Set.of());
        }

        private static CachedCarrier empty(final ClientSubLevel carrier,
                                           final long revision,
                                           final long signature,
                                           final long resourceGeneration,
                                           final Set<Long> geometryWatchCells,
                                           final Set<Long> plotFluidWatchCells) {
            final var bounds = carrier.getPlot().getBoundingBox();
            final AABB localBounds = bounds == null
                    ? new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D)
                    : new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX() + 1.0D, bounds.maxY() + 1.0D,
                    bounds.maxZ() + 1.0D);
            return new CachedCarrier(carrier.getUniqueId(), carrier, carrier.getLevel(),
                    revision, signature, resourceGeneration, allocateGeometryGeneration(),
                    bounds == null ? 0 : bounds.minX(),
                    bounds == null ? 0 : bounds.minY(),
                    bounds == null ? 0 : bounds.minZ(),
                    List.of(), List.of(), localBounds, localBounds,
                    List.of(), Map.of(), Map.of(), geometryWatchCells,
                    plotFluidWatchCells, new HashMap<>());
        }
    }

    private record ExteriorFluid(FluidPlane plane, boolean plotLocal) {
    }

    private static final class ExteriorSampleCache {
        private boolean initialized;
        private boolean plotLocal;
        private boolean plotAirFallback;
        private long worldCell;
        private long parentEpoch;
        private FluidPlane plane = FluidPlane.invalid();
    }

    private record PreparedCarrier(CachedCarrier cached,
                                   Matrix4d pose,
                                   Matrix4d inversePose,
                                   List<Event> events,
                                   Map<UUID, RoomRuntime> runtimeRooms) {
    }

    private static final class FloatMesh {
        private float[] values = new float[1024];
        private int size;

        private void add(final float value) {
            if (size == values.length) {
                final float[] replacement = new float[values.length << 1];
                System.arraycopy(values, 0, replacement, 0, size);
                values = replacement;
            }
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private float[] toArray() {
            final float[] output = new float[size];
            System.arraycopy(values, 0, output, 0, size);
            return output;
        }
    }

    record Invalidation(boolean changed, boolean geometryChanged) {
        private static final Invalidation NONE = new Invalidation(false, false);
        private static final Invalidation GEOMETRY = new Invalidation(true, true);

        Invalidation {
            if (geometryChanged && !changed) {
                throw new IllegalArgumentException("geometry invalidation must be a change");
            }
        }
    }
}
