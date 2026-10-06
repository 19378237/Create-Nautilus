package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/** Produces Sable's depth masks from the matrices of each real parent-world water pass. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
        remap = false)
public abstract class SodiumWorldRendererMixin {
    @Unique
    private static Method createNautilus$projection;
    @Unique
    private static Method createNautilus$modelView;

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), require = 0)
    private void createNautilus$prepareWaterOcclusion(
            final RenderType renderType,
            @Coerce final Object matrices,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final CallbackInfo callback) {
        if (renderType != RenderType.translucent()
                || !WaterOcclusionPassBridge.isSupportedAndEnabled()) {
            return;
        }
        try {
            Method projection = createNautilus$projection;
            Method modelView = createNautilus$modelView;
            if (projection == null || modelView == null) {
                projection = matrices.getClass().getMethod("projection");
                modelView = matrices.getClass().getMethod("modelView");
                createNautilus$projection = projection;
                createNautilus$modelView = modelView;
            }
            final Matrix4fc projectionMatrix = (Matrix4fc) projection.invoke(matrices);
            final Matrix4fc modelViewMatrix = (Matrix4fc) modelView.invoke(matrices);
            WaterOcclusionPassBridge.prepareParentWaterPass(
                    new Matrix4f(modelViewMatrix), new Matrix4f(projectionMatrix),
                    cameraX, cameraY, cameraZ);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError exception) {
            WaterOcclusionPassBridge.disableUntilReload(
                    "Sodium drawChunkLayer matrices no longer match the verified 0.8.12 API",
                    exception);
        }
    }
}
