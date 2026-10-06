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

/** Redstone-open emergency dump valve; front face is the exhaust. */
public final class EmergencyVentBlock extends HorizontalDirectionalBlock
        implements IBE<EmergencyVentBlockEntity> {
    public static final MapCodec<EmergencyVentBlock> CODEC =
            simpleCodec(EmergencyVentBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public EmergencyVentBlock(final Properties properties) {
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
    public Class<EmergencyVentBlockEntity> getBlockEntityClass() {
        return EmergencyVentBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EmergencyVentBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.EMERGENCY_VENT.get();
    }
}
