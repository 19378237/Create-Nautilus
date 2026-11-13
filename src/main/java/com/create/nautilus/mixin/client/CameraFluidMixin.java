package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.CameraNativeMedium;
import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import com.create.nautilus.client.render.ClientWaterDiagnostics;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import com.create.nautilus.config.NautilusConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Nautilus' local compartment water state consistent with vanilla/Iris camera fluid.
 *
 * <p>Sable's own {@code CameraMixin} turns {@code getFluidInCamera()} into NONE inside a
 * registered water-occlusion region. Dry rooms must remain air, but an eye below a compartment's
 * virtual water surface reports WATER to vanilla and Iris. The shader-fog option can make only
 * that virtual water report NONE while a pack is active; real water keeps its native medium.</p>
 *
 * <p>Resolve known compartment states at HEAD with cancellation. This early return bypasses
 * Sable's coarse-occlusion RETURN hooks, so the chosen virtual-water medium reaches Iris intact.
 * Real eye-water retains its native priority over near-plane lava and powder snow. Parent ocean
 * is masked inside an exact tracked room; real plot water remains visible there. Near-plane
 * non-water media still win over dry/virtual-water overrides. Untracked air follows the original
 * method.</p>
 */
@Mixin(value = Camera.class, priority = 2000)
public abstract class CameraFluidMixin {
    @Unique
    private static long lastCameraDiagnosticNanos;
    @Unique
    private static ClientCompartmentVisualCache.CameraWaterState lastCameraState;
    @Unique
    private static boolean lastExternalParentWater;
    @Unique
    private static boolean lastVirtualFogSuppressed;
    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void createNautilus$separateVirtualRoomMedium(
            final CallbackInfoReturnable<FogType> callback) {
        final int diagnosticsSource = ClientWaterDiagnostics.enter(ClientWaterDiagnostics.Source.CAMERA);
        try {
            final Camera camera = (Camera) (Object) this;
            final var cameraPosition = camera.getPosition();
            final Minecraft minecraft = Minecraft.getInstance();
            final boolean parentFluidIsWater = CameraNativeMedium.isEyeInParentWater(
                    camera, minecraft.level);
            final boolean externalParentWater = parentFluidIsWater
                    && !ClientCompartmentVisualCache.isInsideTrackedRoomExact(cameraPosition);
            final ClientCompartmentVisualCache.CameraWaterState state =
                    ClientCompartmentVisualCache.cameraWaterState(cameraPosition);
            if (externalParentWater || state != ClientCompartmentVisualCache.CameraWaterState.OUTSIDE) {
                final FogType nativeMedium = externalParentWater
                        ? FogType.WATER : CameraNativeMedium.sample(camera, minecraft.level);
                if (nativeMedium != FogType.NONE) {
                    callback.setReturnValue(nativeMedium);
                    return;
                }
            }
            final boolean suppressVirtualFog =
                    state == ClientCompartmentVisualCache.CameraWaterState.WET
                    && !externalParentWater
                    && !NautilusConfig.shaderUnderwaterFogEnabled()
                    && WaterOcclusionPassBridge.isShaderPackActive()
                    && ClientCompartmentVisualCache.isCameraInVirtualWater(cameraPosition);

            if (CreateNautilus.LOGGER.isDebugEnabled()) {
                final long now = System.nanoTime();
                final boolean stateChanged = state != lastCameraState
                        || externalParentWater != lastExternalParentWater
                        || suppressVirtualFog != lastVirtualFogSuppressed;
                final boolean insideCompartment =
                        state != ClientCompartmentVisualCache.CameraWaterState.OUTSIDE;
                if (stateChanged || (insideCompartment
                        && now - lastCameraDiagnosticNanos > 5_000_000_000L)) {
                    lastCameraState = state;
                    lastExternalParentWater = externalParentWater;
                    lastVirtualFogSuppressed = suppressVirtualFog;
                    lastCameraDiagnosticNanos = now;
                    final String forced = externalParentWater ? "WATER" : switch (state) {
                        case WET -> suppressVirtualFog ? "NONE" : "WATER";
                        case DRY -> "NONE";
                        case OUTSIDE -> "none";
                    };
                    CreateNautilus.LOGGER.debug(
                            "[camera] compartmentState={} forcedFogType={} parentFluidIsWater={}",
                            state, forced, parentFluidIsWater);
                }
            }

            if (externalParentWater || state == ClientCompartmentVisualCache.CameraWaterState.WET) {
                callback.setReturnValue(suppressVirtualFog ? FogType.NONE : FogType.WATER);
                return;
            }
            if (state == ClientCompartmentVisualCache.CameraWaterState.DRY) {
                callback.setReturnValue(FogType.NONE);
            }
        } finally {
            ClientWaterDiagnostics.leave(diagnosticsSource);
        }
    }
}
