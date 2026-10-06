package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Client mirror of the continuous virtual-water state used for local swimming/camera state. */
@Mixin(value = Entity.class, priority = 1000)
public abstract class VirtualCompartmentWaterClientMixin {
    @Shadow @Final private Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    @Shadow @Final private Set<TagKey<Fluid>> fluidOnEyes;

    @Inject(method = "updateFluidHeightAndDoFluidPushing", at = @At("RETURN"),
            cancellable = true)
    private void nautilus$restoreClientCompartmentWater(
            final TagKey<Fluid> fluidTag, final double pushScale,
            final CallbackInfoReturnable<Boolean> callback) {
        if (fluidTag != FluidTags.WATER) {
            return;
        }
        final Entity entity = (Entity) (Object) this;
        final AABB bounds = entity.getBoundingBox();
        final Vec3 feetSample = new Vec3((bounds.minX + bounds.maxX) * 0.5D,
                bounds.minY + 1.0E-4D, (bounds.minZ + bounds.maxZ) * 0.5D);
        final double depth = nautilus$clientDepthAt(entity, feetSample);
        if (depth <= 0.0D) {
            return;
        }
        fluidHeight.put(fluidTag, Math.max(fluidHeight.getDouble(fluidTag), depth));
        callback.setReturnValue(true);
    }

    @Inject(method = "updateFluidOnEyes", at = @At("RETURN"))
    private void nautilus$restoreClientCompartmentWaterOnEyes(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (nautilus$clientDepthAt(entity, entity.getEyePosition()) > 0.0D) {
            fluidOnEyes.add(FluidTags.WATER);
        }
    }

    private static double nautilus$clientDepthAt(final Entity entity, final Vec3 worldSample) {
        final AABB probe = new AABB(worldSample, worldSample).inflate(0.05D);
        double deepest = 0.0D;
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(
                entity.level(), new BoundingBox3d(probe))) {
            if (!(candidate instanceof ClientSubLevel carrier)) {
                continue;
            }
            // Rendering and camera/entity state must use the same interpolated client pose.
            // logicalPose can be one network step ahead of renderPose and previously shifted the
            // physical water boundary away from the visible plane on moving/rotating carriers.
            final Vector3d local = carrier.renderPose().transformPositionInverse(
                    new Vector3d(worldSample.x, worldSample.y, worldSample.z));
            deepest = Math.max(deepest, ClientCompartmentVisualCache.waterDepthAt(
                    carrier, local, worldSample.y));
        }
        return Math.max(0.0D, deepest);
    }
}
