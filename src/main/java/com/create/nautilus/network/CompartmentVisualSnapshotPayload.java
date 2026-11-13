package com.create.nautilus.network;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Low-frequency compartment topology. Water levels are deliberately carried by
 * {@link CompartmentWaterLevelPayload}, so normal flooding no longer retransmits thousands of
 * voxel coordinates every five ticks.
 */
public record CompartmentVisualSnapshotPayload(UUID carrierId, long topologyRevision,
                                               List<Room> rooms)
        implements CustomPacketPayload {
    static final int MAX_ROOMS = 4096;
    static final int MAX_CELLS_PER_ROOM = 131_072;
    static final int MAX_CELLS_TOTAL = 1_000_000;
    public static final Type<CompartmentVisualSnapshotPayload> TYPE =
            new Type<>(CreateNautilus.id("compartment_visual_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CompartmentVisualSnapshotPayload>
            STREAM_CODEC = StreamCodec.of(CompartmentVisualSnapshotPayload::write,
            CompartmentVisualSnapshotPayload::read);

    public CompartmentVisualSnapshotPayload {
        carrierId = Objects.requireNonNull(carrierId, "carrierId");
        rooms = List.copyOf(Objects.requireNonNull(rooms, "rooms"));
        if (topologyRevision < 0L || rooms.size() > MAX_ROOMS) {
            throw new IllegalArgumentException("Invalid compartment topology header");
        }
        final long total = rooms.stream().mapToLong(room -> room.cells().size()).sum();
        if (total > MAX_CELLS_TOTAL) {
            throw new IllegalArgumentException("Too many compartment cells: " + total);
        }
        final HashSet<UUID> identities = new HashSet<>();
        for (final Room room : rooms) {
            if (!identities.add(room.id())) {
                throw new IllegalArgumentException("Duplicate compartment identity: " + room.id());
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(final RegistryFriendlyByteBuf buffer,
                              final CompartmentVisualSnapshotPayload payload) {
        buffer.writeUUID(payload.carrierId());
        buffer.writeVarLong(payload.topologyRevision());
        buffer.writeVarInt(payload.rooms().size());
        for (final Room room : payload.rooms()) {
            buffer.writeUUID(room.id());
            final AABB b = room.bounds();
            buffer.writeDouble(b.minX);
            buffer.writeDouble(b.minY);
            buffer.writeDouble(b.minZ);
            buffer.writeDouble(b.maxX);
            buffer.writeDouble(b.maxY);
            buffer.writeDouble(b.maxZ);
            buffer.writeVarInt(room.cells().size());
            room.cells().forEach(buffer::writeBlockPos);
        }
    }

    private static CompartmentVisualSnapshotPayload read(final RegistryFriendlyByteBuf buffer) {
        final UUID carrierId = buffer.readUUID();
        final long revision = buffer.readVarLong();
        final int roomCount = buffer.readVarInt();
        if (roomCount < 0 || roomCount > MAX_ROOMS) {
            throw new IllegalArgumentException("Invalid compartment count: " + roomCount);
        }
        final List<Room> rooms = new ArrayList<>(roomCount);
        int totalCells = 0;
        for (int roomIndex = 0; roomIndex < roomCount; roomIndex++) {
            final UUID id = buffer.readUUID();
            final AABB bounds = readBounds(buffer);
            final int cellCount = buffer.readVarInt();
            if (cellCount < 0 || cellCount > MAX_CELLS_PER_ROOM
                    || totalCells + cellCount > MAX_CELLS_TOTAL) {
                throw new IllegalArgumentException("Invalid compartment cell count: " + cellCount);
            }
            totalCells += cellCount;
            final List<BlockPos> cells = new ArrayList<>(cellCount);
            for (int cellIndex = 0; cellIndex < cellCount; cellIndex++) {
                cells.add(buffer.readBlockPos());
            }
            rooms.add(new Room(id, bounds, cells));
        }
        return new CompartmentVisualSnapshotPayload(carrierId, revision, rooms);
    }

    private static AABB readBounds(final RegistryFriendlyByteBuf buffer) {
        final double minX = buffer.readDouble();
        final double minY = buffer.readDouble();
        final double minZ = buffer.readDouble();
        final double maxX = buffer.readDouble();
        final double maxY = buffer.readDouble();
        final double maxZ = buffer.readDouble();
        // AABB normalizes reversed endpoints, so validate the wire values before construction.
        if (!validBounds(minX, minY, minZ, maxX, maxY, maxZ)) {
            throw new IllegalArgumentException("Invalid compartment bounds");
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean validBounds(final double minX, final double minY, final double minZ,
                                       final double maxX, final double maxY, final double maxZ) {
        return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                && minX < maxX && minY < maxY && minZ < maxZ;
    }

    static void handleClient(final CompartmentVisualSnapshotPayload payload,
                             final IPayloadContext context) {
        ClientCompartmentVisualCache.acceptTopology(payload);
    }

    public record Room(UUID id, AABB bounds, List<BlockPos> cells) {
        public Room {
            id = Objects.requireNonNull(id, "id");
            bounds = Objects.requireNonNull(bounds, "bounds");
            cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
            if (cells.size() > MAX_CELLS_PER_ROOM) {
                throw new IllegalArgumentException("Too many room cells: " + cells.size());
            }
            if (!validBounds(bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX, bounds.maxY, bounds.maxZ)) {
                throw new IllegalArgumentException("Invalid compartment bounds");
            }
            for (final BlockPos cell : cells) {
                if (cell.getX() < bounds.minX || cell.getY() < bounds.minY
                        || cell.getZ() < bounds.minZ || cell.getX() + 1.0D > bounds.maxX
                        || cell.getY() + 1.0D > bounds.maxY || cell.getZ() + 1.0D > bounds.maxZ) {
                    throw new IllegalArgumentException("Compartment cell lies outside its bounds");
                }
            }
        }
    }
}
