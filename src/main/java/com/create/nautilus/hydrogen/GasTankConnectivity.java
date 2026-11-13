package com.create.nautilus.hydrogen;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Create 6.0.10's vertical tank formation rules, restricted to gas tanks.
 *
 * <p>The upstream generic connector cannot specialize its private fluid merge:
 * an empty origin never adopts the first gas as its comparison baseline, and
 * a refused fill still empties the source. Keep the same negative-axis search,
 * largest-volume/widest-tie selection and native splitting, but validate the
 * gas in every layer and finish inventory transfers before linking segments.
 * Ordinary Create fluid tanks continue to use the upstream connector.</p>
 */
final class GasTankConnectivity {
    private GasTankConnectivity() {}

    static void formMulti(final GasTankBlockEntity start) {
        final Level level = start.getLevel();
        if (level == null || level.isClientSide) return;

        final List<GasTankBlockEntity> frontier = new ArrayList<>();
        final PriorityQueue<Candidate> candidates = new PriorityQueue<>(
                Comparator.comparingInt(Candidate::blocks).reversed());
        final Set<BlockPos> visited = new HashSet<>();
        frontier.add(start);
        final BlockPos startPos = start.getBlockPos();
        final int minX = startPos.getX() - start.getMaxWidth();
        final int minZ = startPos.getZ() - start.getMaxWidth();
        for (int index = 0; index < frontier.size(); index++) {
            final GasTankBlockEntity part = frontier.get(index);
            final BlockPos pos = part.getBlockPos();
            if (!visited.add(pos)) continue;
            if (part.isController()) {
                final Candidate candidate = bestCandidate(part);
                if (candidate.blocks() > 1) candidates.add(candidate);
            }
            for (final Direction direction : List.of(Direction.WEST, Direction.DOWN, Direction.NORTH)) {
                final BlockPos next = pos.relative(direction);
                if (next.getX() <= minX || next.getZ() <= minZ || visited.contains(next)) continue;
                final GasTankBlockEntity neighbour = partAt(start, next);
                if (neighbour != null) frontier.add(neighbour);
            }
        }

        while (!candidates.isEmpty()) {
            final Candidate candidate = candidates.poll();
            final GasTankBlockEntity controller = candidate.controller();
            // Earlier formations may have consumed this candidate or changed
            // its neighbours. Recompute against the current controllers.
            if (controller.isController()) form(bestCandidate(controller));
        }
    }

    private static Candidate bestCandidate(final GasTankBlockEntity controller) {
        int bestWidth = 1;
        int bestBlocks = -1;
        for (int width = 1; width <= controller.getMaxWidth(); width++) {
            final int blocks = compatibleBlocks(controller, width);
            if (blocks >= bestBlocks) {
                bestWidth = width;
                bestBlocks = blocks;
            }
        }
        return new Candidate(controller, bestWidth, bestBlocks);
    }

    private static int compatibleBlocks(final GasTankBlockEntity originTank, final int width) {
        final BlockPos origin = originTank.getBlockPos();
        FluidStack gas = originTank.getTankInventory().getFluid();
        int blocks = 0;
        Search:
        for (int y = 0; y < originTank.getMaxLength(Direction.Axis.Y, width); y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    final GasTankBlockEntity part = partAt(originTank, origin.offset(x, y, z));
                    if (part == null || !(part.getControllerBE() instanceof GasTankBlockEntity controller)) break Search;
                    final int otherWidth = controller.getWidth();
                    if (otherWidth > width || (otherWidth == width
                            && controller.getHeight() == originTank.getMaxLength(Direction.Axis.Y, width))) break Search;
                    final BlockPos conPos = controller.getBlockPos();
                    if (!conPos.equals(origin) && (conPos.getX() < origin.getX()
                            || conPos.getZ() < origin.getZ()
                            || conPos.getX() + otherWidth > origin.getX() + width
                            || conPos.getZ() + otherWidth > origin.getZ() + width)) break Search;
                    final FluidStack otherGas = controller.getTankInventory().getFluid();
                    if (!otherGas.isEmpty()) {
                        if (gas.isEmpty()) gas = otherGas.copy();
                        else if (!FluidStack.isSameFluidSameComponents(gas, otherGas)) break Search;
                    }
                }
            }
            blocks += width * width;
        }
        return blocks;
    }

    private static void form(final Candidate candidate) {
        final GasTankBlockEntity controller = candidate.controller();
        final int width = candidate.width();
        final int blocks = candidate.blocks();
        if (blocks <= 1 || (controller.getWidth() == width && controller.getTotalTankSize() == blocks)) return;

        final int height = blocks / width / width;
        final List<GasTankBlockEntity> parts = new ArrayList<>(blocks);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    final GasTankBlockEntity part = partAt(controller, controller.getBlockPos().offset(x, y, z));
                    if (part == null) return;
                    parts.add(part);
                }
            }
        }

        // Native splitting also redistributes any old controller that extends
        // above the selected volume. Never attach a segment with residual gas.
        for (final GasTankBlockEntity part : parts) ConnectivityHandler.splitMulti(part);
        controller.applyFluidTankSize(blocks);
        if (!mergeInventories(controller, parts)) {
            // All inventories were restored; leave the native split segments
            // independent instead of hiding a rejected remainder in a member.
            controller.applyFluidTankSize(1);
            for (final GasTankBlockEntity part : parts) part.notifyMultiUpdated();
            return;
        }

        Object extraData = controller.getExtraData();
        for (final GasTankBlockEntity part : parts) {
            if (part != controller) {
                extraData = controller.modifyExtraData(extraData);
                part.setController(controller.getBlockPos());
            }
            part.preventConnectivityUpdate();
            part.setWidth(width);
            part.setHeight(height);
            if (part != controller) part.notifyMultiUpdated();
        }
        controller.setExtraData(extraData);
        controller.notifyMultiUpdated();
    }

    /** Inventory changes are atomic with respect to the subsequent controller links. */
    private static boolean mergeInventories(final GasTankBlockEntity controller,
                                            final List<GasTankBlockEntity> parts) {
        final List<FluidStack> before = parts.stream()
                .map(part -> part.getTankInventory().getFluid().copy()).toList();
        final FluidTank target = controller.getTankInventory();
        for (final GasTankBlockEntity part : parts) {
            if (part == controller) continue;
            final FluidTank source = part.getTankInventory();
            final FluidStack offered = source.getFluid().copy();
            if (offered.isEmpty()) continue;
            final int accepted = target.fill(offered, IFluidHandler.FluidAction.EXECUTE);
            final int drained = source.drain(accepted, IFluidHandler.FluidAction.EXECUTE).getAmount();
            if (accepted != offered.getAmount() || drained != accepted) {
                for (int i = 0; i < parts.size(); i++) parts.get(i).getTankInventory().setFluid(before.get(i));
                return false;
            }
        }
        return true;
    }

    private static GasTankBlockEntity partAt(final GasTankBlockEntity origin, final BlockPos pos) {
        return origin.getLevel().getBlockEntity(pos) instanceof GasTankBlockEntity part
                && part.getType() == origin.getType() && !part.isRemoved() ? part : null;
    }

    private record Candidate(GasTankBlockEntity controller, int width, int blocks) {}
}
