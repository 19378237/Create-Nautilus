package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.IrisCompositeShaderSource;
import net.irisshaders.iris.gl.program.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Patches already-preprocessed pack source immediately before Iris parses and reformats it with
 * glsl-transformer. Conditional pack directives are no longer legal at this boundary.
 *
 * <p>The previous ProgramBuilder hook runs after {@code patchComposite}; Photon happened to
 * survive that formatting, while the Bliss and Complementary anchors did not.  Keeping this hook
 * at the TransformPatcher boundary records the source generation; a concrete adapter is enabled
 * only later, after its linked Program is actually used and exposes the complete boundary-uniform
 * set.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
public abstract class IrisCompositeRendererMixin {
    @ModifyArgs(method = "createProgram", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;"
                    + "patchComposite(Ljava/lang/String;Ljava/lang/String;"
                    + "Ljava/lang/String;Ljava/lang/String;"
                    + "Lnet/irisshaders/iris/shaderpack/texture/TextureStage;"
                    + "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;"),
            require = 0)
    private void createNautilus$patchBeforeIrisTransform(final Args args) {
        final String name = args.get(0);
        if (!IrisCompositeShaderSource.isCandidateProgram(name)) return;
        final String source = args.get(3);
        final String patched = IrisCompositeShaderSource.patchFragment(source, name);
        if (patched.equals(source)) return;
        args.set(3, patched);
        CreateNautilus.LOGGER.info(
                "[water-boundary] patched pre-transform Iris fragment program: {}", name);
    }

    /**
     * Iris clears its active samplers and calls glUseProgram(0) directly after the final pass,
     * bypassing Program.unbind(). Route normal completion through unbind once so Nautilus' one
     * dynamically selected entry-depth texture unit is restored before another renderer runs.
     */
    @Inject(method = "renderAll", at = @At("RETURN"), require = 0)
    private void createNautilus$restoreBoundaryTextureUnits(final CallbackInfo callback) {
        Program.unbind();
    }
}
