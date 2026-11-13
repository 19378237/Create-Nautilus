package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import com.create.nautilus.config.NautilusConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps vanilla's current fog color when Sable draws a flooded compartment's blocks. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData",
        remap = false)
public abstract class SableCompartmentFogMixin {
    @WrapOperation(method = "renderChunkedSubLevel(Lnet/minecraft/client/renderer/RenderType;"
            + "Lnet/minecraft/client/renderer/ShaderInstance;Lorg/joml/Matrix4f;DDD)V",
            at = @At(value = "INVOKE", target =
                    "Ldev/ryanhcode/sable/sublevel/water_occlusion/WaterOcclusionContainer;"
                            + "getOccludingRegion(Lnet/minecraft/world/phys/Vec3;)"
                            + "Ldev/ryanhcode/sable/sublevel/water_occlusion/WaterOcclusionRegion;"),
            require = 0)
    private WaterOcclusionRegion createNautilus$preserveWetCompartmentFog(
            final WaterOcclusionContainer<?> container, final Vec3 cameraPosition,
            final Operation<WaterOcclusionRegion> original) {
        /*
         * Sable uses this query only to clear the carrier shader's FogColor alpha. Its integer
         * region can still cover a now-flooded eye while our continuous water surface has moved.
         * Skip that clear for WET; vanilla's biome color, adaptation and effect fog remain intact.
         * The live option restores this query only for virtual water under an active shader pack;
         * real plot water and the no-shader path keep their native wet fog.
         * Other camera, entity and occlusion queries retain the actual registered region.
         */
        if (ClientCompartmentVisualCache.cameraWaterState(cameraPosition)
                == ClientCompartmentVisualCache.CameraWaterState.WET
                && (NautilusConfig.shaderUnderwaterFogEnabled()
                || !WaterOcclusionPassBridge.isShaderPackActive()
                || !ClientCompartmentVisualCache.isCameraInVirtualWater(cameraPosition))) {
            return null;
        }
        return original.call(container, cameraPosition);
    }
}
