package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientFrameDiagnostics;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Deletes queries while Window.close still owns a live render-thread GL context. */
@Mixin(Window.class)
public abstract class WindowFrameDiagnosticsMixin {
    @Inject(method = "close()V", at = @At("HEAD"))
    private void nautilus$releaseFrameDiagnostics(final CallbackInfo callback) {
        ClientFrameDiagnostics.release();
    }
}
