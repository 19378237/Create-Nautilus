package com.create.nautilus.data;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.physics.CompartmentSealer;
import com.create.nautilus.physics.CompartmentFluidInventory;
import com.create.nautilus.physics.CompartmentContentAllocator;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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
    /** Topology rules version; exact membership is persisted separately from runtime caches. */
    public static final int CURRENT_TOPOLOGY_VERSION = 9;
    private final UUID carrierId;
    /** Persisted receipt: a duplicate notification cannot donate the source twice. */
    private UUID splitInventorySource;
    private final Map<UUID, CompartmentState> compartments = new LinkedHashMap<>();
    private final Map<Long, HullBreach> breaches = new LinkedHashMap<>();
    private double hullIntegrity = 1.0D;
    private long lastCompartmentScanTick = Long.MIN_VALUE;
    private long lastOxygenEffectsTick = Long.MIN_VALUE;
    private long lastVisualSyncTick = Long.MIN_VALUE;
    private int topologyVersion = CURRENT_TOPOLOGY_VERSION;
    private transient long topologyRevision;
    private transient long fluidRevision;
    private transient CompartmentFluidInventory.Snapshot appliedRealWater;
    private transient long lastBreachParticleTick = Long.MIN_VALUE;
    /** Repeat the first few scans while Sable finishes publishing a newly assembled plot. */
    private transient int topologyWarmupScansRemaining = 5;
    /** A known exact room is removed only after several consecutive empty scans. */
    private transient int consecutiveUnexpectedEmptyScans;
    /** BreakEvent is fired before the plot commits the air block; resolve it next physics step. */
    private final transient java.util.Set<Long> pendingBrokenHullCells =
            new java.util.LinkedHashSet<>();
    /** First pre-change membrane state, resolved against the final plot after event rollback. */
    private final transient Map<Long, Boolean> pendingCommittedCells = new LinkedHashMap<>();
    /**
     * Legacy rooms whose stored water may have come from an inferred breach. They are repaired only
     * after current geometry proves that the room still exists without the legacy virtual breach;
     * a real side or bottom opening makes it exterior-connected and prevents the repair.
     */
    private final transient java.util.Set<UUID> pendingLegacyWaterRepairs =
            new java.util.LinkedHashSet<>();
    private transient int completedLegacyWaterRepairs;
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
    /** Previous completed sub-step's connected-water entry count; allocation hint only. */
    private transient int lastConnectedWaterEntries;
    private transient long lastScanFailureDiagnosticTick = Long.MIN_VALUE;
    private transient String lastScanFailureReason;
    private transient boolean topologyRebuildDeferred;
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
    public long fluidRevision() { return fluidRevision; }
    public int lastConnectedWaterEntries() { return lastConnectedWaterEntries; }
    public void setLastConnectedWaterEntries(final int entries) { lastConnectedWaterEntries = entries; }
    public void invalidateFluidGeometry() { fluidRevision++; }
    public boolean needsTopologyRebuild() {
        return !topologyRebuildDeferred && (topologyVersion < CURRENT_TOPOLOGY_VERSION
                || compartments.values().stream().anyMatch(room -> !room.hasExactGeometry()));
    }
    public long lastBreachParticleTick() { return lastBreachParticleTick; }
    public void setLastBreachParticleTick(final long tick) { lastBreachParticleTick = tick; }
    public boolean needsTopologyWarmupScan(final long gameTime) {
        return topologyWarmupScansRemaining > 0 && lastCompartmentScanTick != gameTime;
    }
    public void recordTopologyScan(final boolean foundCompartment) {
        lastScanFailureReason = null;
        topologyRebuildDeferred = false;
        if (foundCompartment) {
            topologyWarmupScansRemaining = 0;
            consecutiveUnexpectedEmptyScans = 0;
        } else if (topologyWarmupScansRemaining > 0) {
            topologyWarmupScansRemaining--;
        }
    }
    public void reportTopologyScanFailure(final String reason, final long gameTime) {
        topologyRebuildDeferred = true;
        topologyWarmupScansRemaining = 0;
        if (!reason.equals(lastScanFailureReason) || lastScanFailureDiagnosticTick == Long.MIN_VALUE
                || gameTime - lastScanFailureDiagnosticTick >= 100L) {
            CreateNautilus.LOGGER.warn("Preserving carrier {} compartment inventory after {} topology scan (cell limit {})",
                    carrierId, reason, NautilusConfig.maximumCompartmentVolume());
            lastScanFailureReason = reason;
            lastScanFailureDiagnosticTick = gameTime;
        }
    }
    public boolean shouldDeferTopologyRetry(final long gameTime) {
        return topologyRebuildDeferred && lastCompartmentScanTick != Long.MIN_VALUE
                && gameTime - lastCompartmentScanTick < NautilusConfig.compartmentScanIntervalTicks();
    }

    public void updateRealWaterSources(final java.util.Set<Long> sources) {
        boolean changed = false;
        for (final CompartmentState room : compartments.values()) changed |= room.setRealWaterCells(sources);
        if (changed) topologyRevision++;
    }

    /** Rooms reuse immutable measurements, with invalidation owned by their geometry setters. */
    public void updateRealWaterSources(final CompartmentFluidInventory.Snapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        boolean changed = false;
        for (final CompartmentState room : compartments.values()) changed |= room.setRealWaterCells(snapshot);
        if (changed) topologyRevision++;
    }

    public CompartmentFluidInventory.Snapshot appliedRealWaterSnapshot() {
        return appliedRealWater;
    }

    public void setAppliedRealWaterSnapshot(final CompartmentFluidInventory.Snapshot snapshot) {
        appliedRealWater = snapshot;
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
    public void queueCommittedHullCell(final BlockPos pos, final boolean wasPassable) {
        pendingCommittedCells.putIfAbsent(pos.asLong(), wasPassable);
    }
    public boolean hasPendingHullChanges() {
        return !pendingCommittedCells.isEmpty() || !pendingBrokenHullCells.isEmpty();
    }
    public Map<Long, Boolean> takePendingCommittedHullCells() {
        final Map<Long, Boolean> copy = Map.copyOf(pendingCommittedCells);
        pendingCommittedCells.clear();
        return copy;
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
        final int repaired = completedLegacyWaterRepairs;
        completedLegacyWaterRepairs = 0;
        return repaired;
    }

    /** Releases object-heavy runtime state while retaining compact exact geometry and inventories. */
    public void clearRuntimeCachesOnUnload() {
        compartments.values().forEach(CompartmentState::clearRuntimeCachesOnUnload);
        appliedWaterMasses.clear();
        appliedRealWaterMasses.clear();
        appliedWaterMassTracker = null;
        appliedRealWater = null;
        previousLinearVelocity.zero();
        hasPreviousLinearVelocity = false;
        lastCompartmentScanTick = Long.MIN_VALUE;
        lastOxygenEffectsTick = Long.MIN_VALUE;
        lastVisualSyncTick = Long.MIN_VALUE;
        lastBreachParticleTick = Long.MIN_VALUE;
        lastBallastDiagnosticTick = Long.MIN_VALUE;
        lastHydrostaticDiagnosticTick = Long.MIN_VALUE;
        lastConnectedWaterEntries = 0;
        lastScanFailureDiagnosticTick = Long.MIN_VALUE;
        lastScanFailureReason = null;
        topologyWarmupScansRemaining = 5;
        consecutiveUnexpectedEmptyScans = 0;
        hydrostaticWakeStateInitialized = false;
        hydrostaticInputsChanging = false;
        topologyRebuildDeferred = false;
    }
    public Vector3d previousLinearVelocity() { return previousLinearVelocity; }
    public boolean hasPreviousLinearVelocity() { return hasPreviousLinearVelocity; }
    public void setHasPreviousLinearVelocity(final boolean value) { hasPreviousLinearVelocity = value; }
    public Map<UUID, AppliedWaterMass> appliedWaterMasses() { return appliedWaterMasses; }
    public Map<Long, Double> appliedRealWaterMasses() { return appliedRealWaterMasses; }
    public Object appliedWaterMassTracker() { return appliedWaterMassTracker; }
    public void setAppliedWaterMassTracker(final Object tracker) {
        appliedRealWater = null;
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

    public UUID splitInventorySource() { return splitInventorySource; }
    public void setSplitInventorySource(final UUID source) { splitInventorySource = source; }

    /** Installs one disjoint part of a jointly reconciled split; runtime mass records stay local. */
    public void installSplitTopology(final CarrierState reconciled, final java.util.Set<UUID> roomIds,
                                     final BlockPos offset) {
        compartments.clear(); breaches.clear(); pendingBrokenHullCells.clear(); pendingCommittedCells.clear();
        for (final CompartmentState room : reconciled.compartments()) {
            if (!roomIds.contains(room.id())) continue;
            final java.util.Set<Long> cells = translateCells(room.cells(), offset);
            final CompartmentState copy = new CompartmentState(room.id(), room.bounds().move(offset),
                    room.volumeBlocks(), cells);
            final java.util.Set<Long> real = new java.util.HashSet<>(room.cells());
            real.removeAll(room.waterCells());
            copy.setRealWaterCells(translateCells(real, offset));
            copy.restoreContents(room.oxygenMb(), room.standardGasMb(), room.waterMb());
            copy.setInternalPressureKpa(room.internalPressureKpa());
            compartments.put(copy.id(), copy);
        }
        for (final HullBreach breach : reconciled.breaches()) if (roomIds.contains(breach.compartmentId())) {
            addBreach(new HullBreach(breach.position().offset(offset), breach.compartmentId(),
                    breach.interiorDirection(), breach.areaSquareMetres(), breach.atmosphericOpening()));
        }
        hullIntegrity = reconciled.hullIntegrity;
        topologyVersion = CURRENT_TOPOLOGY_VERSION;
        pendingLegacyWaterRepairs.clear();
        refreshSealFlags(); topologyRevision++; fluidRevision++;
        appliedRealWater = null;
        lastCompartmentScanTick = Long.MIN_VALUE; lastVisualSyncTick = Long.MIN_VALUE;
        topologyWarmupScansRemaining = 5; topologyRebuildDeferred = false;
    }

    public static java.util.Set<Long> translateCells(final java.util.Set<Long> cells, final BlockPos offset) {
        if (offset.equals(BlockPos.ZERO)) return cells;
        return cells.stream().map(cell -> BlockPos.of(cell).offset(offset).asLong())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Discards unidentifiable legacy stock before it can defer an authoritative topology scan. */
    public void discardUnmatchedLegacyCompartments(final List<CompartmentSealer.DetectedCompartment> detected) {
        final List<CompartmentState> legacy = compartments.values().stream()
                .filter(room -> !room.hasExactGeometry()).toList();
        boolean discarded = false;
        for (final CompartmentState previous : legacy) {
            final long matches = detected.stream().filter(candidate ->
                    sameBounds(candidate.bounds(), previous.bounds())
                            && candidate.volumeBlocks() == previous.volumeBlocks()).count();
            final long possibleOwners = legacy.stream().filter(other ->
                    sameBounds(other.bounds(), previous.bounds())
                            && other.volumeBlocks() == previous.volumeBlocks()).count();
            if (matches != 1L || possibleOwners != 1L) {
                compartments.remove(previous.id());
                breaches.values().removeIf(breach -> breach.compartmentId().equals(previous.id()));
                pendingLegacyWaterRepairs.remove(previous.id());
                discarded = true;
            }
        }
        if (discarded) topologyRevision++;
    }

    /**
     * Matches exact cell overlap, preserving inventories and assigning every candidate a unique ID.
     * Legacy saves without cells require an unambiguous, unchanged bounds/volume match. If no
     * unique match exists, discard that old state and rebuild from the current scan.
     */
    public boolean reconcileCompartments(final List<CompartmentSealer.DetectedCompartment> detected) {
        final java.util.Set<Long> sources = new java.util.HashSet<>();
        for (final CompartmentState room : compartments.values()) {
            final java.util.Set<Long> occupied = new java.util.HashSet<>(room.cells());
            occupied.removeAll(room.waterCells()); sources.addAll(occupied);
        }
        return reconcileCompartments(detected, new Pose3d(), sources);
    }

    public boolean reconcileCompartments(final List<CompartmentSealer.DetectedCompartment> detected,
                                         final Pose3dc pose, final java.util.Set<Long> realSources) {
        reconcileContents(detected, pose, java.util.Collections.nCopies(detected.size(), realSources), null);
        return true;
    }

    /** Returns IDs in candidate order, even when different carriers have overlapping room geometry. */
    public List<UUID> reconcileSplitCompartments(final List<CompartmentSealer.DetectedCompartment> detected,
                                                 final Pose3dc pose,
                                                 final List<java.util.Set<Long>> realSources,
                                                 final List<java.util.Set<Long>> transferCells) {
        return reconcileContents(detected, pose, realSources, transferCells);
    }

    private List<UUID> reconcileContents(final List<CompartmentSealer.DetectedCompartment> detected,
                                         final Pose3dc pose,
                                         final List<java.util.Set<Long>> realSourcesByRoom,
                                         final List<java.util.Set<Long>> transferCells) {
        final java.util.Set<Long> realSources = new java.util.HashSet<>();
        realSourcesByRoom.forEach(realSources::addAll);
        discardUnmatchedLegacyCompartments(detected);
        // Periodic unchanged scans must neither reproject every room nor perturb inventories by
        // inverse-solve rounding. Changed source exclusions still take the full transfer path.
        if (transferCells == null && pendingLegacyWaterRepairs.isEmpty() && detected.size() == compartments.size()
                && breaches.values().stream().allMatch(b -> compartments.containsKey(b.compartmentId()))
                && detected.stream().allMatch(candidate -> compartments.values().stream().anyMatch(room -> {
                    if (!room.hasKnownRealWaterGeometry() || room.volumeBlocks() != candidate.volumeBlocks()
                            || !sameBounds(room.bounds(), candidate.bounds()) || !room.cells().equals(candidate.cells())) return false;
                    final java.util.Set<Long> available = new java.util.HashSet<>(candidate.cells());
                    available.removeAll(realSources); return room.waterCells().equals(available);
                }))) {
            topologyVersion = CURRENT_TOPOLOGY_VERSION;
            refreshSealFlags();
            return detected.stream().map(candidate -> compartments.values().stream()
                    .filter(room -> room.cells().equals(candidate.cells())).findFirst().orElseThrow().id()).toList();
        }
        final List<OldCompartment> old = compartments.values().stream()
                .map(OldCompartment::capture).toList();
        final Map<UUID, CompartmentState> replacement = new LinkedHashMap<>();
        final java.util.Set<UUID> reusedIds = new java.util.HashSet<>();
        final List<ReconciledCandidate> candidates = detected.stream()
                .map(ReconciledCandidate::new)
                .toList();
        final double[][] overlaps = new double[old.size()][candidates.size()];
        final List<CompartmentContentAllocator.Source> sources = new ArrayList<>();
        for (int sourceIndex = 0; sourceIndex < old.size(); sourceIndex++) {
            final OldCompartment previous = old.get(sourceIndex);
            final boolean repairLegacy = pendingLegacyWaterRepairs.contains(previous.id())
                    && breaches.values().stream().noneMatch(breach -> !breach.atmosphericOpening()
                    && breach.compartmentId().equals(previous.id()));
            final java.util.Set<Long> oldWaterCells;
            if (previous.state().hasKnownRealWaterGeometry()) oldWaterCells = previous.waterCells();
            else {
                final java.util.Set<Long> inferred = new java.util.HashSet<>(previous.cells());
                inferred.removeAll(realSources); oldWaterCells = java.util.Set.copyOf(inferred);
            }
            sources.add(new CompartmentContentAllocator.Source(previous.cells(), oldWaterCells,
                    previous.bounds(), previous.waterMb(), previous.standardGasMb(), previous.oxygenMb(), repairLegacy));
            for (int index = 0; index < candidates.size(); index++) {
                final double amount = transferCells == null || previous.cells().isEmpty()
                        ? overlap(candidates.get(index).candidate(), previous)
                        : transferCells.get(index).stream().filter(previous.cells()::contains).count();
                overlaps[sourceIndex][index] = amount;
                if (amount > 0.0D) {
                    candidates.get(index).sources.add(new SourceOverlap(previous, amount));
                    candidates.get(index).repairedLegacy |= repairLegacy;
                }
            }
        }
        final CompartmentContentAllocator.Contents contents = CompartmentContentAllocator.allocate(
                sources, detected, overlaps, pose, realSourcesByRoom,
                transferCells == null ? detected.stream().map(CompartmentSealer.DetectedCompartment::cells).toList()
                        : transferCells);
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).waterMb = contents.water()[i];
            candidates.get(i).standardGasMb = contents.gas()[i];
            candidates.get(i).oxygenMb = contents.oxygen()[i];
        }

        // Assign the strongest overlap globally before creating any new room. Candidate scan order
        // must not give a tiny split child the old identity ahead of the large surviving component.
        final Map<ReconciledCandidate, OldCompartment> identities = new LinkedHashMap<>();
        final List<IdentityMatch> matches = new ArrayList<>();
        for (final ReconciledCandidate candidate : candidates) {
            for (final SourceOverlap source : candidate.sources) {
                matches.add(new IdentityMatch(candidate, source.source(), source.overlap()));
            }
        }
        matches.sort(Comparator.comparingDouble(IdentityMatch::overlap).reversed()
                .thenComparing(match -> match.source().id()));
        for (final IdentityMatch match : matches) {
            if (!identities.containsKey(match.candidate()) && reusedIds.add(match.source().id())) {
                identities.put(match.candidate(), match.source());
            }
        }
        final java.util.Set<UUID> allocatedIds = new java.util.HashSet<>(reusedIds);
        final List<UUID> candidateIds = new ArrayList<>(candidates.size());
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            final ReconciledCandidate allocation = candidates.get(candidateIndex);
            final CompartmentSealer.DetectedCompartment candidate = allocation.candidate();
            final java.util.Set<Long> candidateSources = realSourcesByRoom.get(candidateIndex);
            final OldCompartment best = identities.get(allocation);
            if (best != null) {
                final CompartmentState room = best.state();
                if (room.volumeBlocks() != candidate.volumeBlocks()
                        || !sameBounds(room.bounds(), candidate.bounds())
                        || !room.cells().equals(candidate.cells())) {
                    room.updateGeometry(candidate.bounds(), candidate.volumeBlocks(), candidate.cells());
                }
                room.setRealWaterCells(candidateSources);
                room.restoreContents(allocation.oxygenMb(), allocation.standardGasMb(),
                        allocation.waterMb());
                replacement.put(room.id(), room);
                candidateIds.add(room.id());
            } else {
                UUID id;
                do { id = UUID.randomUUID(); } while (!allocatedIds.add(id));
                final CompartmentState room = new CompartmentState(id, candidate.bounds(),
                        candidate.volumeBlocks(), candidate.cells());
                room.setRealWaterCells(candidateSources);
                // A new room starts with ambient air. A split room, including a genuinely
                // exhausted/vacuum source, must retain its allocated inventory instead.
                if (allocation.hasSources()) {
                    room.restoreContents(allocation.oxygenMb(), allocation.standardGasMb(),
                            allocation.waterMb());
                }
                if (!allocation.hasSources()) {
                    // A genuinely new region still starts at ambient. Shared cells assigned to
                    // another split target cannot manufacture a second copy of its air.
                    final double initialGas = transferCells == null ? room.waterCapacityMb()
                            : transferCells.get(candidateIndex).stream()
                                    .filter(cell -> !candidateSources.contains(cell)).count() * 1000.0D;
                    room.restoreContents(initialGas * NautilusConfig.airDensityPerBlock() / 1000.0D,
                            initialGas, 0.0D);
                }
                replacement.put(id, room);
                candidateIds.add(id);
            }
        }

        compartments.clear();
        compartments.putAll(replacement);
        completedLegacyWaterRepairs = (int) candidates.stream().filter(candidate -> candidate.repairedLegacy).count();
        pendingLegacyWaterRepairs.clear();

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
                        || !previous.cells().equals(current.cells())
                        || !previous.waterCells().equals(current.waterCells())) {
                    topologyChanged = true;
                    break;
                }
            }
        }

        // 破洞的旧舱室消失时，重新绑定到相邻/包含的舱室；找不到则删除。
        breaches.values().removeIf(breach -> {
            for (final net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                final BlockPos adjacent = breach.position().relative(direction);
                Optional<CompartmentState> match = Optional.empty();
                if (transferCells == null) {
                    match = compartmentAt(adjacent);
                } else {
                    for (int i = 0; i < transferCells.size(); i++) if (transferCells.get(i).contains(adjacent.asLong())) {
                        match = compartment(candidateIds.get(i));
                        break;
                    }
                }
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
        return List.copyOf(candidateIds);
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
        return sameBounds(candidate.bounds(), previous.bounds())
                && candidate.volumeBlocks() == previous.volumeBlocks() ? candidate.volumeBlocks() : 0.0D;
    }

    /** One candidate's conserved contents plus the old-room sources eligible to donate its UUID. */
    private static final class ReconciledCandidate {
        private final CompartmentSealer.DetectedCompartment candidate;
        private final List<SourceOverlap> sources = new ArrayList<>();
        private double oxygenMb;
        private double standardGasMb;
        private double waterMb;
        private boolean repairedLegacy;

        private ReconciledCandidate(final CompartmentSealer.DetectedCompartment candidate) {
            this.candidate = candidate;
        }

        private CompartmentSealer.DetectedCompartment candidate() {
            return candidate;
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

    }

    private record SourceOverlap(OldCompartment source, double overlap) {
    }

    private record IdentityMatch(ReconciledCandidate candidate, OldCompartment source, double overlap) {}

    private record OldCompartment(CompartmentState state, UUID id, AABB bounds,
                                  int volumeBlocks, java.util.Set<Long> cells, java.util.Set<Long> waterCells,
                                  double oxygenMb, double standardGasMb, double waterMb) {
        static OldCompartment capture(final CompartmentState room) {
            return new OldCompartment(room, room.id(), room.bounds(), room.volumeBlocks(),
                    room.cells(), room.waterCells(), room.oxygenMb(), room.standardGasMb(), room.waterMb());
        }
    }

    public CompoundTag save() {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("carrier", carrierId);
        if (splitInventorySource != null) tag.putUUID("split_inventory_source", splitInventorySource);
        tag.putDouble("hull_integrity", hullIntegrity);
        tag.putInt("topology_version", CURRENT_TOPOLOGY_VERSION);
        final ListTag compartmentList = new ListTag();
        compartments.values().forEach(c -> compartmentList.add(c.save()));
        tag.put("compartments", compartmentList);
        final ListTag breachList = new ListTag();
        breaches.values().forEach(b -> breachList.add(b.save()));
        tag.put("breaches", breachList);
        final ListTag repairs = new ListTag();
        pendingLegacyWaterRepairs.forEach(id -> {
            final CompoundTag repair = new CompoundTag();
            repair.putUUID("room", id);
            repairs.add(repair);
        });
        tag.put("legacy_water_repairs", repairs);
        tag.putLongArray("pending_hull_breaks", pendingBrokenHullCells.stream().mapToLong(Long::longValue).sorted().toArray());
        final ListTag changes = new ListTag();
        pendingCommittedCells.forEach((cell, passable) -> {
            final CompoundTag change = new CompoundTag(); change.putLong("cell", cell);
            change.putBoolean("was_passable", passable); changes.add(change);
        });
        tag.put("pending_hull_changes", changes);
        return tag;
    }

    public static CarrierState load(final CompoundTag tag) {
        final CarrierState state = new CarrierState(tag.getUUID("carrier"));
        if (tag.hasUUID("split_inventory_source")) state.splitInventorySource = tag.getUUID("split_inventory_source");
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
        for (final Tag entry : tag.getList("legacy_water_repairs", Tag.TAG_COMPOUND)) {
            state.pendingLegacyWaterRepairs.add(((CompoundTag) entry).getUUID("room"));
        }
        for (final long cell : tag.getLongArray("pending_hull_breaks")) state.pendingBrokenHullCells.add(cell);
        for (final Tag entry : tag.getList("pending_hull_changes", Tag.TAG_COMPOUND)) {
            final CompoundTag change = (CompoundTag) entry;
            state.pendingCommittedCells.put(change.getLong("cell"), change.getBoolean("was_passable"));
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

