package com.create.nautilus.hydrogen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Shaft-driven hydrogen injector installed directly beside a propeller. */
public final class HydrogenBoosterBlock extends DirectionalKineticBlock
        implements IBE<HydrogenBoosterBlockEntity> {
    public HydrogenBoosterBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(final LevelReader level, final BlockPos pos,
                                   final BlockState state, final Direction face) {
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    public Class<HydrogenBoosterBlockEntity> getBlockEntityClass() {
        return HydrogenBoosterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends HydrogenBoosterBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.HYDROGEN_BOOSTER.get();
    }
}
