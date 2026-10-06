package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Client-side counterpart of {@link CompartmentEntityOcclusionMixin}.
 *
 * <p>The client's local player shares the same entity fluid state as the server, so the vanilla
 * drowning counter is authoritative there, but swimming state, water sounds and particles are
 * derived client-side. Sable's own client occlusion container is unreliable in the same way as
 * the server one, so this mixin suppresses the parent-ocean water state using Nautilus' visual
 * cache, which is driven by the same compartment topology that renders the hull.</p>
 */
@Mixin(value = Entity.class, priority = 1500)
public abstract class CompartmentEntityOcclusionClientMixin {
    @Shadow
    @Final
    private Set<TagKey<Fluid>> fluidOnEyes;

    @Inject(method = "updateFluidHeightAndDoFluidPushing", at = @At("HEAD"), cancellable = true)
    private void nautilus$suppressDryCompartmentParentWater(
            final TagKey<Fluid> fluidTag, final double pushScale,
            final CallbackInfoReturnable<Boolean> callback) {
        if (fluidTag != FluidTags.WATER) {
            return;
        }
        final Entity entity = (Entity) (Object) this;
        if (isDrySample(entity, feetSample(entity))) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "updateFluidOnEyes", at = @At("RETURN"))
    private void nautilus$removeDryCompartmentWaterOnEyes(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (isDrySample(entity, new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()))) {
            fluidOnEyes.remove(FluidTags.WATER);
        }
    }

    @Unique
    private static Vec3 feetSample(final Entity entity) {
        final AABB bounds = entity.getBoundingBox();
        return new Vec3(
                (bounds.minX + bounds.maxX) * 0.5D,
                bounds.minY + 1.0E-4D,
                (bounds.minZ + bounds.maxZ) * 0.5D);
    }

    @Unique
    private static boolean isDrySample(final Entity entity, final Vec3 worldSample) {
        return ClientCompartmentVisualCache.cameraWaterState(worldSample)
                == ClientCompartmentVisualCache.CameraWaterState.DRY;
    }
}
