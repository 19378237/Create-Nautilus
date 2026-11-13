package com.create.nautilus.physics;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Call-local plot view in the original global X, then Y, then Z scan order. */
final class PlotBlockCursor {
    private static final Comparator<Section> ORDER = Comparator.comparingInt(Section::minX)
            .thenComparingInt(Section::minY).thenComparingInt(Section::minZ);

    private final Level level;
    private final BoundingBox3ic bounds;
    private final boolean diagnostics;
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private final List<Section> sections;
    private BlockState blockState;
    private boolean available;
    private int x, y, z;
    private int chunkStart, chunkEnd, rowStart, rowEnd, sectionIndex;
    private long skippedEmptySectionCells, skippedEmptySections, nativeSectionReads, fallbackReads;

    static PlotBlockCursor create(final ServerSubLevel carrier, final BoundingBox3ic bounds) {
        return create(carrier, bounds, false);
    }

    static PlotBlockCursor create(final ServerSubLevel carrier, final BoundingBox3ic bounds,
                                  final boolean diagnostics) {
        return new PlotBlockCursor(carrier, bounds, diagnostics);
    }

    private PlotBlockCursor(final ServerSubLevel carrier, final BoundingBox3ic bounds,
                            final boolean diagnostics) {
        this.level = carrier.getLevel();
        this.bounds = bounds;
        this.diagnostics = diagnostics;
        if (bounds == null || bounds.minX() > bounds.maxX() || bounds.minY() > bounds.maxY()
                || bounds.minZ() > bounds.maxZ()) {
            sections = List.of();
            return;
        }
        sections = collectSections(carrier);
        if (sections == null) {
            // An incomplete/unavailable native view must retain the old Level lookup behavior.
            skippedEmptySectionCells = skippedEmptySections = 0L;
            x = bounds.minX();
            y = bounds.minY();
            z = bounds.minZ();
            available = true;
        } else if (!sections.isEmpty()) {
            beginChunk(0);
            available = true;
        }
    }

    /** The returned position is reused and is valid until the next advance. */
    BlockPos position() { return position; }

    BlockState blockState() { return blockState; }

    long inspectedCells() { return nativeSectionReads + fallbackReads; }

    long skippedEmptySectionCells() { return skippedEmptySectionCells; }

    long skippedEmptySections() { return skippedEmptySections; }

    long nativeSectionReads() { return nativeSectionReads; }

    long fallbackReads() { return fallbackReads; }

    /** Advances over non-air states only. Collision shapes and volumes remain the caller's live query. */
    boolean advance() {
        while (available) {
            position.set(x, y, z);
            final BlockState next;
            if (sections == null) {
                next = level.getBlockState(position);
                if (diagnostics) fallbackReads++;
                advanceFallback();
            } else {
                next = sections.get(sectionIndex).section().getBlockState(x & 15, y & 15, z & 15);
                if (diagnostics) nativeSectionReads++;
                advanceNative();
            }
            if (!next.isAir()) {
                blockState = next;
                return true;
            }
        }
        blockState = null;
        return false;
    }

    private List<Section> collectSections(final ServerSubLevel carrier) {
        final Collection<PlotChunkHolder> holders = carrier.getPlot().getLoadedChunks();
        if (holders == null || holders.isEmpty()) return null;
        final List<Section> result = new ArrayList<>();
        boolean intersectsPlot = false;
        for (final PlotChunkHolder holder : holders) {
            if (holder == null) return null;
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) return null;
            // These are the actual plot-storage coordinates, already in the Level's block space.
            final ChunkPos chunkPos = chunk.getPos();
            final int minX = Math.max(bounds.minX(), chunkPos.getMinBlockX());
            final int maxX = Math.min(bounds.maxX(), chunkPos.getMaxBlockX());
            final int minZ = Math.max(bounds.minZ(), chunkPos.getMinBlockZ());
            final int maxZ = Math.min(bounds.maxZ(), chunkPos.getMaxBlockZ());
            if (minX > maxX || minZ > maxZ) continue;
            intersectsPlot = true;
            final LevelChunkSection[] chunkSections = chunk.getSections();
            if (chunkSections == null || chunkSections.length == 0) return null;
            for (int index = 0; index < chunkSections.length; index++) {
                final int sectionMinY = chunk.getSectionYFromSectionIndex(index) << 4;
                final int minY = Math.max(bounds.minY(), sectionMinY);
                final int maxY = Math.min(bounds.maxY(), sectionMinY + 15);
                if (minY > maxY) continue;
                final LevelChunkSection section = chunkSections[index];
                if (section == null) return null;
                if (section.hasOnlyAir()) {
                    if (diagnostics) {
                        skippedEmptySections++;
                        skippedEmptySectionCells += (long) (maxX - minX + 1)
                                * (maxY - minY + 1) * (maxZ - minZ + 1);
                    }
                } else {
                    result.add(new Section(section, minX, maxX, minY, maxY, minZ, maxZ));
                }
            }
        }
        if (!intersectsPlot) return null;
        result.sort(ORDER);
        for (int index = 1; index < result.size(); index++) {
            if (ORDER.compare(result.get(index - 1), result.get(index)) == 0) return null;
        }
        return result;
    }

    private void beginChunk(final int start) {
        chunkStart = start;
        chunkEnd = start + 1;
        x = sections.get(start).minX();
        while (chunkEnd < sections.size() && sections.get(chunkEnd).minX() == x) chunkEnd++;
        beginRow(start);
    }

    private void beginRow(final int start) {
        rowStart = start;
        rowEnd = start + 1;
        y = sections.get(start).minY();
        while (rowEnd < chunkEnd && sections.get(rowEnd).minY() == y) rowEnd++;
        sectionIndex = start;
        z = sections.get(start).minZ();
    }

    private void advanceNative() {
        final Section section = sections.get(sectionIndex);
        if (z < section.maxZ()) {
            z++;
        } else if (++sectionIndex < rowEnd) {
            z = sections.get(sectionIndex).minZ();
        } else if (y < section.maxY()) {
            y++;
            sectionIndex = rowStart;
            z = sections.get(rowStart).minZ();
        } else if (rowEnd < chunkEnd) {
            beginRow(rowEnd);
        } else if (x < section.maxX()) {
            x++;
            beginRow(chunkStart);
        } else if (chunkEnd < sections.size()) {
            beginChunk(chunkEnd);
        } else {
            available = false;
        }
    }

    private void advanceFallback() {
        if (z < bounds.maxZ()) {
            z++;
        } else if (y < bounds.maxY()) {
            y++;
            z = bounds.minZ();
        } else if (x < bounds.maxX()) {
            x++;
            y = bounds.minY();
            z = bounds.minZ();
        } else {
            available = false;
        }
    }

    private record Section(LevelChunkSection section, int minX, int maxX, int minY, int maxY,
                           int minZ, int maxZ) {
    }
}
