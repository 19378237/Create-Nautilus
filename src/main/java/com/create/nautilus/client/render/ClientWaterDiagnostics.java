package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.physics.CompartmentWaterGeometry;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Optional CPU/work counters. They never cache or supply water/geometry results. */
public final class ClientWaterDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("create_nautilus.clientWaterDiagnostics");
    private static final long REPORT_NANOS = 5_000_000_000L;
    private static final int MAX_ROOMS_PER_SCOPE = 512;
    // In particular, the disabled path does not create maps, keys or timing objects.
    private static final State STATE = ENABLED ? new State() : null;
    private static final CompartmentWaterGeometry.SurfaceSolveObserver SOLVE_OBSERVER =
            ENABLED ? ClientWaterDiagnostics::bisection : null;

    public enum Source {
        OTHER, ENTITY_BODY_SUPPRESS, ENTITY_EYES_SUPPRESS, ENTITY_BODY_RESTORE,
        ENTITY_EYES_RESTORE, CAMERA, ATLAS_WATER, ATLAS_EARLY_FOG,
        APERTURE_WATER, APERTURE_EARLY_FOG, FLOODED_VISIBILITY,
        FLOODED_OUTSIDE, FLOODED_INSIDE, FLOODED_VANILLA, CONTINUOUS, DRY_HULL, WATER_ENTRY
    }

    public enum Work {
        SNAPSHOTS, ATLAS_SNAPSHOTS, LEVEL_SAMPLE, CAMERA_QUERY, DEPTH_QUERY,
        SURFACE_POSE, SURFACE_MATRIX, BISECTION, BISECTION_CELL_VISITS, REPEATED_SURFACE, CHANGED_SURFACE_INPUT,
        ROOM_TRACKING_LIMIT, DOMAIN_UNLOADED, FLOODED_SNAPSHOT_HIT,
        ROOM_GEOMETRY_HIT, ROOM_GEOMETRY_MISS, ROOM_GEOMETRY_PREPARE,
        ROOM_GEOMETRY_MISS_COLD, ROOM_GEOMETRY_MISS_INSTANCE, ROOM_GEOMETRY_MISS_REVISION,
        ROOM_GEOMETRY_MISS_DOMAIN, ROOM_GEOMETRY_MISS_BOUNDS, ROOM_GEOMETRY_MISS_POSE,
        ROOM_GEOMETRY_EVICT, ROOM_GEOMETRY_OVERSIZED, ROOM_GEOMETRY_INVALIDATE,
        ROOM_SURFACE_HIT, SURFACE_PREPARED,
        ROOM_SURFACE_MISS_COLD, ROOM_SURFACE_MISS_TICK, ROOM_SURFACE_MISS_FRACTION,
        FLOODED_GEOMETRY, FLOODED_BUFFER, CONTINUOUS_GEOMETRY, DRY_HULL_GEOMETRY,
        FLOODED_TOPOLOGY_HIT, FLOODED_TOPOLOGY_PREPARE,
        FLOODED_GEOMETRY_CACHE_HIT, FLOODED_GEOMETRY_PREPARE,
        FLOODED_GEOMETRY_EVICT, FLOODED_GEOMETRY_OVERSIZED, FLOODED_GEOMETRY_INVALIDATE,
        MESH_PASSES, MESH_INPUT_CELLS, MESH_EFFECTIVE_CELLS, MESH_FILTERED_WATER,
        MESH_CORNER_TRANSFORMS, MESH_SURFACE_CELLS, MESH_BOUNDARY_CANDIDATES,
        MESH_DYNAMIC_NEIGHBOUR_CHECKS, MESH_OUTPUT_POLYGONS, MESH_OUTPUT_VERTICES,
        RAW_DEPTH_PACK, RAW_DEPTH_UPLOAD, RAW_DEPTH_SUBMIT,
        BOUNDS_TESTED, BOUNDS_BEHIND, BOUNDS_FULL_NEAR, BOUNDS_FULL_INVALID,
        MASK_BOUNDS_PIXELS, MASK_CLEAR_PIXELS,
        ATLAS_HIT, ATLAS_MISS_COLD, ATLAS_MISS_TOPOLOGY, ATLAS_MISS_DOMAIN,
        ATLAS_MISS_FRACTION, ATLAS_MISS_BASIS, ATLAS_ENDPOINT, ATLAS_EARLY_RETAINED, ATLAS_PAUSED_REUSED,
        ATLAS_PREFIX_UNCHANGED, ATLAS_PREFIX_FULL, ATLAS_PREFIX_PARTIAL, ATLAS_PREFIX_CHANGED,
        ATLAS_PREFIX_MERGED, ATLAS_PREFIX_RECTS, ATLAS_TEXTURE_UPLOAD, ATLAS_STAGING,
        APERTURE_ATLAS_REUSED, APERTURE_FALLBACK
    }

    private enum Domain { RENDER, CLIENT_TICK, OUTSIDE_SCOPE }

    private ClientWaterDiagnostics() { }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static int enter(final Source source) {
        if (!ENABLED) return 0;
        final int previous = STATE.source;
        STATE.source = source.ordinal();
        return previous;
    }

    public static Source source() {
        return ENABLED ? STATE.sources[STATE.source] : Source.OTHER;
    }

    public static void leave(final int previous) {
        if (ENABLED) STATE.source = previous;
    }

    /** units: rooms, cells, vertices, iterations, or bytes as named by the work bucket. */
    public static void finish(final Work work, final long started, final long units) {
        if (ENABLED) STATE.record(work, System.nanoTime() - started, units);
    }

    public static void count(final Work work, final long units) {
        if (ENABLED) STATE.record(work, 0L, units);
    }

    /** Retained/peak are gauges in the report header, not accumulated work units. */
    static void floodedRetainedGeometry(final int rooms, final int cells) {
        if (!ENABLED) return;
        STATE.floodedRetainedRooms = rooms;
        STATE.floodedRetainedCells = cells;
        STATE.floodedPeakRetainedRooms = Math.max(STATE.floodedPeakRetainedRooms, rooms);
        STATE.floodedPeakRetainedCells = Math.max(STATE.floodedPeakRetainedCells, cells);
    }

    public static void snapshots(final Work work, final long started,
                                 final Map<UUID, ? extends Collection<?>> snapshots) {
        if (!ENABLED) return;
        long rooms = 0L;
        for (final Collection<?> values : snapshots.values()) rooms += values.size();
        finish(work, started, rooms);
    }

    /** Delegates the exact original call; timing excludes input tracking and counter bookkeeping. */
    public static double surfaceY(final UUID carrierId, final UUID roomId, final long revision,
                                  final Pose3dc pose, final Collection<BlockPos> cells,
                                  final AABB bounds, final double fraction) {
        if (!ENABLED) return CompartmentWaterGeometry.surfaceY(pose, cells, bounds, fraction);
        observeSurface(carrierId, roomId, revision, cells, fraction, pose.bakeIntoMatrix(new Matrix4d()));
        final long started = System.nanoTime();
        try {
            return CompartmentWaterGeometry.surfaceY(pose, cells, bounds, fraction, SOLVE_OBSERVER);
        } finally {
            finish(Work.SURFACE_POSE, started, cells.size());
        }
    }

    /** Prepared projection timing is recorded separately from the remaining scalar solve. */
    static double surfaceY(final CompartmentWaterGeometry.PreparedGeometry geometry,
                           final int cells, final double fraction) {
        if (!ENABLED) return CompartmentWaterGeometry.surfaceY(geometry, fraction, null);
        final long started = System.nanoTime();
        try {
            return CompartmentWaterGeometry.surfaceY(geometry, fraction, SOLVE_OBSERVER);
        } finally {
            finish(Work.SURFACE_PREPARED, started, cells);
        }
    }

    static void bisection(final int cells, final int iterations) {
        count(Work.BISECTION, iterations);
        count(Work.BISECTION_CELL_VISITS, (long) cells * iterations);
    }

    static void observeSurface(final UUID carrierId, final UUID roomId, final long revision,
                               final Object cells, final double fraction, final Matrix4d pose) {
        if (!ENABLED) return;
        final Map<RoomKey, RoomInput> rooms = STATE.renderActive ? STATE.renderRooms
                : STATE.tickActive ? STATE.tickRooms : null;
        if (rooms == null) return;
        final RoomKey key = new RoomKey(carrierId, roomId);
        final RoomInput previous = rooms.get(key);
        if (previous == null && rooms.size() >= MAX_ROOMS_PER_SCOPE) {
            count(Work.ROOM_TRACKING_LIMIT, 1L);
            return;
        }
        final RoomInput input = new RoomInput(revision, cells, Double.doubleToLongBits(fraction),
                Double.doubleToLongBits(pose.m01()), Double.doubleToLongBits(pose.m11()),
                Double.doubleToLongBits(pose.m21()), Double.doubleToLongBits(pose.m31()));
        if (previous != null) {
            count(Work.REPEATED_SURFACE, 1L);
            if (!previous.same(input)) count(Work.CHANGED_SURFACE_INPUT, 1L);
        }
        rooms.put(key, input);
    }

    public static void beginRender() {
        if (!ENABLED) return;
        STATE.renderActive = true;
        STATE.renderId++;
        STATE.frames++;
        STATE.renderRooms.clear();
        STATE.source = Source.OTHER.ordinal();
    }

    public static void endRender() {
        if (!ENABLED) return;
        STATE.reportIfDue();
        STATE.renderActive = false;
        STATE.renderRooms.clear();
        STATE.source = Source.OTHER.ordinal();
    }

    public static void beginClientTick() {
        if (!ENABLED) return;
        STATE.tickActive = true;
        STATE.tickId++;
        STATE.ticks++;
        STATE.tickRooms.clear();
        STATE.source = Source.OTHER.ordinal();
    }

    public static void endClientTick() {
        if (!ENABLED) return;
        STATE.reportIfDue();
        STATE.tickActive = false;
        STATE.tickRooms.clear();
        STATE.source = Source.OTHER.ordinal();
    }

    public static void clear() {
        if (ENABLED) STATE.clear();
    }

    private record RoomKey(UUID carrierId, UUID roomId) { }

    private record RoomInput(long revision, Object cells, long fraction, long yx, long yy,
                             long yz, long translationY) {
        private boolean same(final RoomInput other) {
            return revision == other.revision && cells == other.cells && fraction == other.fraction
                    && yx == other.yx && yy == other.yy && yz == other.yz
                    && translationY == other.translationY;
        }
    }

    private static final class State {
        private final Source[] sources = Source.values();
        private final Work[] works = Work.values();
        private final Domain[] domains = Domain.values();
        private final int buckets = domains.length * sources.length * works.length;
        private final long[] calls = new long[buckets];
        private final long[] nanos = new long[buckets];
        private final long[] units = new long[buckets];
        private final long[] maxNanos = new long[buckets];
        private final Map<RoomKey, RoomInput> renderRooms = new HashMap<>();
        private final Map<RoomKey, RoomInput> tickRooms = new HashMap<>();
        private int source;
        private boolean renderActive;
        private boolean tickActive;
        private long renderId;
        private long tickId;
        private long frames;
        private long ticks;
        private int floodedRetainedRooms;
        private int floodedRetainedCells;
        private int floodedPeakRetainedRooms;
        private int floodedPeakRetainedCells;
        private boolean hasWork;
        private long lastReport = System.nanoTime();

        private void record(final Work work, final long elapsed, final long amount) {
            final Domain domain = renderActive ? Domain.RENDER
                    : tickActive ? Domain.CLIENT_TICK : Domain.OUTSIDE_SCOPE;
            final int index = (domain.ordinal() * sources.length + source) * works.length + work.ordinal();
            calls[index]++;
            nanos[index] += elapsed;
            units[index] += amount;
            maxNanos[index] = Math.max(maxNanos[index], elapsed);
            hasWork = true;
        }

        private void reportIfDue() {
            final long now = System.nanoTime();
            if (now - lastReport < REPORT_NANOS) return;
            if (!hasWork) {
                frames = 0L;
                ticks = 0L;
                lastReport = now;
                return;
            }
            CreateNautilus.LOGGER.info("[client-water] intervalNs={} frames={} ticks={} renderId={} tickId={} "
                            + "floodedRetainedRooms={} floodedRetainedCells={} "
                            + "floodedPeakRetainedRooms={} floodedPeakRetainedCells={} "
                            + "SURFACE_POSE includes projection+solve; SURFACE_PREPARED is solve only; "
                            + "ROOM_GEOMETRY_PREPARE is projection only; CPU parents/children must not be added; "
                            + "FLOODED_GEOMETRY includes surface+mesh and has cell units; "
                            + "CONTINUOUS_GEOMETRY includes surface+mesh and has vertex units; "
                            + "FLOODED_BUFFER and RAW_DEPTH_* are separate build/upload/submit stages; "
                            + "BISECTION has counts/iterations only; texture bytes are submitted bytes, not GPU time",
                    now - lastReport, frames, ticks, renderId, tickId,
                    floodedRetainedRooms, floodedRetainedCells,
                    floodedPeakRetainedRooms, floodedPeakRetainedCells);
            for (final Domain domain : domains) {
                for (final Source origin : sources) {
                    final StringBuilder line = new StringBuilder();
                    for (final Work work : works) {
                        final int index = (domain.ordinal() * sources.length + origin.ordinal())
                                * works.length + work.ordinal();
                        if (calls[index] == 0L) continue;
                        line.append(' ').append(work).append("={calls:").append(calls[index])
                                .append(",ns:").append(nanos[index]).append(",maxNs:")
                                .append(maxNanos[index]).append(",units:").append(units[index]).append('}');
                    }
                    if (!line.isEmpty()) CreateNautilus.LOGGER.info("[client-water] domain={} source={}{}",
                            domain, origin, line);
                }
            }
            Arrays.fill(calls, 0L);
            Arrays.fill(nanos, 0L);
            Arrays.fill(units, 0L);
            Arrays.fill(maxNanos, 0L);
            frames = 0L;
            ticks = 0L;
            floodedPeakRetainedRooms = floodedRetainedRooms;
            floodedPeakRetainedCells = floodedRetainedCells;
            hasWork = false;
            lastReport = now;
        }

        private void clear() {
            Arrays.fill(calls, 0L);
            Arrays.fill(nanos, 0L);
            Arrays.fill(units, 0L);
            Arrays.fill(maxNanos, 0L);
            renderRooms.clear();
            tickRooms.clear();
            renderActive = false;
            tickActive = false;
            source = Source.OTHER.ordinal();
            renderId = 0L;
            tickId = 0L;
            floodedRetainedRooms = 0;
            floodedRetainedCells = 0;
            floodedPeakRetainedRooms = 0;
            floodedPeakRetainedCells = 0;
            frames = 0L;
            ticks = 0L;
            hasWork = false;
            lastReport = System.nanoTime();
        }
    }
}
