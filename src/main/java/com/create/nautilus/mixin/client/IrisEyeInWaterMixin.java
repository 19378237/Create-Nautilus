package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Diagnostic: logs the actual {@code isEyeInWater} value Iris feeds to shader packs.
 *
 * <p>Iris computes this uniform from {@code Camera.getFluidInCamera()} every frame. Logging it
 * lets us verify that the camera fluid override in {@link CameraFluidMixin} actually reaches the
 * composite programs (inside a flooded compartment the value must be 1, otherwise the pack
 * cannot render the underwater effect).</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class IrisEyeInWaterMixin {
    @Unique
    private static int lastLoggedEyeInWater = Integer.MIN_VALUE;

    @Inject(method = "isEyeInWater", at = @At("RETURN"), require = 0)
    private static void createNautilus$logEyeInWater(
            final CallbackInfoReturnable<Integer> callback) {
        final int value = callback.getReturnValue();
        if (value != lastLoggedEyeInWater) {
            lastLoggedEyeInWater = value;
            CreateNautilus.LOGGER.info("[iris-eye] isEyeInWater={}", value);
        }
    }
}
