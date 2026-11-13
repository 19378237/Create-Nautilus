package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ApertureMediumMaskRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Model/material notifications can keep the same BlockState and bypass Level.setBlock. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelModelUpdateMixin {
    @Inject(method = "sendBlockUpdated", at = @At("RETURN"))
    private void createNautilus$invalidateModel(final BlockPos position,
                                               final BlockState oldState,
                                               final BlockState newState,
                                               final int flags,
                                               final CallbackInfo callback) {
        ApertureMediumMaskRenderer.notifyModelDataChanged((ClientLevel) (Object) this, position);
    }
}
