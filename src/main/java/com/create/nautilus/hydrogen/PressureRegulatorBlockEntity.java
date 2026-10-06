package com.create.nautilus.hydrogen;

import com.create.nautilus.content.NautilusGoggleTooltips;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Expands 100 mB compressed gas into exactly 400 mB normal gas. */
public final class PressureRegulatorBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation {
    private static final int CAPACITY = 8_000;

    public static int capacity() {
        return CAPACITY;
    }
    private SmartFluidTankBehaviour input;
    private SmartFluidTankBehaviour output;
    private IFluidHandler unsided;
    private IFluidHandler outputOnly;

    public PressureRegulatorBlockEntity(final BlockEntityType<?> type,
                                        final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(GasCapabilities.HANDLER,
                NautilusBlockEntityTypes.PRESSURE_REGULATOR.get(),
                (be, side) -> be.gasHandler(side));
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        input = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.INPUT,
                this, 1, CAPACITY, false).forbidExtraction();
        input.getPrimaryHandler().setValidator(HydrogenFluids::isCompressed);
        output = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.OUTPUT,
                this, 1, CAPACITY, false).forbidInsertion();
        output.getPrimaryHandler().setValidator(HydrogenFluids::isLowPressure);
        behaviours.add(input);
        behaviours.add(output);
        unsided = new UnsidedHandler();
        outputOnly = new OutputHandler();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || level.hasNeighborSignal(worldPosition)) {
            return;
        }
        final FluidStack source = input.getPrimaryHandler().getFluid();
        final Fluid expanded = source.isEmpty()
                ? null : HydrogenFluids.expandedForm(source.getFluid());
        if (expanded == null) {
            return;
        }
        final FluidStack target = output.getPrimaryHandler().getFluid();
        if (!target.isEmpty() && !target.getFluid().isSame(expanded)) {
            return;
        }

        // Work in 100 mB batches so the documented ratio is exact and cannot
        // create or lose gas through integer rounding.
        final int batches = Math.min(input.getPrimaryHandler().getFluidAmount() / 100,
                output.getPrimaryHandler().getSpace() / 400);
        if (batches <= 0) {
            return;
        }
        final int boundedBatches = Math.min(batches, 1); // 100 mB/tick valve throughput
        input.getPrimaryHandler().drain(boundedBatches * 100,
                IFluidHandler.FluidAction.EXECUTE);
        output.getPrimaryHandler().fill(new FluidStack(expanded, boundedBatches * 400),
                IFluidHandler.FluidAction.EXECUTE);
    }

    public IFluidHandler fluidHandler(final @Nullable Direction side) {
        if (side == null) {
            return unsided;
        }
        final Direction front = getBlockState().getValue(PressureRegulatorBlock.FACING);
        if (side == front) {
            // This stable facade re-checks redstone on every operation. A
            // caller may cache a capability obtained while the valve was open.
            return outputOnly;
        }
        if (side == front.getOpposite()) {
            return input.getCapability();
        }
        return null;
    }

    public IGasHandler gasHandler(final @Nullable Direction side) {
        final IFluidHandler handler = fluidHandler(side);
        return handler == null ? null : GasHandlerView.of(handler);
    }

    private boolean isClosed() {
        return level != null
                ? level.hasNeighborSignal(worldPosition)
                : getBlockState().getValue(PressureRegulatorBlock.POWERED);
    }

    /** Unsided automation inserts into the input and extracts from the output. */
    private final class UnsidedHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return 2;
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            return tank == 0
                    ? input.getCapability().getFluidInTank(0)
                    : tank == 1
                    ? output.getCapability().getFluidInTank(0)
                    : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(final int tank) {
            return tank == 0
                    ? input.getCapability().getTankCapacity(0)
                    : tank == 1
                    ? output.getCapability().getTankCapacity(0)
                    : 0;
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return tank == 0 && input.getCapability().isFluidValid(0, stack);
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            // Redstone closes only the regulated outlet; the input buffer may
            // continue accepting compressed gas while closed.
            return input.getCapability().fill(resource, action);
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            return isClosed()
                    ? FluidStack.EMPTY : output.getCapability().drain(resource, action);
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return isClosed()
                    ? FluidStack.EMPTY : output.getCapability().drain(maxDrain, action);
        }
    }

    /** Front-face facade that cannot bypass a later redstone closure. */
    private final class OutputHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return output.getCapability().getTanks();
        }

        @Override
        public FluidStack getFluidInTank(final int tank) {
            return output.getCapability().getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(final int tank) {
            return output.getCapability().getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(final int tank, final FluidStack stack) {
            return false;
        }

        @Override
        public int fill(final FluidStack resource, final FluidAction action) {
            return 0;
        }

        @Override
        public FluidStack drain(final FluidStack resource, final FluidAction action) {
            return isClosed()
                    ? FluidStack.EMPTY : output.getCapability().drain(resource, action);
        }

        @Override
        public FluidStack drain(final int maxDrain, final FluidAction action) {
            return isClosed()
                    ? FluidStack.EMPTY : output.getCapability().drain(maxDrain, action);
        }
    }

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip,
                                      final boolean isPlayerSneaking) {
        return NautilusGoggleTooltips.addTanks(tooltip, unsided,
                "create_nautilus.goggles.compressed_input",
                "create_nautilus.goggles.low_pressure_output");
    }
}
