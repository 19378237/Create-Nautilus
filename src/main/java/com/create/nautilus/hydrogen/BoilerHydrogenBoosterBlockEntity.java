package com.create.nautilus.hydrogen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.api.boiler.BoilerHeater;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Supplies configurable temporary heat levels from any outside face of a Create boiler. */
public final class BoilerHydrogenBoosterBlockEntity extends KineticBlockEntity {
    public static final int MAX_HEAT_LEVEL = 18;
    private static final int CAPACITY = 4_000;

    public static int capacity() {
        return CAPACITY;
    }
    private SmartFluidTankBehaviour hydrogen;
    private double consumptionRemainder;
    /** Synced so the client-side BoilerData used by goggles sees the boost. */
    private boolean active;

    public BoilerHydrogenBoosterBlockEntity(final BlockEntityType<?> type,
                                            final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(GasCapabilities.HANDLER,
                NautilusBlockEntityTypes.BOILER_HYDROGEN_BOOSTER.get(),
                (be, side) -> side != be.getBlockState().getValue(BoilerHydrogenBoosterBlock.FACING)
                        .getOpposite() ? GasHandlerView.of(be.hydrogen.getCapability()) : null);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        hydrogen = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.INPUT,
                this, 1, CAPACITY, false).forbidExtraction();
        hydrogen.getPrimaryHandler().setValidator(HydrogenFluids::isLowPressureHydrogen);
        behaviours.add(hydrogen);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) {
            return;
        }
        final FluidTankBlockEntity boilerTank = adjacentBoiler();
        if (level.isClientSide) {
            return;
        }
        if (Math.abs(getSpeed()) < 1.0F || hydrogen.getPrimaryHandler().isEmpty()
                || boilerTank == null) {
            setActive(false, boilerTank);
            return;
        }
        if (nativeHeat(boilerTank) >= MAX_HEAT_LEVEL) {
            consumptionRemainder = 0.0D;
            setActive(false, boilerTank);
            return;
        }

        consumptionRemainder += NautilusConfig.BOILER_HYDROGEN_CONSUMPTION_RATE / 20.0D;
        final int due = (int) Math.floor(consumptionRemainder);
        if (due > 0) {
            final int drained = hydrogen.getPrimaryHandler().drain(due,
                    IFluidHandler.FluidAction.EXECUTE).getAmount();
            consumptionRemainder -= drained;
            if (drained < due) {
                setActive(false, boilerTank);
                return;
            }
        }
        setActive(true, boilerTank);
    }

    private void setActive(final boolean value, final FluidTankBlockEntity boilerTank) {
        if (active == value) {
            return;
        }
        active = value;
        notifyUpdate();
        if (boilerTank != null) {
            // Ask Create to run its normal heat-source scan again. The single
            // BoilerData hook adds the configured levels after that scan completes.
            boilerTank.updateBoilerTemperature();
        }
    }

    /**
     * Repeats Create's public heater lookup without changing its scan layout.
     * This distinguishes a native level-18 boiler from a level-16 boiler that
     * currently displays 18 because of our +2 boost.
     */
    private int nativeHeat(final FluidTankBlockEntity boilerTank) {
        if (boilerTank.getLevel() == null) {
            return 0;
        }
        int heat = 0;
        final BlockPos origin = boilerTank.getBlockPos();
        for (int x = 0; x < boilerTank.getWidth(); x++) {
            for (int z = 0; z < boilerTank.getWidth(); z++) {
                final BlockPos heaterPos = origin.offset(x, -1, z);
                heat += BoilerHeater.findHeat(boilerTank.getLevel(), heaterPos,
                        boilerTank.getLevel().getBlockState(heaterPos));
            }
        }
        return heat;
    }

    private FluidTankBlockEntity adjacentBoiler() {
        if (level == null) {
            return null;
        }
        final Direction heatingFace = getBlockState().getValue(BoilerHydrogenBoosterBlock.FACING);
        final BlockEntity neighbour = level.getBlockEntity(worldPosition.relative(heatingFace));
        if (neighbour instanceof FluidTankBlockEntity tank && !(tank instanceof GasTankBlockEntity)) {
            final FluidTankBlockEntity controller = tank.getControllerBE();
            if (controller != null && controller.boiler.isActive()) {
                return controller;
            }
        }
        return null;
    }

    /** Counts active, correctly oriented boosters around a boiler controller. */
    public static int activeAdjacentBoosterCount(final FluidTankBlockEntity controller) {
        if (controller.getLevel() == null) {
            return 0;
        }
        final BlockPos origin = controller.getBlockPos();
        final Set<Long> countedBoosters = new HashSet<>();
        int count = 0;
        for (int y = 0; y < controller.getHeight(); y++) {
            for (int x = 0; x < controller.getWidth(); x++) {
                for (int z = 0; z < controller.getWidth(); z++) {
                    final BlockPos tankPos = origin.offset(x, y, z);
                    for (final Direction direction : Direction.values()) {
                        if (controller.getLevel().getBlockEntity(tankPos.relative(direction))
                                instanceof BoilerHydrogenBoosterBlockEntity booster
                                && booster.active
                                && booster.getBlockState().getValue(BoilerHydrogenBoosterBlock.FACING)
                                        == direction.getOpposite()
                                && countedBoosters.add(booster.getBlockPos().asLong())) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip,
                                      final boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return containedFluidTooltip(tooltip, isPlayerSneaking, hydrogen.getCapability());
    }

    @Override
    protected void write(final CompoundTag tag, final HolderLookup.Provider registries,
                         final boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putBoolean("Active", active);
        tag.putDouble("ConsumptionRemainder", consumptionRemainder);
    }

    @Override
    protected void read(final CompoundTag tag, final HolderLookup.Provider registries,
                        final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        active = tag.getBoolean("Active");
        consumptionRemainder = tag.getDouble("ConsumptionRemainder");
    }

    @Override
    public void remove() {
        final FluidTankBlockEntity boilerTank = level == null ? null : adjacentBoiler();
        if (boilerTank != null) {
            active = false;
            boilerTank.updateBoilerTemperature();
        }
        super.remove();
    }
}
