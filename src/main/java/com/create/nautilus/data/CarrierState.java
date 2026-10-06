package com.create.nautilus.data;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.physics.CompartmentSealer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 一个 Sable 载具的全部 Nautilus 状态。 */
public final class CarrierState {
    /** Persisted topology schema; exact cell membership is rebuilt after every world load. */
    public static final int CURRENT_TOPOLOGY_VERSION = 9;
    private final UUID carrierId;
    private final Map<UUID, CompartmentState> compartments = new LinkedHashMap<>();
    private final Map<Long, HullBreach> breaches = new LinkedHashMap<>();
    private double hullIntegrity = 1.0D;
    private long lastCompartmentScanTick = Long.MIN_VALUE;
    private long lastOxygenEffectsTick = Long.MIN_VALUE;
    private long lastVisualSyncTick = Long.MIN_VALUE;
    private int topologyVersion = CURRENT_TOPOLOGY_VERSION;
    private transient long topologyRevision;
    private transient long lastBreachParticleTick = Long.MIN_VALUE;
    /** Repeat the first few scans while Sable finishes publishing a newly assembled plot. */
    private transient int topologyWarmupScansRemaining = 5;
    /** A known room is removed only after several consecutive empty scans. */
    private transient int consecutiveUnexpectedEmptyScans;
    /** BreakEvent is fired before the plot commits the air block; resolve it next physics step. */
    private final transient java.util.Set<Long> pendingBrokenHullCells =
            new java.util.LinkedHashSet<>();
    /**
     * Legacy rooms whose stored water may have come from an inferred breach. They are repaired only
     * after current geometry proves that the room still exists without the legacy virtual breach;
     * a real side or bottom opening makes it exterior-connected and prevents the repair.
     */
    private final transient java.util.Set<UUID> pendingLegacyWaterRepairs =
            new java.util.LinkedHashSet<>();
    private final Vector3d previousLinearVelocity = new Vector3d();
    private boolean hasPreviousLinearVelocity;
    /** Runtime-only water masses already injected into Sable's self mass tracker. */
    private final transient Map<UUID, AppliedWaterMass> appliedWaterMasses = new LinkedHashMap<>();
    /**
     * Runtime-only direct masses for real source-water blocks, keyed by their local block position.
     * These entries never participate in compartment mB storage or virtual water rendering.
     */
    private final transient Map<Long, Double> appliedRealWaterMasses = new LinkedHashMap<>();
    /**
     * Identity of the Sable self-mass tracker which owns {@link #appliedWaterMasses}.
     *
     * <p>Sable replaces this tracker when a sub-level is assembled or rebuilt.  The numeric
     * water records must never survive that replacement: the new tracker contains only plot
     * blocks and therefore needs every compartment mass injected again.</p>
     */
    private transient Object appliedWaterMassTracker;
    /** Last game tick used by the deliberately low-frequency ballast diagnostic. */
    private transient long lastBallastDiagnosticTick = Long.MIN_VALUE;
    /** Last game tick used by the temporary hydrostatic balance diagnostic. */
    private transient long lastHydrostaticDiagnosticTick = Long.MIN_VALUE;
    /**
     * Previous inputs used only to wake a body when hydrostatic state starts changing.
     *
     * <p>Sable is allowed to put a motionless rigid body to sleep.  A loaded carrier can therefore
     * retain the sleeping state established by an older buoyancy implementation even after its
     * compartment has become full.  Waking on every physics sub-step would defeat Sable's sleeping
     * optimisation, so Nautilus remembers a short change episode and wakes only at its leading
     * edge (or after a real topology revision).</p>
     */
    private transient boolean hydrostaticWakeStateInitialized;
    private transient boolean hydrostaticInputsChanging;
    private transient double lastHydrostaticAirMass;
    private transient double lastHydrostaticWaterMb;
    private transient long lastHydrostaticWakeTopologyRevision = Long.MIN_VALUE;

    public CarrierState(final UUID carrierId) {
        this.carrierId = carrierId;
    }

    public UUID carrierId() { return carrierId; }
    public Collection<CompartmentState> compartments() { return compartments.values(); }
    public Collection<HullBreach> breaches() { return breaches.values(); }
    public double hullIntegrity() { return hullIntegrity; }
    public long lastCompartmentScanTick() { return lastCompartmentScanTick; }
    public void setLastCompartmentScanTick(final long tick) { lastCompartmentScanTick = tick; }
    public long lastOxygenEffectsTick() { return lastOxygenEffectsTick; }
    public void setLastOxygenEffectsTick(final long tick) { lastOxygenEffectsTick = tick; }
    public long lastVisualSyncTick() { return lastVisualSyncTick; }
    public void setLastVisualSyncTick(final long tick) { lastVisualSyncTick = tick; }
    public long topologyRevision() { return topologyRevision; }
    public boolean needsTopologyRebuild() {
        return topologyVersion < CURRENT_TOPOLOGY_VERSION
                || compartments.values().stream().anyMatch(room -> room.cells().isEmpty());
    }
    public long lastBreachParticleTick() { return lastBreachParticleTick; }
    public void setLastBreachParticleTick(final long tick) { lastBreachParticleTick = tick; }
    public boolean needsTopologyWarmupScan(final long gameTime) {
        return topologyWarmupScansRemaining > 0 && lastCompartmentScanTick != gameTime;
    }
    public void recordTopologyScan(final boolean foundCompartment) {
        if (foundCompartment) {
            topologyWarmupScansRemaining = 0;
            consecutiveUnexpectedEmptyScans = 0;
        } else if (topologyWarmupScansRemaining > 0) {
            topologyWarmupScansRemaining--;
        }
    }
    public boolean deferUnexpectedEmptyTopology() {
        consecutiveUnexpectedEmptyScans++;
        return consecutiveUnexpectedEmptyScans < 3;
    }
    public void acceptEmptyTopology() {
        consecutiveUnexpectedEmptyScans = 0;
    }
    public void queueBrokenHullCell(final BlockPos pos) {
        pendingBrokenHullCells.add(pos.asLong());
    }
    public java.util.Set<Long> takePendingBrokenHullCells() {
        final java.util.Set<Long> copy = java.util.Set.copyOf(pendingBrokenHullCells);
        pendingBrokenHullCells.clear();
        return copy;
    }

    /**
     * Completes the legacy water repair after an authoritative geometry scan.
     *
     * @return number of surviving, geometry-confirmed rooms restored to dry ambient air
     */
    public int repairLegacyContaminatedWaterAfterScan() {
        int repaired = 0;
        final java.util.Iterator<UUID> iterator = pendingLegacyWaterRepairs.iterator();
        while (iterator.hasNext()) {
            final UUID roomId = iterator.next();
            iterator.remove();
            final Optional<CompartmentState> candidate = compartment(roomId);
            if (candidate.isEmpty()) {
                // A real side/bottom opening makes the component exterior-connected, so the current
                // scan removes it. Never manufacture a dry room in that case.
                continue;
            }
            final boolean hasConfirmedHullBreach = breaches.values().stream()
                    .anyMatch(breach -> !breach.atmosphericOpening()
                            && breach.compartmentId().equals(roomId));
            if (hasConfirmedHullBreach) {
                continue;
            }
            // The old record was untrusted, but water already integrated through it is persistent.
            // Only a room which survives the membrane-free authoritative scan reaches this point.
            // A genuinely open side/bottom is exterior-connected and has no candidate to repair.
            final CompartmentState room = candidate.get();
            room.restoreContents(room.capacityMb(), room.waterCapacityMb(), 0.0D);
            room.setInternalPressureKpa(NautilusConfig.SURFACE_PRESSURE_KPA);
            repaired++;
        }
        return repaired;
    }
    public Vector3d previousLinearVelocity() { return previousLinearVelocity; }
    public boolean hasPreviousLinearVelocity() { return hasPreviousLinearVelocity; }
    public void setHasPreviousLinearVelocity(final boolean value) { hasPreviousLinearVelocity = value; }
    public Map<UUID, AppliedWaterMass> appliedWaterMasses() { return appliedWaterMasses; }
    public Map<Long, Double> appliedRealWaterMasses() { return appliedRealWaterMasses; }
    public Object appliedWaterMassTracker() { return appliedWaterMassTracker; }
    public void setAppliedWaterMassTracker(final Object tracker) {
        appliedWaterMassTracker = tracker;
    }
    public long lastBallastDiagnosticTick() { return lastBallastDiagnosticTick; }
    public void setLastBallastDiagnosticTick(final long tick) {
        lastBallastDiagnosticTick = tick;
    }
    public long lastHydrostaticDiagnosticTick() { return lastHydrostaticDiagnosticTick; }
    public void setLastHydrostaticDiagnosticTick(final long tick) {
        lastHydrostaticDiagnosticTick = tick;
    }

    /**
     * Returns {@code true} once when a new hydrostatic change episode begins.
     *
     * <p>Flooding changes water and retained-air volume on every physics sub-step. Treating every
     * numeric delta as a wake request would keep every wet carrier permanently awake. Instead, a
     * continuous run of deltas is one episode: its first sample wakes the body, subsequent samples
     * merely update the baseline, and a stable sample arms the next episode. A topology revision is
     * always an independent wake event. All fields involved are runtime-only.</p>
     */
    public boolean shouldWakeForHydrostaticChange(final double additionalAirMass,
                                                   final double totalWaterMb) {
        final double finiteAirMass = Double.isFinite(additionalAirMass)
                ? Math.max(0.0D, additionalAirMass) : 0.0D;
        final double finiteWaterMb = Double.isFinite(totalWaterMb)
                ? Math.max(0.0D, totalWaterMb) : 0.0D;

        if (!hydrostaticWakeStateInitialized) {
            hydrostaticWakeStateInitialized = true;
            hydrostaticInputsChanging = false;
            lastHydrostaticAirMass = finiteAirMass;
            lastHydrostaticWaterMb = finiteWaterMb;
            lastHydrostaticWakeTopologyRevision = topologyRevision;
            // This one initial wake releases carriers which were saved asleep under an older
            // hydrostatic balance, especially already-full compartments with no remaining air.
            return true;
        }

        final boolean topologyChanged = topologyRevision
                != lastHydrostaticWakeTopologyRevision;
        final boolean numericInputsChanged = materiallyDifferent(
                finiteAirMass, lastHydrostaticAirMass, 1.0E-7D)
                || materiallyDifferent(finiteWaterMb, lastHydrostaticWaterMb, 1.0E-3D);
        final boolean shouldWake = topologyChanged
                || (numericInputsChanged && !hydrostaticInputsChanging);

        hydrostaticInputsChanging = numericInputsChanged;
        lastHydrostaticAirMass = finiteAirMass;
        lastHydrostaticWaterMb = finiteWaterMb;
        lastHydrostaticWakeTopologyRevision = topologyRevision;
        return shouldWake;
    }

    private static boolean materiallyDifferent(final double current, final double previous,
                                                final double absoluteTolerance) {
        final double scale = Math.max(Math.abs(current), Math.abs(previous));
        return Math.abs(current - previous) > Math.max(absoluteTolerance, scale * 1.0E-9D);
    }

    public record AppliedWaterMass(BlockPos position, double kilograms) {}

    public void setHullIntegrity(final double value) {
        hullIntegrity = Math.clamp(value, 0.0D, 1.0D);
    }

    public Optional<CompartmentState> compartment(final UUID id) {
        return Optional.ofNullable(compartments.get(id));
    }

    public Optional<CompartmentState> compartmentAt(final BlockPos pos) {
        return compartments.values().stream().filter(c -> c.contains(pos)).findFirst();
    }

    public Optional<CompartmentState> compartmentAt(final Vector3dc pos) {
        return compartments.values().stream().filter(c -> c.contains(pos)).findFirst();
    }

    public void addBreach(final HullBreach breach) {
        breaches.put(breach.position().asLong(), breach);
    }

    public void removeBreach(final BlockPos pos) {
        breaches.remove(pos.asLong());
    }

    /**
     * 将新体素结果与旧舱室按包围盒交叠匹配，尽可能保持 UUID 和氧气/水量。
     */
    public void reconcileCompartments(final List<CompartmentSealer.DetectedCompartment> detected) {
        final List<OldCompartment> old = compartments.values().stream()
                .map(OldCompartment::capture)
                .toList();
        final Map<UUID, CompartmentState> replacement = new LinkedHashMap<>();
        final java.util.Set<UUID> reusedIds = new java.util.HashSet<>();
        final List<ReconciledCandidate> candidates = detected.stream()
                .map(ReconciledCandidate::new)
                .toList();

        /*
         * Distribute each old inventory exactly once across all of its surviving overlap. Using
         * overlap / old volume discarded contents whenever a room split or its scanned geometry
         * shrank. Normalizing by the old room's total candidate overlap preserves its complete
         * inventory; restoreContents remains the final physical capacity clamp for the new rooms.
         */
        for (final OldCompartment previous : old) {
            final double[] overlaps = new double[candidates.size()];
            double totalOverlap = 0.0D;
            for (int index = 0; index < candidates.size(); index++) {
                final double candidateOverlap = overlap(candidates.get(index).candidate(), previous);
                overlaps[index] = candidateOverlap;
                totalOverlap += candidateOverlap;
            }
            if (totalOverlap <= 0.0D) {
                // A vanished old room has no defensible destination for its stored contents.
                continue;
            }
            for (int index = 0; index < candidates.size(); index++) {
                if (overlaps[index] > 0.0D) {
                    candidates.get(index).addSource(previous, overlaps[index],
                            overlaps[index] / totalOverlap);
                }
            }
        }

        for (final ReconciledCandidate allocation : candidates) {
            final CompartmentSealer.DetectedCompartment candidate = allocation.candidate();
            final OldCompartment best = allocation.bestUnusedSource(reusedIds);
            if (best != null) {
                final CompartmentState room = best.state();
                room.updateGeometry(candidate.bounds(), candidate.volumeBlocks(), candidate.cells());
                room.restoreContents(allocation.oxygenMb(), allocation.standardGasMb(),
                        allocation.waterMb());
                replacement.put(room.id(), room);
                reusedIds.add(room.id());
            } else {
                final String stableKey = carrierId + ":" + candidate.bounds();
                final UUID id = UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8));
                final CompartmentState room = new CompartmentState(id, candidate.bounds(),
                        candidate.volumeBlocks(), candidate.cells());
                // A new room starts with ambient air. A split room, including a genuinely
                // exhausted/vacuum source, must retain its allocated inventory instead.
                if (allocation.hasSources()) {
                    room.restoreContents(allocation.oxygenMb(), allocation.standardGasMb(),
                            allocation.waterMb());
                }
                replacement.put(id, room);
            }
        }

        compartments.clear();
        compartments.putAll(replacement);

        // A periodic safety scan usually rediscovers byte-for-byte identical voxel geometry.
        // Treating every scan as a new topology made the client discard/rebuild all room meshes
        // once per second while its water level was moving. Only a real room/id/bounds/cell change
        // advances the revision; continuous contents are carried by the separate level payload.
        boolean topologyChanged = old.size() != replacement.size();
        if (!topologyChanged) {
            final Map<UUID, OldCompartment> oldById = old.stream().collect(
                    java.util.stream.Collectors.toMap(OldCompartment::id, value -> value));
            for (final Map.Entry<UUID, CompartmentState> entry : replacement.entrySet()) {
                final OldCompartment previous = oldById.get(entry.getKey());
                final CompartmentState current = entry.getValue();
                if (previous == null
                        || previous.volumeBlocks() != current.volumeBlocks()
                        || !sameBounds(previous.bounds(), current.bounds())
                        || !previous.cells().equals(current.cells())) {
                    topologyChanged = true;
                    break;
                }
            }
        }

        // 破洞的旧舱室消失时，重新绑定到相邻/包含的舱室；找不到则删除。
        breaches.values().removeIf(breach -> {
            for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                final Optional<CompartmentState> match = compartmentAt(
                        breach.position().relative(direction));
                if (match.isPresent()) {
                    breach.setCompartmentId(match.get().id());
                    breach.setInteriorDirection(direction);
                    return false;
                }
            }
            return true;
        });

        // A registered opening is treated as a temporary membrane by CompartmentSealer so the
        // flooded room keeps its identity, but it is still structurally unsealed for API/rendering
        // purposes.
        refreshSealFlags();
        topologyVersion = CURRENT_TOPOLOGY_VERSION;
        if (topologyChanged) {
            topologyRevision++;
        }
    }

    public void refreshSealFlags() {
        compartments.values().forEach(compartment -> compartment.setSealed(true));
        breaches.values().forEach(breach -> compartment(breach.compartmentId())
                .ifPresent(compartment -> compartment.setSealed(false)));
    }

    public List<CompartmentState> orderedCompartments() {
        return compartments.values().stream()
                .sorted(Comparator.comparingDouble(c -> c.bounds().getCenter().z))
                .toList();
    }

    private static List<BlockPos> adjacentPositions(final BlockPos pos) {
        return List.of(pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west());
    }

    private static double intersectionVolume(final AABB a, final AABB b) {
        final double x = Math.max(0.0D, Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX));
        final double y = Math.max(0.0D, Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY));
        final double z = Math.max(0.0D, Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ));
        return x * y * z;
    }

    private static boolean sameBounds(final AABB first, final AABB second) {
        return Double.compare(first.minX, second.minX) == 0
                && Double.compare(first.minY, second.minY) == 0
                && Double.compare(first.minZ, second.minZ) == 0
                && Double.compare(first.maxX, second.maxX) == 0
                && Double.compare(first.maxY, second.maxY) == 0
                && Double.compare(first.maxZ, second.maxZ) == 0;
    }

    private static double overlap(final CompartmentSealer.DetectedCompartment candidate,
                                  final OldCompartment previous) {
        if (!candidate.cells().isEmpty() && !previous.cells().isEmpty()) {
            return candidate.cells().stream().filter(previous.cells()::contains).count();
        }
        return intersectionVolume(candidate.bounds(), previous.bounds());
    }

    /** One candidate's conserved contents plus the old-room sources eligible to donate its UUID. */
    private static final class ReconciledCandidate {
        private final CompartmentSealer.DetectedCompartment candidate;
        private final List<SourceOverlap> sources = new ArrayList<>();
        private double oxygenMb;
        private double standardGasMb;
        private double waterMb;

        private ReconciledCandidate(final CompartmentSealer.DetectedCompartment candidate) {
            this.candidate = candidate;
        }

        private CompartmentSealer.DetectedCompartment candidate() {
            return candidate;
        }

        private void addSource(final OldCompartment source, final double sourceOverlap,
                               final double inventoryShare) {
            sources.add(new SourceOverlap(source, sourceOverlap));
            oxygenMb += source.oxygenMb() * inventoryShare;
            standardGasMb += source.standardGasMb() * inventoryShare;
            waterMb += source.waterMb() * inventoryShare;
        }

        private boolean hasSources() {
            return !sources.isEmpty();
        }

        private double oxygenMb() {
            return oxygenMb;
        }

        private double standardGasMb() {
            return standardGasMb;
        }

        private double waterMb() {
            return waterMb;
        }

        private OldCompartment bestUnusedSource(final java.util.Set<UUID> reusedIds) {
            OldCompartment best = null;
            double bestOverlap = 0.0D;
            for (final SourceOverlap source : sources) {
                if (!reusedIds.contains(source.source().id())
                        && source.overlap() > bestOverlap) {
                    best = source.source();
                    bestOverlap = source.overlap();
                }
            }
            return best;
        }
    }

    private record SourceOverlap(OldCompartment source, double overlap) {
    }

    private record OldCompartment(CompartmentState state, UUID id, AABB bounds,
                                  int volumeBlocks, java.util.Set<Long> cells,
                                  double oxygenMb, double standardGasMb, double waterMb) {
        static OldCompartment capture(final CompartmentState room) {
            return new OldCompartment(room, room.id(), room.bounds(), room.volumeBlocks(),
                    room.cells(), room.oxygenMb(), room.standardGasMb(), room.waterMb());
        }
    }

    public CompoundTag save() {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("carrier", carrierId);
        tag.putDouble("hull_integrity", hullIntegrity);
        tag.putInt("topology_version", CURRENT_TOPOLOGY_VERSION);
        final ListTag compartmentList = new ListTag();
        compartments.values().forEach(c -> compartmentList.add(c.save()));
        tag.put("compartments", compartmentList);
        final ListTag breachList = new ListTag();
        breaches.values().forEach(b -> breachList.add(b.save()));
        tag.put("breaches", breachList);
        return tag;
    }

    public static CarrierState load(final CompoundTag tag) {
        final CarrierState state = new CarrierState(tag.getUUID("carrier"));
        final int savedTopologyVersion = tag.contains("topology_version")
                ? tag.getInt("topology_version") : 0;
        state.topologyVersion = savedTopologyVersion;
        state.hullIntegrity = Math.clamp(tag.getDouble("hull_integrity"), 0.0D, 1.0D);
        for (final Tag entry : tag.getList("compartments", Tag.TAG_COMPOUND)) {
            final CompartmentState compartment = CompartmentState.load((CompoundTag) entry);
            state.compartments.put(compartment.id(), compartment);
        }
        for (final Tag entry : tag.getList("breaches", Tag.TAG_COMPOUND)) {
            final HullBreach breach = HullBreach.load((CompoundTag) entry);
            state.breaches.put(breach.position().asLong(), breach);
        }
        if (savedTopologyVersion < CURRENT_TOPOLOGY_VERSION) {
            // Topology versions through v6 could infer a destroyed hull voxel by searching from a
            // committed internal edit all the way through an open-top room to the exterior. Their
            // ordinary records therefore have no trustworthy provenance and must be discarded
            // once; geometry-derived atmospheric openings are safe and are regenerated by the
            // mandatory current-version topology scan.
            final java.util.Set<UUID> contaminatedRooms = state.breaches.values().stream()
                    .filter(breach -> !breach.atmosphericOpening())
                    .map(HullBreach::compartmentId)
                    .collect(java.util.stream.Collectors.toSet());
            final int before = state.breaches.size();
            state.breaches.values().removeIf(breach -> !breach.atmosphericOpening());
            final int removed = before - state.breaches.size();
            // Do not clear water here. The authoritative current scan must first prove that the
            // room still exists without treating the untrusted record as a virtual hull membrane.
            state.pendingLegacyWaterRepairs.addAll(contaminatedRooms);
            if (removed > 0) {
                CreateNautilus.LOGGER.info(
                        "Removed {} unverified legacy hull breaches and scheduled {} contaminated rooms for topology-v9 validation on carrier {}",
                        removed, contaminatedRooms.size(), state.carrierId);
            }
        }
        return state;
    }
}
