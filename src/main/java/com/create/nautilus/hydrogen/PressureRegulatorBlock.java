package com.create.nautilus.hydrogen;

import com.mojang.serialization.MapCodec;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/** Passive 1:4 expansion valve; a redstone signal closes it. */
public final class PressureRegulatorBlock extends HorizontalDirectionalBlock
        implements IBE<PressureRegulatorBlockEntity> {
    public static final MapCodec<PressureRegulatorBlock> CODEC =
            simpleCodec(PressureRegulatorBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public PressureRegulatorBlock(final Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, net.minecraft.core.Direction.NORTH)
                .setValue(POWERED, false));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    public void neighborChanged(final BlockState state, final Level level,
                                final BlockPos pos, final Block neighbour,
                                final BlockPos neighbourPos, final boolean movedByPiston) {
        final boolean powered = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) != powered) {
            level.setBlock(pos, state.setValue(POWERED, powered), 2);
        }
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<PressureRegulatorBlockEntity> getBlockEntityClass() {
        return PressureRegulatorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PressureRegulatorBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.PRESSURE_REGULATOR.get();
    }
}
