package com.create.nautilus.hydrogen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Mechanically driven hydrogen burner mounted next to a Create boiler tank. */
public final class BoilerHydrogenBoosterBlock extends DirectionalKineticBlock
        implements IBE<BoilerHydrogenBoosterBlockEntity> {
    public BoilerHydrogenBoosterBlock(final Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        final BlockState fallback = super.getStateForPlacement(context);
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) return fallback;
        final Direction attachedFace = context.getClickedFace().getOpposite();
        if (isBoilerTank(context, attachedFace)) {
            return fallback.setValue(FACING, attachedFace);
        }
        Direction uniqueBoiler = null;
        for (final Direction direction : Direction.values()) {
            if (!isBoilerTank(context, direction)) continue;
            if (uniqueBoiler != null) return fallback;
            uniqueBoiler = direction;
        }
        // Unlike generic kinetic placement, the heating face points at the
        // boiler; the existing back-shaft contract remains FACING.opposite().
        return uniqueBoiler == null ? fallback : fallback.setValue(FACING, uniqueBoiler);
    }

    private static boolean isBoilerTank(final BlockPlaceContext context,
                                        final Direction direction) {
        final var neighbour = context.getLevel().getBlockEntity(context.getClickedPos().relative(direction));
        return neighbour instanceof FluidTankBlockEntity && !(neighbour instanceof GasTankBlockEntity);
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
    public Class<BoilerHydrogenBoosterBlockEntity> getBlockEntityClass() {
        return BoilerHydrogenBoosterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends BoilerHydrogenBoosterBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.BOILER_HYDROGEN_BOOSTER.get();
    }
}
