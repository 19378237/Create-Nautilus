package com.create.nautilus.content.oxygen;

import com.create.nautilus.data.NautilusData;
import com.create.nautilus.physics.CompartmentVoxelSemantics;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Intake queries must respect the carrier before consulting the parent world's sea/air. */
final class IntakeEnvironment {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int AIR_SEARCH_DISTANCE = 16;
    private static final int AIR_SEARCH_NODES = 4_096;

    private IntakeEnvironment() {}

    static boolean externalWaterAt(final Level localLevel, final SubLevel carrier,
                                   final BlockPos machine, final Direction face) {
        final BlockPos adjacent = machine.relative(face);
        if (!localLevel.isLoaded(adjacent)
                || !CompartmentVoxelSemantics.isPassable(localLevel, adjacent,
                localLevel.getBlockState(adjacent))) {
            return false;
        }
        // The parent's sea can overlap a dry or flooded carrier room. Its virtual/real
        // inventory is finite and must never become an infinite exterior water source.
        if (carrier instanceof ServerSubLevel serverCarrier
                && NautilusData.carrier(serverCarrier).compartmentAt(adjacent).isPresent()) {
            return false;
        }
        final Level parent = carrier == null ? localLevel : carrier.getLevel();
        Vec3 sample = Vec3.atCenterOf(machine)
                .add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.51D));
        if (carrier != null) sample = carrier.logicalPose().transformPosition(sample);
        final BlockPos worldCell = BlockPos.containing(sample);
        return parent.isLoaded(worldCell)
                && parent.getFluidState(worldCell).is(FluidTags.WATER);
    }

    /** A finite sealed pocket is not an infinite atmosphere; require an open route to sky. */
    static boolean atmosphericAirAt(final ServerSubLevel carrier, final BlockPos localInlet) {
        final Level localLevel = carrier.getLevel();
        if (!isExteriorLocalCell(carrier, localInlet)) return false;
        final Vec3 inlet = carrier.logicalPose().transformPosition(Vec3.atCenterOf(localInlet));
        final BlockPos start = BlockPos.containing(inlet);
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        final Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty() && visited.size() < AIR_SEARCH_NODES) {
            final BlockPos cell = queue.removeFirst();
            if (!visited.add(cell) || !localLevel.isLoaded(cell)
                    || !localLevel.getFluidState(cell).isEmpty()
                    || !CompartmentVoxelSemantics.isPassable(localLevel, cell,
                    localLevel.getBlockState(cell))) continue;
            // Parent-world air can overlap another cell of this hull or an air room.
            final Vec3 local = carrier.logicalPose().transformPositionInverse(Vec3.atCenterOf(cell));
            if (!isExteriorLocalCell(carrier, BlockPos.containing(local))) continue;
            if (localLevel.canSeeSky(cell)) return true;
            for (final Direction direction : DIRECTIONS) {
                final BlockPos next = cell.relative(direction);
                if (next.distManhattan(start) <= AIR_SEARCH_DISTANCE && !visited.contains(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    private static boolean isExteriorLocalCell(final ServerSubLevel carrier, final BlockPos cell) {
        final Level level = carrier.getLevel();
        return level.isLoaded(cell)
                && level.getFluidState(cell).isEmpty()
                && CompartmentVoxelSemantics.isPassable(level, cell, level.getBlockState(cell))
                && NautilusData.carrier(carrier).compartmentAt(cell).isEmpty();
    }
}
