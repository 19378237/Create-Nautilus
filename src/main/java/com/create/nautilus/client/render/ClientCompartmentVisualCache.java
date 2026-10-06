package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.network.CompartmentVisualSnapshotPayload;
import com.create.nautilus.network.CompartmentWaterLevelPayload;
import com.create.nautilus.physics.CompartmentWaterGeometry;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe topology/level cache shared by rendering and client entity-fluid queries. */
public final class ClientCompartmentVisualCache {
    /**
     * Render one and a half normal game ticks behind the newest water sample.  A small jitter
     * buffer is preferable to restarting a fixed-duration interpolation on every packet: the old
     * scheme was position-continuous but changed velocity at every arrival, which appeared as a
     * periodically twitching free surface while a room was flooding.
     */
    private static final long RENDER_DELAY_NANOS = 75_000_000L;
    /** Enough history to cover normal packet jitter without ever extrapolating water volume. */
    private static final int MAX_LEVEL_SAMPLES = 8;
    /** Keeps the camera on the cabin side while its eye touches a thin boundary voxel. */
    private static final double CAMERA_BOUNDARY_MARGIN = 0.25D;
    private static final Map<UUID, CarrierSnapshot> CARRIERS = new ConcurrentHashMap<>();
    private static long levelLogCounter;
    private static long topologyLogCounter;

    private ClientCompartmentVisualCache() {
    }

    public static void acceptTopology(final CompartmentVisualSnapshotPayload payload) {
        if ((topologyLogCounter++ & 0x3F) == 0L) {
            final long cells = payload.rooms().stream()
                    .mapToLong(room -> room.cells().size()).sum();
            CreateNautilus.LOGGER.info(
                    "[visual-cache] topology carrier={} revision={} rooms={} cells={}",
                    payload.carrierId(), payload.topologyRevision(),
                    payload.rooms().size(), cells);
        }
        CARRIERS.compute(payload.carrierId(), (ignored, previous) -> {
            if (previous != null && previous.revision() >= payload.topologyRevision()) {
                if ((topologyLogCounter & 0x3F) == 0L) {
                    CreateNautilus.LOGGER.info(
                            "[visual-cache] topology carrier={} revision={} rejected: cached revision={}",
                            payload.carrierId(), payload.topologyRevision(), previous.revision());
                }
                return previous;
            }
            final Map<UUID, RoomTopology> rooms = new LinkedHashMap<>();
            for (final CompartmentVisualSnapshotPayload.Room room : payload.rooms()) {
                rooms.put(room.id(), new RoomTopology(room.bounds(), room.cells()));
            }
            // A water sample belongs to one exact topology revision. Reusing its interpolation
            // history after a breach/repair would briefly publish new cells with old water planes.
            FloodedChamberRenderer.invalidateSnapshots();
            return new CarrierSnapshot(payload.topologyRevision(), Map.copyOf(rooms),
                    Map.of());
        });
    }

    public static void acceptLevels(final CompartmentWaterLevelPayload payload) {
        final long now = System.nanoTime();
        CARRIERS.computeIfPresent(payload.carrierId(), (ignored, previous) -> {
            // TCP normally delivers topology first. Reject a mismatched revision instead of
            // applying a room UUID to stale geometry; the next game-tick packet repairs it.
            if (previous.revision() != payload.topologyRevision()) {
                if ((levelLogCounter & 0x3F) == 0L) {
                    CreateNautilus.LOGGER.warn(
                            "[visual-cache] levels carrier={} revision={} rejected: cached revision={}",
                            payload.carrierId(), payload.topologyRevision(), previous.revision());
                }
                return previous;
            }
            final Map<UUID, WaterLevel> next = new LinkedHashMap<>();
            for (final CompartmentWaterLevelPayload.RoomLevel room : payload.rooms()) {
                if (!previous.rooms().containsKey(room.id())) {
                    continue;
                }
                final WaterLevel old = previous.levels().get(room.id());
                next.put(room.id(), old == null
                        ? WaterLevel.initial(room.waterFraction(), now)
                        : old.append(room.waterFraction(), now));
            }
            return new CarrierSnapshot(previous.revision(), previous.rooms(), Map.copyOf(next));
        });
        if ((++levelLogCounter & 0x3F) == 0L) {
            final CarrierSnapshot snapshot = CARRIERS.get(payload.carrierId());
            final long rooms = snapshot == null ? -1L : snapshot.rooms().size();
            final double maxFraction = snapshot == null ? -1.0D : snapshot.levels().values().stream()
                    .mapToDouble(level -> level.sample(now).fraction()).max().orElse(-1.0D);
            CreateNautilus.LOGGER.info(
                    "[visual-cache] levels carrier={} revision={} cachedRooms={} maxFraction={}",
                    payload.carrierId(), payload.topologyRevision(), rooms, maxFraction);
        }
    }

    /** Immutable interpolated view consumed once per render pass. */
    public static Map<UUID, List<RoomSnapshot>> snapshots() {
        final long now = System.nanoTime();
        final Map<UUID, List<RoomSnapshot>> result = new LinkedHashMap<>();
        CARRIERS.forEach((carrierId, carrier) -> {
            final List<RoomSnapshot> rooms = new ArrayList<>();
            carrier.rooms().forEach((roomId, topology) -> {
                final WaterLevel water = carrier.levels().get(roomId);
                if (water == null) {
                    return;
                }
                final Sample sample = water.sample(now);
                rooms.add(new RoomSnapshot(roomId, carrier.revision(), topology.bounds(),
                        topology.cells(), sample.fraction()));
            });
            result.put(carrierId, List.copyOf(rooms));
        });
        return Map.copyOf(result);
    }

    /**
     * Complete, revision-consistent input for the shader voxel atlas.
     *
     * <p>A topology packet can legitimately arrive before the first level packet.  The ordinary
     * visual consumers may use whichever rooms already have a sample, but an atlas must never
     * publish a partial carrier: missing rooms would turn their ocean pixels into false empty
     * space.  Such carriers therefore fail open until every topology room has a water level.</p>
     */
    public static Map<UUID, List<RoomSnapshot>> shaderAtlasSnapshots() {
        final long now = System.nanoTime();
        final Map<UUID, List<RoomSnapshot>> result = new LinkedHashMap<>();
        CARRIERS.forEach((carrierId, carrier) -> {
            if (!carrier.levels().keySet().containsAll(carrier.rooms().keySet())) {
                return;
            }
            final List<RoomSnapshot> rooms = new ArrayList<>(carrier.rooms().size());
            carrier.rooms().forEach((roomId, topology) -> {
                final WaterLevel water = carrier.levels().get(roomId);
                if (water != null) {
                    rooms.add(new RoomSnapshot(roomId, carrier.revision(), topology.bounds(),
                            topology.cells(), water.sample(now).fraction()));
                }
            });
            if (rooms.size() == carrier.rooms().size()) {
                result.put(carrierId, List.copyOf(rooms));
            }
        });
        return Map.copyOf(result);
    }

    /** Same interpolated plane used by rendering, swimming, camera fog and the bubble bar. */
    public static double waterDepthAt(final ClientSubLevel subLevel,
                                      final Vector3dc localPosition,
                                      final double worldY) {
        final CarrierSnapshot carrier = CARRIERS.get(subLevel.getUniqueId());
        if (carrier == null) {
            return 0.0D;
        }
        final long now = System.nanoTime();
        double deepest = 0.0D;
        for (final Map.Entry<UUID, RoomTopology> entry : carrier.rooms().entrySet()) {
            final RoomTopology room = entry.getValue();
            final WaterLevel water = carrier.levels().get(entry.getKey());
            if (water == null || !room.contains(localPosition)) {
                continue;
            }
            final Sample sample = water.sample(now);
            if (sample.fraction() > 0.0D) {
                final double surfaceY = CompartmentWaterGeometry.surfaceY(
                        subLevel.renderPose(), room.cells(), room.bounds(), sample.fraction());
                deepest = Math.max(deepest, surfaceY - worldY);
            }
        }
        return Math.max(0.0D, deepest);
    }

    /**
     * Returns whether parent-ocean water at the camera must be ignored because the camera is in
     * the dry part of a tracked compartment.
     *
     * <p>Sable's water-occlusion region removes ocean meshes but vanilla {@code Camera} still
     * samples the parent {@code ClientLevel}.  Below sea level that stale sample reports WATER and
     * enables the dark underwater fog even in a dry cabin.  This query uses the same interpolated
     * render pose and local free surface as the chamber renderer. Unknown packet state fails open
     * and leaves vanilla's result untouched.</p>
     */
    public static CameraWaterState cameraWaterState(final Vec3 worldPosition) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return CameraWaterState.OUTSIDE;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return CameraWaterState.OUTSIDE;
        }

        boolean foundKnownDryInterior = false;
        final long now = System.nanoTime();
        for (final Map.Entry<UUID, CarrierSnapshot> carrierEntry : CARRIERS.entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel subLevel)) {
                continue;
            }
            final Vector3d local = subLevel.renderPose().transformPositionInverse(new Vector3d(
                    worldPosition.x, worldPosition.y, worldPosition.z));
            if (!local.isFinite()) {
                continue;
            }
            final BlockPos localCell = BlockPos.containing(local.x, local.y, local.z);
            for (final Map.Entry<UUID, RoomTopology> roomEntry
                    : carrierEntry.getValue().rooms().entrySet()) {
                final RoomTopology room = roomEntry.getValue();
                if (!room.containsCamera(local)) {
                    continue;
                }
                // Bucket-placed plot water is deliberately not part of the attachment flood
                // fraction. It must nevertheless win while the eye is below its rendered top.
                if (room.contains(local)
                        && WaterMediumClassifier.isWaterField(
                        WaterMediumClassifier.fluidField(subLevel.getLevel(), localCell,
                                local.x, local.y, local.z))) {
                    return CameraWaterState.WET;
                }
                final WaterLevel level = carrierEntry.getValue().levels().get(roomEntry.getKey());
                if (level == null) {
                    // Topology arrived before its first level packet. Do not hide real water based
                    // on incomplete client state.
                    return CameraWaterState.OUTSIDE;
                }
                final double fraction = level.sample(now).fraction();
                if (fraction > 0.0D) {
                    final double surfaceY = CompartmentWaterGeometry.surfaceY(
                            subLevel.renderPose(), room.cells(), room.bounds(), fraction);
                    if (worldPosition.y < surfaceY - 1.0E-4D) {
                        // A wet overlapping room wins over a dry result.
                        return CameraWaterState.WET;
                    }
                }
                foundKnownDryInterior = true;
            }
        }
        return foundKnownDryInterior ? CameraWaterState.DRY : CameraWaterState.OUTSIDE;
    }

    /**
     * Exact tracked-room membership without the camera boundary margin used by fog smoothing.
     *
     * <p>This predicate is intentionally strict: callers use it to decide whether real parent
     * water belongs to an outside camera. Expanding the room by {@link #CAMERA_BOUNDARY_MARGIN}
     * would classify an eye just outside a hull as cabin air and suppress Photon's native
     * underwater path.</p>
     */
    public static boolean isInsideTrackedRoomExact(final Vec3 worldPosition) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return false;
        }
        for (final Map.Entry<UUID, CarrierSnapshot> carrierEntry : CARRIERS.entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel subLevel)) {
                continue;
            }
            final Vector3d local = subLevel.renderPose().transformPositionInverse(new Vector3d(
                    worldPosition.x, worldPosition.y, worldPosition.z));
            if (!local.isFinite()) {
                continue;
            }
            for (final RoomTopology room : carrierEntry.getValue().rooms().values()) {
                if (room.contains(local)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the parent world fluid at the centre of the compartment containing the given world
     * position is water. The room centre is deliberately used instead of the camera cell: a
     * floating hull bobs across the waterline, so per-camera-cell sampling would flip the
     * underwater state every frame.
     */
    public static boolean isCompartmentUnderwater(final Vec3 worldPosition) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return false;
        }
        for (final Map.Entry<UUID, CarrierSnapshot> carrierEntry : CARRIERS.entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel subLevel)) {
                continue;
            }
            final Vector3d local = subLevel.renderPose().transformPositionInverse(new Vector3d(
                    worldPosition.x, worldPosition.y, worldPosition.z));
            for (final Map.Entry<UUID, RoomTopology> roomEntry
                    : carrierEntry.getValue().rooms().entrySet()) {
                final RoomTopology room = roomEntry.getValue();
                if (!room.containsCamera(local)) {
                    continue;
                }
                final AABB bounds = room.bounds();
                final Vector3d centerWorld = subLevel.renderPose().transformPosition(new Vector3d(
                        (bounds.minX + bounds.maxX) * 0.5D,
                        (bounds.minY + bounds.maxY) * 0.5D,
                        (bounds.minZ + bounds.maxZ) * 0.5D));
                return minecraft.level.getFluidState(BlockPos.containing(
                        centerWorld.x, centerWorld.y, centerWorld.z)).is(FluidTags.WATER);
            }
        }
        return false;
    }

    public enum CameraWaterState {
        OUTSIDE,
        DRY,
        WET
    }

    public static void clear() {
        CARRIERS.clear();
        FloodedChamberRenderer.invalidateSnapshots();
    }

    public static void forget(final UUID carrierId) {
        CARRIERS.remove(carrierId);
        FloodedChamberRenderer.invalidateSnapshots();
    }

    public record RoomSnapshot(UUID id, long topologyRevision, AABB bounds,
                               List<BlockPos> cells, double waterFraction) {
        public RoomSnapshot {
            cells = List.copyOf(cells);
        }
    }

    private record CarrierSnapshot(long revision, Map<UUID, RoomTopology> rooms,
                                   Map<UUID, WaterLevel> levels) {
    }

    private record RoomTopology(AABB bounds, List<BlockPos> cells, Set<Long> cellKeys) {
        private RoomTopology(final AABB bounds, final List<BlockPos> cells) {
            this(bounds, List.copyOf(cells), cells.stream()
                    .map(BlockPos::asLong)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }

        private RoomTopology {
            cells = List.copyOf(cells);
            cellKeys = Set.copyOf(cellKeys);
        }

        private boolean contains(final Vector3dc localPosition) {
            return bounds.contains(localPosition.x(), localPosition.y(), localPosition.z())
                    && cellKeys.contains(BlockPos.containing(
                    localPosition.x(), localPosition.y(), localPosition.z()).asLong());
        }

        private boolean containsCamera(final Vector3dc localPosition) {
            if (contains(localPosition)) {
                return true;
            }
            if (!bounds.inflate(CAMERA_BOUNDARY_MARGIN).contains(
                    localPosition.x(), localPosition.y(), localPosition.z())) {
                return false;
            }
            final BlockPos cameraCell = BlockPos.containing(
                    localPosition.x(), localPosition.y(), localPosition.z());
            final double fractionX = localPosition.x() - Math.floor(localPosition.x());
            final double fractionY = localPosition.y() - Math.floor(localPosition.y());
            final double fractionZ = localPosition.z() - Math.floor(localPosition.z());
            for (int dx = -1; dx <= 1; dx++) {
                if (!nearInteriorSide(dx, fractionX)) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    if (!nearInteriorSide(dy, fractionY)) continue;
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx == 0 && dy == 0 && dz == 0)
                                || !nearInteriorSide(dz, fractionZ)) {
                            continue;
                        }
                        if (cellKeys.contains(BlockPos.asLong(
                                cameraCell.getX() + dx,
                                cameraCell.getY() + dy,
                                cameraCell.getZ() + dz))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static boolean nearInteriorSide(final int offset,
                                                final double fraction) {
            if (offset < 0) return fraction <= CAMERA_BOUNDARY_MARGIN;
            if (offset > 0) return fraction >= 1.0D - CAMERA_BOUNDARY_MARGIN;
            return true;
        }
    }

    private record WaterLevel(List<TimedFraction> samples) {
        private WaterLevel {
            samples = List.copyOf(samples);
        }

        private static WaterLevel initial(final double fraction, final long now) {
            return new WaterLevel(List.of(new TimedFraction(fraction, now)));
        }

        private WaterLevel append(final double fraction, final long now) {
            final List<TimedFraction> next = new ArrayList<>(samples.size() + 1);
            next.addAll(samples);
            final long timestamp = samples.isEmpty() ? now
                    : Math.max(now, samples.getLast().receivedNanos() + 1L);
            next.add(new TimedFraction(fraction, timestamp));
            if (next.size() > MAX_LEVEL_SAMPLES) {
                next.subList(0, next.size() - MAX_LEVEL_SAMPLES).clear();
            }
            return new WaterLevel(next);
        }

        private Sample sample(final long now) {
            if (samples.isEmpty()) {
                return new Sample(0.0D);
            }
            final long renderTime = now - RENDER_DELAY_NANOS;
            final TimedFraction first = samples.getFirst();
            if (renderTime <= first.receivedNanos()) {
                return new Sample(first.fraction());
            }
            for (int index = 1; index < samples.size(); index++) {
                final TimedFraction previous = samples.get(index - 1);
                final TimedFraction target = samples.get(index);
                if (renderTime > target.receivedNanos()) {
                    continue;
                }
                final double duration = Math.max(1.0D,
                        target.receivedNanos() - previous.receivedNanos());
                final double t = Math.clamp(
                        (renderTime - previous.receivedNanos()) / duration, 0.0D, 1.0D);
                // Linear interpolation is deliberately monotone: it cannot overshoot a server
                // water amount, unlike a cubic visual spline near a fill/drain direction change.
                return new Sample(previous.fraction()
                        + (target.fraction() - previous.fraction()) * t);
            }
            return new Sample(samples.getLast().fraction());
        }
    }

    private record TimedFraction(double fraction, long receivedNanos) {
    }

    private record Sample(double fraction) {
    }
}
