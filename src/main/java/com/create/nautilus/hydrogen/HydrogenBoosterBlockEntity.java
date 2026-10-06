package com.create.nautilus.hydrogen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.kinetics.PropellerEfficiency;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

/** Consumes low-pressure hydrogen while an adjacent propeller is rotating. */
public final class HydrogenBoosterBlockEntity extends KineticBlockEntity {
    private static final int CAPACITY = 4_000;

    public static int capacity() {
        return CAPACITY;
    }
    private SmartFluidTankBehaviour hydrogen;
    private double consumptionRemainder;
    private boolean active;

    public HydrogenBoosterBlockEntity(final BlockEntityType<?> type,
                                      final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(GasCapabilities.HANDLER,
                NautilusBlockEntityTypes.HYDROGEN_BOOSTER.get(),
                (be, side) -> side != be.getBlockState().getValue(HydrogenBoosterBlock.FACING)
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
        if (level == null || level.isClientSide) {
            return;
        }
        final KineticBlockEntity propeller = findAdjacentPropeller();
        if (propeller == null || hydrogen.getPrimaryHandler().isEmpty()
                || Math.abs(getTheoreticalSpeed()) < 1.0F) {
            setActive(false, propeller);
            return;
        }

        if (Math.abs(getSpeed()) < 1.0F) {
            setActive(false, propeller);
            return;
        }

        consumptionRemainder += NautilusConfig.HYDROGEN_BOOSTER_CONSUMPTION_RATE / 20.0D;
        final int due = (int) Math.floor(consumptionRemainder);
        if (due > 0) {
            final int drained = hydrogen.getPrimaryHandler().drain(due,
                    IFluidHandler.FluidAction.EXECUTE).getAmount();
            consumptionRemainder -= drained;
            if (drained < due) {
                setActive(false, propeller);
                return;
            }
        }
        // Publish the result from the main block-entity tick. Propeller thrust
        // callbacks only read this lock-free snapshot and never traverse the
        // live block-entity graph, avoiding Sable/main-thread lock inversion.
        setActive(true, propeller);
    }

    public boolean isActive() {
        return active;
    }

    /**
     * The propeller goggle is rendered entirely on the client.  Synchronising
     * this flag lets the existing Aeronautics getScaledThrust() path observe
     * the same booster state used by server-side physics; no tooltip injection
     * or duplicated display formula is required.
     */
    @Override
    protected void write(final CompoundTag tag,
                         final HolderLookup.Provider registries,
                         final boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putBoolean("Active", active);
        tag.putDouble("ConsumptionRemainder", consumptionRemainder);
    }

    @Override
    protected void read(final CompoundTag tag,
                        final HolderLookup.Provider registries,
                        final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        active = tag.getBoolean("Active");
        consumptionRemainder = tag.getDouble("ConsumptionRemainder");
    }

    /** Called by propeller hooks; adjacency deliberately has no wireless range. */
    public static boolean hasActiveBooster(final KineticBlockEntity propeller) {
        // The server uses the tick-scoped registry so physics callbacks never
        // traverse a live sublevel block-entity graph. Client goggle rendering
        // is main-thread-only, so it can safely inspect the synced adjacent
        // booster and display the already-boosted thrust value immediately.
        if (propeller.getLevel() != null && propeller.getLevel().isClientSide) {
            for (final Direction direction : Direction.values()) {
                if (propeller.getLevel().getBlockEntity(
                        propeller.getBlockPos().relative(direction))
                        instanceof HydrogenBoosterBlockEntity booster
                        && booster.isActive()) {
                    return true;
                }
            }
            return false;
        }
        return PropellerBoostRegistry.isActive(propeller);
    }

    private KineticBlockEntity findAdjacentPropeller() {
        for (final Direction direction : Direction.values()) {
            final BlockEntity neighbour = level.getBlockEntity(worldPosition.relative(direction));
            if (neighbour instanceof KineticBlockEntity kinetic
                    && PropellerEfficiency.isAeronauticsPropeller(kinetic)) {
                return kinetic;
            }
        }
        return null;
    }

    private void setActive(final boolean next,
                           final KineticBlockEntity propeller) {
        if (next && propeller != null) {
            PropellerBoostRegistry.markActive(propeller, this);
        } else if (!next) {
            PropellerBoostRegistry.markInactive(this);
        }
        if (active == next) {
            return;
        }
        active = next;
        notifyUpdate();
    }

    @Override
    public void remove() {
        PropellerBoostRegistry.markInactive(this);
        super.remove();
    }

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip,
                                      final boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return containedFluidTooltip(tooltip, isPlayerSneaking, hydrogen.getCapability());
    }
}
