package com.create.nautilus.network;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ClientDryCompartmentCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative server-to-client snapshot of compartment voxels whose parent-ocean rendering is
 * suppressed. Wet rooms are redrawn by Nautilus' local water-surface renderer.
 *
 * <p>The packed longs are {@link net.minecraft.core.BlockPos#asLong() plot
 * coordinates}, not transformed world coordinates. Sable gives the client the
 * same reserved plot coordinates as the server, so no pose data belongs in
 * this payload.</p>
 *
 * <p>The upper bound prevents a malformed peer or an accidentally unbounded
 * scan from allocating an arbitrary array. NeoForge's packet splitter handles
 * valid snapshots that exceed a single vanilla packet.</p>
 */
public record DryCompartmentSnapshotPayload(UUID carrierId, long revision, long[] dryCells)
        implements CustomPacketPayload {
    public static final int MAX_DRY_CELLS = 1_000_000;

    public static final Type<DryCompartmentSnapshotPayload> TYPE =
            new Type<>(CreateNautilus.id("dry_compartment_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DryCompartmentSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(DryCompartmentSnapshotPayload::write,
                    DryCompartmentSnapshotPayload::read);

    public DryCompartmentSnapshotPayload {
        carrierId = Objects.requireNonNull(carrierId, "carrierId");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        dryCells = normalize(dryCells);
    }

    /**
     * Records containing arrays are otherwise only shallowly immutable. Return
     * a copy so packet callers cannot mutate a snapshot after publication.
     */
    @Override
    public long[] dryCells() {
        return dryCells.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(final RegistryFriendlyByteBuf buffer,
                              final DryCompartmentSnapshotPayload payload) {
        buffer.writeUUID(payload.carrierId);
        buffer.writeVarLong(payload.revision);
        buffer.writeLongArray(payload.dryCells);
    }

    private static DryCompartmentSnapshotPayload read(final RegistryFriendlyByteBuf buffer) {
        final UUID carrierId = buffer.readUUID();
        final long revision = buffer.readVarLong();
        final long[] dryCells = buffer.readLongArray(null, MAX_DRY_CELLS);
        return new DryCompartmentSnapshotPayload(carrierId, revision, dryCells);
    }

    /**
     * NeoForge invokes play payload handlers on the main thread by default.
     * Keeping this bridge in the payload class prevents common registration
     * code from eagerly linking Minecraft client classes on a dedicated server.
     */
    static void handleClient(final DryCompartmentSnapshotPayload payload,
                             final IPayloadContext context) {
        ClientDryCompartmentCache.accept(payload);
    }

    private static long[] normalize(final long[] source) {
        Objects.requireNonNull(source, "dryCells");
        if (source.length > MAX_DRY_CELLS) {
            throw new IllegalArgumentException(
                    "Dry-cell snapshot has " + source.length + " cells; maximum is " + MAX_DRY_CELLS);
        }

        final long[] sorted = source.clone();
        Arrays.sort(sorted);
        if (sorted.length < 2) {
            return sorted;
        }

        int uniqueLength = 1;
        for (int index = 1; index < sorted.length; index++) {
            if (sorted[index] != sorted[uniqueLength - 1]) {
                sorted[uniqueLength++] = sorted[index];
            }
        }
        return uniqueLength == sorted.length ? sorted : Arrays.copyOf(sorted, uniqueLength);
    }
}
