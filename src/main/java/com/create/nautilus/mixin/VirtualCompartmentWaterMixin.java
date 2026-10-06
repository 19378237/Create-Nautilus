package com.create.nautilus.mixin;

import com.create.nautilus.physics.VirtualCompartmentWater;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Reintroduces exact virtual-water entity state after Sable occludes the parent ocean. */
@Mixin(value = Entity.class, priority = 1000)
public abstract class VirtualCompartmentWaterMixin {
    @Shadow @Final private Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    @Shadow @Final private Set<TagKey<Fluid>> fluidOnEyes;

    @Inject(method = "updateFluidHeightAndDoFluidPushing", at = @At("RETURN"),
            cancellable = true)
    private void nautilus$restoreServerCompartmentWater(
            final TagKey<Fluid> fluidTag, final double pushScale,
            final CallbackInfoReturnable<Boolean> callback) {
        if (fluidTag != FluidTags.WATER) {
            return;
        }
        final Entity entity = (Entity) (Object) this;
        final net.minecraft.world.phys.AABB bounds = entity.getBoundingBox();
        final net.minecraft.world.phys.Vec3 feetSample = new net.minecraft.world.phys.Vec3(
                (bounds.minX + bounds.maxX) * 0.5D, bounds.minY + 1.0E-4D,
                (bounds.minZ + bounds.maxZ) * 0.5D);
        final double depth = VirtualCompartmentWater.serverDepthAt(
                entity, feetSample);
        if (depth <= 0.0D) {
            return;
        }
        fluidHeight.put(fluidTag, Math.max(fluidHeight.getDouble(fluidTag), depth));
        callback.setReturnValue(true);
    }

    @Inject(method = "updateFluidOnEyes", at = @At("RETURN"))
    private void nautilus$restoreServerCompartmentWaterOnEyes(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (VirtualCompartmentWater.serverDepthAt(entity, entity.getEyePosition()) > 0.0D) {
            fluidOnEyes.add(FluidTags.WATER);
        }
    }
}
