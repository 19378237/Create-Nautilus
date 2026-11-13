package com.create.nautilus.physics;

import com.create.nautilus.CreateNautilus;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;

/** Optional call counts, collected locally and merged once per carrier physics sub-step. */
final class WaterQueryDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("create_nautilus.waterQueryDiagnostics");
    private static final int BATCH_SAMPLES = 4096;
    static final int MAX_TRACKED_ENTRIES = 65536;
    static final int READ_POINT = 0;
    static final int READ_BAND = 1;
    static final int READ_ABOVE = 2;
    static final int READ_SOURCE_WALK = 3;
    static final int WALK_CACHED = 0;
    static final int WALK_DRY_ABOVE = 1;
    static final int WALK_NON_SOURCE_ABOVE = 2;
    static final int WALK_BUILD_HEIGHT = 3;
    // The off path neither creates a ThreadLocal nor installs a value on a server/client thread.
    private static final ThreadLocal<WaterQueryDiagnostics> CURRENT = ENABLED ? new ThreadLocal<>() : null;
    private static final String LENGTH_BUCKETS =
            "0,1,2-3,4-7,8-15,16-31,32-63,64-127,128-255,256-511,512-1023,"
                    + "1024-2047,2048-4095,4096-8191,8192-16383,16384-32767,32768-65535,65536+";
    private static WaterQueryDiagnostics batch;

    // Histogram index is the number of queried integer columns, including a distinct centre.
    final long[] cellColumns = new long[10];
    final long[] faceColumns = new long[6];
    // Hit indices refer to the original insertion order; diagnostics never reorder the array.
    final long[] cornerHitPositions = new long[8];
    final long[] centreHitPositions = new long[8];
    long cornerLookups;
    long cornerComparisons;
    long centreLookups;
    long centreComparisons;
    long cellCentreFallbacks;
    long cellCentreQueries;
    long faceCentreQueries;
    long vanillaHeightQueries;
    long customHeightQueries;
    long vanillaAboveReads;
    long vanillaAboveReuses;
    long vanillaOccupiedTopReuses;
    long chunkLookups;
    long bandQueries;
    long bandRejected;
    long bandUnavailableChunks;
    long bandSteps;
    long bandDrySteps;
    long bandWaterSteps;
    long bandHits;
    long bandMisses;
    long bandUniqueVoxels;
    long bandRepeatedVoxelSteps;
    long bandUntrackedVoxelSteps;
    long bandUniqueDryVoxels;
    long bandUniqueWaterVoxels;
    long bandRepeatedDrySteps;
    long bandRepeatedWaterSteps;
    long bandUntrackedDrySteps;
    long bandUntrackedWaterSteps;
    // Per phase: point, band, vanilla above-height check, connected-source walk.
    final long[] directFluidRequests = new long[4];
    final long[] directFluidReads = new long[4];
    final long[] directFluidCacheHits = new long[4];
    long fluidCacheCapBypasses;
    // Reads after an unsupported fluid disabled the cache; the triggering read is not included.
    long fluidCacheDisabledReads;
    long fluidCacheDisables;
    long fluidCachePeakEntries;
    // First/repeated here refer to all direct phases together within this context.
    final long[] directFluidFirstObserved = new long[4];
    final long[] directFluidRepeated = new long[4];
    // These additionally distinguish first/repeated observations within the named phase.
    final long[] directFluidPhaseUnique = new long[4];
    final long[] directFluidPhaseRepeated = new long[4];
    final long[] directFluidUntracked = new long[4];
    long worldColumnQueries;
    long trackedWorldColumns;
    long untrackedWorldColumnQueries;
    final long[] trackedColumnQueryHistogram = new long[18];
    final long[] bandRequestedLengthHistogram = new long[18];
    final long[] bandScannedLengthHistogram = new long[18];
    // A walk ending at cached water observes only its traversed prefix, not a complete water run.
    final long[] sourceObservedWalkLengthHistogram = new long[18];
    final long[] sourceWalkEnds = new long[4];
    long sourceObservedWalkVoxels;
    // Exclusive preparedSurface outcomes: missing, same inputs/fraction, fraction resolve,
    // then changed domain/bounds/pose bit masks 1..7. Wrappers do not count another lookup.
    final long[] geometryPreparationOutcomes = new long[10];
    final long[] geometryCandidateOutcomes = new long[2]; // matching fraction, candidate solve
    long geometryProjectedCells;
    long connectedStartHits;
    long connectedStartMisses;
    long connectedWalkHits;
    long connectedLocalSurfaces;
    long connectedUpwardSteps;
    long connectedBackfilledVoxels;
    long connectedCacheExpectedEntries;
    long connectedCacheExpectedEntriesMax;
    long connectedCacheEntries;
    long connectedCacheEntriesMax;
    long apertureSampleHits;
    long apertureSampleMisses;
    long apertureSampleCustomBypasses;
    long preparedLookups;
    long preparedMissing;
    long preparedGeometryMisses;
    long preparedPoseMatches;
    long preparedPoseMisses;
    long preparedMatches;
    long projectionQueries;
    long projectionReused;
    long projectionComputed;
    long intervalIndexBuilds;
    long intervalIndexEntries;
    long intervalIndexBuildNanos;
    long hullBoundsCells;
    long hullInspectedCells;
    long hullEffectiveCells;
    long hullSkippedEmptySectionCells;
    long hullSkippedSections;
    long hullNativeSectionReads;
    long hullFallbackReads;
    long hullPrepareNanos;
    // Diagnostics instances only exist when enabled. The set dies with its owning sub-step.
    private final LongOpenHashSet bandVoxels = new LongOpenHashSet();
    private final Long2IntOpenHashMap fluidReadPhaseMasks = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap columnQueries = new Long2LongOpenHashMap();
    private long contexts;
    private long samples;

    /** Explicitly scoped by the dispatcher, never by a QueryContext constructor. */
    static WaterQueryDiagnostics begin(final WaterQueryDiagnostics diagnostics) {
        if (!ENABLED) return null;
        final WaterQueryDiagnostics previous = CURRENT.get();
        if (diagnostics == null) CURRENT.remove();
        else CURRENT.set(diagnostics);
        return previous;
    }

    static void restore(final WaterQueryDiagnostics previous) {
        if (!ENABLED) return;
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    static WaterQueryDiagnostics current() { return ENABLED ? CURRENT.get() : null; }

    void recordBandVoxel(final long packed, final boolean water) {
        bandSteps++;
        if (water) bandWaterSteps++;
        else bandDrySteps++;
        if (bandVoxels.size() < MAX_TRACKED_ENTRIES) {
            if (bandVoxels.add(packed)) {
                bandUniqueVoxels++;
                if (water) bandUniqueWaterVoxels++;
                else bandUniqueDryVoxels++;
            } else {
                bandRepeatedVoxelSteps++;
                if (water) bandRepeatedWaterSteps++;
                else bandRepeatedDrySteps++;
            }
        } else if (bandVoxels.contains(packed)) {
            bandRepeatedVoxelSteps++;
            if (water) bandRepeatedWaterSteps++;
            else bandRepeatedDrySteps++;
        } else {
            // Count steps excluded by the cap; do not mislabel them as distinct voxels.
            bandUntrackedVoxelSteps++;
            if (water) bandUntrackedWaterSteps++;
            else bandUntrackedDrySteps++;
        }
    }

    /** Counts existing direct chunk reads only; custom getHeight-internal world reads are unknown. */
    void recordFluidRead(final int phase, final long packed) {
        directFluidReads[phase]++;
        final int previous = fluidReadPhaseMasks.get(packed);
        final int bit = 1 << phase;
        if (previous != 0) {
            directFluidRepeated[phase]++;
            if ((previous & bit) == 0) {
                directFluidPhaseUnique[phase]++;
                fluidReadPhaseMasks.put(packed, previous | bit);
            } else directFluidPhaseRepeated[phase]++;
        } else if (fluidReadPhaseMasks.size() < MAX_TRACKED_ENTRIES) {
            fluidReadPhaseMasks.put(packed, bit);
            directFluidFirstObserved[phase]++;
            directFluidPhaseUnique[phase]++;
        } else directFluidUntracked[phase]++;
    }

    void recordColumnQuery(final long column) {
        worldColumnQueries++;
        final long previous = columnQueries.get(column);
        if (previous != 0) columnQueries.put(column, previous + 1);
        else if (columnQueries.size() < MAX_TRACKED_ENTRIES) {
            columnQueries.put(column, 1L);
            trackedWorldColumns++;
        } else untrackedWorldColumnQueries++;
    }

    void finishObservations() {
        Arrays.fill(trackedColumnQueryHistogram, 0L);
        for (final long queries : columnQueries.values()) {
            trackedColumnQueryHistogram[lengthBucket(queries)]++;
        }
    }

    void recordBandRequestedLength(final int length) { bandRequestedLengthHistogram[lengthBucket(length)]++; }

    void recordBandScannedLength(final int length) { bandScannedLengthHistogram[lengthBucket(length)]++; }

    void recordSourceWalk(final int observedVoxels, final int end) {
        sourceObservedWalkVoxels += observedVoxels;
        sourceObservedWalkLengthHistogram[lengthBucket(observedVoxels)]++;
        sourceWalkEnds[end]++;
    }

    void recordGeometryPreparation(final int outcome, final int projectedCells) {
        geometryPreparationOutcomes[outcome]++;
        geometryProjectedCells += projectedCells;
    }

    private static int lengthBucket(final long length) {
        return length <= 0 ? 0 : Math.min(17, Long.SIZE - Long.numberOfLeadingZeros(length));
    }

    void recordPreparedLookup(final boolean available, final boolean geometryMatches,
                              final boolean poseMatches) {
        preparedLookups++;
        if (!available) {
            preparedMissing++;
            return;
        }
        if (!geometryMatches) preparedGeometryMisses++;
        if (poseMatches) preparedPoseMatches++;
        else preparedPoseMisses++;
        if (geometryMatches && poseMatches) preparedMatches++;
    }

    void recordProjection(final boolean reused) {
        projectionQueries++;
        if (reused) projectionReused++;
        else projectionComputed++;
    }

    /** Caller starts its timer only after finding a non-null diagnostics instance. */
    void recordHullScan(final long boundsCells, final long inspectedCells, final long effectiveCells,
                        final long skippedEmptySectionCells, final long skippedSections,
                        final long nativeSectionReads, final long fallbackReads, final long prepareNanos) {
        hullBoundsCells += boundsCells;
        hullInspectedCells += inspectedCells;
        hullEffectiveCells += effectiveCells;
        hullSkippedEmptySectionCells += skippedEmptySectionCells;
        hullSkippedSections += skippedSections;
        hullNativeSectionReads += nativeSectionReads;
        hullFallbackReads += fallbackReads;
        hullPrepareNanos += prepareNanos;
    }

    boolean containsColumn(final long[] columns, final int count, final long column,
                           final boolean centre) {
        if (centre) centreLookups++;
        else cornerLookups++;
        for (int index = 0; index < count; index++) {
            if (centre) centreComparisons++;
            else cornerComparisons++;
            if (columns[index] == column) {
                if (centre) centreHitPositions[index]++;
                else cornerHitPositions[index]++;
                return true;
            }
        }
        return false;
    }

    static synchronized void submit(final WaterQueryDiagnostics sample) {
        if (batch == null) batch = new WaterQueryDiagnostics();
        batch.contexts++;
        batch.samples += add(batch.cellColumns, sample.cellColumns)
                + add(batch.faceColumns, sample.faceColumns);
        add(batch.cornerHitPositions, sample.cornerHitPositions);
        add(batch.centreHitPositions, sample.centreHitPositions);
        batch.cornerLookups += sample.cornerLookups;
        batch.cornerComparisons += sample.cornerComparisons;
        batch.centreLookups += sample.centreLookups;
        batch.centreComparisons += sample.centreComparisons;
        batch.cellCentreFallbacks += sample.cellCentreFallbacks;
        batch.cellCentreQueries += sample.cellCentreQueries;
        batch.faceCentreQueries += sample.faceCentreQueries;
        batch.vanillaHeightQueries += sample.vanillaHeightQueries;
        batch.customHeightQueries += sample.customHeightQueries;
        batch.vanillaAboveReads += sample.vanillaAboveReads;
        batch.vanillaAboveReuses += sample.vanillaAboveReuses;
        batch.vanillaOccupiedTopReuses += sample.vanillaOccupiedTopReuses;
        batch.chunkLookups += sample.chunkLookups;
        batch.bandQueries += sample.bandQueries;
        batch.bandRejected += sample.bandRejected;
        batch.bandUnavailableChunks += sample.bandUnavailableChunks;
        batch.bandSteps += sample.bandSteps;
        batch.bandDrySteps += sample.bandDrySteps;
        batch.bandWaterSteps += sample.bandWaterSteps;
        batch.bandHits += sample.bandHits;
        batch.bandMisses += sample.bandMisses;
        batch.bandUniqueVoxels += sample.bandUniqueVoxels;
        batch.bandRepeatedVoxelSteps += sample.bandRepeatedVoxelSteps;
        batch.bandUntrackedVoxelSteps += sample.bandUntrackedVoxelSteps;
        batch.bandUniqueDryVoxels += sample.bandUniqueDryVoxels;
        batch.bandUniqueWaterVoxels += sample.bandUniqueWaterVoxels;
        batch.bandRepeatedDrySteps += sample.bandRepeatedDrySteps;
        batch.bandRepeatedWaterSteps += sample.bandRepeatedWaterSteps;
        batch.bandUntrackedDrySteps += sample.bandUntrackedDrySteps;
        batch.bandUntrackedWaterSteps += sample.bandUntrackedWaterSteps;
        add(batch.directFluidRequests, sample.directFluidRequests);
        add(batch.directFluidReads, sample.directFluidReads);
        add(batch.directFluidCacheHits, sample.directFluidCacheHits);
        batch.fluidCacheCapBypasses += sample.fluidCacheCapBypasses;
        batch.fluidCacheDisabledReads += sample.fluidCacheDisabledReads;
        batch.fluidCacheDisables += sample.fluidCacheDisables;
        batch.fluidCachePeakEntries = Math.max(batch.fluidCachePeakEntries, sample.fluidCachePeakEntries);
        add(batch.directFluidFirstObserved, sample.directFluidFirstObserved);
        add(batch.directFluidRepeated, sample.directFluidRepeated);
        add(batch.directFluidPhaseUnique, sample.directFluidPhaseUnique);
        add(batch.directFluidPhaseRepeated, sample.directFluidPhaseRepeated);
        add(batch.directFluidUntracked, sample.directFluidUntracked);
        batch.worldColumnQueries += sample.worldColumnQueries;
        batch.trackedWorldColumns += sample.trackedWorldColumns;
        batch.untrackedWorldColumnQueries += sample.untrackedWorldColumnQueries;
        add(batch.trackedColumnQueryHistogram, sample.trackedColumnQueryHistogram);
        add(batch.bandRequestedLengthHistogram, sample.bandRequestedLengthHistogram);
        add(batch.bandScannedLengthHistogram, sample.bandScannedLengthHistogram);
        add(batch.sourceObservedWalkLengthHistogram, sample.sourceObservedWalkLengthHistogram);
        add(batch.sourceWalkEnds, sample.sourceWalkEnds);
        batch.sourceObservedWalkVoxels += sample.sourceObservedWalkVoxels;
        add(batch.geometryPreparationOutcomes, sample.geometryPreparationOutcomes);
        add(batch.geometryCandidateOutcomes, sample.geometryCandidateOutcomes);
        batch.geometryProjectedCells += sample.geometryProjectedCells;
        batch.connectedStartHits += sample.connectedStartHits;
        batch.connectedStartMisses += sample.connectedStartMisses;
        batch.connectedWalkHits += sample.connectedWalkHits;
        batch.connectedLocalSurfaces += sample.connectedLocalSurfaces;
        batch.connectedUpwardSteps += sample.connectedUpwardSteps;
        batch.connectedBackfilledVoxels += sample.connectedBackfilledVoxels;
        batch.connectedCacheExpectedEntries += sample.connectedCacheExpectedEntries;
        batch.connectedCacheExpectedEntriesMax = Math.max(
                batch.connectedCacheExpectedEntriesMax, sample.connectedCacheExpectedEntries);
        batch.connectedCacheEntries += sample.connectedCacheEntries;
        batch.connectedCacheEntriesMax = Math.max(batch.connectedCacheEntriesMax, sample.connectedCacheEntries);
        batch.apertureSampleHits += sample.apertureSampleHits;
        batch.apertureSampleMisses += sample.apertureSampleMisses;
        batch.apertureSampleCustomBypasses += sample.apertureSampleCustomBypasses;
        batch.preparedLookups += sample.preparedLookups;
        batch.preparedMissing += sample.preparedMissing;
        batch.preparedGeometryMisses += sample.preparedGeometryMisses;
        batch.preparedPoseMatches += sample.preparedPoseMatches;
        batch.preparedPoseMisses += sample.preparedPoseMisses;
        batch.preparedMatches += sample.preparedMatches;
        batch.projectionQueries += sample.projectionQueries;
        batch.projectionReused += sample.projectionReused;
        batch.projectionComputed += sample.projectionComputed;
        batch.intervalIndexBuilds += sample.intervalIndexBuilds;
        batch.intervalIndexEntries += sample.intervalIndexEntries;
        batch.intervalIndexBuildNanos += sample.intervalIndexBuildNanos;
        batch.hullBoundsCells += sample.hullBoundsCells;
        batch.hullInspectedCells += sample.hullInspectedCells;
        batch.hullEffectiveCells += sample.hullEffectiveCells;
        batch.hullSkippedEmptySectionCells += sample.hullSkippedEmptySectionCells;
        batch.hullSkippedSections += sample.hullSkippedSections;
        batch.hullNativeSectionReads += sample.hullNativeSectionReads;
        batch.hullFallbackReads += sample.hullFallbackReads;
        batch.hullPrepareNanos += sample.hullPrepareNanos;
        if (batch.samples < BATCH_SAMPLES) return;

        CreateNautilus.LOGGER.info(
                "[water-query] contexts={} cellColumns={} faceColumns={} "
                        + "cornerLookups={} cornerComparisons={} cornerHitPositions={} "
                        + "centreLookups={} centreComparisons={} centreHitPositions={} "
                        + "cellCentreFallbacks={} cellCentreQueries={} faceCentreQueries={} "
                        + "vanillaHeightQueries={} customHeightQueries={} getChunkNowCalls={} "
                        + "vanillaAboveReads={} vanillaAboveReuses={} vanillaOccupiedTopReuses={} "
                        + "bandQueries={} bandRejected={} bandUnavailableChunks={} bandSteps={} "
                        + "bandDrySteps={} bandWaterSteps={} bandHits={} bandMisses={} "
                        + "bandUniqueVoxels={} bandRepeatedVoxelSteps={} bandUntrackedVoxelSteps={} "
                        + "connectedStartHits={} connectedStartMisses={} connectedWalkHits={} "
                        + "connectedLocalSurfaces={} connectedUpwardSteps={} connectedBackfilledVoxels={} "
                        + "connectedCacheExpectedEntries={} connectedCacheExpectedEntriesMax={} "
                        + "connectedCacheEntries={} connectedCacheEntriesMax={} "
                        + "apertureSampleHits={} apertureSampleMisses={} apertureSampleCustomBypasses={} "
                        + "preparedLookups={} preparedMissing={} preparedGeometryMisses={} "
                        + "preparedPoseMatches={} preparedPoseMisses={} preparedMatches={} "
                        + "projectionQueries={} projectionReused={} projectionComputed={} "
                        + "intervalIndexBuilds={} intervalIndexEntries={} intervalIndexBuildNanos={} "
                        + "hullBoundsCells={} hullInspectedCells={} hullEffectiveCells={} "
                        + "hullSkippedEmptySectionCells={} hullSkippedSections={} "
                        + "hullNativeSectionReads={} hullFallbackReads={} hullPrepareNanos={}",
                batch.contexts, Arrays.toString(batch.cellColumns), Arrays.toString(batch.faceColumns),
                batch.cornerLookups, batch.cornerComparisons, Arrays.toString(batch.cornerHitPositions),
                batch.centreLookups, batch.centreComparisons, Arrays.toString(batch.centreHitPositions),
                batch.cellCentreFallbacks, batch.cellCentreQueries, batch.faceCentreQueries,
                batch.vanillaHeightQueries, batch.customHeightQueries, batch.chunkLookups,
                batch.vanillaAboveReads, batch.vanillaAboveReuses, batch.vanillaOccupiedTopReuses,
                batch.bandQueries, batch.bandRejected, batch.bandUnavailableChunks, batch.bandSteps,
                batch.bandDrySteps, batch.bandWaterSteps, batch.bandHits, batch.bandMisses,
                batch.bandUniqueVoxels, batch.bandRepeatedVoxelSteps, batch.bandUntrackedVoxelSteps,
                batch.connectedStartHits, batch.connectedStartMisses, batch.connectedWalkHits,
                batch.connectedLocalSurfaces, batch.connectedUpwardSteps, batch.connectedBackfilledVoxels,
                batch.connectedCacheExpectedEntries, batch.connectedCacheExpectedEntriesMax,
                batch.connectedCacheEntries, batch.connectedCacheEntriesMax,
                batch.apertureSampleHits, batch.apertureSampleMisses, batch.apertureSampleCustomBypasses,
                batch.preparedLookups, batch.preparedMissing, batch.preparedGeometryMisses,
                batch.preparedPoseMatches, batch.preparedPoseMisses, batch.preparedMatches,
                batch.projectionQueries, batch.projectionReused, batch.projectionComputed,
                batch.intervalIndexBuilds, batch.intervalIndexEntries, batch.intervalIndexBuildNanos,
                batch.hullBoundsCells, batch.hullInspectedCells, batch.hullEffectiveCells,
                batch.hullSkippedEmptySectionCells, batch.hullSkippedSections,
                batch.hullNativeSectionReads, batch.hullFallbackReads, batch.hullPrepareNanos);
        CreateNautilus.LOGGER.info(
                "[water-query-observations] contexts={} capPerContextTracker={} "
                        + "bandUniqueDryVoxels={} bandUniqueWaterVoxels={} "
                        + "bandRepeatedDrySteps={} bandRepeatedWaterSteps={} "
                        + "bandOverflowDrySteps={} bandOverflowWaterSteps={} "
                        + "directFluidPhases=point,band,above,sourceWalk directFluidRequests={} "
                        + "directFluidReads={} directFluidCacheHits={} fluidCacheCapBypasses={} "
                        + "fluidCacheDisabledReads={} fluidCacheDisables={} fluidCachePeakEntriesMax={} "
                        + "directFluidFirstObservedAcrossPhases={} directFluidRepeatedAcrossPhases={} "
                        + "directFluidUniqueWithinPhase={} directFluidRepeatedWithinPhase={} "
                        + "directFluidOverflowReads={} worldColumnQueries={} sumTrackedWorldColumns={} "
                        + "worldColumnOverflowQueries={} lengthBuckets={} trackedColumnQueryHistogram={} "
                        + "bandRequestedLengthHistogram={} bandScannedLengthHistogram={} "
                        + "sourceObservedWalkVoxels={} sourceObservedWalkLengthHistogram={} "
                        + "sourceWalkEnds(cached,dryAbove,nonSourceAbove,buildHeight)={} "
                        + "geometryPreparationOutcomes(missing,sameInputsSameFraction,fractionResolve,"
                        + "domain,bounds,domainBounds,pose,domainPose,boundsPose,domainBoundsPose)={} "
                        + "geometryCandidateOutcomes(sameFraction,solve)={} geometryProjectedCells={}",
                batch.contexts, MAX_TRACKED_ENTRIES,
                batch.bandUniqueDryVoxels, batch.bandUniqueWaterVoxels,
                batch.bandRepeatedDrySteps, batch.bandRepeatedWaterSteps,
                batch.bandUntrackedDrySteps, batch.bandUntrackedWaterSteps,
                Arrays.toString(batch.directFluidRequests), Arrays.toString(batch.directFluidReads),
                Arrays.toString(batch.directFluidCacheHits), batch.fluidCacheCapBypasses,
                batch.fluidCacheDisabledReads, batch.fluidCacheDisables, batch.fluidCachePeakEntries,
                Arrays.toString(batch.directFluidFirstObserved),
                Arrays.toString(batch.directFluidRepeated), Arrays.toString(batch.directFluidPhaseUnique),
                Arrays.toString(batch.directFluidPhaseRepeated), Arrays.toString(batch.directFluidUntracked),
                batch.worldColumnQueries, batch.trackedWorldColumns, batch.untrackedWorldColumnQueries,
                LENGTH_BUCKETS, Arrays.toString(batch.trackedColumnQueryHistogram),
                Arrays.toString(batch.bandRequestedLengthHistogram), Arrays.toString(batch.bandScannedLengthHistogram),
                batch.sourceObservedWalkVoxels, Arrays.toString(batch.sourceObservedWalkLengthHistogram),
                Arrays.toString(batch.sourceWalkEnds), Arrays.toString(batch.geometryPreparationOutcomes),
                Arrays.toString(batch.geometryCandidateOutcomes), batch.geometryProjectedCells);
        batch = null;
    }

    private static long add(final long[] total, final long[] values) {
        long sum = 0L;
        for (int index = 0; index < total.length; index++) {
            total[index] += values[index];
            sum += values[index];
        }
        return sum;
    }
}
