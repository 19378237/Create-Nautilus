package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientWaterDiagnostics;
import com.create.nautilus.client.render.ClientFrameDiagnostics;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic invocation numbers are independent of entity ticks and Iris' frame counter. */
@Mixin(LevelRenderer.class)
public abstract class ClientWaterDiagnosticsFrameMixin {
    @Unique private long nautilus$levelWall;
    @Unique private long nautilus$levelGpu;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void nautilus$beginWaterDiagnostics(final CallbackInfo callback) {
        ClientWaterDiagnostics.beginRender();
        nautilus$levelWall = ClientFrameDiagnostics.startWall();
        nautilus$levelGpu = ClientFrameDiagnostics.beginGpu(ClientFrameDiagnostics.Stage.LEVEL_RENDER);
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void nautilus$endWaterDiagnostics(final CallbackInfo callback) {
        ClientFrameDiagnostics.endGpu(nautilus$levelGpu);
        ClientFrameDiagnostics.recordWall(ClientFrameDiagnostics.Stage.LEVEL_RENDER, nautilus$levelWall);
        nautilus$levelGpu = 0L;
        nautilus$levelWall = 0L;
        ClientWaterDiagnostics.endRender();
    }
}
