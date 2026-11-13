package com.create.nautilus.physics;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.OptionalDouble;

/** Small, side-effect-free water queries shared by pressure, flooding and buoyancy. */
public final class WaterEnvironment {
    private WaterEnvironment() {
    }

    public static Vector3d toWorld(final ServerSubLevel carrier, final Vector3dc plotPosition) {
        return carrier.logicalPose().transformPosition(plotPosition, new Vector3d());
    }

    public static OptionalDouble waterSurfaceY(final ServerLevel level, final Vector3dc probe) {
        return new QueryContext(level).waterSurfaceY(probe);
    }

    /**
     * Finds the highest connected external-water surface intersecting a transformed plot voxel.
     * Inset corners catch partial immersion even when the voxel centre is still above water.
     */
    public static OptionalDouble waterSurfaceIntersectingCell(final ServerSubLevel carrier,
                                                               final BlockPos cell,
                                                               final QueryContext context) {
        return waterSurfaceIntersectingCell(carrier, cell, context,
                CompartmentWaterGeometry.projectedCell(carrier, cell));
    }

    static OptionalDouble waterSurfaceIntersectingCell(final ServerSubLevel carrier,
                                                        final BlockPos cell,
                                                        final QueryContext context,
                                                        final CompartmentWaterGeometry.ProjectedInterval interval) {
        final double worldLow = interval.low();
        final double worldHigh = interval.high();
        final Pose3dc pose = carrier.logicalPose();
        final Vector3d local = new Vector3d();
        final Vector3d world = new Vector3d();
        final long[] columns = context.sampledColumns;
        int columnCount = 0;
        double highest = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            final double x = xIndex == 0 ? 0.0001D : 0.9999D;
            for (int yIndex = 0; yIndex < 2; yIndex++) {
                final double y = yIndex == 0 ? 0.0001D : 0.9999D;
                for (int zIndex = 0; zIndex < 2; zIndex++) {
                    final double z = zIndex == 0 ? 0.0001D : 0.9999D;
                    pose.transformPosition(local.set(
                            cell.getX() + x, cell.getY() + y, cell.getZ() + z), world);
                    final int blockX = Mth.floor(world.x());
                    final int blockZ = Mth.floor(world.z());
                    final long column = ChunkPos.asLong(blockX, blockZ);
                    if (context.containsColumn(columnCount, column, false)) continue;
                    columns[columnCount++] = column;
                    final OptionalDouble candidate = context.waterSurfaceInVerticalBand(
                            blockX, blockZ, worldLow, worldHigh);
                    if (candidate.isPresent()) {
                        highest = Math.max(highest, candidate.getAsDouble());
                        found = true;
                    }
                }
            }
        }
        int queriedColumns = columnCount;
        if (!found) {
            if (context.diagnostics != null) context.diagnostics.cellCentreFallbacks++;
            pose.transformPosition(local.set(
                    cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D), world);
            final int centreX = Mth.floor(world.x());
            final int centreZ = Mth.floor(world.z());
            if (!context.containsColumn(columnCount, ChunkPos.asLong(centreX, centreZ), true)) {
                queriedColumns++;
                if (context.diagnostics != null) context.diagnostics.cellCentreQueries++;
                final OptionalDouble candidate = context.waterSurfaceInVerticalBand(
                        centreX, centreZ, worldLow, worldHigh);
                if (candidate.isPresent()) {
                    highest = candidate.getAsDouble();
                    found = true;
                }
            }
        }
        if (context.diagnostics != null) context.diagnostics.cellColumns[queriedColumns]++;
        return found ? OptionalDouble.of(highest) : OptionalDouble.empty();
    }

    /**
     * Finds external water touching one actual face of a transformed plot voxel.
     *
     * <p>This is used for geometry-derived open-top inlets.  The queried vertical band is the
     * face's world-Y projection, expanded by a tiny epsilon so an upright horizontal face still
     * has measurable thickness.  Consequently an opening above the sea is dry, a tilted opening
     * is partially wetted as its low edge enters the sea, and a fully submerged opening remains
     * connected to the top of the correct external water column.</p>
     */
    public static OptionalDouble waterSurfaceIntersectingFace(final ServerSubLevel carrier,
                                                               final BlockPos cell,
                                                               final Direction face,
                                                               final QueryContext context) {
        final CompartmentWaterGeometry.ProjectedInterval interval =
                CompartmentWaterGeometry.projectedFaceInterval(
                        carrier.logicalPose(), new AABB(cell), face);
        return waterSurfaceIntersectingFace(carrier, cell, face, context, interval);
    }

    static OptionalDouble waterSurfaceIntersectingFace(final ServerSubLevel carrier,
                                                        final BlockPos cell,
                                                        final Direction face,
                                                        final QueryContext context,
                                                        final CompartmentWaterGeometry.ProjectedInterval interval) {
        final double faceLow = interval.low();
        final double faceHigh = interval.high();
        final double epsilon = 1.0E-4D;
        final Pose3dc pose = carrier.logicalPose();
        final Vector3d local = new Vector3d();
        final Vector3d world = new Vector3d();
        final long[] columns = context.sampledColumns;
        int columnCount = 0;
        double highest = Double.NEGATIVE_INFINITY;
        boolean found = false;

        for (int firstIndex = 0; firstIndex < 2; firstIndex++) {
            final double first = firstIndex == 0 ? 0.0001D : 0.9999D;
            for (int secondIndex = 0; secondIndex < 2; secondIndex++) {
                final double second = secondIndex == 0 ? 0.0001D : 0.9999D;
                pose.transformPosition(faceSample(cell, face, first, second, local), world);
                final int blockX = Mth.floor(world.x());
                final int blockZ = Mth.floor(world.z());
                final long column = ChunkPos.asLong(blockX, blockZ);
                if (context.containsColumn(columnCount, column, false)) continue;
                columns[columnCount++] = column;
                final OptionalDouble candidate = context.waterSurfaceInVerticalBand(
                        blockX, blockZ, faceLow - epsilon, faceHigh + epsilon);
                if (candidate.isPresent()) {
                    highest = Math.max(highest, candidate.getAsDouble());
                    found = true;
                }
            }
        }
        pose.transformPosition(faceSample(cell, face, 0.5D, 0.5D, local), world);
        final int centreX = Mth.floor(world.x());
        final int centreZ = Mth.floor(world.z());
        final boolean centreCovered = context.containsColumn(
                columnCount, ChunkPos.asLong(centreX, centreZ), true);
        final OptionalDouble centreCandidate = centreCovered ? OptionalDouble.empty()
                : context.waterSurfaceInVerticalBand(centreX, centreZ,
                        faceLow - epsilon, faceHigh + epsilon);
        if (context.diagnostics != null) {
            if (!centreCovered) context.diagnostics.faceCentreQueries++;
            context.diagnostics.faceColumns[columnCount + (centreCovered ? 0 : 1)]++;
        }
        if (centreCandidate.isPresent()) {
            highest = Math.max(highest, centreCandidate.getAsDouble());
            found = true;
        }
        return found ? OptionalDouble.of(highest) : OptionalDouble.empty();
    }

    private static boolean containsColumn(final long[] columns, final int count, final long column) {
        for (int index = 0; index < count; index++) {
            if (columns[index] == column) return true;
        }
        return false;
    }

    private static Vector3d faceSample(final BlockPos cell, final Direction face,
                                       final double first, final double second,
                                       final Vector3d target) {
        final double fixed = face.getAxisDirection() == Direction.AxisDirection.NEGATIVE
                ? 0.0001D : 0.9999D;
        return switch (face.getAxis()) {
            case X -> target.set(cell.getX() + fixed,
                    cell.getY() + first, cell.getZ() + second);
            case Y -> target.set(cell.getX() + first,
                    cell.getY() + fixed, cell.getZ() + second);
            case Z -> target.set(cell.getX() + first,
                    cell.getY() + second, cell.getZ() + fixed);
        };
    }

    /**
     * Per-physics-step query cache. A deep ocean consists of many full source blocks; asking
     * {@link FluidState#getHeight} only at the submerged block would incorrectly report the top of
     * that one block. We instead walk to the top of the connected vertical water column and cache
     * the resulting surface for every connected water voxel. A context belongs to one carrier's
     * synchronous physics sub-step on the level's server thread, during which the exterior world
     * is not changed by the solvers. Missing chunks remain unknown; queries never request a load.
     */
    public static final class QueryContext {
        private static final double MIN_FLUID_OVERLAP = 1.0E-6D;
        private static final int MAX_CACHED_FLUID_STATES = 65536;
        private final ServerLevel level;
        /**
         * A world X/Z column may contain multiple disconnected bodies of water.  Cache by an
         * actual connected water voxel, not merely by column, and back-fill every traversed voxel
         * while walking to its free surface.
         */
        private final Long2DoubleOpenHashMap connectedWaterSurfaces;
        /** A present null entry means unavailable. References live only for this sub-step. */
        private final Long2ObjectOpenHashMap<LevelChunk> loadedChunks = new Long2ObjectOpenHashMap<>();
        /** Raw vanilla states, including dry voxels, shared by all four direct-read phases. */
        private Long2ObjectOpenHashMap<FluidState> fluidStates;
        private boolean fluidCacheDisabled;
        private final long[] sampledColumns = new long[8];
        private final LongArrayList traversed = new LongArrayList();
        /** Always active: external aperture caching must retain custom-fluid height calls. */
        private long customHeightQuerySerial;
        private final WaterQueryDiagnostics diagnostics = WaterQueryDiagnostics.ENABLED
                ? new WaterQueryDiagnostics() : null;

        public QueryContext(final ServerLevel level) {
            this(level, 16);
        }

        /** Previous occupancy affects initial allocation only; the map still grows as needed. */
        QueryContext(final ServerLevel level, final int previousConnectedWaterEntries) {
            this.level = level;
            final int expectedEntries = Math.clamp(previousConnectedWaterEntries, 16, 4096);
            connectedWaterSurfaces = new Long2DoubleOpenHashMap(expectedEntries);
            // Connected surfaces are finite world heights, so NaN is an unambiguous miss.
            connectedWaterSurfaces.defaultReturnValue(Double.NaN);
            if (diagnostics != null) diagnostics.connectedCacheExpectedEntries = expectedEntries;
        }

        int connectedWaterEntryCount() { return connectedWaterSurfaces.size(); }

        long customHeightQuerySerial() { return customHeightQuerySerial; }

        private boolean containsColumn(final int count, final long column, final boolean centre) {
            return !WaterQueryDiagnostics.ENABLED ? WaterEnvironment.containsColumn(sampledColumns, count, column)
                    : diagnostics.containsColumn(sampledColumns, count, column, centre);
        }

        /** Called once by the shared-context physics dispatcher; no work when disabled. */
        void finishDiagnostics() {
            if (diagnostics != null) {
                diagnostics.connectedCacheEntries = connectedWaterSurfaces.size();
                diagnostics.finishObservations();
                WaterQueryDiagnostics.submit(diagnostics);
            }
        }

        WaterQueryDiagnostics diagnostics() { return diagnostics; }

        /** Callers have already checked build height and resolved a loaded chunk. */
        private FluidState fluidState(final LevelChunk chunk, final BlockPos pos, final int phase) {
            final long key = pos.asLong();
            if (diagnostics != null) diagnostics.directFluidRequests[phase]++;
            if (fluidStates != null) {
                final FluidState cached = fluidStates.get(key);
                if (cached != null) {
                    if (diagnostics != null) diagnostics.directFluidCacheHits[phase]++;
                    return cached;
                }
            }
            if (diagnostics != null) {
                diagnostics.recordFluidRead(phase, key);
                if (phase == WaterQueryDiagnostics.READ_ABOVE) diagnostics.vanillaAboveReads++;
                if (fluidCacheDisabled) diagnostics.fluidCacheDisabledReads++;
            }
            final FluidState fluid = chunk.getFluidState(pos);
            if (fluidCacheDisabled) return fluid;

            final var type = fluid.getType();
            if (type != Fluids.EMPTY && type != Fluids.WATER && type != Fluids.FLOWING_WATER) {
                // A custom getHeight may mutate neighbouring world state. Stop reusing raw
                // observations before invoking it, while retaining the pre-existing surface cache.
                fluidStates = null;
                fluidCacheDisabled = true;
                if (diagnostics != null) diagnostics.fluidCacheDisables++;
            } else {
                if (fluidStates == null) fluidStates = new Long2ObjectOpenHashMap<>(256);
                if (fluidStates.size() < MAX_CACHED_FLUID_STATES) {
                    fluidStates.put(key, fluid);
                    if (diagnostics != null) diagnostics.fluidCachePeakEntries = Math.max(
                            diagnostics.fluidCachePeakEntries, fluidStates.size());
                } else if (diagnostics != null) diagnostics.fluidCacheCapBypasses++;
            }
            return fluid;
        }

        /** Keep the Level context for modded fluids, which may inspect neighbouring chunks. */
        private float fluidHeight(final FluidState fluid, final BlockPos pos, final LevelChunk chunk) {
            return fluidHeight(fluid, pos, chunk, null);
        }

        /** knownAbove was already read inside build height by the connected-source walk. */
        private float fluidHeight(final FluidState fluid, final BlockPos pos, final LevelChunk chunk,
                                  final FluidState knownAbove) {
            final var type = fluid.getType();
            if (type != Fluids.WATER && type != Fluids.FLOWING_WATER) {
                customHeightQuerySerial++;
                if (diagnostics != null) diagnostics.customHeightQueries++;
                return fluid.getHeight(level, pos);
            }
            if (diagnostics != null) diagnostics.vanillaHeightQueries++;
            if (knownAbove != null) {
                if (diagnostics != null) diagnostics.vanillaAboveReuses++;
                return type.isSame(knownAbove.getType()) ? 1.0F : fluid.getOwnHeight();
            }
            final BlockPos above = pos.above();
            // Level.getFluidState returns empty outside build height before resolving a chunk.
            if (level.isOutsideBuildHeight(above)) return fluid.getOwnHeight();
            return type.isSame(fluidState(chunk, above, WaterQueryDiagnostics.READ_ABOVE).getType())
                    ? 1.0F : fluid.getOwnHeight();
        }

        public OptionalDouble waterSurfaceY(final Vector3dc probe) {
            final BlockPos start = BlockPos.containing(probe.x(), probe.y(), probe.z());
            if (diagnostics != null) diagnostics.recordColumnQuery(ChunkPos.asLong(start.getX(), start.getZ()));
            if (level.isOutsideBuildHeight(start)) {
                return OptionalDouble.empty();
            }
            final LevelChunk chunk = loadedChunk(start.getX(), start.getZ());
            if (chunk == null) {
                return OptionalDouble.empty();
            }
            final FluidState startFluid = fluidState(chunk, start, WaterQueryDiagnostics.READ_POINT);
            if (!startFluid.is(FluidTags.WATER)) {
                return OptionalDouble.empty();
            }
            // A flowing/partial fluid does not occupy the air portion at the top of its block.
            // Checking only the block tag made points above a shallow layer appear submerged.
            final double occupiedTop = start.getY() + fluidHeight(startFluid, start, chunk);
            if (probe.y() >= occupiedTop - MIN_FLUID_OVERLAP) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(connectedSurface(start, startFluid, chunk, occupiedTop));
        }

        private LevelChunk loadedChunk(final int blockX, final int blockZ) {
            final int chunkX = blockX >> 4;
            final int chunkZ = blockZ >> 4;
            final long key = ChunkPos.asLong(chunkX, chunkZ);
            LevelChunk chunk = loadedChunks.get(key);
            if (chunk == null && !loadedChunks.containsKey(key)) {
                // Unlike getChunk/getChunkAt, this never loads or waits for a missing chunk.
                // Sable's getChunkNow hook also resolves plot chunks through the container.
                chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (diagnostics != null) diagnostics.chunkLookups++;
                loadedChunks.put(key, chunk);
            }
            return chunk;
        }

        /**
         * Finds water overlapping only the supplied world-Y projection of a transformed voxel.
         * This catches partial immersion when the original corner probe happens to lie in an air
         * block, while the strict lower bound prevents water far below the voxel from being used.
         */
        public OptionalDouble waterSurfaceInVerticalBand(final double worldX,
                                                         final double worldZ,
                                                         final double minimumWorldY,
                                                         final double maximumWorldY) {
            return waterSurfaceInVerticalBand(Mth.floor(worldX), Mth.floor(worldZ),
                    minimumWorldY, maximumWorldY);
        }

        private OptionalDouble waterSurfaceInVerticalBand(final int blockX, final int blockZ,
                                                           final double minimumWorldY,
                                                           final double maximumWorldY) {
            if (diagnostics != null) {
                diagnostics.bandQueries++;
                diagnostics.recordColumnQuery(ChunkPos.asLong(blockX, blockZ));
            }
            final double low = Math.min(minimumWorldY, maximumWorldY);
            final double high = Math.max(minimumWorldY, maximumWorldY);
            if (!Double.isFinite(low) || !Double.isFinite(high)
                    || high - low <= MIN_FLUID_OVERLAP) {
                if (diagnostics != null) {
                    diagnostics.bandRejected++;
                    diagnostics.recordBandScannedLength(0);
                }
                return OptionalDouble.empty();
            }

            final int minimumY = Math.max(level.getMinBuildHeight(), Mth.floor(low));
            final int maximumY = Math.min(level.getMaxBuildHeight() - 1, Mth.floor(Math.nextDown(high)));
            if (maximumY < minimumY) {
                if (diagnostics != null) {
                    diagnostics.bandRejected++;
                    diagnostics.recordBandScannedLength(0);
                }
                return OptionalDouble.empty();
            }
            if (diagnostics != null) diagnostics.recordBandRequestedLength(maximumY - minimumY + 1);

            final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                    blockX, maximumY, blockZ);
            final LevelChunk chunk = loadedChunk(blockX, blockZ);
            if (chunk == null) {
                if (diagnostics != null) {
                    diagnostics.bandUnavailableChunks++;
                    diagnostics.recordBandScannedLength(0);
                }
                return OptionalDouble.empty();
            }
            for (int y = maximumY; y >= minimumY; y--) {
                cursor.setY(y);
                final FluidState fluid = fluidState(chunk, cursor, WaterQueryDiagnostics.READ_BAND);
                final boolean water = fluid.is(FluidTags.WATER);
                if (diagnostics != null) diagnostics.recordBandVoxel(cursor.asLong(), water);
                if (!water) {
                    continue;
                }
                final double occupiedTop = y + fluidHeight(fluid, cursor, chunk);
                final double overlap = Math.min(high, occupiedTop) - Math.max(low, y);
                if (overlap > MIN_FLUID_OVERLAP) {
                    if (diagnostics != null) {
                        diagnostics.bandHits++;
                        diagnostics.recordBandScannedLength(maximumY - y + 1);
                    }
                    return OptionalDouble.of(connectedSurface(cursor.immutable(), fluid, chunk, occupiedTop));
                }
            }
            if (diagnostics != null) {
                diagnostics.bandMisses++;
                diagnostics.recordBandScannedLength(maximumY - minimumY + 1);
            }
            return OptionalDouble.empty();
        }

        private double connectedSurface(final BlockPos start, final FluidState startFluid,
                                          final LevelChunk chunk, final double occupiedTop) {
            final long startKey = start.asLong();
            final double cachedStart = connectedWaterSurfaces.get(startKey);
            if (!Double.isNaN(cachedStart)) {
                if (diagnostics != null) diagnostics.connectedStartHits++;
                return cachedStart;
            }
            if (diagnostics != null) diagnostics.connectedStartMisses++;

            /*
             * A falling/flowing WATER-tagged column is not a hydrostatic reservoir whose pressure
             * head reaches the source block at the top. The previous unconditional upward walk
             * made touching the bottom of a tall waterfall report the waterfall's source Y as the
             * outside sea surface, so the whole carrier acquired full-depth buoyancy at once.
             *
             * Source blocks form static water bodies (ocean/lake columns) and may be followed.
             * A non-source state contributes only the volume actually occupying its own block.
             */
            if (!startFluid.isSource()) {
                final var type = startFluid.getType();
                final boolean vanilla = type == Fluids.WATER || type == Fluids.FLOWING_WATER;
                // Only vanilla heights are immutable here. Keep the original custom-height call.
                final double localSurface = vanilla ? occupiedTop
                        : start.getY() + fluidHeight(startFluid, start, chunk);
                if (diagnostics != null) {
                    diagnostics.connectedLocalSurfaces++;
                    if (vanilla) diagnostics.vanillaOccupiedTopReuses++;
                }
                connectedWaterSurfaces.put(startKey, localSurface);
                return localSurface;
            }

            traversed.clear();
            BlockPos.MutableBlockPos cursor = start.mutable();
            FluidState currentFluid = startFluid;
            double surface;
            int observedWalkEnd = WaterQueryDiagnostics.WALK_CACHED;
            while (true) {
                final long key = cursor.asLong();
                // The starting voxel was already checked before entering the walk.
                if (!traversed.isEmpty()) {
                    final double cached = connectedWaterSurfaces.get(key);
                    if (!Double.isNaN(cached)) {
                        if (diagnostics != null) diagnostics.connectedWalkHits++;
                        surface = cached;
                        break;
                    }
                }
                traversed.add(key);

                if (cursor.getY() + 1 >= level.getMaxBuildHeight()) {
                    observedWalkEnd = WaterQueryDiagnostics.WALK_BUILD_HEIGHT;
                    surface = cursor.getY() + fluidHeight(currentFluid, cursor, chunk);
                    break;
                }
                cursor.move(0, 1, 0);
                if (diagnostics != null) {
                    diagnostics.connectedUpwardSteps++;
                }
                final FluidState above = fluidState(chunk, cursor, WaterQueryDiagnostics.READ_SOURCE_WALK);
                if (!above.is(FluidTags.WATER) || !above.isSource()) {
                    if (diagnostics != null) observedWalkEnd = above.is(FluidTags.WATER)
                            ? WaterQueryDiagnostics.WALK_NON_SOURCE_ABOVE : WaterQueryDiagnostics.WALK_DRY_ABOVE;
                    cursor.move(0, -1, 0);
                    surface = cursor.getY() + fluidHeight(currentFluid, cursor, chunk, above);
                    break;
                }
                currentFluid = above;
            }
            for (int index = 0; index < traversed.size(); index++) {
                connectedWaterSurfaces.put(traversed.getLong(index), surface);
            }
            if (diagnostics != null) {
                diagnostics.connectedBackfilledVoxels += traversed.size();
                diagnostics.recordSourceWalk(traversed.size(), observedWalkEnd);
            }
            return surface;
        }
    }
}
