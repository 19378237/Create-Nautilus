package com.create.nautilus.hydrogen;

import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.world.level.block.entity.BlockEntityType;

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
