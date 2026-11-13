package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.HullBreach;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 在 Sable plot 方块网格上执行六邻域体素洪水填充。
 * 完整碰撞立方体视作水密壳体；非整块装饰、打开的门和流体均允许洪水通过。
 */
public final class CompartmentSealer {
    private CompartmentSealer() {
    }

    public static List<DetectedCompartment> scan(final ServerSubLevel carrier) {
        return scan(carrier, Set.of()).compartments();
    }

    private static ScanResult scan(final ServerSubLevel carrier,
                                                   final Set<Long> virtualMembranes) {
        // Assembly and plot block publication are separate Sable operations. Recompute the plot
        // bounds before every authoritative scan so the first physics callback cannot observe a
        // partially updated box that is repaired only by a later manual block-state change.
        carrier.getPlot().updateBoundingBox();
        final BoundingBox3ic plotBounds = carrier.getPlot().getBoundingBox();
        if (plotBounds == null || plotBounds.volume() <= 0) {
            return new ScanResult(List.of(), ScanStatus.NOT_READY);
        }

        final int minX = plotBounds.minX() - 1;
        final int minY = plotBounds.minY() - 1;
        final int minZ = plotBounds.minZ() - 1;
        final int maxX = plotBounds.maxX() + 1;
        final int maxY = plotBounds.maxY() + 1;
        final int maxZ = plotBounds.maxZ() + 1;

        final long scanVolume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (scanVolume > (long) NautilusConfig.maximumCompartmentVolume() * 16L) {
            return new ScanResult(List.of(), ScanStatus.TOO_LARGE);
        }

        final Level level = carrier.getLevel();
        final Set<Long> exterior = collectExterior(level, plotBounds, virtualMembranes, true);
        final Queue<BlockPos> queue = new ArrayDeque<>();

        final Set<Long> visitedInterior = new HashSet<>();
        final List<DetectedCompartment> result = new ArrayList<>();
        final int limit = NautilusConfig.maximumCompartmentVolume();
        boolean oversized = false;

        for (int x = plotBounds.minX(); x <= plotBounds.maxX(); x++) {
            for (int y = plotBounds.minY(); y <= plotBounds.maxY(); y++) {
                for (int z = plotBounds.minZ(); z <= plotBounds.maxZ(); z++) {
                    final BlockPos seed = new BlockPos(x, y, z);
                    final long key = seed.asLong();
                    if (exterior.contains(key) || visitedInterior.contains(key)
                            || !isPassable(level, seed, virtualMembranes)) {
                        continue;
                    }

                    final Set<Long> cells = new HashSet<>();
                    queue.add(seed);
                    visitedInterior.add(key);
                    flood(level, queue, visitedInterior, plotBounds.minX(), plotBounds.minY(),
                            plotBounds.minZ(), plotBounds.maxX(), plotBounds.maxY(), plotBounds.maxZ(),
                            limit, cells, virtualMembranes);
                    if (!cells.isEmpty() && cells.size() <= limit) {
                        result.add(toDetected(cells));
                    } else if (cells.isEmpty()) {
                        oversized = true;
                    }
                }
            }
        }
        return new ScanResult(result, oversized ? ScanStatus.TOO_LARGE
                : result.isEmpty() ? ScanStatus.EMPTY : ScanStatus.VALID);
    }

    public static void rescanInto(final ServerSubLevel carrier, final CarrierState state) {
        rescanInto(carrier, state, FloodingHandler.resolvePendingBreaks(carrier, state));
    }

    public static void rescanInto(final ServerSubLevel carrier, final CarrierState state,
                                  final ScanResult preparedScan) {
        // If an opened hull cell were allowed to participate in the exterior flood fill, the
        // entire wet compartment would disappear on the next scan and its accumulated water would
        // be lost. Registered breaches therefore act as zero-thickness virtual membranes only for
        // topology discovery; CarrierState.refreshSealFlags still marks the room as unsealed.
        final ScanResult scan = preparedScan != null ? preparedScan : detectCurrentScan(carrier, state);
        if (!scan.complete()) {
            state.setLastCompartmentScanTick(carrier.getLevel().getGameTime());
            state.reportTopologyScanFailure(scan.status().name(), carrier.getLevel().getGameTime());
            return;
        }
        final List<DetectedCompartment> detected = scan.compartments();
        state.discardUnmatchedLegacyCompartments(detected);
        // A single empty result must not erase a known exact room: chunk/render-data publication
        // can briefly race the first physics callback. Require three consecutive empty scans for
        // a genuine topology removal. Normal hull breaks retain the room through virtual breach
        // membranes and therefore do not use this fallback. Unmatchable legacy rooms were already
        // discarded above, so they never wait for this exact-room publication guard.
        if (detected.isEmpty() && !state.compartments().isEmpty()
                && state.deferUnexpectedEmptyTopology()) {
            state.recordTopologyScan(false);
            state.setLastCompartmentScanTick(carrier.getLevel().getGameTime());
            return;
        }
        if (detected.isEmpty()) {
            state.acceptEmptyTopology();
        }
        final CompartmentFluidInventory.Snapshot realWater = CompartmentFluidInventory.measure(carrier, state);
        state.reconcileCompartments(detected, carrier.logicalPose(), realWater.sources().keySet());
        // Discovery already proved the surviving boundary and rescanned any retired membranes.
        // Commit both together: deleting a breach only after installing its old geometry would
        // leave a one-scan hole in the cabin mask/capacity behind newly placed exterior armor.
        state.breaches().removeIf(breach -> !breach.atmosphericOpening()
                && !scan.ordinaryBreaches().contains(breach.position().asLong()));
        synchronizeAtmosphericOpenings(state, detected);
        final int repairedLegacyRooms = state.repairLegacyContaminatedWaterAfterScan();
        if (repairedLegacyRooms > 0) {
            com.create.nautilus.CreateNautilus.LOGGER.info(
                    "Restored {} geometry-confirmed rooms affected by legacy false breaches to dry ambient state on carrier {}",
                    repairedLegacyRooms, state.carrierId());
        }
        state.recordTopologyScan(!detected.isEmpty());
        state.setLastCompartmentScanTick(carrier.getLevel().getGameTime());
    }

    /**
     * Produces a read-only topology probe using only already confirmed hull breaches as virtual
     * membranes. Pending block removals are deliberately absent, allowing callers to distinguish
     * a newly exposed interior voxel from a real aperture before mutating persistent state.
     */
    static List<DetectedCompartment> detectCurrentCompartments(final ServerSubLevel carrier,
                                                               final CarrierState state) {
        return detectCurrentScan(carrier, state).compartments();
    }

    static ScanResult detectCurrentScan(final ServerSubLevel carrier, final CarrierState state) {
        Set<Long> membranes = state.breaches().stream()
                // Open-top vents are rediscovered from geometry on every scan. Treating them as
                // membranes here would make the normal airtight scan alternate between sealed
                // and open on successive passes.
                // Direction.DOWN points from a top aperture to its interior cell. Let open-top
                // geometry own these apertures so stale ordinary records cannot form a false roof.
                .filter(breach -> !breach.atmosphericOpening()
                        && breach.interiorDirection() != Direction.DOWN
                        && isPassable(carrier.getLevel(), breach.position(), Set.of()))
                .map(breach -> breach.position().asLong())
                .collect(Collectors.toUnmodifiableSet());
        while (true) {
            final ScanResult airtight = scan(carrier, membranes);
            if (!airtight.complete()) return airtight;
            final ScanResult openTop = scanOpenTop(carrier, membranes, airtight.compartments());
            if (!openTop.complete()) return openTop;
            final List<DetectedCompartment> detected = new ArrayList<>(airtight.compartments());
            detected.addAll(openTop.compartments());
            final List<Set<Long>> passages = membraneComponents(membranes);
            final Set<Long> boundary = exteriorBoundaryMembranes(carrier, detected, membranes, passages);
            if (!boundary.equals(membranes)) {
                // Only remove membranes, so this loop terminates. Interior cells of a thick
                // opening join the cabin; its actual exterior-facing mouth remains a membrane.
                // Complete the new scan before mutating stock or publishing the new topology.
                membranes = boundary;
                continue;
            }
            final List<DetectedCompartment> connected = mergeRoomsThroughBreaches(detected, passages);
            return new ScanResult(connected, connected.isEmpty() ? ScanStatus.EMPTY : ScanStatus.VALID,
                    membranes);
        }
    }

    /** Consecutive damaged voxels are one passage, including paths longer than a single voxel. */
    private static List<Set<Long>> membraneComponents(final Set<Long> membranes) {
        final Set<Long> remaining = new HashSet<>(membranes);
        final List<Set<Long>> result = new ArrayList<>();
        final Queue<BlockPos> queue = new ArrayDeque<>();
        while (!remaining.isEmpty()) {
            final long seed = remaining.iterator().next();
            remaining.remove(seed);
            final Set<Long> component = new HashSet<>();
            component.add(seed);
            queue.add(BlockPos.of(seed));
            while (!queue.isEmpty()) {
                final BlockPos current = queue.remove();
                for (final Direction direction : Direction.values()) {
                    final BlockPos adjacent = current.relative(direction);
                    if (remaining.remove(adjacent.asLong())) {
                        component.add(adjacent.asLong());
                        queue.add(adjacent);
                    }
                }
            }
            result.add(component);
        }
        return result;
    }

    /**
     * Exterior proof cannot walk through a cabin or bootstrap from another unproven membrane.
     * Keep only the actual outside-facing cells of a passage touching a detected room. The next
     * scan absorbs its covered/interior cells, while preserving the mouth of a multi-voxel hole.
     */
    private static Set<Long> exteriorBoundaryMembranes(
            final ServerSubLevel carrier, final List<DetectedCompartment> rooms,
            final Set<Long> membranes, final List<Set<Long>> passages) {
        if (membranes.isEmpty()) return Set.of();
        final Set<Long> roomCells = rooms.stream().flatMap(room -> room.cells().stream())
                .collect(Collectors.toSet());
        final Set<Long> blocked = new HashSet<>(membranes);
        blocked.addAll(roomCells);
        final Set<Long> exterior = collectExterior(carrier.getLevel(),
                carrier.getPlot().getBoundingBox(), blocked, true);
        final Set<Long> boundary = new HashSet<>();
        for (final Set<Long> passage : passages) {
            boolean touchesRoom = false;
            final Set<Long> mouths = new HashSet<>();
            for (final long cell : passage) {
                for (final Direction direction : Direction.values()) {
                    final long adjacent = BlockPos.of(cell).relative(direction).asLong();
                    touchesRoom |= roomCells.contains(adjacent);
                    if (exterior.contains(adjacent)) mouths.add(cell);
                }
            }
            if (touchesRoom) boundary.addAll(mouths);
        }
        return Set.copyOf(boundary);
    }

    /** The zero-thickness membranes block exterior flood fill, but never separate their rooms. */
    private static List<DetectedCompartment> mergeRoomsThroughBreaches(
            final List<DetectedCompartment> rooms, final List<Set<Long>> passages) {
        if (rooms.size() < 2 || passages.isEmpty()) return rooms;
        final Map<Long, Integer> roomAt = new java.util.HashMap<>();
        final int[] parent = new int[rooms.size()];
        for (int i = 0; i < rooms.size(); i++) {
            parent[i] = i;
            for (final long cell : rooms.get(i).cells()) roomAt.put(cell, i);
        }
        boolean merged = false;
        for (final Set<Long> passage : passages) {
            int first = -1;
            for (final long cell : passage) {
                for (final Direction direction : Direction.values()) {
                    final Integer room = roomAt.get(BlockPos.of(cell).relative(direction).asLong());
                    if (room == null) continue;
                    final int root = root(parent, room);
                    if (first < 0) first = root;
                    else if (root(parent, first) != root) {
                        parent[root] = root(parent, first);
                        merged = true;
                    }
                }
            }
        }
        if (!merged) return rooms;
        final Map<Integer, Set<Long>> cells = new LinkedHashMap<>();
        final Map<Integer, Set<Long>> openings = new LinkedHashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            final int root = root(parent, i);
            cells.computeIfAbsent(root, ignored -> new HashSet<>()).addAll(rooms.get(i).cells());
            openings.computeIfAbsent(root, ignored -> new HashSet<>()).addAll(rooms.get(i).atmosphericOpenings());
        }
        return cells.entrySet().stream().map(entry -> toDetected(entry.getValue(), openings.get(entry.getKey()))).toList();
    }

    private static int root(final int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    /**
     * Detects basin-shaped rooms which are connected to the exterior only by descending through
     * an opening above them.
     *
     * <p>The ordinary airtight scan allows movement in all six directions. This second exterior
     * flood fill deliberately forbids {@link Direction#DOWN}: outside air can move sideways and
     * upward, but it cannot enter an upright open vessel without descending over its rim. The
     * remaining passable components are therefore open-top water-displacement envelopes. Fully
     * airtight rooms are removed from this result to avoid duplicates.</p>
     */
    private static ScanResult scanOpenTop(
            final ServerSubLevel carrier, final Set<Long> virtualMembranes,
            final List<DetectedCompartment> airtight) {
        final BoundingBox3ic plotBounds = carrier.getPlot().getBoundingBox();
        if (plotBounds == null || plotBounds.volume() <= 0) {
            return new ScanResult(List.of(), ScanStatus.NOT_READY);
        }
        final int minX = plotBounds.minX() - 1;
        final int minY = plotBounds.minY() - 1;
        final int minZ = plotBounds.minZ() - 1;
        final int maxX = plotBounds.maxX() + 1;
        final int maxY = plotBounds.maxY() + 1;
        final int maxZ = plotBounds.maxZ() + 1;
        final long scanVolume = (long) (maxX - minX + 1)
                * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (scanVolume > (long) NautilusConfig.maximumCompartmentVolume() * 16L) {
            return new ScanResult(List.of(), ScanStatus.TOO_LARGE);
        }

        final Level level = carrier.getLevel();
        final Set<Long> exterior = collectExterior(level, plotBounds, virtualMembranes, false);

        final Set<Long> airtightCells = airtight.stream()
                .flatMap(room -> room.cells().stream())
                .collect(Collectors.toSet());
        final Set<Long> visited = new HashSet<>();
        final List<DetectedCompartment> result = new ArrayList<>();
        final int limit = NautilusConfig.maximumCompartmentVolume();
        boolean oversized = false;
        for (int x = plotBounds.minX(); x <= plotBounds.maxX(); x++) {
            for (int y = plotBounds.minY(); y <= plotBounds.maxY(); y++) {
                for (int z = plotBounds.minZ(); z <= plotBounds.maxZ(); z++) {
                    final BlockPos seed = new BlockPos(x, y, z);
                    if (exterior.contains(seed.asLong()) || visited.contains(seed.asLong())
                            || !isPassable(level, seed, virtualMembranes)) {
                        continue;
                    }
                    final Set<Long> cells = collectNonExteriorComponent(level, seed,
                            plotBounds, exterior, visited, virtualMembranes, limit);
                    if (cells.isEmpty()) {
                        oversized = true;
                        continue;
                    }
                    if (cells.stream().anyMatch(airtightCells::contains)) {
                        continue;
                    }
                    final Set<Long> openings = cells.stream()
                            .map(BlockPos::of)
                            .map(BlockPos::above)
                            .filter(above -> exterior.contains(above.asLong()))
                            .filter(above -> isPassable(level, above, virtualMembranes))
                            .map(BlockPos::asLong)
                            .collect(Collectors.toSet());
                    if (!openings.isEmpty()) {
                        result.add(toDetected(cells, openings));
                    }
                }
            }
        }
        return new ScanResult(result, oversized ? ScanStatus.TOO_LARGE
                : result.isEmpty() ? ScanStatus.EMPTY : ScanStatus.VALID);
    }

    /** Padded-boundary flood, shared by airtight, open-top and ordinary-aperture discovery. */
    private static Set<Long> collectExterior(final Level level, final BoundingBox3ic bounds,
                                              final Set<Long> blocked, final boolean descend) {
        final int minX = bounds.minX() - 1, minY = bounds.minY() - 1, minZ = bounds.minZ() - 1;
        final int maxX = bounds.maxX() + 1, maxY = bounds.maxY() + 1, maxZ = bounds.maxZ() + 1;
        final Set<Long> exterior = new HashSet<>();
        final Queue<BlockPos> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                enqueueExterior(level, new BlockPos(x, y, minZ), queue, exterior, blocked);
                enqueueExterior(level, new BlockPos(x, y, maxZ), queue, exterior, blocked);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                enqueueExterior(level, new BlockPos(x, minY, z), queue, exterior, blocked);
                enqueueExterior(level, new BlockPos(x, maxY, z), queue, exterior, blocked);
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                enqueueExterior(level, new BlockPos(minX, y, z), queue, exterior, blocked);
                enqueueExterior(level, new BlockPos(maxX, y, z), queue, exterior, blocked);
            }
        }
        if (descend) flood(level, queue, exterior, minX, minY, minZ, maxX, maxY, maxZ,
                Integer.MAX_VALUE, blocked);
        else floodExteriorWithoutDescending(level, queue, exterior,
                minX, minY, minZ, maxX, maxY, maxZ, blocked);
        return exterior;
    }

    private static void floodExteriorWithoutDescending(
            final Level level, final Queue<BlockPos> queue, final Set<Long> visited,
            final int minX, final int minY, final int minZ,
            final int maxX, final int maxY, final int maxZ,
            final Set<Long> virtualMembranes) {
        while (!queue.isEmpty()) {
            final BlockPos current = queue.remove();
            for (final Direction direction : Direction.values()) {
                if (direction == Direction.DOWN) {
                    continue;
                }
                final BlockPos next = current.relative(direction);
                if (next.getX() < minX || next.getX() > maxX
                        || next.getY() < minY || next.getY() > maxY
                        || next.getZ() < minZ || next.getZ() > maxZ
                        || !isPassable(level, next, virtualMembranes)
                        || !visited.add(next.asLong())) {
                    continue;
                }
                queue.add(next);
            }
        }
    }

    private static Set<Long> collectNonExteriorComponent(
            final Level level, final BlockPos seed, final BoundingBox3ic bounds,
            final Set<Long> exterior, final Set<Long> visited,
            final Set<Long> virtualMembranes, final int limit) {
        final Set<Long> cells = new HashSet<>();
        final Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed.asLong());
        boolean oversized = false;
        while (!queue.isEmpty()) {
            final BlockPos current = queue.remove();
            if (!oversized) {
                cells.add(current.asLong());
                if (cells.size() > limit) {
                    cells.clear();
                    oversized = true;
                }
            }
            for (final Direction direction : Direction.values()) {
                final BlockPos next = current.relative(direction);
                if (next.getX() < bounds.minX() || next.getX() > bounds.maxX()
                        || next.getY() < bounds.minY() || next.getY() > bounds.maxY()
                        || next.getZ() < bounds.minZ() || next.getZ() > bounds.maxZ()
                        || exterior.contains(next.asLong())
                        || !isPassable(level, next, virtualMembranes)
                        || !visited.add(next.asLong())) {
                    continue;
                }
                queue.add(next);
            }
        }
        return cells;
    }

    /** Keeps geometry-derived atmospheric vents synchronized without touching real hull damage. */
    private static void synchronizeAtmosphericOpenings(
            final CarrierState state, final List<DetectedCompartment> detected) {
        final Map<Long, AtmosphericOpening> desired = new LinkedHashMap<>();
        for (final DetectedCompartment candidate : detected) {
            for (final long packedOpening : candidate.atmosphericOpenings()) {
                final BlockPos opening = BlockPos.of(packedOpening);
                final Optional<CompartmentState> room = state.compartmentAt(opening.below());
                room.ifPresent(value -> desired.put(packedOpening,
                        new AtmosphericOpening(opening, value.id())));
            }
        }
        state.breaches().removeIf(breach -> breach.atmosphericOpening()
                && !desired.containsKey(breach.position().asLong()));

        final Map<Long, HullBreach> existing = state.breaches().stream()
                .collect(Collectors.toMap(breach -> breach.position().asLong(), breach -> breach,
                        (first, ignored) -> first, LinkedHashMap::new));
        for (final Map.Entry<Long, AtmosphericOpening> entry : desired.entrySet()) {
            final HullBreach old = existing.get(entry.getKey());
            if (old != null) {
                if (old.atmosphericOpening()) {
                    old.setCompartmentId(entry.getValue().compartmentId());
                    old.setInteriorDirection(Direction.DOWN);
                    continue;
                }
                // A previous topology pass (or a save made before open-top detection existed) may
                // have registered this same passable cell as an ordinary destroyed-hull breach.
                // Keeping it would make FloodingHandler use the whole voxel's lowest corner and
                // admit water below the real rim. HullBreach.atmosphericOpening is immutable, so
                // replace the stale record instead of trying to retag it in place.
                state.removeBreach(old.position());
            }
            state.addBreach(new HullBreach(entry.getValue().position(),
                    entry.getValue().compartmentId(), Direction.DOWN, 1.0D, true));
        }
        state.refreshSealFlags();
    }

    private record AtmosphericOpening(BlockPos position, java.util.UUID compartmentId) {
    }

    private static void enqueueExterior(final Level level, final BlockPos pos,
                                        final Queue<BlockPos> queue, final Set<Long> visited,
                                        final Set<Long> virtualMembranes) {
        if (isPassable(level, pos, virtualMembranes) && visited.add(pos.asLong())) {
            queue.add(pos);
        }
    }

    private static void flood(final Level level, final Queue<BlockPos> queue, final Set<Long> visited,
                              final int minX, final int minY, final int minZ,
                              final int maxX, final int maxY, final int maxZ, final int limit) {
        flood(level, queue, visited, minX, minY, minZ, maxX, maxY, maxZ, limit,
                Set.of());
    }

    private static void flood(final Level level, final Queue<BlockPos> queue, final Set<Long> visited,
                              final int minX, final int minY, final int minZ,
                              final int maxX, final int maxY, final int maxZ, final int limit,
                              final Set<Long> virtualMembranes) {
        flood(level, queue, visited, minX, minY, minZ, maxX, maxY, maxZ, limit,
                null, virtualMembranes);
    }

    private static void flood(final Level level, final Queue<BlockPos> queue, final Set<Long> visited,
                              final int minX, final int minY, final int minZ,
                              final int maxX, final int maxY, final int maxZ, final int limit,
                              final Set<Long> component, final Set<Long> virtualMembranes) {
        boolean oversized = false;
        while (!queue.isEmpty()) {
            final BlockPos current = queue.remove();
            if (component != null && !oversized) {
                component.add(current.asLong());
                if (component.size() > limit) {
                    component.clear();
                    // Finish marking this connected space as visited. Returning early lets
                    // the outer scan accept its unvisited tail as a fictitious smaller room.
                    oversized = true;
                }
            }
            for (final Direction direction : Direction.values()) {
                final BlockPos next = current.relative(direction);
                if (next.getX() < minX || next.getX() > maxX
                        || next.getY() < minY || next.getY() > maxY
                        || next.getZ() < minZ || next.getZ() > maxZ) {
                    continue;
                }
                if (isPassable(level, next, virtualMembranes) && visited.add(next.asLong())) {
                    queue.add(next);
                }
            }
        }
    }

    private static boolean isPassable(final Level level, final BlockPos pos,
                                      final Set<Long> virtualMembranes) {
        if (virtualMembranes.contains(pos.asLong())) {
            return false;
        }
        final BlockState state = level.getBlockState(pos);
        return isPassable(level, pos, state);
    }

    /** Evaluates an explicit old/new state supplied by Sable's committed block-change hook. */
    static boolean isPassable(final Level level, final BlockPos pos, final BlockState state) {
        return CompartmentVoxelSemantics.isPassable(level, pos, state);
    }

    /** Shared break-event predicate: true when water/air can traverse this voxel. */
    static boolean isPassable(final Level level, final BlockPos pos) {
        return isPassable(level, pos, Set.of());
    }

    private static DetectedCompartment toDetected(final Set<Long> cells) {
        return toDetected(cells, Set.of());
    }

    private static DetectedCompartment toDetected(final Set<Long> cells,
                                                  final Set<Long> atmosphericOpenings) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        // Real plot water stays in Minecraft/Create's inventory. CompartmentFluidInventory
        // accounts for its mass and displacement without duplicating it as virtual water here.
        for (final long key : cells) {
            final BlockPos pos = BlockPos.of(key);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new DetectedCompartment(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1),
                cells.size(), Set.copyOf(cells),
                Set.copyOf(atmosphericOpenings));
    }

    public record DetectedCompartment(AABB bounds, int volumeBlocks, Set<Long> cells,
                                      Set<Long> atmosphericOpenings) {
    }

    public enum ScanStatus { VALID, EMPTY, TOO_LARGE, NOT_READY }

    /** Completed geometry and the ordinary membranes proven against that same geometry. */
    public record ScanResult(List<DetectedCompartment> compartments, ScanStatus status,
                             Set<Long> ordinaryBreaches) {
        public ScanResult(final List<DetectedCompartment> compartments, final ScanStatus status) {
            this(compartments, status, Set.of());
        }
        public ScanResult {
            compartments = List.copyOf(compartments);
            ordinaryBreaches = Set.copyOf(ordinaryBreaches);
        }
        public boolean complete() { return status == ScanStatus.VALID || status == ScanStatus.EMPTY; }
    }
}
