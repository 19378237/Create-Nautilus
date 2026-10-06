package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/** Binds and restores Nautilus' optional Sable depth samplers around Sodium's translucent pass. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer",
        remap = false)
public abstract class SodiumChunkRendererMixin {
    @Unique
    private static Method createNautilus$isTranslucent;
    @Unique
    private static boolean createNautilus$lookupFailed;

    @Inject(method = "begin", at = @At("TAIL"), require = 0)
    private void createNautilus$beginOcclusion(@Coerce final Object pass,
                                               final CallbackInfo callback) {
        if (!createNautilus$beginLogged) {
            createNautilus$beginLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] ShaderChunkRenderer.begin hook reached");
        }
        if (createNautilus$isTranslucent(pass)) {
            WaterOcclusionPassBridge.beginParentWaterPass();
        }
    }

    @Unique
    private static boolean createNautilus$isTranslucent(final Object pass) {
        if (createNautilus$lookupFailed || pass == null) {
            return false;
        }
        try {
            Method method = createNautilus$isTranslucent;
            if (method == null) {
                method = pass.getClass().getMethod("isTranslucent");
                createNautilus$isTranslucent = method;
            }
            return (boolean) method.invoke(pass);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            createNautilus$lookupFailed = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to identify Sodium's translucent terrain pass; "
                            + "continuous compartment water occlusion is disabled",
                    exception);
            return false;
        }
    }

    @Inject(method = "end", at = @At("HEAD"), require = 0)
    private void createNautilus$endOcclusion(@Coerce final Object pass,
                                             final CallbackInfo callback) {
        WaterOcclusionPassBridge.endParentWaterPass();
    }

    @Unique
    private static boolean createNautilus$beginLogged;
}
