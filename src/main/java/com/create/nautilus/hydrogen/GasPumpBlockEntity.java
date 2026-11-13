package com.create.nautilus.hydrogen;

import com.create.nautilus.CreateNautilus;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent gas-network driver; facing is the outlet for either rotation sign. */
public final class GasPumpBlockEntity extends KineticBlockEntity {
    private static final int MAX_VISITED_NODES = 4_096;
    private static final Direction[] DIRECTIONS = Direction.values();
    private double transferRemainder;
    private int sourceCursor;
    private FluidStack pendingTransfer = FluidStack.EMPTY;
    private long lastNetworkWarningTick = Long.MIN_VALUE;

    public GasPumpBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                              final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || getSpeed() == 0.0F) return;
        // Discard unused whole-tick work even when starved or blocked. Only the
        // fractional budget survives, so fractional RPM is linear without bursts.
        int budget = nextTransferBudget(getSpeed());
        if (budget <= 0) return;

        final Direction output = getBlockState().getValue(GasPumpBlock.FACING);
        final Discovery sources = discoverEndpoints(output.getOpposite());
        final Discovery sinks = discoverEndpoints(output);
        if (!sources.complete() || !sinks.complete()) {
            final long now = level.getGameTime();
            if (lastNetworkWarningTick == Long.MIN_VALUE || now - lastNetworkWarningTick >= 200L) {
                lastNetworkWarningTick = now;
                CreateNautilus.LOGGER.warn("Gas pump at {} stopped: network exceeds {} pipe nodes",
                        worldPosition, MAX_VISITED_NODES);
            }
            return;
        }
        if (sinks.endpoints().isEmpty()) return;
        budget -= deliverPending(sinks.endpoints(), budget);
        if (!pendingTransfer.isEmpty() || budget <= 0 || sources.endpoints().isEmpty()) return;
        pumpFromSources(sources.endpoints(), sinks.endpoints(), budget);
    }

    private int nextTransferBudget(final double rpm) {
        transferRemainder += Math.abs(rpm) / 2.0D;
        final int budget = (int) Math.floor(transferRemainder);
        transferRemainder -= budget;
        return budget;
    }

    private void pumpFromSources(final List<Endpoint> candidates, final List<Endpoint> sinks,
                                 int budget) {
        final int start = Math.floorMod(sourceCursor, candidates.size());
        // Advance after every attempt, including a blocked source. A permanently
        // productive first source therefore cannot starve the rest of the network.
        for (int offset = 0; offset < candidates.size() && budget > 0; offset++) {
            final int index = (start + offset) % candidates.size();
            sourceCursor = (index + 1) % candidates.size();
            budget -= transferFrom(candidates.get(index), sinks, budget);
            if (!pendingTransfer.isEmpty()) break;
        }
    }

    /** Amount removed from a source, including any retained, undelivered gas. */
    private int transferFrom(final Endpoint sourceEndpoint,
                             final List<Endpoint> sinks, final int maximum) {
        final IGasHandler source = sourceEndpoint.handler();
        int transferred = 0;
        for (int tank = 0; tank < source.getTanks() && transferred < maximum; tank++) {
            final FluidStack stored = source.getFluidInTank(tank);
            if (!HydrogenFluids.isGas(stored)) continue;
            final FluidStack available = source.drain(stored.copyWithAmount(
                    Math.min(maximum - transferred, stored.getAmount())), IFluidHandler.FluidAction.SIMULATE);
            if (!HydrogenFluids.isGas(available)) continue;
            int remaining = available.getAmount();
            for (final Endpoint sink : sinks) {
                if (remaining <= 0 || transferred >= maximum) break;
                if (sink.inventory().equals(sourceEndpoint.inventory()) || sink.handler() == source) continue;
                final FluidStack offer = available.copyWithAmount(Math.min(remaining, maximum - transferred));
                final int accepted = Math.clamp(sink.handler().fill(offer,
                        IFluidHandler.FluidAction.SIMULATE), 0, offer.getAmount());
                if (accepted <= 0) continue;
                // Take ownership before invoking another capability. A sink may
                // accept less on commit; the remainder remains a persisted pump
                // inventory instead of relying on an output-only source refund.
                pendingTransfer = source.drain(offer.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
                if (pendingTransfer.isEmpty()) break;
                final int extracted = pendingTransfer.getAmount();
                transferred += extracted;
                remaining -= extracted;
                setChanged();
                final int filled = Math.clamp(sink.handler().fill(pendingTransfer.copy(),
                        IFluidHandler.FluidAction.EXECUTE), 0, extracted);
                pendingTransfer.shrink(filled);
                if (!pendingTransfer.isEmpty()) return transferred;
                pendingTransfer = FluidStack.EMPTY;
            }
        }
        return transferred;
    }

    private int deliverPending(final List<Endpoint> sinks, final int maximum) {
        if (pendingTransfer.isEmpty()) return 0;
        int inserted = 0;
        for (final Endpoint sink : sinks) {
            if (inserted >= maximum || pendingTransfer.isEmpty()) break;
            final FluidStack offer = pendingTransfer.copyWithAmount(
                    Math.min(maximum - inserted, pendingTransfer.getAmount()));
            final int filled = Math.clamp(sink.handler().fill(offer,
                    IFluidHandler.FluidAction.EXECUTE), 0, offer.getAmount());
            pendingTransfer.shrink(filled);
            inserted += filled;
        }
        if (pendingTransfer.isEmpty()) pendingTransfer = FluidStack.EMPTY;
        if (inserted > 0) setChanged();
        return inserted;
    }

    private Discovery discoverEndpoints(final Direction side) {
        final BlockPos first = worldPosition.relative(side);
        if (!level.isLoaded(first)) return new Discovery(List.of(), true);
        final IGasHandler direct = level.getCapability(GasCapabilities.HANDLER, first, side.getOpposite());
        if (direct != null) return new Discovery(List.of(endpoint(first, side.getOpposite(), direct)), true);
        if (!(level.getBlockState(first).getBlock() instanceof GasPipeBlock)) {
            return new Discovery(List.of(), true);
        }
        final int maxDistance = Math.max(1, FluidPropagator.getPumpRange());
        final ArrayDeque<Node> queue = new ArrayDeque<>();
        final Set<BlockPos> enqueued = new HashSet<>();
        final Set<InventoryKey> inventories = new HashSet<>();
        final List<Endpoint> endpoints = new ArrayList<>();
        enqueued.add(first.immutable());
        queue.add(new Node(first.immutable(), 1));
        // Deduplicate on first discovery, but keep the budget on loaded, dequeued nodes.
        int visitedNodes = 0;
        while (!queue.isEmpty()) {
            final Node node = queue.removeFirst();
            if (node.distance() > maxDistance || !level.isLoaded(node.pos())) continue;
            if (visitedNodes >= MAX_VISITED_NODES) return new Discovery(List.of(), false);
            visitedNodes++;
            final BlockState pipeState = level.getBlockState(node.pos());
            if (!(pipeState.getBlock() instanceof GasPipeBlock)) continue;
            for (final Direction direction : DIRECTIONS) {
                if (!GasPipeBlock.isOpenAt(pipeState, direction)) continue;
                final BlockPos adjacent = node.pos().relative(direction);
                if (adjacent.equals(worldPosition) || !level.isLoaded(adjacent)) continue;
                final BlockState adjacentState = level.getBlockState(adjacent);
                if (adjacentState.getBlock() instanceof GasPipeBlock) {
                    if (node.distance() < maxDistance
                            && GasPipeBlock.isOpenAt(adjacentState, direction.getOpposite())
                            && enqueued.add(adjacent.immutable())) {
                        queue.addLast(new Node(adjacent.immutable(), node.distance() + 1));
                    }
                    continue;
                }
                if (adjacentState.getBlock() instanceof GasPumpBlock) continue;
                final IGasHandler handler = level.getCapability(GasCapabilities.HANDLER, adjacent, direction.getOpposite());
                if (handler != null) {
                    final Endpoint endpoint = endpoint(adjacent, direction.getOpposite(), handler);
                    if (inventories.add(endpoint.inventory())) endpoints.add(endpoint);
                }
            }
        }
        return new Discovery(endpoints, true);
    }

    private Endpoint endpoint(final BlockPos pos, final Direction side, final IGasHandler handler) {
        // Only Create multiblock tank segments are known to share an inventory.
        // Other machines may expose independent O2/H2/input/output inventories by face.
        final InventoryKey inventory = level.getBlockEntity(pos) instanceof GasTankBlockEntity tank
                ? new InventoryKey(tank.getController().immutable(), null)
                : new InventoryKey(pos.immutable(), side);
        return new Endpoint(inventory, handler);
    }

    @Override
    protected void write(final CompoundTag tag, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putDouble("TransferRemainder", transferRemainder);
        if (!pendingTransfer.isEmpty()) tag.put("PendingGas", pendingTransfer.save(registries));
    }

    @Override
    protected void read(final CompoundTag tag, final HolderLookup.Provider registries, final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        transferRemainder = tag.getDouble("TransferRemainder");
        if (!Double.isFinite(transferRemainder)) transferRemainder = 0.0D;
        transferRemainder -= Math.floor(transferRemainder);
        pendingTransfer = FluidStack.parseOptional(registries, tag.getCompound("PendingGas"));
    }

    private record Node(BlockPos pos, int distance) {}
    private record InventoryKey(BlockPos pos, Direction side) {}
    private record Endpoint(InventoryKey inventory, IGasHandler handler) {}
    private record Discovery(List<Endpoint> endpoints, boolean complete) {}
}
