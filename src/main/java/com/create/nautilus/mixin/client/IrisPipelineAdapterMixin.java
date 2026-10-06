package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.IrisCompositeShaderSource;
import com.create.nautilus.client.render.ApertureMediumMaskRenderer;
import net.minecraft.client.Minecraft;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.MergedBooleanOption;
import net.irisshaders.iris.shaderpack.option.MergedStringOption;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalInt;
import java.util.OptionalDouble;

/** Defines the source-compilation lifetime used by all pack-specific adapter stages. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineAdapterMixin {
    @Shadow
    @Final
    private RenderTargets renderTargets;

    /** The immutable adapter generation owned by this cached dimension pipeline. */
    @Unique
    private int createNautilus$adapterGeneration;

    @Inject(method = "<init>", at = @At("HEAD"), require = 0)
    private static void createNautilus$beginAdapterGeneration(final CallbackInfo callback) {
        int dofQuality = IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION;
        int taaJitter = IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION;
        int temporalAa = IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION;
        int photonTaau = IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION;
        float photonTaauRenderScale = Float.NaN;
        float blissRenderScale = Float.NaN;
        int complementaryTaaMode = IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION;
        try {
            final ShaderPack pack = Iris.getCurrentPack().orElse(null);
            if (pack != null) {
                dofQuality = createNautilus$readIntOption(
                        pack, "DOF_QUALITY", -1, 5)
                        .orElse(IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION);
                taaJitter = createNautilus$readIntOption(
                        pack, "TAA_JITTER", 0, 3)
                        .orElse(IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION);
                temporalAa = createNautilus$readBooleanOption(pack, "TAA")
                        .orElse(IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION);
                photonTaau = createNautilus$readBooleanOption(pack, "TAAU")
                        .orElse(IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION);
                photonTaauRenderScale = (float) createNautilus$readDoubleOption(
                        pack, "TAAU_RENDER_SCALE", 0.25D, 1.0D)
                        .orElse(Double.NaN);
                complementaryTaaMode = createNautilus$readIntOption(
                        pack, "TAA_MODE", 0, 1)
                        .orElse(IrisCompositeShaderSource.UNKNOWN_SHADER_OPTION);
                // Complementary's lowest detail profiles compile out TAA even with TAA_MODE=1.
                final OptionalInt detailQuality = createNautilus$readIntOption(
                        pack, "DETAIL_QUALITY", 0, 4);
                if (detailQuality.isPresent() && detailQuality.getAsInt() == 0) {
                    complementaryTaaMode = 0;
                }
                final OptionalInt blissUpscaling = createNautilus$readBooleanOption(pack, "TAA_UPSCALING");
                if (temporalAa == 0 || blissUpscaling.orElse(-1) == 0) {
                    blissRenderScale = 1.0F;
                } else if (blissUpscaling.orElse(-1) == 1) {
                    blissRenderScale = (float) createNautilus$readDoubleOption(
                            pack, "SCALE_FACTOR", 0.1D, 1.0D).orElse(Double.NaN);
                }
            }
        } catch (final RuntimeException | LinkageError unavailable) {
            // A missing or incompatible option API disables only the affected adapter stages.
            // beginShaderReload always receives UNKNOWN here and cannot inherit an old pack value.
        }
        IrisCompositeShaderSource.beginShaderReload(
                dofQuality, taaJitter, temporalAa, photonTaau,
                complementaryTaaMode, photonTaauRenderScale, blissRenderScale);
    }

    private static OptionalInt createNautilus$readIntOption(
            final ShaderPack pack,
            final String name,
            final int minimum,
            final int maximum) {
        final ShaderPackOptions options = pack.getShaderPackOptions();
        final MergedStringOption merged = options.getOptionSet()
                .getStringOptions().get(name);
        if (merged == null) return OptionalInt.empty();

        final String encoded = options.getOptionValues().getStringValue(name)
                .orElse(merged.getOption().getDefaultValue());
        if (encoded == null) return OptionalInt.empty();
        try {
            final int value = Integer.parseInt(encoded.trim());
            return value >= minimum && value <= maximum
                    ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (final NumberFormatException malformed) {
            return OptionalInt.empty();
        }
    }

    private static OptionalInt createNautilus$readBooleanOption(
            final ShaderPack pack,
            final String name) {
        final ShaderPackOptions options = pack.getShaderPackOptions();
        final MergedBooleanOption merged = options.getOptionSet()
                .getBooleanOptions().get(name);
        if (merged == null) return OptionalInt.empty();
        final boolean value = options.getOptionValues().getBooleanValue(name)
                .orElse(merged.getOption().getDefaultValue());
        return OptionalInt.of(value ? 1 : 0);
    }

    private static OptionalDouble createNautilus$readDoubleOption(
            final ShaderPack pack, final String name,
            final double minimum, final double maximum) {
        final ShaderPackOptions options = pack.getShaderPackOptions();
        final MergedStringOption merged = options.getOptionSet()
                .getStringOptions().get(name);
        if (merged == null) return OptionalDouble.empty();
        final String encoded = options.getOptionValues().getStringValue(name)
                .orElse(merged.getOption().getDefaultValue());
        if (encoded == null) return OptionalDouble.empty();
        try {
            final double value = Double.parseDouble(encoded.trim());
            return Double.isFinite(value) && value >= minimum && value <= maximum
                    ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (final NumberFormatException malformed) {
            return OptionalDouble.empty();
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void createNautilus$finishAdapterSourceCollection(final CallbackInfo callback) {
        // This only closes source-anchor collection.  IrisProgramMixin separately requires a
        // linked GL program with the complete uniform set to be actually used before Ready=1.
        IrisCompositeShaderSource.finishShaderReload();
        createNautilus$adapterGeneration = IrisCompositeShaderSource.adapterGeneration();
    }

    /**
     * Selects this cached pipeline before Sodium freezes the semantic water frame.  A global
     * "last compiled" generation is incorrect when Iris retains one pipeline per dimension.
     */
    @Inject(method = "beginTranslucents", at = @At("HEAD"), require = 0)
    private void createNautilus$selectWaterBoundaryGeneration(final CallbackInfo callback) {
        ApertureMediumMaskRenderer.selectAdapterGeneration(
                createNautilus$adapterGeneration);
    }

    /** Publishes Complementary's fog inputs after opaque depth is copied but before deferred1. */
    @Inject(method = "beginTranslucents", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/targets/RenderTargets;"
                    + "copyPreTranslucentDepth()V",
            shift = At.Shift.AFTER), require = 0)
    private void createNautilus$prepareEarlyFogBeforeDeferred(final CallbackInfo callback) {
        if (!IrisCompositeShaderSource.requiresEarlyFog(createNautilus$adapterGeneration)) {
            return;
        }
        final var captured = CapturedRenderingState.INSTANCE;
        final var modelView = captured.getGbufferModelView();
        final var projection = captured.getGbufferProjection();
        if (modelView == null || projection == null) return;

        final var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        final int targetWidth = renderTargets.getCurrentWidth();
        final int targetHeight = renderTargets.getCurrentHeight();
        ApertureMediumMaskRenderer.prepareAndFinalizeEarlyFog(
                new Matrix4f(modelView), new Matrix4f(projection),
                camera.x, camera.y, camera.z,
                renderTargets.getDepthTextureNoTranslucents().getTextureId(),
                targetWidth, targetHeight, SystemTimeUniforms.COUNTER.getAsInt(),
                createNautilus$adapterGeneration);
    }

    /**
     * Finalizes after the parent translucent terrain draw and immediately before Iris composites
     * that draw. {@code beginTranslucents()} runs before Sodium submits the water layer, so doing
     * this beside Iris' deferred pass used to consume an empty semantic frame every time. The
     * opaque depth copy made by {@code beginTranslucents()} remains valid here and is the exact
     * scene endpoint consumed by the same composite generation.
     *
     * <p>This pseudo mixin is only applied when Iris is present. The shadowed field and public
     * accessors are taken from the locked Iris 1.8.14 compatibility target, avoiding private
     * reflection in the render loop.</p>
     */
    @Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",
            ordinal = 0, shift = At.Shift.BEFORE), require = 0)
    private void createNautilus$finalizeWaterBoundariesBeforeDeferred(
            final CallbackInfo callback) {
        // These dimensions belong to Iris' active render targets (including render scale and
        // TAAU), not to Minecraft's main window framebuffer.  The boundary renderer must use
        // this exact coordinate space for both private depth textures and shader sampling.
        final int targetWidth = renderTargets.getCurrentWidth();
        final int targetHeight = renderTargets.getCurrentHeight();
        ApertureMediumMaskRenderer.finalizeBeforeDeferred(
                renderTargets.getDepthTextureNoTranslucents().getTextureId(),
                targetWidth, targetHeight, SystemTimeUniforms.COUNTER.getAsInt(),
                createNautilus$adapterGeneration);
    }
}
