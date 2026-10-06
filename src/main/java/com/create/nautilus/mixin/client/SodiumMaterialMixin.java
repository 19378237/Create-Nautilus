package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.SodiumFluidMaterialMarker;
import com.create.nautilus.client.render.SodiumOcclusionShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publishes the stable water marker through Sodium's existing packed material byte. */
@Pseudo
@Mixin(targets =
        "net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material",
        remap = false)
public abstract class SodiumMaterialMixin {
    @Inject(method = "bits", at = @At("RETURN"), cancellable = true, require = 0)
    private void createNautilus$markFluidMaterial(
            final CallbackInfoReturnable<Integer> callback) {
        if (SodiumOcclusionShaderSource.isSafeToUseCustomOcclusion()
                && SodiumFluidMaterialMarker.isWaterQuad()) {
            if (!createNautilus$markLogged) {
                createNautilus$markLogged = true;
                CreateNautilus.LOGGER.info("[occlusion] Sodium material water bit set");
            }
            callback.setReturnValue(callback.getReturnValueI()
                    | SodiumFluidMaterialMarker.WATER_MATERIAL_BIT);
        }
    }

    @Unique
    private static boolean createNautilus$markLogged;
}
