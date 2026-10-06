package com.create.nautilus.hydrogen;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mechanical driver for the standalone gas network.
 *
 * <p>The facing side is the outlet and the opposite side is the inlet, just
 * like Create's mechanical pump. Throughput also follows Create's pressure
 * scale ({@code |RPM| / 2 mB per tick}), but graph discovery and transfer use
 * only {@link GasCapabilities#HANDLER}. No Create FluidNetwork is created.</p>
 */
public final class GasPumpBlockEntity extends KineticBlockEntity {
    private static final int MAX_VISITED_NODES = 4_096;

    public GasPumpBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                              final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || getSpeed() == 0.0F) {
            return;
        }

        final Direction output = getBlockState().getValue(GasPumpBlock.FACING);
        final List<Endpoint> sources = discoverEndpoints(output.getOpposite());
        final List<Endpoint> sinks = discoverEndpoints(output);
        if (sources.isEmpty() || sinks.isEmpty()) {
            return;
        }

        int budget = Math.max(1, (int) (Math.abs(getSpeed()) / 2.0F));
        for (final Endpoint source : sources) {
            if (budget <= 0) {
                break;
            }
            budget -= transferFrom(source, sinks, budget);
        }
    }

    private static int transferFrom(final Endpoint sourceEndpoint,
                                    final List<Endpoint> sinks, final int maximum) {
        final IGasHandler source = sourceEndpoint.handler();
        FluidStack candidate = FluidStack.EMPTY;
        for (int tank = 0; tank < source.getTanks(); tank++) {
            final FluidStack stored = source.getFluidInTank(tank);
            if (HydrogenFluids.isGas(stored)) {
                candidate = stored.copyWithAmount(Math.min(maximum, stored.getAmount()));
                break;
            }
        }
        if (candidate.isEmpty()) {
            candidate = source.drain(maximum, IFluidHandler.FluidAction.SIMULATE);
        }
        if (!HydrogenFluids.isGas(candidate)) {
            return 0;
        }

        final FluidStack available = source.drain(candidate,
                IFluidHandler.FluidAction.SIMULATE);
        if (available.isEmpty()) {
            return 0;
        }

        int inserted = 0;
        for (final Endpoint sink : sinks) {
            if (inserted >= available.getAmount()) {
                break;
            }
            if (sink.pos().equals(sourceEndpoint.pos()) || sink.handler() == source) {
                continue;
            }
            // Several endpoints can expose the same multiblock inventory.
            // Commit each destination before simulating the next one, so its
            // shared free space cannot be counted twice and over-extracted
            // from an output-only source that cannot accept a refund.
            final FluidStack offer = available.copyWithAmount(available.getAmount() - inserted);
            final int accepted = sink.handler().fill(offer, IFluidHandler.FluidAction.SIMULATE);
            if (accepted <= 0) {
                continue;
            }
            final FluidStack extracted = source.drain(offer.copyWithAmount(accepted),
                    IFluidHandler.FluidAction.EXECUTE);
            if (extracted.isEmpty()) {
                break;
            }
            final int filled = sink.handler().fill(extracted,
                    IFluidHandler.FluidAction.EXECUTE);
            inserted += filled;
            extracted.shrink(filled);
            // Our main-thread handlers agree with their simulation. Keep a
            // best-effort refund for external handlers that do not, and stop
            // this source instead of extracting again after a failed commit.
            if (!extracted.isEmpty()) {
                source.fill(extracted, IFluidHandler.FluidAction.EXECUTE);
                break;
            }
        }
        return inserted;
    }

    private List<Endpoint> discoverEndpoints(final Direction side) {
        final BlockPos first = worldPosition.relative(side);
        if (!level.isLoaded(first)) {
            return List.of();
        }

        final IGasHandler direct = level.getCapability(GasCapabilities.HANDLER,
                first, side.getOpposite());
        if (direct != null) {
            return List.of(endpoint(first, direct));
        }
        if (!(level.getBlockState(first).getBlock() instanceof GasPipeBlock)) {
            return List.of();
        }

        final int maxDistance = Math.max(1, FluidPropagator.getPumpRange());
        final ArrayDeque<Node> queue = new ArrayDeque<>();
        final Set<BlockPos> visited = new HashSet<>();
        final Set<BlockPos> endpointPositions = new HashSet<>();
        final List<Endpoint> endpoints = new ArrayList<>();
        queue.add(new Node(first.immutable(), 1));

        while (!queue.isEmpty() && visited.size() < MAX_VISITED_NODES) {
            final Node node = queue.removeFirst();
            if (node.distance() > maxDistance || !visited.add(node.pos())
                    || !level.isLoaded(node.pos())) {
                continue;
            }
            final BlockState pipeState = level.getBlockState(node.pos());
            if (!(pipeState.getBlock() instanceof GasPipeBlock)) {
                continue;
            }

            for (final Direction direction : Direction.values()) {
                if (!GasPipeBlock.isOpenAt(pipeState, direction)) {
                    continue;
                }
                final BlockPos adjacent = node.pos().relative(direction);
                if (adjacent.equals(worldPosition) || !level.isLoaded(adjacent)) {
                    continue;
                }
                final BlockState adjacentState = level.getBlockState(adjacent);
                if (adjacentState.getBlock() instanceof GasPipeBlock) {
                    if (node.distance() < maxDistance
                            && GasPipeBlock.isOpenAt(adjacentState,
                            direction.getOpposite())) {
                        queue.addLast(new Node(adjacent.immutable(), node.distance() + 1));
                    }
                    continue;
                }
                // Pumps separate pressure domains and are never endpoints.
                if (adjacentState.getBlock() instanceof GasPumpBlock) {
                    continue;
                }
                final IGasHandler handler = level.getCapability(
                        GasCapabilities.HANDLER, adjacent, direction.getOpposite());
                if (handler != null) {
                    final Endpoint endpoint = endpoint(adjacent, handler);
                    if (endpointPositions.add(endpoint.pos())) {
                        endpoints.add(endpoint);
                    }
                }
            }
        }
        return endpoints;
    }

    private Endpoint endpoint(final BlockPos pos, final IGasHandler handler) {
        // Every segment of a gas tank exposes its controller's inventory.
        // Use that inventory position both for discovery deduplication and
        // to exclude loops from the pump outlet back into its own source.
        final BlockPos inventoryPos = level.getBlockEntity(pos) instanceof GasTankBlockEntity tank
                ? tank.getController() : pos;
        return new Endpoint(inventoryPos.immutable(), handler);
    }

    private record Node(BlockPos pos, int distance) {
    }

    private record Endpoint(BlockPos pos, IGasHandler handler) {
    }
}
