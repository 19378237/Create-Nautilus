package com.create.nautilus.hydrogen;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.Objects;

/**
 * Gas-filtered view over an existing Create/NeoForge tank handler.
 *
 * <p>The wrapped machine inventory still owns all storage and persistence.
 * This view only establishes the transport boundary: insertion and extraction
 * through {@link GasCapabilities#HANDLER} are accepted exclusively for fluids
 * tagged {@code create_nautilus:gas}.</p>
 */
public final class GasHandlerView implements IGasHandler {
    private final IFluidHandler delegate;

    private GasHandlerView(final IFluidHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static IGasHandler of(final IFluidHandler delegate) {
        return delegate instanceof IGasHandler gas ? gas : new GasHandlerView(delegate);
    }

    @Override
    public int getTanks() {
        return delegate.getTanks();
    }

    @Override
    public FluidStack getFluidInTank(final int tank) {
        final FluidStack stack = delegate.getFluidInTank(tank);
        return HydrogenFluids.isGas(stack) ? stack : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(final int tank) {
        return delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(final int tank, final FluidStack stack) {
        return HydrogenFluids.isGas(stack) && delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(final FluidStack resource, final FluidAction action) {
        return HydrogenFluids.isGas(resource) ? delegate.fill(resource, action) : 0;
    }

    @Override
    public FluidStack drain(final FluidStack resource, final FluidAction action) {
        return HydrogenFluids.isGas(resource)
                ? delegate.drain(resource, action) : FluidStack.EMPTY;
    }

    @Override
    public FluidStack drain(final int maxDrain, final FluidAction action) {
        final FluidStack simulated = delegate.drain(maxDrain, FluidAction.SIMULATE);
        if (!HydrogenFluids.isGas(simulated)) {
            return FluidStack.EMPTY;
        }
        return action.simulate() ? simulated : delegate.drain(simulated, action);
    }
}
