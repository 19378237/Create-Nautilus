package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientFrameDiagnostics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** runTick is one client frame (including client ticks, present and the FPS limit wait). */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameDiagnosticsMixin {
    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void nautilus$beginFrame(final CallbackInfo callback) {
        ClientFrameDiagnostics.beginFrame();
    }

    @Inject(method = "runTick(Z)V", at = @At("RETURN"))
    private void nautilus$endFrame(final CallbackInfo callback) {
        ClientFrameDiagnostics.endFrame();
    }
}
