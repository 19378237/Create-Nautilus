package com.create.nautilus.client.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure CPU planning for the atlas' linear RGBA descriptor prefix. */
final class AtlasPrefixUploadPlanner {
    private static final int COMPONENTS = 4;
    private static final int MAX_MERGED_GAP_TEXELS = 4;
    private static final int MAX_PARTIAL_RECTANGLES = 8;

    private AtlasPrefixUploadPlanner() {
    }

    static Plan plan(final float[] data, final float[] uploaded, final int width) {
        Objects.requireNonNull(data, "data");
        if (width <= 0 || data.length % COMPONENTS != 0) {
            throw new IllegalArgumentException("Invalid RGBA atlas prefix dimensions");
        }
        final int texels = data.length / COMPONENTS;
        if (texels == 0) return new Plan(false, 0, 0, List.of(), List.of());
        if (uploaded == null || uploaded.length != data.length) {
            return whole(texels, texels, width);
        }

        final List<Run> runs = new ArrayList<>();
        final List<Rectangle> rectangles = new ArrayList<>();
        int changed = 0;
        int merged = 0;
        int start = -1;
        int lastChanged = -1;
        boolean full = false;
        for (int texel = 0; texel < texels; texel++) {
            if (sameTexel(data, uploaded, texel)) continue;
            changed++;
            if (start >= 0 && texel - lastChanged - 1 > MAX_MERGED_GAP_TEXELS) {
                final int count = lastChanged + 1 - start;
                if (!full) {
                    runs.add(new Run(start, count));
                    addRectangles(rectangles, start, count, merged, width);
                    merged += count;
                    full = (long) merged * 2L >= texels
                            || rectangles.size() > MAX_PARTIAL_RECTANGLES;
                    if (full) {
                        runs.clear();
                        rectangles.clear();
                    }
                }
                start = texel;
            } else if (start < 0) {
                start = texel;
            }
            lastChanged = texel;
        }
        if (start < 0) return new Plan(false, 0, 0, List.of(), List.of());
        if (!full) {
            final int count = lastChanged + 1 - start;
            runs.add(new Run(start, count));
            addRectangles(rectangles, start, count, merged, width);
            merged += count;
            full = (long) merged * 2L >= texels
                    || rectangles.size() > MAX_PARTIAL_RECTANGLES;
        }
        return full ? whole(texels, changed, width)
                : new Plan(false, changed, merged, List.copyOf(runs), List.copyOf(rectangles));
    }

    /** Float.floatToIntBits matches Arrays.equals(float[]) for NaNs and signed zero. */
    private static boolean sameTexel(final float[] data, final float[] uploaded,
                                     final int texel) {
        final int offset = texel * COMPONENTS;
        for (int component = 0; component < COMPONENTS; component++) {
            if (Float.floatToIntBits(data[offset + component])
                    != Float.floatToIntBits(uploaded[offset + component])) return false;
        }
        return true;
    }

    private static Plan whole(final int texels, final int changed, final int width) {
        final List<Rectangle> rectangles = new ArrayList<>(2);
        addRectangles(rectangles, 0, texels, 0, width);
        return new Plan(true, changed, texels, List.of(new Run(0, texels)),
                List.copyOf(rectangles));
    }

    /** Compact source offsets advance only through selected runs, including their merged gaps. */
    private static void addRectangles(final List<Rectangle> rectangles,
                                      final int start, final int count,
                                      final int stagingOffset, final int width) {
        int destination = start;
        int source = stagingOffset;
        int remaining = count;
        final int startX = destination % width;
        if (startX != 0) {
            final int first = Math.min(remaining, width - startX);
            rectangles.add(new Rectangle(startX, destination / width, first, 1, source));
            destination += first;
            source += first;
            remaining -= first;
        }
        final int rows = remaining / width;
        if (rows > 0) {
            rectangles.add(new Rectangle(0, destination / width, width, rows, source));
            final int middle = rows * width;
            destination += middle;
            source += middle;
            remaining -= middle;
        }
        if (remaining > 0) {
            rectangles.add(new Rectangle(0, destination / width, remaining, 1, source));
        }
    }

    record Run(int startTexel, int texels) {
    }

    record Rectangle(int x, int y, int width, int height, int stagingTexel) {
    }

    record Plan(boolean full, int changedTexels, int stagedTexels,
                List<Run> runs, List<Rectangle> rectangles) {
        boolean unchanged() {
            return stagedTexels == 0;
        }
    }
}
