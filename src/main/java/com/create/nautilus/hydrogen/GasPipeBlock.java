package com.create.nautilus.hydrogen;

import com.mojang.serialization.MapCodec;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.TickPriority;
import org.jetbrains.annotations.Nullable;

/**
 * A standalone six-way gas conduit.
 *
 * <p>This class intentionally extends Minecraft's geometric {@link PipeBlock},
 * not Create's {@code FluidPipeBlock}. It has no
 * {@code FluidTransportBehaviour}, never joins a Create {@code FluidNetwork},
 * and exposes no ordinary fluid capability. The familiar automatic connection
 * rules are implemented locally for {@link GasCapabilities#HANDLER}.</p>
 */
public final class GasPipeBlock extends PipeBlock
        implements SimpleWaterloggedBlock, IBE<GasPipeBlockEntity> {
    public static final MapCodec<GasPipeBlock> CODEC = simpleCodec(GasPipeBlock::new);

    public GasPipeBlock(final Properties properties) {
        // PipeBlock's apothem is the half-width of the centre/arm collision
        // boxes.  The visible pipe body is 5 px wide and its end collar is
        // about 6 px wide, so 3/16 keeps the collision inside the rendered
        // collar instead of producing the old 10 px invisible square post.
        super(3.0F / 16.0F, properties);
        registerDefaultState(defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(
            final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN,
                BlockStateProperties.WATERLOGGED);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        final FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return updateConnections(defaultBlockState(), context.getNearestLookingDirection(),
                context.getLevel(), context.getClickedPos())
                .setValue(BlockStateProperties.WATERLOGGED,
                        fluid.getType() == Fluids.WATER);
    }

    @Override
    public BlockState updateShape(final BlockState state, final Direction direction,
                                  final BlockState neighbourState,
                                  final LevelAccessor level, final BlockPos pos,
                                  final BlockPos neighbourPos) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (level instanceof Level realLevel) {
            realLevel.scheduleTick(pos, this, 1, TickPriority.HIGH);
            return updateConnections(state, direction, realLevel, pos);
        }
        return state;
    }

    @Override
    protected void tick(final BlockState state, final net.minecraft.server.level.ServerLevel level,
                        final BlockPos pos, final RandomSource random) {
        final BlockState updated = updateConnections(state, Direction.NORTH, level, pos);
        if (updated != state) {
            level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
        }
    }

    private BlockState updateConnections(BlockState state,
                                         final Direction preferredDirection,
                                         final Level level, final BlockPos pos) {
        int connections = 0;
        Direction soleConnection = null;
        for (final Direction direction : Direction.values()) {
            final boolean connected = canConnectTo(level, pos.relative(direction), direction);
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), connected);
            if (connected) {
                connections++;
                soleConnection = direction;
            }
        }

        // The second arm is visual only, matching Create's placement grammar:
        // an isolated pipe is straight, and an end connected to one machine
        // still has a usable exposed end on the opposite side.
        if (connections == 0) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(preferredDirection), true)
                    .setValue(PROPERTY_BY_DIRECTION.get(preferredDirection.getOpposite()), true);
        } else if (connections == 1 && soleConnection != null) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(soleConnection.getOpposite()), true);
        }
        return state;
    }

    public static boolean canConnectTo(final BlockAndTintGetter level,
                                       final BlockPos neighbourPos,
                                       final Direction directionFromPipe) {
        final BlockState neighbour = level.getBlockState(neighbourPos);
        if (neighbour.getBlock() instanceof GasPipeBlock) {
            // Adjacent pipes form their topology together. Requiring an
            // existing opposite arm here would prevent a newly placed branch
            // from opening the side of a straight pipe. Rendering and gas
            // traversal check both resulting arms after this update.
            return true;
        }
        if (neighbour.getBlock() instanceof GasPumpBlock
                && neighbour.getValue(GasPumpBlock.FACING).getAxis()
                == directionFromPipe.getAxis()) {
            return true;
        }
        if (!(level instanceof Level realLevel) || !realLevel.isLoaded(neighbourPos)) {
            return false;
        }
        return realLevel.getCapability(GasCapabilities.HANDLER, neighbourPos,
                directionFromPipe.getOpposite()) != null;
    }

    public static boolean isOpenAt(final BlockState state, final Direction direction) {
        return state.getBlock() instanceof GasPipeBlock
                && state.getValue(PROPERTY_BY_DIRECTION.get(direction));
    }

    @Override
    public FluidState getFluidState(final BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED)
                ? Fluids.WATER.getSource(false) : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public BlockState rotate(final BlockState state, final Rotation rotation) {
        BlockState result = state;
        for (final Direction direction : Direction.values()) {
            result = result.setValue(PROPERTY_BY_DIRECTION.get(rotation.rotate(direction)),
                    state.getValue(PROPERTY_BY_DIRECTION.get(direction)));
        }
        return result;
    }

    @Override
    public BlockState mirror(final BlockState state, final Mirror mirror) {
        BlockState result = state;
        for (final Direction direction : Direction.values()) {
            final Direction mirrored = switch (mirror) {
                case LEFT_RIGHT -> direction == Direction.NORTH ? Direction.SOUTH
                        : direction == Direction.SOUTH ? Direction.NORTH : direction;
                case FRONT_BACK -> direction == Direction.EAST ? Direction.WEST
                        : direction == Direction.WEST ? Direction.EAST : direction;
                case NONE -> direction;
            };
            result = result.setValue(PROPERTY_BY_DIRECTION.get(mirrored),
                    state.getValue(PROPERTY_BY_DIRECTION.get(direction)));
        }
        return result;
    }

    @Override
    public Class<GasPipeBlockEntity> getBlockEntityClass() {
        return GasPipeBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GasPipeBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.GAS_PIPE.get();
    }

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }
}
