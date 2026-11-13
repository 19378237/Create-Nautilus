package com.create.nautilus.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import com.create.nautilus.physics.CompartmentSealer;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 挂在父 ServerLevel 上的可序列化 Attachment。
 *
 * <p>Sable 的 ServerSubLevel 不是 NeoForge Attachment holder，因此不能把附件
 * 直接装到载具对象上。这里按 carrier UUID -> compartment UUID 分层存储，既使用
 * 官方 Attachment 持久化，又不会把状态绑到可能被拆分/重建的方块实体。</p>
 */
public final class NautilusLevelData implements INBTSerializable<CompoundTag> {
    private final Map<UUID, CarrierState> carriers = new LinkedHashMap<>();
    private final Map<UUID, PendingSplit> pendingSplits = new LinkedHashMap<>();

    public CarrierState carrier(final UUID id) {
        return carriers.computeIfAbsent(id, CarrierState::new);
    }

    public Collection<CarrierState> carriers() {
        return carriers.values();
    }

    public CarrierState existingCarrier(final UUID id) { return carriers.get(id); }
    public Collection<PendingSplit> pendingSplits() { return pendingSplits.values(); }
    public boolean hasPendingSplit(final UUID id) {
        return pendingSplitForMember(id) != null;
    }
    public PendingSplit pendingSplit(final UUID source) { return pendingSplits.get(source); }
    public PendingSplit pendingSplitForMember(final UUID member) {
        final PendingSplit source = pendingSplits.get(member);
        return source != null ? source : pendingSplits.values().stream()
                .filter(pending -> pending.children.containsKey(member)).findFirst().orElse(null);
    }
    public void finishSplit(final UUID source) { pendingSplits.remove(source); }
    public void recordSplit(final CarrierState snapshot, final Pose3dc pose, final UUID child,
                            final BlockPos offset, final boolean sourceRemoved) {
        final PendingSplit group = pendingSplitForMember(snapshot.carrierId());
        if (group != null && !group.snapshot.carrierId().equals(snapshot.carrierId())) {
            recordSplit(group.snapshot, group.pose, snapshot.carrierId(),
                    group.children.get(snapshot.carrierId()), child, offset, sourceRemoved);
            return;
        }
        recordSplit(snapshot, pose, snapshot.carrierId(), BlockPos.ZERO, child, offset, sourceRemoved);
    }

    /** A child can split while the common donor waits; keep every descendant in that donor's lattice. */
    public void recordSplit(final CarrierState snapshot, final Pose3dc pose, final UUID member,
                            final BlockPos memberOffset, final UUID child,
                            final BlockPos childOffset, final boolean memberRemoved) {
        if (carrier(child).splitInventorySource() != null) return;
        final PendingSplit pending = pendingSplits.computeIfAbsent(snapshot.carrierId(),
                // During a delayed transfer, pumps/oxygen interfaces can still mutate the source
                // through its public API. Keep one inventory object, not an immutable copy that
                // could later resurrect everything consumed while this transaction was pending.
                ignored -> new PendingSplit(memberRemoved && member.equals(snapshot.carrierId()) ? snapshot
                        : carriers.getOrDefault(snapshot.carrierId(), snapshot), new Pose3d(pose)));
        if (pending.children.putIfAbsent(child, memberOffset.offset(childOffset).immutable()) == null) {
            pending.lastAttemptGameTick = Long.MIN_VALUE;
        }
        if (memberRemoved) {
            if (member.equals(snapshot.carrierId())) pending.sourceRemoved = true;
            else pending.children.remove(member);
        }
    }

    /** All candidate cells and real sources are expressed in the original source plot lattice. */
    public boolean completeSplit(final PendingSplit pending,
                                 final Map<UUID, java.util.List<CompartmentSealer.DetectedCompartment>> detected,
                                 final java.util.Set<Long> realSources) {
        final Map<UUID, Set<Long>> byCarrier = new LinkedHashMap<>();
        detected.keySet().forEach(id -> byCarrier.put(id, realSources));
        return completeSplit(pending, detected, byCarrier);
    }

    /** Source masks remain carrier-local even when another carrier's cavity overlaps this one. */
    public boolean completeSplit(final PendingSplit pending,
                                 final Map<UUID, List<CompartmentSealer.DetectedCompartment>> detected,
                                 final Map<UUID, Set<Long>> realSources) {
        if (pendingSplits.get(pending.snapshot.carrierId()) != pending) return true;
        if (!detected.keySet().containsAll(pending.children.keySet())
                || (!pending.sourceRemoved && !detected.containsKey(pending.snapshot.carrierId()))) return false;
        final List<CompartmentSealer.DetectedCompartment> all = new ArrayList<>();
        final List<UUID> owners = new ArrayList<>();
        final List<Set<Long>> sourceMasks = new ArrayList<>();
        for (final var destination : detected.entrySet()) {
            final Set<Long> occupied = new HashSet<>();
            for (final var room : destination.getValue()) {
                // Rooms within one scan must be disjoint. Across plots, nested hulls legitimately
                // leave overlapping air volumes after the inner hull's blocks have been moved.
                for (final long cell : room.cells()) if (!occupied.add(cell)) return false;
                all.add(room);
                owners.add(destination.getKey());
                sourceMasks.add(realSources.getOrDefault(destination.getKey(), Set.of()));
            }
        }
        final List<Set<Long>> transferCells = splitTransferCells(all, owners);
        final CarrierState reconciled = CarrierState.load(pending.snapshot.save());
        final List<UUID> candidateIds = reconciled.reconcileSplitCompartments(
                all, pending.pose, sourceMasks, transferCells);
        int index = 0;
        for (final var destination : detected.entrySet()) {
            final UUID id = destination.getKey();
            final java.util.Set<UUID> roomIds = new java.util.HashSet<>();
            for (int i = 0; i < destination.getValue().size(); i++) roomIds.add(candidateIds.get(index++));
            final CarrierState target = carrier(id);
            target.installSplitTopology(reconciled, roomIds, pending.children.getOrDefault(id, BlockPos.ZERO));
            if (!id.equals(pending.snapshot.carrierId())) target.setSplitInventorySource(pending.snapshot.carrierId());
        }
        finishSplit(pending.snapshot.carrierId());
        return true;
    }

    /**
     * The most specific surviving cavity receives old cells shared with an enclosing cavity.
     * Only inventory ownership is partitioned: every target keeps its complete scanned geometry.
     * Stable ties prevent iteration/tracking order from changing the recipient.
     */
    private static List<Set<Long>> splitTransferCells(
            final List<CompartmentSealer.DetectedCompartment> rooms, final List<UUID> owners) {
        final List<Integer> order = new ArrayList<>();
        final List<Set<Long>> result = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) { order.add(i); result.add(Set.of()); }
        order.sort(Comparator.<Integer>comparingInt(i -> rooms.get(i).cells().size())
                .thenComparing(owners::get));
        final Set<Long> claimed = new HashSet<>();
        for (final int index : order) {
            final Set<Long> owned = new HashSet<>();
            for (final long cell : rooms.get(index).cells()) if (claimed.add(cell)) owned.add(cell);
            result.set(index, Set.copyOf(owned));
        }
        return result;
    }

    public void removeCarrier(final UUID id) {
        carriers.remove(id);
        final PendingSplit split = pendingSplits.get(id);
        if (split != null) { split.sourceRemoved = true; split.lastAttemptGameTick = Long.MIN_VALUE; }
        // Permanent removal follows the vanished-region capacity rule. A temporary unload never
        // calls this method, so it retains the child and keeps the joint transfer pending.
        for (final PendingSplit pending : pendingSplits.values()) {
            if (pending.children.remove(id) != null) pending.lastAttemptGameTick = Long.MIN_VALUE;
        }
    }

    @Override
    public CompoundTag serializeNBT(final HolderLookup.Provider provider) {
        final CompoundTag root = new CompoundTag();
        final ListTag list = new ListTag();
        carriers.values().forEach(carrier -> list.add(carrier.save()));
        root.put("carriers", list);
        final ListTag splits = new ListTag();
        for (final PendingSplit pending : pendingSplits.values()) splits.add(pending.save());
        root.put("pending_splits", splits);
        return root;
    }

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag nbt) {
        carriers.clear();
        pendingSplits.clear();
        for (final Tag entry : nbt.getList("carriers", Tag.TAG_COMPOUND)) {
            final CarrierState carrier = CarrierState.load((CompoundTag) entry);
            carriers.put(carrier.carrierId(), carrier);
        }
        for (final Tag entry : nbt.getList("pending_splits", Tag.TAG_COMPOUND)) {
            final PendingSplit split = PendingSplit.load((CompoundTag) entry, carriers);
            pendingSplits.put(split.snapshot.carrierId(), split);
        }
        mergeSavedDescendantSplits();
    }

    /** Older captures could save an independent child transaction before the parent's commit. */
    private void mergeSavedDescendantSplits() {
        boolean merged;
        do {
            merged = false;
            for (final PendingSplit descendant : List.copyOf(pendingSplits.values())) {
                final UUID member = descendant.snapshot.carrierId();
                final List<PendingSplit> parents = pendingSplits.values().stream()
                        .filter(candidate -> candidate != descendant && candidate.children.containsKey(member)).toList();
                if (parents.size() != 1) continue;
                final PendingSplit parent = parents.getFirst();
                if (!hasUniqueSplitRoot(parent, member)) continue;
                final BlockPos offset = parent.children.get(member);
                final Map<UUID, BlockPos> translated = new LinkedHashMap<>();
                descendant.children.forEach((child, childOffset) -> translated.put(child, offset.offset(childOffset)));
                if (translated.entrySet().stream().anyMatch(entry -> parent.children.containsKey(entry.getKey())
                        && !parent.children.get(entry.getKey()).equals(entry.getValue()))) continue;
                translated.forEach(parent.children::putIfAbsent);
                if (descendant.sourceRemoved) parent.children.remove(member);
                parent.lastAttemptGameTick = Long.MIN_VALUE;
                pendingSplits.remove(member);
                merged = true;
                break;
            }
        } while (merged);
    }

    private boolean hasUniqueSplitRoot(PendingSplit current, final UUID descendant) {
        final Set<UUID> visited = new HashSet<>();
        visited.add(descendant);
        while (current != null) {
            final UUID id = current.snapshot.carrierId();
            if (!visited.add(id)) return false;
            final List<PendingSplit> parents = pendingSplits.values().stream()
                    .filter(candidate -> candidate.children.containsKey(id)).toList();
            if (parents.size() > 1) return false;
            current = parents.isEmpty() ? null : parents.getFirst();
        }
        return true;
    }

    /** Persisted only while a split group waits for all plots to be ready/loaded. */
    public static final class PendingSplit {
        public final CarrierState snapshot;
        public final Pose3d pose;
        public final Map<UUID, BlockPos> children = new LinkedHashMap<>();
        public boolean sourceRemoved;
        public transient long lastAttemptGameTick = Long.MIN_VALUE;
        private PendingSplit(final CarrierState snapshot, final Pose3d pose) {
            this.snapshot = snapshot; this.pose = pose;
        }
        private CompoundTag save() {
            final CompoundTag tag = new CompoundTag(); tag.put("snapshot", snapshot.save());
            final double[] values = {pose.position().x, pose.position().y, pose.position().z,
                    pose.orientation().x, pose.orientation().y, pose.orientation().z, pose.orientation().w,
                    pose.rotationPoint().x, pose.rotationPoint().y, pose.rotationPoint().z,
                    pose.scale().x, pose.scale().y, pose.scale().z};
            for (int i = 0; i < values.length; i++) tag.putDouble("pose_" + i, values[i]);
            tag.putBoolean("source_removed", sourceRemoved);
            final ListTag list = new ListTag();
            children.forEach((id, offset) -> { final CompoundTag child = new CompoundTag();
                child.putUUID("id", id); child.putInt("offset_x", offset.getX());
                child.putInt("offset_y", offset.getY()); child.putInt("offset_z", offset.getZ()); list.add(child); });
            tag.put("children", list); return tag;
        }
        private static PendingSplit load(final CompoundTag tag, final Map<UUID, CarrierState> carriers) {
            final Pose3d pose = new Pose3d();
            pose.position().set(tag.getDouble("pose_0"), tag.getDouble("pose_1"), tag.getDouble("pose_2"));
            pose.orientation().set(tag.getDouble("pose_3"), tag.getDouble("pose_4"), tag.getDouble("pose_5"), tag.getDouble("pose_6"));
            pose.rotationPoint().set(tag.getDouble("pose_7"), tag.getDouble("pose_8"), tag.getDouble("pose_9"));
            pose.scale().set(tag.getDouble("pose_10"), tag.getDouble("pose_11"), tag.getDouble("pose_12"));
            final CompoundTag snapshotTag = tag.getCompound("snapshot");
            final CarrierState liveSource = carriers.get(snapshotTag.getUUID("carrier"));
            final PendingSplit split = new PendingSplit(liveSource != null ? liveSource : CarrierState.load(snapshotTag), pose);
            split.sourceRemoved = tag.getBoolean("source_removed");
            for (final Tag entry : tag.getList("children", Tag.TAG_COMPOUND)) {
                final CompoundTag child = (CompoundTag) entry;
                split.children.put(child.getUUID("id"), child.contains("offset_x")
                        ? new BlockPos(child.getInt("offset_x"), child.getInt("offset_y"), child.getInt("offset_z"))
                        : BlockPos.of(child.getLong("offset")));
            }
            return split;
        }
    }
}

