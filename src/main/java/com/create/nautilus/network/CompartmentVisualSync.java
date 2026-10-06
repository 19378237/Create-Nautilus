package com.create.nautilus.network;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Publishes low-frequency room topology and high-frequency continuous water levels separately. */
public final class CompartmentVisualSync {
    private static final Map<UUID, TopologyBroadcast> TOPOLOGY = new ConcurrentHashMap<>();

    private CompartmentVisualSync() {
    }

    public static void syncTopology(final ServerSubLevel carrier, final CarrierState state) {
        final UUID carrierId = Objects.requireNonNull(state.carrierId(), "carrier UUID");
        final long revision = state.topologyRevision();
        final List<ServerPlayer> players = trackingPlayers(carrier);
        final Set<UUID> playerIds = players.stream().map(ServerPlayer::getUUID)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final TopologyBroadcast previous = TOPOLOGY.get(carrierId);

        if (previous != null && previous.revision() == revision) {
            for (final ServerPlayer player : players) {
                if (!previous.trackingPlayers().contains(player.getUUID())) {
                    PacketDistributor.sendToPlayer(player, previous.payload());
                }
            }
            TOPOLOGY.put(carrierId, previous.withTrackingPlayers(playerIds));
            return;
        }

        final List<CompartmentState> compartments = List.copyOf(state.compartments());
        final String invalidReason = validateTopologySize(compartments);
        if (invalidReason != null) {
            rejectTopology(carrierId, revision, invalidReason, players);
            return;
        }
        if (players.isEmpty()) {
            TOPOLOGY.remove(carrierId);
            return;
        }

        final CompartmentVisualSnapshotPayload payload;
        try {
            final List<CompartmentVisualSnapshotPayload.Room> rooms = compartments.stream()
                    .map(room -> new CompartmentVisualSnapshotPayload.Room(
                            room.id(), room.bounds(),
                            room.cells().stream().map(BlockPos::of).toList()))
                    .toList();
            payload = new CompartmentVisualSnapshotPayload(carrierId, revision, rooms);
        } catch (final IllegalArgumentException exception) {
            rejectTopology(carrierId, revision, exception.getMessage(), players);
            return;
        }

        players.forEach(player -> PacketDistributor.sendToPlayer(player, payload));
        TOPOLOGY.put(carrierId, new TopologyBroadcast(
                revision, payload, playerIds, false));
    }

    public static void syncLevels(final ServerSubLevel carrier, final CarrierState state) {
        final UUID carrierId = Objects.requireNonNull(state.carrierId(), "carrier UUID");
        final List<ServerPlayer> players = trackingPlayers(carrier);
        if (players.isEmpty()) {
            TOPOLOGY.computeIfPresent(carrierId,
                    (ignored, previous) -> previous.withTrackingPlayers(Set.of()));
            return;
        }
        // Tracking changes independently of compartment rescans. Reuse the cached topology
        // each tick, but send it to a newly tracking connection before its first level packet.
        syncTopology(carrier, state);
        final TopologyBroadcast topology = TOPOLOGY.get(carrierId);
        if (topology != null && topology.revision() == state.topologyRevision()
                && topology.rejected()) {
            return;
        }
        // Include dry rooms as zero-fraction entries. Omitting them would leave the client's last
        // nonzero surface alive after drainage or a repaired topology transition.
        final List<CompartmentWaterLevelPayload.RoomLevel> rooms = state.compartments().stream()
                .map(room -> new CompartmentWaterLevelPayload.RoomLevel(
                        room.id(), room.waterCapacityMb() <= 0.0D ? 0.0D
                        : room.waterMb() / room.waterCapacityMb()))
                .toList();
        final CompartmentWaterLevelPayload payload = new CompartmentWaterLevelPayload(
                carrierId, state.topologyRevision(), rooms);
        players.forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    /** Immediate full publication used by placement/repair events outside the physics callback. */
    public static void sync(final ServerSubLevel carrier, final CarrierState state) {
        syncLevels(carrier, state);
    }

    /** A UUID is not a client cache lifetime: dimension travel and respawn can reuse it. */
    public static void forgetPlayer(final UUID playerId) {
        TOPOLOGY.replaceAll((carrierId, previous) -> {
            if (!previous.trackingPlayers().contains(playerId)) return previous;
            final Set<UUID> retained = new java.util.HashSet<>(previous.trackingPlayers());
            retained.remove(playerId);
            return previous.withTrackingPlayers(retained);
        });
    }

    static boolean isTopologyRejected(final UUID carrierId) {
        final TopologyBroadcast topology = TOPOLOGY.get(carrierId);
        return topology != null && topology.rejected();
    }

    public static void forget(final UUID carrierId) {
        TOPOLOGY.remove(Objects.requireNonNull(carrierId, "carrierId"));
    }

    public static void clear() {
        TOPOLOGY.clear();
    }

    private static List<ServerPlayer> trackingPlayers(final ServerSubLevel carrier) {
        final List<ServerPlayer> players = new ArrayList<>();
        for (final UUID playerId : carrier.getTrackingPlayers()) {
            if (carrier.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                players.add(player);
            }
        }
        return List.copyOf(players);
    }

    private static String validateTopologySize(final List<CompartmentState> rooms) {
        if (rooms.size() > CompartmentVisualSnapshotPayload.MAX_ROOMS) {
            return "room count " + rooms.size() + " exceeds "
                    + CompartmentVisualSnapshotPayload.MAX_ROOMS;
        }
        long totalCells = 0L;
        for (final CompartmentState room : rooms) {
            final int roomCells = room.cells().size();
            if (roomCells > CompartmentVisualSnapshotPayload.MAX_CELLS_PER_ROOM) {
                return "room " + room.id() + " has " + roomCells + " cells; maximum is "
                        + CompartmentVisualSnapshotPayload.MAX_CELLS_PER_ROOM;
            }
            totalCells += roomCells;
            if (totalCells > CompartmentVisualSnapshotPayload.MAX_CELLS_TOTAL) {
                return "total cell count " + totalCells + " exceeds "
                        + CompartmentVisualSnapshotPayload.MAX_CELLS_TOTAL;
            }
        }
        return null;
    }

    private static void rejectTopology(final UUID carrierId, final long revision,
                                       final String reason, final List<ServerPlayer> players) {
        final TopologyBroadcast previous = TOPOLOGY.get(carrierId);
        if (previous == null || previous.revision() != revision || !previous.rejected()) {
            CreateNautilus.LOGGER.error(
                    "Refusing compartment topology {} for carrier {}: {}. No partial topology was sent.",
                    revision, carrierId, reason);
        }
        // Withdraw the previous client geometry atomically, rather than leaving its last
        // water level and ocean cutout frozen after an unsupported topology change.
        final CompartmentVisualSnapshotPayload empty =
                new CompartmentVisualSnapshotPayload(carrierId, revision, List.of());
        players.forEach(player -> PacketDistributor.sendToPlayer(player, empty));
        final Set<UUID> playerIds = players.stream().map(ServerPlayer::getUUID)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        TOPOLOGY.put(carrierId, new TopologyBroadcast(revision, empty, playerIds, true));
    }

    private record TopologyBroadcast(long revision,
                                     CompartmentVisualSnapshotPayload payload,
                                     Set<UUID> trackingPlayers,
                                     boolean rejected) {
        private TopologyBroadcast {
            trackingPlayers = Set.copyOf(trackingPlayers);
            Objects.requireNonNull(payload, "payload");
        }

        private TopologyBroadcast withTrackingPlayers(final Set<UUID> players) {
            return new TopologyBroadcast(revision, payload, players, rejected);
        }
    }
}
