package com.create.nautilus.physics;

import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Shared voxel boundary rules for compartment topology and visual water clipping. */
public final class CompartmentVoxelSemantics {
    private CompartmentVoxelSemantics() {
    }

    /**
     * Returns whether this voxel is an airtight physical membrane.
     *
     * <p>This deliberately mirrors Sable's non-empty collision-shape classification instead of
     * {@link BlockState#canOcclude()}. Transparent glass, panes and connected Create hull pieces
     * are real membranes even though they do not visually occlude their complete cube.</p>
     */
    public static boolean isSolidMembrane(final Level level, final BlockPos position,
                                          final BlockState state) {
        if (state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock) {
            return !state.getValue(BlockStateProperties.OPEN);
        }
        return !state.isAir() && VoxelNeighborhoodState.isSolid(level, position, state);
    }

    /** Returns whether air or water can traverse this voxel. */
    public static boolean isPassable(final Level level, final BlockPos position,
                                     final BlockState state) {
        return !isSolidMembrane(level, position, state);
    }
}
