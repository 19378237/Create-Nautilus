package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientFrameDiagnostics;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Targets verified against local 1.21.1 bytecode; neither includes event polling/replay overhead. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemFrameDiagnosticsMixin {
    @Inject(method = "flipFrame(J)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
    private static void nautilus$beforeSwap(final CallbackInfo callback) {
        ClientFrameDiagnostics.beginPresent();
    }

    @Inject(method = "flipFrame(J)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", shift = At.Shift.AFTER, remap = false))
    private static void nautilus$afterSwap(final CallbackInfo callback) {
        ClientFrameDiagnostics.endPresent();
    }

    @Inject(method = "limitDisplayFPS(I)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwWaitEventsTimeout(D)V", remap = false))
    private static void nautilus$beforeFrameLimitWait(final CallbackInfo callback) {
        ClientFrameDiagnostics.beginFrameLimitWait();
    }

    @Inject(method = "limitDisplayFPS(I)V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwWaitEventsTimeout(D)V", shift = At.Shift.AFTER, remap = false))
    private static void nautilus$afterFrameLimitWait(final CallbackInfo callback) {
        ClientFrameDiagnostics.endFrameLimitWait();
    }
}
