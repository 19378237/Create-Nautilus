package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.SodiumFluidMaterialMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Publishes Nautilus' pack-independent water bit in Iris' extended Sodium vertex format. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.vertices.sodium.terrain.XHFPTerrainVertex", remap = false)
public abstract class IrisXHFPTerrainVertexMixin {
    /**
     * XHFP ignores Sodium's {@code materialBits} encoder argument. Its third light/data byte uses
     * only bit 0 for tangent handedness, leaving bit 3 available for Nautilus. The fragment source
     * reads this byte as {@code a_LightAndData.z}, so the marker survives shader-pack switches even
     * while an existing chunk mesh still carries the previous pack's {@code mc_Entity} block id.
     */
    @Inject(method = "packLightAndData(IZI)I", at = @At("RETURN"), cancellable = true,
            require = 0)
    private static void createNautilus$packStableWaterBit(
            final int light,
            final boolean tangentPositive,
            final int sectionIndex,
            final CallbackInfoReturnable<Integer> callback) {
        if (!SodiumFluidMaterialMarker.isWaterQuad()) {
            return;
        }
        callback.setReturnValue(callback.getReturnValueI()
                | (SodiumFluidMaterialMarker.WATER_MATERIAL_BIT << 16));
        if (!createNautilus$markerLogged) {
            createNautilus$markerLogged = true;
            CreateNautilus.LOGGER.info("[occlusion] Iris XHFP stable water bit set");
        }
    }

    @Unique
    private static boolean createNautilus$markerLogged;
}
