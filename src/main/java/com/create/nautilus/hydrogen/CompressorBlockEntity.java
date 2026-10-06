package com.create.nautilus.hydrogen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.content.NautilusGoggleTooltips;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
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

/** Converts 1000 mB low-pressure gas into 250 mB compressed gas. */
public final class CompressorBlockEntity extends KineticBlockEntity {
    private static final int MIN_OPERATING_RPM = 32;
    private static final double REFERENCE_RPM = 64.0D;
    private static final double MAX_THROUGHPUT_MB_PER_SECOND = 2_000.0D;
    private static final int INTERNAL_CAPACITY = 8_000;

    public static int internalCapacity() {
        return INTERNAL_CAPACITY;
    }
    private SmartFluidTankBehaviour input;
    private SmartFluidTankBehaviour output;
    private IFluidHandler unsided;
    private double processRemainder;

    public CompressorBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                                 final BlockState state) {
        super(type, pos, state);
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(GasCapabilities.HANDLER,
                NautilusBlockEntityTypes.COMPRESSOR.get(),
                (be, side) -> be.gasHandler(side));
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        input = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.INPUT,
                this, 1, INTERNAL_CAPACITY, false).forbidExtraction();
        input.getPrimaryHandler().setValidator(HydrogenFluids::isLowPressure);
        output = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.OUTPUT,
                this, 1, INTERNAL_CAPACITY, false).forbidInsertion();
        output.getPrimaryHandler().setValidator(HydrogenFluids::isCompressed);
        behaviours.add(input);
        behaviours.add(output);
        unsided = new CombinedTankWrapper(input.getCapability(), output.getCapability());
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide
                || Math.abs(getSpeed()) < MIN_OPERATING_RPM) {
            return;
        }
        final FluidStack source = input.getPrimaryHandler().getFluid();
        final Fluid compressed = source.isEmpty()
                ? null : HydrogenFluids.compressedForm(source.getFluid());
        if (compressed == null) {
            return;
        }
        final FluidStack target = output.getPrimaryHandler().getFluid();
        if (!target.isEmpty() && !target.getFluid().isSame(compressed)) {
            return;
        }
        if (output.getPrimaryHandler().getSpace() <= 0) {
            // Do not bank throughput while blocked. Otherwise reopening a
            // full output after several seconds could discharge the backlog
            // in one tick and violate the 2000 mB/s hard limit.
            return;
        }

        // The configured rate remains the 64 RPM reference point. Processing
        // starts at 32 RPM, scales linearly, and is capped at 2000 mB/s so a
        // high-speed network cannot empty an entire multiblock tank in a tick.
        final double throughputPerSecond = Math.min(
                NautilusConfig.COMPRESSOR_GAS_RATE
                        * Math.abs(getSpeed()) / REFERENCE_RPM,
                MAX_THROUGHPUT_MB_PER_SECOND);
        processRemainder += throughputPerSecond / 20.0D;
        final int processingBudget = (int) Math.floor(processRemainder / 4.0D) * 4;
        // Only carry the sub-batch rounding remainder. Whole batches that
        // cannot run because of missing input or output space expire this
        // tick instead of becoming a burst when the machine is supplied.
        processRemainder -= processingBudget;
        int lowPressureMb = Math.min(processingBudget,
                input.getPrimaryHandler().getFluidAmount());
        lowPressureMb = Math.min(lowPressureMb,
                output.getPrimaryHandler().getSpace() * 4);
        lowPressureMb -= lowPressureMb % 4;
        if (lowPressureMb < 4) {
            return;
        }

        final int compressedMb = lowPressureMb / 4;
        input.getPrimaryHandler().drain(lowPressureMb, IFluidHandler.FluidAction.EXECUTE);
        output.getPrimaryHandler().fill(
                new FluidStack(compressed, compressedMb), IFluidHandler.FluidAction.EXECUTE);
    }

    public IFluidHandler fluidHandler(final @Nullable Direction side) {
        if (side == null) {
            return unsided;
        }
        final Direction front = getBlockState().getValue(CompressorBlock.HORIZONTAL_FACING);
        if (side == front) {
            return output.getCapability();
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

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip,
                                      final boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return NautilusGoggleTooltips.addTanks(tooltip, unsided,
                "create_nautilus.goggles.low_pressure_input",
                "create_nautilus.goggles.compressed_output");
    }
}
