package com.create.nautilus.network;

import com.create.nautilus.CreateNautilus;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import dev.ryanhcode.sable.util.BoundedBitVolume3i;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronizes two deliberately different occlusion volumes.
 *
 * <p>The server removes parent-water physics from the complete tracked room and Nautilus restores
 * entity water state from its continuous stored surface. The client receives only the dry cells
 * at and above that surface; already-flooded cells retain normal parent-water volume and therefore
 * do not become shader-dependent black/transparent holes.</p>
 */
public final class DryCompartmentSync {
    private static final Map<UUID, ServerSnapshot> CURRENT = new ConcurrentHashMap<>();

    private DryCompartmentSync() {
    }

    public static long syncToTrackingPlayers(final ServerSubLevel carrier,
                                             final long[] physicalDryCells,
                                             final long[] visualOcclusionCells) {
        Objects.requireNonNull(carrier, "carrier");
        final UUID carrierId = Objects.requireNonNull(carrier.getUniqueId(), "carrier UUID");
        return syncNormalizedToTrackingPlayers(carrier, OcclusionCells.copyOf(physicalDryCells),
                CompartmentVisualSync.isTopologyRejected(carrierId) ? OcclusionCells.empty()
                        : OcclusionCells.copyOf(visualOcclusionCells));
    }

    /** Internal producers retain immutable, canonical masks instead of sorting them each tick. */
    public static long syncNormalizedToTrackingPlayers(final ServerSubLevel carrier,
                                                       final OcclusionCells physicalDryCells,
                                                       final OcclusionCells visualOcclusionCells) {
        Objects.requireNonNull(carrier, "carrier");
        final UUID carrierId = Objects.requireNonNull(carrier.getUniqueId(), "carrier UUID");
        final OcclusionCells physical = Objects.requireNonNull(physicalDryCells, "physicalDryCells");
        final OcclusionCells visual = CompartmentVisualSync.isTopologyRejected(carrierId)
                    ? OcclusionCells.empty()
                    : Objects.requireNonNull(visualOcclusionCells, "visualOcclusionCells");
        final ServerSnapshot previous = CURRENT.get(carrierId);
        final Set<UUID> players = carrier.getTrackingPlayers().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final boolean sameVisual = previous != null
                && previous.payload().matchesNormalizedCells(visual.values());
        final boolean samePhysical = previous != null
                && previous.physicalDryCells().sameCells(physical);

        if (sameVisual) {
            for (final UUID playerId : players) {
                if (!previous.trackingPlayers().contains(playerId)) {
                    sendTo(carrier, playerId, previous.payload());
                }
            }
            final OcclusionRegistration region = samePhysical
                    && previous.serverOcclusion() != null
                    ? previous.serverOcclusion()
                    : replaceServerOcclusion(carrier, physical, previous.serverOcclusion());
            if (!samePhysical || region != previous.serverOcclusion()
                    || !players.equals(previous.trackingPlayers())) {
                CURRENT.put(carrierId, new ServerSnapshot(previous.payload(), physical, region,
                        players));
            }
            return previous.payload().revision();
        }

        final long revision = previous == null ? 1L
                : Math.incrementExact(previous.payload().revision());
        final DryCompartmentSnapshotPayload payload;
        try {
            payload = new DryCompartmentSnapshotPayload(carrierId, revision, visual.values());
        } catch (final IllegalArgumentException exception) {
            CreateNautilus.LOGGER.error("Refusing invalid compartment-occlusion snapshot for {}",
                    carrierId, exception);
            return previous == null ? 0L : previous.payload().revision();
        }
        // Waterline/rendering changes do not alter the server's physical cell mask.
        final OcclusionRegistration region = samePhysical && previous.serverOcclusion() != null
                ? previous.serverOcclusion()
                : replaceServerOcclusion(carrier, physical,
                        previous == null ? null : previous.serverOcclusion());
        CURRENT.put(carrierId, new ServerSnapshot(payload, physical, region, players));
        for (final UUID playerId : players) {
            sendTo(carrier, playerId, payload);
        }
        return revision;
    }

    public static void syncToPlayer(final ServerSubLevel carrier, final ServerPlayer player) {
        final UUID carrierId = Objects.requireNonNull(carrier.getUniqueId(), "carrier UUID");
        final ServerSnapshot snapshot = CURRENT.get(carrierId);
        final DryCompartmentSnapshotPayload payload = snapshot == null
                ? new DryCompartmentSnapshotPayload(carrierId, 0L, new long[0])
                : snapshot.payload();
        PacketDistributor.sendToPlayer(player, payload);
        if (snapshot != null) {
            final Set<UUID> players = new HashSet<>(snapshot.trackingPlayers());
            players.add(player.getUUID());
            CURRENT.put(carrierId, new ServerSnapshot(snapshot.payload(),
                    snapshot.physicalDryCells(), snapshot.serverOcclusion(),
                    Set.copyOf(players)));
        }
    }

    /** The same UUID can return with an empty client cache after respawn or dimension travel. */
    public static void forgetPlayer(final UUID playerId) {
        CURRENT.replaceAll((carrierId, previous) -> {
            if (!previous.trackingPlayers().contains(playerId)) return previous;
            final Set<UUID> retained = new HashSet<>(previous.trackingPlayers());
            retained.remove(playerId);
            return new ServerSnapshot(previous.payload(), previous.physicalDryCells(),
                    previous.serverOcclusion(), Set.copyOf(retained));
        });
    }

    public static void forget(final UUID carrierId) {
        final ServerSnapshot removed = CURRENT.remove(carrierId);
        if (removed != null && removed.serverOcclusion() != null) {
            try {
                removed.serverOcclusion().container().removeRegion(
                        removed.serverOcclusion().region());
            } catch (final RuntimeException exception) {
                // Level unload may tear down the occlusion container before the fallback cleanup.
                // The static snapshot is already gone, so never let teardown escape into the tick.
                CreateNautilus.LOGGER.debug("Occlusion region for {} was already unloaded",
                        carrierId, exception);
            }
        }
    }

    public static void clear() {
        new HashSet<>(CURRENT.keySet()).forEach(DryCompartmentSync::forget);
    }

    private static OcclusionRegistration replaceServerOcclusion(
            final ServerSubLevel carrier, final OcclusionCells physicalDryCells,
            final OcclusionRegistration previous) {
        if (previous != null) {
            previous.container().removeRegion(previous.region());
        }
        final WaterOcclusionContainer<?> container =
                WaterOcclusionContainer.getContainer(carrier.getLevel());
        final BoundedBitVolume3i volume = toVolume(physicalDryCells.values());
        if (container == null || volume == null) {
            return null;
        }
        return new OcclusionRegistration(container, container.addRegion(volume));
    }

    private static BoundedBitVolume3i toVolume(final long[] cells) {
        if (cells.length == 0) {
            return null;
        }
        final List<BlockPos> positions = Arrays.stream(cells).mapToObj(BlockPos::of).toList();
        return BoundedBitVolume3i.fromBlocks(positions);
    }

    private static void sendTo(final ServerSubLevel carrier, final UUID playerId,
                               final DryCompartmentSnapshotPayload payload) {
        if (carrier.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private record OcclusionRegistration(WaterOcclusionContainer<?> container,
                                         WaterOcclusionRegion region) {
    }

    private record ServerSnapshot(DryCompartmentSnapshotPayload payload,
                                  OcclusionCells physicalDryCells,
                                  OcclusionRegistration serverOcclusion,
                                  Set<UUID> trackingPlayers) {
        // The immutable physical mask can be retained directly across sync and tracking changes.
    }
}
