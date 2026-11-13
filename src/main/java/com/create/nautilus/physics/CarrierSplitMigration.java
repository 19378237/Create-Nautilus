package com.create.nautilus.physics;

import com.create.nautilus.data.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/** Captures before assembly removes blocks, and resolves every split child before physics. */
public final class CarrierSplitMigration {
    private static final ThreadLocal<Integer> MOVING_BLOCKS = ThreadLocal.withInitial(() -> 0);
    private CarrierSplitMigration() {}

    public record Capture(ServerSubLevel source, NautilusLevelData data,
                          CarrierState snapshot, Pose3d pose, BlockPos origin, BlockPos sourceOffset) {}

    public static Capture capture(final ServerLevel level, final BlockPos origin) {
        if (!(Sable.HELPER.getContaining(level, origin) instanceof ServerSubLevel source)) return null;
        final NautilusLevelData data = NautilusData.levelData(source);
        final NautilusLevelData.PendingSplit pending = data.pendingSplitForMember(source.getUniqueId());
        if (pending != null) return new Capture(source, data, pending.snapshot, pending.pose, origin.immutable(),
                pending.children.getOrDefault(source.getUniqueId(), BlockPos.ZERO));
        final CarrierState state = data.carrier(source.getUniqueId());
        if (state.hasPendingHullChanges() || state.lastCompartmentScanTick() == Long.MIN_VALUE)
            CompartmentSealer.rescanInto(source, state);
        state.updateRealWaterSources(CompartmentFluidInventory.measure(source, state));
        return new Capture(source, data, CarrierState.load(state.save()), new Pose3d(source.logicalPose()),
                origin.immutable(), BlockPos.ZERO);
    }

    public static void beginMovingBlocks() { MOVING_BLOCKS.set(MOVING_BLOCKS.get() + 1); }
    public static void endMovingBlocks() {
        final int depth = MOVING_BLOCKS.get() - 1;
        if (depth == 0) MOVING_BLOCKS.remove(); else MOVING_BLOCKS.set(depth);
    }
    public static boolean isMovingBlocks() { return MOVING_BLOCKS.get() != 0; }

    public static void record(final Capture capture, final ServerSubLevel child) {
        if (capture == null || child == null || child.isRemoved()) return;
        // Sable sets these only after allocation and block movement. An already-removed source
        // has no splitFrom entry; the pre-assembly capture still gives its authoritative identity.
        final UUID sourceId = child.getSplitFromSubLevel();
        if (sourceId != null && (!sourceId.equals(capture.source().getUniqueId())
                || child.getSplitFromPose() == null)) throw new IllegalStateException("Unexpected Sable split source");
        // assembleBlocks uses AssemblyTransform(origin, newPlot.centerBlock, Rotation.NONE).
        // This integer lattice translation survives negative coordinates, scale, roll and any
        // later centre-of-mass/rotationPoint changes; live-pose inversion would not.
        final BlockPos offset = child.getPlot().getCenterBlock().subtract(capture.origin());
        capture.data().recordSplit(capture.snapshot(), capture.pose(), capture.source().getUniqueId(),
                capture.sourceOffset(), child.getUniqueId(), offset, capture.source().isRemoved());
        CompartmentFluidInventory.forget(capture.source());
        CompartmentFluidInventory.forget(child);
        // Assembly has synchronously published all blocks at this return boundary. Finish here
        // whenever possible, before a heatmap caller can remove a child or any body can tick.
        // The physics prepass remains the retry path for genuinely incomplete/unloaded plots.
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(child.getLevel());
        if (container != null) resolvePending(container, capture.data());
    }

    public static void resolvePending(final ServerSubLevelContainer container, final NautilusLevelData data) {
        if (data.pendingSplits().isEmpty()) return;
        final Map<UUID, ServerSubLevel> loaded = new LinkedHashMap<>();
        for (final ServerSubLevel carrier : container.getAllSubLevels()) loaded.put(carrier.getUniqueId(), carrier);
        for (final NautilusLevelData.PendingSplit pending : List.copyOf(data.pendingSplits())) {
            final Map<UUID, List<CompartmentSealer.DetectedCompartment>> detected = new LinkedHashMap<>();
            final Map<UUID, Set<Long>> realSources = new LinkedHashMap<>();
            final Map<UUID, BlockPos> destinations = new LinkedHashMap<>(pending.children);
            if (!pending.sourceRemoved) destinations.put(pending.snapshot.carrierId(), BlockPos.ZERO);
            // A missing sibling cannot be resolved by repeatedly scanning the loaded plots.
            if (destinations.keySet().stream().anyMatch(id -> !loaded.containsKey(id) || loaded.get(id).isRemoved())) continue;
            if (!destinations.isEmpty()) {
                final long tick = loaded.get(destinations.keySet().iterator().next()).getLevel().getGameTime();
                if (pending.lastAttemptGameTick == tick) continue;
                pending.lastAttemptGameTick = tick;
            }
            boolean ready = true;
            for (final var destination : destinations.entrySet()) {
                final ServerSubLevel carrier = loaded.get(destination.getKey());
                // A temporarily unloaded sibling retains its share in the persisted transaction.
                if (carrier == null || carrier.isRemoved()) { ready = false; break; }
                final BlockPos offset = destination.getValue();
                final CarrierState membranes = new CarrierState(carrier.getUniqueId());
                for (final HullBreach breach : pending.snapshot.breaches())
                    membranes.addBreach(new HullBreach(breach.position().offset(offset), breach.compartmentId(),
                            breach.interiorDirection(), breach.areaSquareMetres(), breach.atmosphericOpening()));
                final CompartmentSealer.ScanResult scan = CompartmentSealer.detectCurrentScan(carrier, membranes);
                if (!scan.complete()) {
                    final CarrierState state = data.carrier(carrier.getUniqueId());
                    state.reportTopologyScanFailure("SPLIT_" + scan.status().name(), carrier.getLevel().getGameTime());
                    ready = false; break;
                }
                final BlockPos inverse = new BlockPos(-offset.getX(), -offset.getY(), -offset.getZ());
                detected.put(destination.getKey(), scan.compartments().stream().map(room ->
                        new CompartmentSealer.DetectedCompartment(room.bounds().move(inverse), room.volumeBlocks(),
                                CarrierState.translateCells(room.cells(), inverse),
                                CarrierState.translateCells(room.atmosphericOpenings(), inverse))).toList());
                realSources.put(destination.getKey(), CarrierState.translateCells(CompartmentFluidInventory.measure(carrier,
                        data.carrier(carrier.getUniqueId())).sources().keySet(), inverse));
            }
            if (ready && !data.completeSplit(pending, detected, realSources)) {
                final ServerSubLevel source = loaded.get(pending.snapshot.carrierId());
                if (source != null) data.carrier(source.getUniqueId()).reportTopologyScanFailure(
                        "SPLIT_GEOMETRY_AMBIGUOUS", source.getLevel().getGameTime());
            }
        }
    }
}
