package com.create.nautilus.client.render;

import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Keeps shader-pack fog out of dry interiors. Without a pack, the camera's NONE/WATER medium
 * already selects the correct vanilla fog, including the sky and main framebuffer clear color.
 * Wet rooms retain vanilla's complete biome color, water-vision range and underwater state.
 */
public final class WetCompartmentFog {
    private WetCompartmentFog() {
    }

    public static void onRenderFog(final ViewportEvent.RenderFog event) {
        if (WaterOcclusionPassBridge.isShaderPackActive()
                && cameraInDryCompartment(event.getCamera())) {
            /*
             * Dry cabin: the interior is air, so the vanilla underwater fog must not darken the
             * cabin walls. The far plane is pushed out so the fog factor stays effectively zero
             * for every interior distance; Iris reads these values into its fog uniforms.
             */
            event.setNearPlaneDistance(100000.0F);
            event.setFarPlaneDistance(200000.0F);
            event.setCanceled(true);
        }
    }

    public static void onFogColour(final ViewportEvent.ComputeFogColor event) {
        if (WaterOcclusionPassBridge.isShaderPackActive()
                && cameraInDryCompartment(event.getCamera())) {
            // Fog is disabled anyway; keep the colour neutral so any residual fog is invisible.
            event.setRed(0.0F);
            event.setGreen(0.0F);
            event.setBlue(0.0F);
        }
    }

    private static boolean cameraInDryCompartment(final Camera camera) {
        final int diagnosticsSource = ClientWaterDiagnostics.enter(ClientWaterDiagnostics.Source.CAMERA);
        try {
            if (ClientCompartmentVisualCache.cameraWaterState(camera.getPosition())
                    != ClientCompartmentVisualCache.CameraWaterState.DRY) return false;
            final FogType medium = camera.getFluidInCamera();
            // A padded room candidate may still have a real WATER eye outside its exact cells.
            // Consume Camera's final decision, including native media and the virtual-fog option.
            return medium == FogType.NONE;
        } finally {
            ClientWaterDiagnostics.leave(diagnosticsSource);
        }
    }

}
