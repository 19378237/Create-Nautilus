package com.create.nautilus.client.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Render-only cabin cells; padding follows the carrier's local block axes. */
final class PlainWaterOcclusion {
    private static final int MAX_ROOMS = 256;
    private static final int MAX_CELLS = 65_536;
    private static final Map<RoomKey, PaddedCells> PADDED = new LinkedHashMap<>(16, 0.75F, true);
    private static int retainedCells;

    private PlainWaterOcclusion() { }

    static long[] paddedCells(final UUID carrierId, final UUID roomId, final long revision,
                               final List<BlockPos> roomCells) {
        final RoomKey key = new RoomKey(carrierId, roomId);
        final PaddedCells previous = PADDED.get(key);
        if (previous != null && previous.revision() == revision && previous.source() == roomCells) {
            return previous.cells();
        }
        if (previous != null) {
            PADDED.remove(key);
            retainedCells -= previous.cells().length;
        }
        final long[] cells = cells(roomCells, true);
        if (cells.length <= MAX_CELLS) {
            PADDED.put(key, new PaddedCells(revision, roomCells, cells));
            retainedCells += cells.length;
            while (PADDED.size() > MAX_ROOMS || retainedCells > MAX_CELLS) {
                final var oldest = PADDED.entrySet().iterator();
                retainedCells -= oldest.next().getValue().cells().length;
                oldest.remove();
            }
        }
        return cells;
    }

    static void forget(final UUID carrierId) {
        final var entries = PADDED.entrySet().iterator();
        while (entries.hasNext()) {
            final var entry = entries.next();
            if (!entry.getKey().carrierId().equals(carrierId)) continue;
            retainedCells -= entry.getValue().cells().length;
            entries.remove();
        }
    }

    static void clear() {
        PADDED.clear();
        retainedCells = 0;
    }

    static long[] cells(final List<BlockPos> roomCells, final boolean padded) {
        final LongOpenHashSet cells = new LongOpenHashSet(roomCells.size());
        final int margin = padded ? 1 : 0;
        for (final BlockPos cell : roomCells) {
            for (int x = -margin; x <= margin; x++) {
                for (int y = -margin; y <= margin; y++) {
                    for (int z = -margin; z <= margin; z++) {
                        cells.add(BlockPos.asLong(cell.getX() + x, cell.getY() + y, cell.getZ() + z));
                    }
                }
            }
        }
        final long[] result = cells.toLongArray();
        Arrays.sort(result);
        return result;
    }

    /** Filter after padding so a real plot fluid in the added shell keeps its own renderer. */
    static long[] withoutWater(final long[] cells, final Predicate<BlockPos> realWater) {
        long[] filtered = null;
        int count = 0;
        for (int index = 0; index < cells.length; index++) {
            if (realWater.test(BlockPos.of(cells[index]))) {
                if (filtered == null) {
                    filtered = new long[cells.length];
                    System.arraycopy(cells, 0, filtered, 0, index);
                    count = index;
                }
            } else if (filtered != null) {
                filtered[count++] = cells[index];
            }
        }
        return filtered == null ? cells : Arrays.copyOf(filtered, count);
    }

    /** Shared voxel seams are internal; the small margin applies only to the outer hull. */
    static boolean containsCamera(final long[] cells, final Vector3d local, final double margin) {
        final BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
        if (!contains(cells, cell)) return false;
        return insideAxis(cells, cell, local.x - Math.floor(local.x), margin, Direction.WEST, Direction.EAST)
                && insideAxis(cells, cell, local.y - Math.floor(local.y), margin, Direction.DOWN, Direction.UP)
                && insideAxis(cells, cell, local.z - Math.floor(local.z), margin, Direction.NORTH, Direction.SOUTH);
    }

    private static boolean insideAxis(final long[] cells, final BlockPos cell, final double fraction,
                                      final double margin, final Direction negative, final Direction positive) {
        if (fraction <= margin && !contains(cells, cell.relative(negative))) return false;
        return fraction < 1.0D - margin || contains(cells, cell.relative(positive));
    }

    private static boolean contains(final long[] cells, final BlockPos cell) {
        return Arrays.binarySearch(cells, cell.asLong()) >= 0;
    }

    private record RoomKey(UUID carrierId, UUID roomId) { }
    private record PaddedCells(long revision, List<BlockPos> source, long[] cells) { }
}
