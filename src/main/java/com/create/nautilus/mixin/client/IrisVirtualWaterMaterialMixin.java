package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.PhotonVirtualWaterShaderSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Immediate BLOCK water uses Iris ExtendedShader, not Sodium's chunk GlShader. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisVirtualWaterMaterialMixin {
    @ModifyArgs(method = "create", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;"
                    + "patchVanilla(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                    + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                    + "Lnet/irisshaders/iris/gl/blending/AlphaTest;ZZ"
                    + "Lnet/irisshaders/iris/gl/state/ShaderAttributeInputs;"
                    + "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;"),
            require = 0)
    private static void createNautilus$patchVirtualWaterMaterial(final Args args) {
        final String name = args.get(0);
        if (!"terrain_translucent".equalsIgnoreCase(name)) return;
        final String source = args.get(5);
        final String patched = PhotonVirtualWaterShaderSource.patchFragment(source);
        if (source == null || source.equals(patched)) return;
        args.set(5, patched);
        CreateNautilus.LOGGER.info(
                "[virtual-water] patched Photon virtual texture/absorption for immediate shader {}", name);
    }
}
