package com.create.nautilus.hydrogen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.block.ProperWaterloggedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Independent kinetic pump for {@link GasCapabilities#HANDLER}. */
public final class GasPumpBlock extends DirectionalKineticBlock
        implements SimpleWaterloggedBlock, ICogWheel, IBE<GasPumpBlockEntity> {
    public GasPumpBlock(final Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false));
    }

    @Override
    public Direction.Axis getRotationAxis(final BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /**
     * The exposed centre cog is the kinetic input.  The pump deliberately has
     * no hidden bottom/side shaft socket; Create connects it through the
     * ordinary small-cog rule instead.
     */
    @Override
    public boolean hasShaftTowards(final net.minecraft.world.level.LevelReader level,
                                   final BlockPos pos, final BlockState state,
                                   final Direction face) {
        return false;
    }

    @Override
    public boolean isSmallCog() {
        return true;
    }

    @Override
    public BlockState getRotatedBlockState(final BlockState state,
                                           final Direction targetedFace) {
        return state.setValue(FACING, state.getValue(FACING).getOpposite());
    }

    @Override
    public VoxelShape getShape(final BlockState state, final BlockGetter level,
                               final BlockPos pos, final CollisionContext context) {
        return AllShapes.PUMP.get(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        BlockState placed = super.getStateForPlacement(context);
        placed = ProperWaterloggedBlock.withWater(context.getLevel(), placed,
                context.getClickedPos());
        return placed;
    }

    @Override
    protected void createBlockStateDefinition(
            final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.WATERLOGGED);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public BlockState updateShape(final BlockState state, final Direction direction,
                                  final BlockState neighbourState,
                                  final LevelAccessor level, final BlockPos pos,
                                  final BlockPos neighbourPos) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return state;
    }

    @Override
    public FluidState getFluidState(final BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED)
                ? Fluids.WATER.getSource(false) : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public Class<GasPumpBlockEntity> getBlockEntityClass() {
        return GasPumpBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GasPumpBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.GAS_PUMP.get();
    }
}
