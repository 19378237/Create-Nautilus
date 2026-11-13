package com.create.nautilus.client.render;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Latest exact world corners per flooded room; no camera, water amount or material state. */
final class FloodedRoomGeometryCache {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_CELLS = 65_536;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<RoomKey, Entry> ROOMS = new LinkedHashMap<>(16, 0.75F, true);
    private static int retainedCells;

    private FloodedRoomGeometryCache() { }

    static Geometry prepare(final ClientSubLevel carrier, final UUID roomId, final long revision,
                            final Pose3dc pose, final List<BlockPos> waterCells, final AABB bounds) {
        final RoomKey key = new RoomKey(carrier.getUniqueId(), roomId);
        Entry entry = ROOMS.get(key);
        if (entry == null || !entry.matches(carrier, revision, waterCells, bounds)) {
            remove(key);
            final long started = ClientWaterDiagnostics.start();
            entry = new Entry(carrier, revision, waterCells, bounds, topology(waterCells));
            ClientWaterDiagnostics.finish(ClientWaterDiagnostics.Work.FLOODED_TOPOLOGY_PREPARE,
                    started, entry.localCells.size());
            if (waterCells.size() > MAX_CELLS) {
                // Preserve the complete room and other rooms' working set; only retention is capped.
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.FLOODED_GEOMETRY_OVERSIZED,
                        waterCells.size());
            } else {
                ROOMS.put(key, entry);
                retainedCells += waterCells.size();
                while (ROOMS.size() > MAX_ENTRIES || retainedCells > MAX_CELLS) {
                    final var oldest = ROOMS.entrySet().iterator();
                    final Entry evicted = oldest.next().getValue();
                    oldest.remove();
                    retainedCells -= evicted.waterCells.size();
                    ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.FLOODED_GEOMETRY_EVICT,
                            evicted.waterCells.size());
                }
            }
        } else {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.FLOODED_TOPOLOGY_HIT, 1L);
        }
        ClientWaterDiagnostics.floodedRetainedGeometry(ROOMS.size(), retainedCells);
        if (entry.geometry != null && same(entry.pose, pose)) {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.FLOODED_GEOMETRY_CACHE_HIT, 1L);
            return entry.geometry;
        }
        final long started = ClientWaterDiagnostics.start();
        final Pose3d snapshot = new Pose3d(pose);
        final List<CellGeometry> cells = new ArrayList<>(entry.localCells.size());
        double highestY = Double.NEGATIVE_INFINITY;
        for (final LocalCell local : entry.localCells) {
            final AABB box = local.bounds();
            final Vector3d[] corners = {
                    new Vector3d(box.minX, box.minY, box.minZ),
                    new Vector3d(box.maxX, box.minY, box.minZ),
                    new Vector3d(box.minX, box.maxY, box.minZ),
                    new Vector3d(box.maxX, box.maxY, box.minZ),
                    new Vector3d(box.minX, box.minY, box.maxZ),
                    new Vector3d(box.maxX, box.minY, box.maxZ),
                    new Vector3d(box.minX, box.maxY, box.maxZ),
                    new Vector3d(box.maxX, box.maxY, box.maxZ)
            };
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < corners.length; index++) {
                corners[index] = snapshot.transformPosition(corners[index]);
                minimumY = Math.min(minimumY, corners[index].y);
                maximumY = Math.max(maximumY, corners[index].y);
            }
            cells.add(new CellGeometry(local, corners, minimumY, maximumY));
            highestY = Math.max(highestY, maximumY);
        }
        entry.pose = snapshot;
        entry.geometry = new Geometry(List.copyOf(cells), highestY);
        ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.MESH_CORNER_TRANSFORMS, 8L * cells.size());
        ClientWaterDiagnostics.finish(ClientWaterDiagnostics.Work.FLOODED_GEOMETRY_PREPARE,
                started, cells.size());
        return entry.geometry;
    }

    /** The former linked cell map deduplicated keys without changing their first insertion order. */
    private static List<LocalCell> topology(final List<BlockPos> waterCells) {
        final LongLinkedOpenHashSet keys = new LongLinkedOpenHashSet(waterCells.size());
        for (final BlockPos cell : waterCells) keys.add(cell.asLong());
        final List<LocalCell> cells = new ArrayList<>(keys.size());
        final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (final long key : keys) {
            final BlockPos position = BlockPos.of(key);
            int boundaryMask = 0;
            for (final Direction direction : DIRECTIONS) {
                neighbour.set(position).move(direction);
                if (!keys.contains(neighbour.asLong())) boundaryMask |= 1 << direction.get3DDataValue();
            }
            cells.add(new LocalCell(key, new AABB(position), boundaryMask));
        }
        return List.copyOf(cells);
    }

    static void invalidate(final UUID carrierId, final UUID roomId) {
        if (remove(new RoomKey(carrierId, roomId)) != null) {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.FLOODED_GEOMETRY_INVALIDATE, 1L);
        }
        ClientWaterDiagnostics.floodedRetainedGeometry(ROOMS.size(), retainedCells);
    }

    static void forget(final UUID carrierId) {
        final var rooms = ROOMS.entrySet().iterator();
        while (rooms.hasNext()) {
            final var room = rooms.next();
            if (!room.getKey().carrierId().equals(carrierId)) continue;
            retainedCells -= room.getValue().waterCells.size();
            rooms.remove();
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.FLOODED_GEOMETRY_INVALIDATE, 1L);
        }
        ClientWaterDiagnostics.floodedRetainedGeometry(ROOMS.size(), retainedCells);
    }

    static void clear() {
        ROOMS.clear();
        retainedCells = 0;
        ClientWaterDiagnostics.floodedRetainedGeometry(0, 0);
    }

    private static Entry remove(final RoomKey key) {
        final Entry removed = ROOMS.remove(key);
        if (removed != null) retainedCells -= removed.waterCells.size();
        return removed;
    }

    record Geometry(List<CellGeometry> cells, double highestY) { }

    /** Mutable vectors stay private. Every clipping/inset caller receives its own copies. */
    static final class CellGeometry {
        private final LocalCell local;
        private final Vector3d[] corners;
        private final double minimumY;
        private final double maximumY;

        private CellGeometry(final LocalCell local, final Vector3d[] corners,
                             final double minimumY, final double maximumY) {
            this.local = local;
            this.corners = corners;
            this.minimumY = minimumY;
            this.maximumY = maximumY;
        }

        long position() { return local.position(); }
        AABB bounds() { return local.bounds(); }
        double minimumY() { return minimumY; }
        double maximumY() { return maximumY; }
        boolean boundary(final Direction direction) {
            return (local.boundaryMask() & 1 << direction.get3DDataValue()) != 0;
        }
        Vector3d copyCorner(final int index) { return new Vector3d(corners[index]); }
        List<Vector3d> surfacePolygon(final double surfaceY) {
            return FloodedChamberRenderer.surfacePolygon(corners, surfaceY);
        }
        Direction.Axis surfaceUvAxis() { return FloodedChamberRenderer.surfaceUvAxis(corners); }
        Vector3d sideInset(final Direction direction) {
            final int axisCorner = direction.getAxis() == Direction.Axis.X ? 1 : 4;
            return new Vector3d(corners[axisCorner]).sub(corners[0])
                    .mul(-0.02D * direction.getAxisDirection().getStep());
        }
        Vector3d[] copyCorners() {
            final Vector3d[] copy = new Vector3d[corners.length];
            for (int index = 0; index < corners.length; index++) copy[index] = copyCorner(index);
            return copy;
        }
    }

    private record LocalCell(long position, AABB bounds, int boundaryMask) { }
    private record RoomKey(UUID carrierId, UUID roomId) { }

    private static final class Entry {
        private final ClientSubLevel carrier;
        private final long revision;
        private final List<BlockPos> waterCells;
        private final AABB bounds;
        private final List<LocalCell> localCells;
        private Pose3d pose;
        private Geometry geometry;

        private Entry(final ClientSubLevel carrier, final long revision, final List<BlockPos> waterCells,
                      final AABB bounds, final List<LocalCell> localCells) {
            this.carrier = carrier;
            this.revision = revision;
            this.waterCells = waterCells;
            this.bounds = bounds;
            this.localCells = localCells;
        }

        private boolean matches(final ClientSubLevel carrier, final long revision,
                                final List<BlockPos> waterCells, final AABB bounds) {
            return this.carrier == carrier && this.revision == revision && this.waterCells == waterCells
                    && this.bounds.equals(bounds);
        }
    }

    private static boolean same(final Pose3dc first, final Pose3dc second) {
        return same(first.position(), second.position()) && same(first.orientation(), second.orientation())
                && same(first.rotationPoint(), second.rotationPoint()) && same(first.scale(), second.scale());
    }

    private static boolean same(final Vector3dc first, final Vector3dc second) {
        return same(first.x(), second.x()) && same(first.y(), second.y()) && same(first.z(), second.z());
    }

    private static boolean same(final Quaterniondc first, final Quaterniondc second) {
        return same(first.x(), second.x()) && same(first.y(), second.y())
                && same(first.z(), second.z()) && same(first.w(), second.w());
    }

    private static boolean same(final double first, final double second) {
        return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
    }
}
