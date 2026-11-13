package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ApertureMediumMaskRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.client.model.data.ModelDataManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A queued refresh is consumed by getAt() when the watched geometry is next rebuilt.
 * NeoForge rejects requestRefresh on any thread other than the manager's owning client thread
 * before enqueueing; this RETURN hook therefore cannot run for a rejected worker-thread call.
 */
@Mixin(ModelDataManager.class)
public abstract class ClientModelDataManagerMixin {
    @Inject(method = "requestRefresh", at = @At("RETURN"), remap = false)
    private void createNautilus$invalidateModelData(final BlockEntity blockEntity,
                                                   final CallbackInfo callback) {
        if (blockEntity.getLevel() instanceof final ClientLevel level) {
            ApertureMediumMaskRenderer.notifyModelDataChanged(level, blockEntity.getBlockPos());
        }
    }
}
