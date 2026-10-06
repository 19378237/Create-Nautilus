package com.create.nautilus.client.render;

import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Keeps dry interiors clear of parent-ocean fog. Wet rooms report FogType.WATER from the camera
 * and retain vanilla's complete biome color, water-vision range and shader-pack underwater state.
 */
public final class WetCompartmentFog {
    private WetCompartmentFog() {
    }

    public static void onRenderFog(final ViewportEvent.RenderFog event) {
        if (cameraInDryCompartment(event.getCamera().getPosition())) {
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
        if (cameraInDryCompartment(event.getCamera().getPosition())) {
            // Fog is disabled anyway; keep the colour neutral so any residual fog is invisible.
            event.setRed(0.0F);
            event.setGreen(0.0F);
            event.setBlue(0.0F);
        }
    }

    private static boolean cameraInDryCompartment(final Vec3 camera) {
        return ClientCompartmentVisualCache.cameraWaterState(camera)
                == ClientCompartmentVisualCache.CameraWaterState.DRY;
    }

}
