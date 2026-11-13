package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientDryCompartmentCache;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionContainer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Matches Sable's close-depth clear to the plain renderer's expanded dry volume. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.render.water_occlusion.WaterOcclusionRenderer",
        remap = false)
public abstract class SableWaterOcclusionRendererMixin {
    @Redirect(method = "preRenderTranslucent(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At(value = "INVOKE", target =
                    "Ldev/ryanhcode/sable/sublevel/water_occlusion/WaterOcclusionContainer;"
                            + "isOccluded(Lnet/minecraft/world/phys/Vec3;)Z"),
            require = 0)
    private boolean createNautilus$cameraInsideRenderDomain(
            final WaterOcclusionContainer<?> container, final Vec3 cameraPosition) {
        if (WaterOcclusionPassBridge.isShaderPackActive()) {
            return container.isOccluded(cameraPosition);
        }
        return ClientDryCompartmentCache.isCameraOccludedForPlainRender(
                container, cameraPosition);
    }
}
