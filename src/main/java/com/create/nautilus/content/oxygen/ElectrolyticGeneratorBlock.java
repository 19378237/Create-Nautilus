package com.create.nautilus.content.oxygen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Shaft-driven water electrolyzer. The front and back are reserved for O2/H2. */
public final class ElectrolyticGeneratorBlock extends HorizontalKineticBlock
        implements IBE<ElectrolyticGeneratorBlockEntity> {
    public ElectrolyticGeneratorBlock(final Properties properties) {
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
    public Class<ElectrolyticGeneratorBlockEntity> getBlockEntityClass() {
        return ElectrolyticGeneratorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ElectrolyticGeneratorBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.ELECTROLYTIC_GENERATOR.get();
    }
}
