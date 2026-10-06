package com.create.nautilus.content.oxygen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.oxygen.OxygenStorageManager;
import com.create.nautilus.registry.NautilusFluids;
import com.create.nautilus.hydrogen.GasHandlerView;
import com.create.nautilus.hydrogen.IGasHandler;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

/**
 * Gas-network endpoint that transfers received oxygen into its compartment.
 *
 * <p>The standalone gas pipe and gas pump push {@code oxygen_gas} into this
 * block's dedicated gas capability; the Sable actor then consumes the small
 * input buffer at the configured compartment rate.</p>
 */
public final class OxygenInterfaceBlockEntity extends SmartBlockEntity
        implements BlockEntitySubLevelActor {
    private SmartFluidTankBehaviour oxygenInput;

    public OxygenInterfaceBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                                      final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        oxygenInput = SmartFluidTankBehaviour.single(this, 1_000).forbidExtraction();
        oxygenInput.getPrimaryHandler().setValidator(NautilusFluids::isOxygen);
        behaviours.add(oxygenInput);
    }

    public IGasHandler gasHandler() {
        return GasHandlerView.of(oxygenInput.getCapability());
    }

    @Override
    public void sable$tick(final ServerSubLevel carrier) {
        if (carrier.getLevel().getGameTime() % 20L != 0L) {
            return;
        }
        final var compartment = OxygenStorageManager.compartmentAt(carrier, worldPosition);
        if (compartment.isEmpty()) {
            return;
        }
        final int freeCapacity = (int) Math.floor(Math.max(0.0D,
                compartment.get().capacityMb() - compartment.get().oxygenMb()));
        final int transferLimit = Math.min(freeCapacity,
                (int) Math.ceil(NautilusConfig.OXYGEN_INTERFACE_RATE));
        if (transferLimit <= 0) {
            return;
        }
        final var drained = oxygenInput.getPrimaryHandler().drain(
                transferLimit, IFluidHandler.FluidAction.EXECUTE);
        if (!drained.isEmpty()) {
            compartment.get().addOxygen(drained.getAmount());
        }
    }
}
