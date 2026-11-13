package com.create.nautilus.content.oxygen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/** The visible aperture supplies the adjacent air cell in the facing direction. */
public final class OxygenInterfaceBlock extends Block
        implements IBE<OxygenInterfaceBlockEntity>, IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public OxygenInterfaceBlock(final Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public BlockState rotate(final BlockState state, final Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(final BlockState state, final Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public Class<OxygenInterfaceBlockEntity> getBlockEntityClass() {
        return OxygenInterfaceBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends OxygenInterfaceBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.OXYGEN_INTERFACE.get();
    }
}
