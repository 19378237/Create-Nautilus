package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.SodiumOcclusionShaderSource;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Sodium compatibility hook for exact, per-fragment parent-water occlusion.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader", remap = false)
public abstract class SodiumGlShaderMixin {
    @Unique
    private String createNautilus$shaderStage;
    @Unique
    private ResourceLocation createNautilus$shaderName;

    @Inject(method = "<init>", require = 0,
            at = @At(value = "CTOR_HEAD", unsafe = true,
                    args = "enforce=POST_DELEGATE"))
    private void createNautilus$captureShaderStage(@Coerce final Object shaderType,
                                                   final ResourceLocation name,
                                                   @Coerce final Object parsedShader,
                                                   final CallbackInfo callback) {
        createNautilus$shaderStage = shaderType instanceof final Enum<?> stage
                ? stage.name()
                : String.valueOf(shaderType);
        createNautilus$shaderName = name;
    }

    @ModifyArg(method = "<init>", require = 0, index = 1,
            at = @At(value = "INVOKE", target =
                    "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderWorkarounds;"
                            + "safeShaderSource(ILjava/lang/CharSequence;)V",
                    unsafe = true))
    private CharSequence createNautilus$injectOcclusion(final CharSequence source) {
        final CharSequence injected = SodiumOcclusionShaderSource.inject(
                source, createNautilus$shaderStage, createNautilus$shaderName);
        /*
         * The water program is recreated on every shader-pack switch and shader reload. That is
         * the exact moment to clear one-shot log latches and the permanent disable flag, so a
         * program compiled for the new pack is retried from a clean state.
         */
        if (createNautilus$shaderName != null
                && "iris".equals(createNautilus$shaderName.getNamespace())
                && "sodium-shader-translucent".equals(createNautilus$shaderName.getPath())) {
            WaterOcclusionPassBridge.onShaderProgramRecreated();
        }
        if (createNautilus$shaderName != null
                && (createNautilus$shaderName.getPath().contains("sodium_translucent")
                || createNautilus$shaderName.getPath().contains("sodium-shader-translucent")
                || createNautilus$shaderName.getPath().contains("block_layer_opaque"))
                && (createNautilus$shaderName.getNamespace().equals("iris")
                || createNautilus$shaderName.getNamespace().equals("sodium"))) {
            CreateNautilus.LOGGER.info(
                    "[occlusion] Sodium GlShader stage={} name={} changed={}",
                    createNautilus$shaderStage, createNautilus$shaderName,
                    injected != source);
        }
        if (!createNautilus$injectionLogged) {
            createNautilus$injectionLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] GlShader inject stage={} name={} changed={}",
                    createNautilus$shaderStage, createNautilus$shaderName,
                    injected != source);
        }
        return injected;
    }

    @Unique
    private static boolean createNautilus$injectionLogged;
}
