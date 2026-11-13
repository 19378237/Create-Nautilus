package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Transfers inventories through exact voxel overlap, before any destination capacity clamp. */
public final class CompartmentContentAllocator {
    private CompartmentContentAllocator() {}

    public record Source(Set<Long> cells, Set<Long> waterCells, AABB bounds,
                         double water, double gas, double oxygen, boolean repairLegacy) {}
    public record Contents(double[] water, double[] gas, double[] oxygen) {}

    public static Contents allocate(final List<Source> sources,
                                    final List<CompartmentSealer.DetectedCompartment> rooms,
                                    final double[][] overlaps, final Pose3dc pose,
                                    final Set<Long> realSources) {
        return allocate(sources, rooms, overlaps, pose, Collections.nCopies(rooms.size(), realSources),
                rooms.stream().map(CompartmentSealer.DetectedCompartment::cells).toList());
    }

    /** Full destination geometry can overlap across carriers; old cells have one transfer owner. */
    public static Contents allocate(final List<Source> sources,
                                    final List<CompartmentSealer.DetectedCompartment> rooms,
                                    final double[][] overlaps, final Pose3dc pose,
                                    final List<Set<Long>> realSources,
                                    final List<Set<Long>> transferCells) {
        final int count = rooms.size();
        final double[] water = new double[count], gas = new double[count], oxygen = new double[count];
        final List<Map<Long, CompartmentWaterGeometry.ProjectedInterval>> geometry = new ArrayList<>();
        final double[] capacity = new double[count];
        for (int i = 0; i < count; i++) {
            final Map<Long, CompartmentWaterGeometry.ProjectedInterval> cells = new LinkedHashMap<>();
            for (final long cell : rooms.get(i).cells()) {
                if (!realSources.get(i).contains(cell)) cells.put(cell,
                        CompartmentWaterGeometry.projectedCell(pose, BlockPos.of(cell)));
            }
            geometry.add(cells);
            capacity[i] = rooms.get(i).cells().isEmpty()
                    ? rooms.get(i).volumeBlocks() * 1000.0D : cells.size() * 1000.0D;
        }
        final List<Map<Long, Double>> oldWet = new ArrayList<>();
        final double[] displaced = new double[sources.size()];
        // Keep the old liquid where it actually was. Removed/occupied wet cells are dealt with
        // together below, so an early child clamp cannot discard another child's usable capacity.
        for (int s = 0; s < sources.size(); s++) {
            final Source source = sources.get(s);
            final Map<Long, Double> wet = wetFractions(source, pose);
            oldWet.add(wet);
            double assigned = 0.0D, totalOverlap = Arrays.stream(overlaps[s]).sum();
            if (totalOverlap == 0.0D || source.repairLegacy()) continue;
            for (int i = 0; i < count; i++) {
                if (overlaps[s][i] == 0.0D) continue;
                final double preferred = source.cells().isEmpty()
                        ? source.water() * overlaps[s][i] / totalOverlap
                        : transferCells.get(i).stream().filter(geometry.get(i)::containsKey)
                                .mapToDouble(c -> wet.getOrDefault(c, 0.0D)).sum() * 1000.0D;
                final double accepted = Math.min(Math.max(0.0D, capacity[i] - water[i]), preferred);
                water[i] += accepted;
                assigned += accepted;
            }
            displaced[s] = Math.max(0.0D, source.water() - assigned);
        }
        // Old-room/new-room overlap defines independent transfer groups. A merge can connect
        // several old sources; pooling their displaced liquid prevents source iteration order
        // from consuming capacity needed by another source of that same connected group.
        final boolean[] visited = new boolean[count];
        for (int seed = 0; seed < count; seed++) {
            if (visited[seed]) continue;
            final Set<Integer> group = new LinkedHashSet<>(), donors = new LinkedHashSet<>();
            group.add(seed);
            final Queue<Integer> pending = new ArrayDeque<>(); pending.add(seed); visited[seed] = true;
            while (!pending.isEmpty()) {
                final int room = pending.remove();
                for (int s = 0; s < sources.size(); s++) {
                    if (overlaps[s][room] == 0.0D || !donors.add(s)) continue;
                    for (int i = 0; i < count; i++) if (overlaps[s][i] > 0.0D && !visited[i]) {
                        visited[i] = true; group.add(i); pending.add(i);
                    }
                }
            }
            final double excess = donors.stream().mapToDouble(s -> displaced[s]).sum();
            fillDisplaced(group, geometry, capacity, water, excess);
        }
        final List<Map<Long, Double>> newWet = new ArrayList<>();
        for (int i = 0; i < count; i++) newWet.add(fractions(geometry.get(i), water[i]));
        for (int s = 0; s < sources.size(); s++) {
            final Source source = sources.get(s);
            final double[] weights = new double[count];
            for (int i = 0; i < count; i++) {
                if (overlaps[s][i] == 0.0D) continue;
                if (source.repairLegacy()) {
                    gas[i] += overlaps[s][i] * 1000.0D;
                    oxygen[i] += overlaps[s][i] * NautilusConfig.airDensityPerBlock();
                } else if (source.cells().isEmpty()) {
                    weights[i] = Math.max(0.0D, capacity[i] - water[i]);
                } else {
                    for (final long cell : transferCells.get(i)) if (geometry.get(i).containsKey(cell)
                            && source.waterCells().contains(cell)) {
                        weights[i] += Math.max(0.0D, 1.0D - Math.max(oldWet.get(s).getOrDefault(cell, 0.0D),
                                newWet.get(i).getOrDefault(cell, 0.0D)));
                    }
                }
            }
            if (source.repairLegacy()) continue;
            double total = Arrays.stream(weights).sum();
            if (total <= 1.0E-12D) {
                for (int i = 0; i < count; i++) if (overlaps[s][i] > 0.0D)
                    weights[i] = Math.max(0.0D, capacity[i] - water[i]);
                total = Arrays.stream(weights).sum();
            }
            // A completely flooded room retains compressed gas (the pressure solver has its own
            // finite minimum gas volume). No surviving geometry means contents leave the carrier.
            if (total <= 1.0E-12D) { weightsCopy(overlaps[s], weights); total = Arrays.stream(weights).sum(); }
            if (total > 0.0D) for (int i = 0; i < count; i++) {
                gas[i] += source.gas() * weights[i] / total;
                oxygen[i] += source.oxygen() * weights[i] / total;
            }
        }
        return new Contents(water, gas, oxygen);
    }

    private static void weightsCopy(final double[] source, final double[] target) {
        System.arraycopy(source, 0, target, 0, source.length);
    }

    private static Map<Long, Double> wetFractions(final Source source, final Pose3dc pose) {
        final Map<Long, CompartmentWaterGeometry.ProjectedInterval> cells = new LinkedHashMap<>();
        for (final long cell : source.waterCells()) cells.put(cell,
                CompartmentWaterGeometry.projectedCell(pose, BlockPos.of(cell)));
        return fractions(cells, source.water());
    }

    private static Map<Long, Double> fractions(
            final Map<Long, CompartmentWaterGeometry.ProjectedInterval> cells, final double water) {
        final Map<Long, Double> result = new HashMap<>();
        if (cells.isEmpty()) return result;
        double low = cells.values().stream().mapToDouble(CompartmentWaterGeometry.ProjectedInterval::low).min().orElse(0D);
        double high = cells.values().stream().mapToDouble(CompartmentWaterGeometry.ProjectedInterval::high).max().orElse(0D);
        final double target = Math.clamp(water / 1000.0D, 0D, cells.size());
        for (int step = 0; step < 24; step++) {
            final double middle = (low + high) * 0.5D;
            final double volume = cells.values().stream().mapToDouble(c -> c.fractionBelow(middle)).sum();
            if (volume < target) low = middle; else high = middle;
        }
        final double surface = (low + high) * 0.5D;
        cells.forEach((key, interval) -> result.put(key, target <= 0D ? 0D
                : target >= cells.size() ? 1D : interval.fractionBelow(surface)));
        // Correct only the inverse solver's rounding, retaining its spatial distribution.
        final double measured = result.values().stream().mapToDouble(Double::doubleValue).sum();
        if (measured > 0D) result.replaceAll((key, value) -> Math.min(1D, value * target / measured));
        return result;
    }

    private static void fillDisplaced(final Set<Integer> group,
                                     final List<Map<Long, CompartmentWaterGeometry.ProjectedInterval>> geometry,
                                     final double[] capacity, final double[] water, final double excess) {
        final double available = group.stream().mapToDouble(i -> Math.max(0D, capacity[i] - water[i])).sum();
        final double target = Math.min(available, excess);
        if (target <= 1.0E-9D) return;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (final int i : group) for (final var interval : geometry.get(i).values()) {
            low = Math.min(low, interval.low()); high = Math.max(high, interval.high());
        }
        if (Double.isFinite(low) && Double.isFinite(high)) {
            for (int step = 0; step < 24; step++) {
                final double middle = (low + high) * 0.5D;
                double added = 0D;
                for (final int i : group) added += Math.max(0D, below(geometry.get(i), middle) - water[i]);
                if (added < target) low = middle; else high = middle;
            }
            // Use the lower bound so rounding never over-allocates the conserved amount.
            double remaining = target;
            for (final int i : group) {
                final double accepted = Math.min(remaining,
                        Math.max(0D, Math.min(capacity[i], below(geometry.get(i), low)) - water[i]));
                water[i] += accepted; remaining -= accepted;
            }
            finishRemainder(group, capacity, water, remaining);
        } else finishRemainder(group, capacity, water, target);
    }

    private static double below(final Map<Long, CompartmentWaterGeometry.ProjectedInterval> cells, final double height) {
        return cells.values().stream().mapToDouble(c -> c.fractionBelow(height)).sum() * 1000.0D;
    }

    private static void finishRemainder(final Set<Integer> group, final double[] capacity,
                                        final double[] water, double remaining) {
        for (final int i : group) {
            final double accepted = Math.min(remaining, Math.max(0D, capacity[i] - water[i]));
            water[i] += accepted; remaining -= accepted;
        }
    }
}
