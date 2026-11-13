package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientEntityWater;
import com.create.nautilus.client.render.ClientWaterDiagnostics;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Restores virtual water after Sable's NeoForge sampler and local eye-fluid hook. */
@Mixin(value = Entity.class, priority = 2000)
public abstract class VirtualCompartmentWaterClientMixin {
    @Shadow @Final private Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    @Shadow protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;
    @Shadow @Final private Set<TagKey<Fluid>> fluidOnEyes;
    @Shadow private FluidType forgeFluidTypeOnEyes;

    @Inject(method = "updateFluidHeightAndDoFluidPushing()V", at = @At("RETURN"))
    private void nautilus$restoreClientCompartmentWater(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide()) return;
        final int source = ClientWaterDiagnostics.enter(ClientWaterDiagnostics.Source.ENTITY_BODY_RESTORE);
        try {
            final double depth = ClientEntityWater.bodyDepth(entity);
            if (depth <= 0.0D) return;
            final FluidType water = NeoForgeMod.WATER_TYPE.value();
            // Preserve every native real/custom-fluid entry, adding only continuous water.
            forgeFluidTypeHeight.put(water, Math.max(forgeFluidTypeHeight.getDouble(water), depth));
            fluidHeight.put(FluidTags.WATER, Math.max(fluidHeight.getDouble(FluidTags.WATER), depth));
        } finally {
            ClientWaterDiagnostics.leave(source);
        }
    }

    @Inject(method = "updateFluidOnEyes", at = @At("TAIL"))
    private void nautilus$restoreClientCompartmentWaterOnEyes(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide()) return;
        final int source = ClientWaterDiagnostics.enter(ClientWaterDiagnostics.Source.ENTITY_EYES_RESTORE);
        try {
            if (forgeFluidTypeOnEyes != NeoForgeMod.EMPTY_TYPE.value()
                    && forgeFluidTypeOnEyes != NeoForgeMod.WATER_TYPE.value()) return;
            if (ClientEntityWater.depthAt(entity, entity.getEyePosition()) > 0.0D) {
                fluidOnEyes.add(FluidTags.WATER);
                forgeFluidTypeOnEyes = NeoForgeMod.WATER_TYPE.value();
            }
        } finally {
            ClientWaterDiagnostics.leave(source);
        }
    }

    @Inject(method = "canStartSwimming", at = @At("RETURN"), cancellable = true)
    private void nautilus$allowClientVirtualSwimming(final CallbackInfoReturnable<Boolean> callback) {
        final Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide() || callback.getReturnValueZ()) return;
        final FluidType water = NeoForgeMod.WATER_TYPE.value();
        if (entity.getEyeInFluidType() == water && entity.canSwimInFluidType(water)
                && ClientEntityWater.depthAt(entity, entity.position()) > 0.0D) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void nautilus$updateClientCompartmentSwimming(final CallbackInfo callback) {
        final Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide()) return;
        if (!ClientEntityWater.hasBodyCompartment(entity)) return;
        // These are Entity's native start/continue conditions. Apply them before Sable's
        // unconditional occlusion wrapper converts every setSwimming(true) to false.
        final boolean wet = entity.isSwimming()
                ? entity.isInWater() || entity.isInFluidType((type, height) -> entity.canSwimInFluidType(type))
                : entity.isUnderWater() || entity.canStartSwimming();
        entity.setSwimming(entity.isSprinting() && wet && !entity.isPassenger());
        callback.cancel();
    }
}

