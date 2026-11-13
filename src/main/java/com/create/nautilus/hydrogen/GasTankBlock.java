package com.create.nautilus.hydrogen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Gas-only variant of Create's fluid tank.
 *
 * <p>Inheritance intentionally preserves Create's connectivity formation,
 * window toggling, bucket/container interaction, comparator signal and goggle
 * display. Only the block-entity type and accepted fluid are specialized.</p>
 */
public final class GasTankBlock extends FluidTankBlock
        implements BlockWithSubLevelCollisionCallback {
    public GasTankBlock(final Properties properties) {
        super(properties, false);
    }

    @Override
    public BlockEntityType<? extends FluidTankBlockEntity> getBlockEntityType() {
        return NautilusBlockEntityTypes.GAS_TANK.get();
    }

    @Override
    public void onRemove(final BlockState state, final Level level, final BlockPos pos,
                         final BlockState newState, final boolean moving) {
        // LevelChunk has committed newState before invoking oldState.onRemove.
        // Canceled Break/LivingDestroyBlock events never reach this point.
        // Claim the old inventory before Create removes/splits its block entity.
        // Do not destroy sibling segments until super has removed this old BE:
        // Create's split otherwise writes its cached tank state over newState.
        HydrogenExplosion.Rupture rupture = null;
        if (!level.isClientSide && !moving && state.getBlock() != newState.getBlock()
                && level.getBlockState(pos).getBlock() != this
                && level.getBlockEntity(pos) instanceof GasTankBlockEntity tank) {
            rupture = HydrogenExplosion.captureRupture(tank,
                    HydrogenExplosion.Trigger.DIRECT_DESTRUCTION);
        }
        try {
            super.onRemove(state, level, pos, newState, moving);
        } finally {
            HydrogenExplosion.finishRupture(rupture);
        }
    }

    /**
     * Sable invokes this only for a real physical block collision. The callback
     * records the tank-local position and measured relative motion; detonation
     * is deferred to the block entity's server tick because physics callbacks
     * may execute away from Minecraft's main thread.
     */
    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return GasTankImpactTracker.CALLBACK;
    }
}
