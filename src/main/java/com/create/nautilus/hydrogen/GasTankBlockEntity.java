package com.create.nautilus.hydrogen;

import com.create.nautilus.config.NautilusConfig;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

/** Create multiblock tank inventory constrained to the Nautilus gas tag. */
public final class GasTankBlockEntity extends FluidTankBlockEntity {
    public GasTankBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                              final BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected SmartFluidTank createInventory() {
        final SmartFluidTank inventory = new SmartFluidTank(
                capacityPerBlock(), this::onFluidStackChanged);
        inventory.setValidator(HydrogenFluids::isGas);
        return inventory;
    }

    /** Create calls this whenever multiblock width/height changes. */
    @Override
    public void applyFluidTankSize(final int blocks) {
        tankInventory.setCapacity(saturatedCapacity(blocks));
        final int excess = tankInventory.getFluidAmount() - tankInventory.getCapacity();
        if (excess > 0) {
            tankInventory.drain(excess, IFluidHandler.FluidAction.EXECUTE);
        }
        forceFluidLevelUpdate = true;
    }

    @Override
    public int getTankSize(final int tank) {
        return capacityPerBlock();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        final double impactSpeed = GasTankImpactTracker.consume(this);
        if (impactSpeed >= NautilusConfig.impactSpeedThreshold()) {
            HydrogenExplosion.detonateTank(this, HydrogenExplosion.Trigger.CARRIER_IMPACT);
        }
    }

    public IGasHandler gasHandler() {
        return GasHandlerView.of(fluidCapability);
    }

    /**
     * The parent implementation queries the ordinary fluid capability, which
     * this tank intentionally does not publish. Use the same Create tooltip
     * formatter with our dedicated gas capability so goggles still show gas
     * type, amount and multiblock capacity.
     */
    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip,
                                      final boolean isPlayerSneaking) {
        final FluidTankBlockEntity controllerEntity = getControllerBE();
        if (!(controllerEntity instanceof GasTankBlockEntity controller)) {
            return false;
        }
        return containedFluidTooltip(tooltip, isPlayerSneaking,
                controller.gasHandler());
    }

    /** Always resolves to the controller so any segment reports the full tank. */
    public FluidStack storedGas() {
        final FluidTankBlockEntity controllerEntity = getControllerBE();
        return controllerEntity == null
                ? FluidStack.EMPTY
                : controllerEntity.getTankInventory().getFluid().copy();
    }

    public int totalCapacity() {
        final FluidTankBlockEntity controllerEntity = getControllerBE();
        return controllerEntity == null ? 0 : controllerEntity.getTankInventory().getCapacity();
    }

    public FluidStack drainAllForRupture() {
        final FluidTankBlockEntity controllerEntity = getControllerBE();
        if (controllerEntity == null) {
            return FluidStack.EMPTY;
        }
        return controllerEntity.getTankInventory().drain(
                Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
    }

    /**
     * Takes a stable snapshot of every segment belonging to this Create tank.
     *
     * <p>Create stores the controller at the lower south-west corner and its
     * multiblock dimensions are {@code width x height x width}. Resolving and
     * validating every candidate before any block is removed prevents an
     * ordinary-world rupture from degenerating into only the clicked segment
     * after Create immediately splits the remaining structure.</p>
     */
    public List<BlockPos> multiblockPositions() {
        if (level == null) {
            return List.of(worldPosition.immutable());
        }
        final FluidTankBlockEntity controllerEntity = getControllerBE();
        if (!(controllerEntity instanceof GasTankBlockEntity controller)) {
            return List.of(worldPosition.immutable());
        }

        final BlockPos origin = controller.getBlockPos();
        final int width = Math.max(1, controller.getWidth());
        final int height = Math.max(1, controller.getHeight());
        final List<BlockPos> positions = new ArrayList<>(width * width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    final BlockPos candidate = origin.offset(x, y, z);
                    if (level.getBlockEntity(candidate) instanceof GasTankBlockEntity segment
                            && origin.equals(segment.getController())) {
                        positions.add(candidate.immutable());
                    }
                }
            }
        }
        return positions.isEmpty() ? List.of(worldPosition.immutable()) : List.copyOf(positions);
    }

    @Override
    protected void read(final CompoundTag tag, final HolderLookup.Provider registries,
                        final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        // FluidTankBlockEntity's deserializer restores Create's native
        // capacity. Reapply our configurable multiplier after all controller
        // and width/height data has been read.
        applyFluidTankSize(Math.max(1, getTotalTankSize()));
    }

    public static int capacityPerBlock() {
        final double configured = FluidTankBlockEntity.getCapacityMultiplier()
                * NautilusConfig.GAS_TANK_CAPACITY_MULTIPLIER;
        return (int) Math.clamp(Math.round(configured), 1L, Integer.MAX_VALUE);
    }

    private static int saturatedCapacity(final int blocks) {
        return (int) Math.clamp((long) capacityPerBlock() * Math.max(1, blocks),
                1L, Integer.MAX_VALUE);
    }
}
