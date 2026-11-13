package com.create.nautilus.client.render;

import com.create.nautilus.physics.CompartmentWaterGeometry;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client-thread exact water projections; each room retains only its latest render pose. */
public final class ClientRoomGeometryCache {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_CELLS = 65_536;
    private static final Map<RoomKey, Entry> ROOMS = new LinkedHashMap<>(16, 0.75F, true);
    private static int retainedCells;

    private ClientRoomGeometryCache() { }

    /** Callers keep their existing interpolation timestamps and endpoint decisions. */
    public static double surfaceY(final ClientSubLevel carrier, final UUID roomId,
                                  final long revision, final Pose3dc pose,
                                  final List<BlockPos> waterCells, final AABB bounds,
                                  final double fraction) {
        if (ClientWaterDiagnostics.ENABLED) {
            ClientWaterDiagnostics.observeSurface(carrier.getUniqueId(), roomId, revision,
                    waterCells, fraction, pose.bakeIntoMatrix(new Matrix4d()));
        }
        final RoomKey key = new RoomKey(carrier.getUniqueId(), roomId);
        Entry entry = ROOMS.get(key);
        if (entry != null && entry.matches(carrier, revision, pose, waterCells, bounds)) {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_HIT, 1L);
        } else {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS, 1L);
            if (ClientWaterDiagnostics.ENABLED) {
                if (entry == null) {
                    ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS_COLD, 1L);
                } else {
                    entry.recordMismatch(carrier, revision, pose, waterCells, bounds);
                }
            }
            if (entry != null) remove(key);
            final long started = ClientWaterDiagnostics.start();
            entry = new Entry(carrier, revision, new Pose3d(pose), waterCells, bounds,
                    CompartmentWaterGeometry.prepareGeometry(pose, waterCells, bounds));
            ClientWaterDiagnostics.finish(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_PREPARE,
                    started, waterCells.size());
            if (waterCells.size() > MAX_CELLS) {
                // An exceptional room can still be rendered without evicting the working set.
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_OVERSIZED,
                        waterCells.size());
            } else {
                ROOMS.put(key, entry);
                retainedCells += waterCells.size();
                while (ROOMS.size() > MAX_ENTRIES || retainedCells > MAX_CELLS) {
                    final var oldest = ROOMS.entrySet().iterator();
                    final Entry evicted = oldest.next().getValue();
                    oldest.remove();
                    retainedCells -= evicted.cells.size();
                    ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_EVICT,
                            evicted.cells.size());
                }
            }
        }
        // Never carry a scalar solution over a client tick, even while the carrier is stationary.
        final long gameTime = carrier.getLevel().getGameTime();
        final long fractionBits = Double.doubleToRawLongBits(fraction);
        if (entry.hasSurface && entry.gameTime == gameTime && entry.fractionBits == fractionBits) {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_SURFACE_HIT, 1L);
            return entry.surface;
        }
        if (ClientWaterDiagnostics.ENABLED) {
            if (!entry.hasSurface) {
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_SURFACE_MISS_COLD, 1L);
            } else {
                if (entry.gameTime != gameTime) {
                    ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_SURFACE_MISS_TICK, 1L);
                }
                if (entry.fractionBits != fractionBits) {
                    ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_SURFACE_MISS_FRACTION, 1L);
                }
            }
        }
        final double surface = ClientWaterDiagnostics.surfaceY(entry.geometry, waterCells.size(), fraction);
        entry.gameTime = gameTime;
        entry.fractionBits = fractionBits;
        entry.surface = surface;
        entry.hasSurface = true;
        return surface;
    }

    static void invalidate(final UUID carrierId, final UUID roomId) {
        if (remove(new RoomKey(carrierId, roomId)) != null) {
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_INVALIDATE, 1L);
        }
    }

    static void forget(final UUID carrierId) {
        final var rooms = ROOMS.entrySet().iterator();
        while (rooms.hasNext()) {
            final var room = rooms.next();
            if (!room.getKey().carrierId().equals(carrierId)) continue;
            retainedCells -= room.getValue().cells.size();
            rooms.remove();
            ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_INVALIDATE, 1L);
        }
    }

    public static void clear() {
        ROOMS.clear();
        retainedCells = 0;
    }

    private static Entry remove(final RoomKey key) {
        final Entry removed = ROOMS.remove(key);
        if (removed != null) retainedCells -= removed.cells.size();
        return removed;
    }

    private record RoomKey(UUID carrierId, UUID roomId) { }

    private static final class Entry {
        private final ClientSubLevel carrier;
        private final long revision;
        private final Pose3d pose;
        private final List<BlockPos> cells;
        private final AABB bounds;
        private final CompartmentWaterGeometry.PreparedGeometry geometry;
        private boolean hasSurface;
        private long gameTime;
        private long fractionBits;
        private double surface;

        private Entry(final ClientSubLevel carrier, final long revision, final Pose3d pose,
                      final List<BlockPos> cells, final AABB bounds,
                      final CompartmentWaterGeometry.PreparedGeometry geometry) {
            this.carrier = carrier;
            this.revision = revision;
            this.pose = pose;
            this.cells = cells;
            this.bounds = bounds;
            this.geometry = geometry;
        }

        private boolean matches(final ClientSubLevel carrier, final long revision,
                                final Pose3dc pose, final List<BlockPos> cells, final AABB bounds) {
            return this.carrier == carrier && this.revision == revision && this.cells == cells
                    && this.bounds.equals(bounds) && same(this.pose.position(), pose.position())
                    && same(this.pose.orientation(), pose.orientation())
                    && same(this.pose.rotationPoint(), pose.rotationPoint())
                    && same(this.pose.scale(), pose.scale());
        }

        /** Reasons may overlap; they describe one miss and are not additional work counts. */
        private void recordMismatch(final ClientSubLevel carrier, final long revision,
                                    final Pose3dc pose, final List<BlockPos> cells, final AABB bounds) {
            if (this.carrier != carrier) {
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS_INSTANCE, 1L);
            }
            if (this.revision != revision) {
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS_REVISION, 1L);
            }
            if (this.cells != cells) {
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS_DOMAIN, 1L);
            }
            if (!this.bounds.equals(bounds)) {
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS_BOUNDS, 1L);
            }
            if (!same(this.pose.position(), pose.position())
                    || !same(this.pose.orientation(), pose.orientation())
                    || !same(this.pose.rotationPoint(), pose.rotationPoint())
                    || !same(this.pose.scale(), pose.scale())) {
                ClientWaterDiagnostics.count(ClientWaterDiagnostics.Work.ROOM_GEOMETRY_MISS_POSE, 1L);
            }
        }
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
