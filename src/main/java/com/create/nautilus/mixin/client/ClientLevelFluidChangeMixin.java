package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ShaderVoxelMaskAtlas;
import com.create.nautilus.client.render.ApertureMediumMaskRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidates exact plot shell/fluid semantics after a committed client block update. */
@Mixin(Level.class)
public abstract class ClientLevelFluidChangeMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"), require = 0)
    private void createNautilus$invalidateChangedVoxel(
            final BlockPos position, final BlockState state,
            final int flags, final int recursionLeft,
            final CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof ClientLevel && callback.getReturnValueZ()) {
            ShaderVoxelMaskAtlas.notifyBlockChanged((ClientLevel) (Object) this, position);
            ApertureMediumMaskRenderer.notifyBlockChanged(
                    (ClientLevel) (Object) this, position);
        }
    }
}
