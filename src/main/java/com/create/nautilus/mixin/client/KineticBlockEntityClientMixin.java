package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.UnderwaterKineticEffects;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only visual hook; never linked by a dedicated server. */
@Mixin(value = KineticBlockEntity.class, priority = 1000)
public abstract class KineticBlockEntityClientMixin {
    @Inject(method = "tick", at = @At("RETURN"), require = 1)
    private void createNautilus$underwaterVisuals(final CallbackInfo callback) {
        UnderwaterKineticEffects.tick((KineticBlockEntity) (Object) this);
    }
}
