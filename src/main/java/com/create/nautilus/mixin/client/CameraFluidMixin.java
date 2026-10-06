package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import com.create.nautilus.client.render.WaterMediumClassifier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3d;
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
 * virtual water surface is genuinely underwater and must report WATER to vanilla and Iris.</p>
 *
 * <p>Resolve known compartment states at HEAD with cancellation. This early return bypasses
 * Sable's coarse-occlusion RETURN hooks, so WET cannot be turned back into NONE. The shader pack
 * owns the complete native underwater view; untracked air still follows the original method.</p>
 */
@Mixin(value = Camera.class, priority = 2000)
public abstract class CameraFluidMixin {
    @Unique
    private static long lastCameraDiagnosticNanos;
    @Unique
    private static ClientCompartmentVisualCache.CameraWaterState lastCameraState;
    @Unique
    private static boolean lastExternalParentWater;
    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void createNautilus$separateVirtualRoomMedium(
            final CallbackInfoReturnable<FogType> callback) {
        final Camera camera = (Camera) (Object) this;
        final var cameraPosition = camera.getPosition();
        final Minecraft minecraft = Minecraft.getInstance();
        final boolean parentFluidIsWater = minecraft.level != null
                && WaterMediumClassifier.parentWorldContains(minecraft.level, new Vector3d(
                cameraPosition.x, cameraPosition.y, cameraPosition.z));
        final boolean externalParentWater = parentFluidIsWater
                && !ClientCompartmentVisualCache.isInsideTrackedRoomExact(cameraPosition);
        final ClientCompartmentVisualCache.CameraWaterState state =
                ClientCompartmentVisualCache.cameraWaterState(cameraPosition);

        final long now = System.nanoTime();
        final boolean stateChanged = state != lastCameraState
                || externalParentWater != lastExternalParentWater;
        final boolean insideCompartment =
                state != ClientCompartmentVisualCache.CameraWaterState.OUTSIDE;
        if (stateChanged || (insideCompartment
                && now - lastCameraDiagnosticNanos > 5_000_000_000L)) {
            lastCameraState = state;
            lastExternalParentWater = externalParentWater;
            lastCameraDiagnosticNanos = now;
            final String forced = externalParentWater ? "WATER" : switch (state) {
                case WET -> "WATER";
                case DRY -> "NONE";
                case OUTSIDE -> "none";
            };
            CreateNautilus.LOGGER.info(
                    "[camera] compartmentState={} forcedFogType={} parentFluidIsWater={}",
                    state, forced, parentFluidIsWater);
        }

        if (externalParentWater || state == ClientCompartmentVisualCache.CameraWaterState.WET) {
            callback.setReturnValue(FogType.WATER);
            return;
        }
        if (state == ClientCompartmentVisualCache.CameraWaterState.DRY) {
            callback.setReturnValue(FogType.NONE);
        }
    }
}
