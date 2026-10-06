package com.create.nautilus.network;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Lightweight, once-per-game-tick continuous water-level update. */
public record CompartmentWaterLevelPayload(UUID carrierId, long topologyRevision,
                                          List<RoomLevel> rooms)
        implements CustomPacketPayload {
    static final int MAX_ROOMS = 4096;
    public static final Type<CompartmentWaterLevelPayload> TYPE =
            new Type<>(CreateNautilus.id("compartment_water_levels"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CompartmentWaterLevelPayload>
            STREAM_CODEC = StreamCodec.of(CompartmentWaterLevelPayload::write,
            CompartmentWaterLevelPayload::read);

    public CompartmentWaterLevelPayload {
        carrierId = Objects.requireNonNull(carrierId, "carrierId");
        rooms = List.copyOf(Objects.requireNonNull(rooms, "rooms"));
        if (topologyRevision < 0L || rooms.size() > MAX_ROOMS) {
            throw new IllegalArgumentException("Invalid water-level payload");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(final RegistryFriendlyByteBuf buffer,
                              final CompartmentWaterLevelPayload payload) {
        buffer.writeUUID(payload.carrierId());
        buffer.writeVarLong(payload.topologyRevision());
        buffer.writeVarInt(payload.rooms().size());
        for (final RoomLevel room : payload.rooms()) {
            buffer.writeUUID(room.id());
            buffer.writeFloat((float) room.waterFraction());
        }
    }

    private static CompartmentWaterLevelPayload read(final RegistryFriendlyByteBuf buffer) {
        final UUID carrierId = buffer.readUUID();
        final long revision = buffer.readVarLong();
        final int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ROOMS) {
            throw new IllegalArgumentException("Invalid water-level room count: " + count);
        }
        final List<RoomLevel> rooms = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rooms.add(new RoomLevel(buffer.readUUID(), buffer.readFloat()));
        }
        return new CompartmentWaterLevelPayload(carrierId, revision, rooms);
    }

    static void handleClient(final CompartmentWaterLevelPayload payload,
                             final IPayloadContext context) {
        ClientCompartmentVisualCache.acceptLevels(payload);
    }

    public record RoomLevel(UUID id, double waterFraction) {
        public RoomLevel {
            id = Objects.requireNonNull(id, "id");
            waterFraction = Math.clamp(waterFraction, 0.0D, 1.0D);
        }
    }
}
