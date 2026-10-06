package com.create.nautilus.hydrogen;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Lightweight identity node for a gas pipe.
 *
 * <p>There is deliberately no Create fluid transport behaviour here. Network
 * traversal and transfer are owned by the adjacent {@link GasPumpBlockEntity}.</p>
 */
public final class GasPipeBlockEntity extends SmartBlockEntity {
    public GasPipeBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                              final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        // No FluidTransportBehaviour: this is an independent gas node.
    }
}
