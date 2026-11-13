package com.create.nautilus.network;

import java.util.Arrays;
import java.util.Objects;

/** Sorted, distinct plot cells whose backing array never escapes the synchronization layer. */
public final class OcclusionCells {
    private static final OcclusionCells EMPTY = new OcclusionCells(new long[0]);

    private final long[] cells;

    private OcclusionCells(final long[] cells) {
        this.cells = cells;
    }

    public static OcclusionCells empty() {
        return EMPTY;
    }

    /** Retain ownership isolation for external array callers. */
    public static OcclusionCells copyOf(final long[] cells) {
        return normalizeOwned(Objects.requireNonNull(cells, "cells").clone());
    }

    boolean sameCells(final OcclusionCells other) {
        return this == other || Arrays.equals(cells, other.cells);
    }

    // Package-private readers never mutate this array or return it to their own callers.
    long[] values() {
        return cells;
    }

    private static OcclusionCells normalizeOwned(final long[] cells) {
        if (cells.length == 0) return EMPTY;
        Arrays.sort(cells);
        int size = 1;
        for (int i = 1; i < cells.length; i++) {
            if (cells[i] != cells[size - 1]) cells[size++] = cells[i];
        }
        return new OcclusionCells(size == cells.length ? cells : Arrays.copyOf(cells, size));
    }

    /** Collect primitive cells and transfer a private buffer into the immutable result. */
    public static final class Builder {
        private long[] cells = new long[16];
        private int size;

        public void add(final long cell) {
            if (size == cells.length) {
                cells = Arrays.copyOf(cells, Math.max(16, Math.addExact(size, size >>> 1)));
            }
            cells[size++] = cell;
        }

        public OcclusionCells build() {
            final long[] owned = size == cells.length ? cells : Arrays.copyOf(cells, size);
            // A builder may be reused; its next writes must never change a published mask.
            cells = EMPTY.cells;
            size = 0;
            return normalizeOwned(owned);
        }
    }
}
