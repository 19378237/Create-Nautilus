package com.create.nautilus.mixin;

import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import com.create.nautilus.physics.VirtualCompartmentWater;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

/**
 * Server-side entity water occlusion for dry compartments.
 *
 * <p>Sable's own {@code water_occlusion.EntityMixin} suppresses the parent-ocean water state for
 * entities inside its occlusion regions. Those regions are not always registered (the same
 * flakiness that affects Sable's camera occlusion), so without a fallback a player standing in a
 * sealed dry hull would keep the vanilla drowning/oxygen-bar behaviour of the parent world.
 *
 * <p>This mixin re-implements the same two suppressors using Nautilus' own compartment state:
 * the sample point must be inside a non-open-top compartment and NOT submerged in virtual
 * compartment water. WET samples are left untouched so the virtual-water mixin can still add the
 * fluid tags, and airless compartments keep their drowning semantics through
 * {@link com.create.nautilus.oxygen.OxygenConsumer#onLivingBreathe}.</p>
 */
@Mixin(value = Entity.class, priority = 1500)
public abstract class CompartmentEntityOcclusionMixin {
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
        if (isDryCompartmentSample(entity, feetSample(entity))) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "updateFluidOnEyes", at = @At("RETURN"))
    private void nautilus$removeDryCompartmentWaterOnEyes(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (isDryCompartmentSample(
                entity, new Vec3(entity.getX(), entity.getEyeY(), entity.getZ()))) {
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

    /**
     * True when the world-space sample lies in the dry region of a sealed compartment: a known
     * compartment, not an open-top displacement envelope, and no virtual water at the sample.
     */
    @Unique
    private static boolean isDryCompartmentSample(final Entity entity, final Vec3 worldSample) {
        if (!(entity.level() instanceof ServerLevel)) {
            return false;
        }
        final Object tracked = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
        if (!(tracked instanceof ServerSubLevel carrier)) {
            return false;
        }
        final CarrierState state = NautilusData.carrier(carrier);
        if (state == null) {
            return false;
        }
        final Vector3d local = carrier.logicalPose().transformPositionInverse(
                new Vector3d(worldSample.x, worldSample.y, worldSample.z));
        final Optional<CompartmentState> room = state.compartmentAt(local);
        if (room.isEmpty()) {
            return false;
        }
        final CompartmentState compartment = room.get();
        final boolean openTop = state.breaches().stream().anyMatch(breach ->
                breach.atmosphericOpening() && breach.compartmentId().equals(compartment.id()));
        if (openTop) {
            return false;
        }
        return VirtualCompartmentWater.serverDepthAt(entity, worldSample) <= 0.0D;
    }
}
