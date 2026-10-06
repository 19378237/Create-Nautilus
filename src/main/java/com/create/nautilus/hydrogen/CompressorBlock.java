package com.create.nautilus.hydrogen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Shaft-driven 4:1 volumetric gas compressor. */
public final class CompressorBlock extends HorizontalKineticBlock
        implements IBE<CompressorBlockEntity> {
    public CompressorBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState state) {
        return Direction.Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(final LevelReader level, final BlockPos pos,
                                   final BlockState state, final Direction face) {
        return face == Direction.DOWN;
    }

    @Override
    public Class<CompressorBlockEntity> getBlockEntityClass() {
        return CompressorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CompressorBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.COMPRESSOR.get();
    }
}
