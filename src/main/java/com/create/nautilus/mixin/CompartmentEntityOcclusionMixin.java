package com.create.nautilus.mixin;

import com.create.nautilus.physics.VirtualCompartmentWater;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.common.NeoForgeMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Filters only parent-world water, retaining Sable's complete native local-fluid sampler. */
@Mixin(value = Entity.class, priority = 1500)
public abstract class CompartmentEntityOcclusionMixin {
    @WrapMethod(method = "updateFluidHeightAndDoFluidPushing(Lnet/minecraft/tags/TagKey;D)Z")
    private boolean nautilus$sampleServerCompartmentFluids(final TagKey<Fluid> tag, final double scale,
                                                       final Operation<Boolean> original) {
        final Entity entity = (Entity) (Object) this;
        if (!(entity.level() instanceof ServerLevel) || tag != FluidTags.WATER) return original.call(tag, scale);
        if (VirtualCompartmentWater.serverHasBodyCompartment(entity)) {
            // Sable cancels the legacy entry point in an occlusion region. The NeoForge overload
            // still scans real fluids on the parent and every intersecting carrier, with currents.
            entity.updateFluidHeightAndDoFluidPushing();
            return entity.isInFluidType(NeoForgeMod.WATER_TYPE.value());
        }
        return original.call(tag, scale);
    }

    @WrapOperation(method = "updateFluidHeightAndDoFluidPushing()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;getHeight(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            ordinal = 0))
    private float nautilus$filterServerParentBodyWater(final FluidState fluid, final BlockGetter level,
                                                     final BlockPos cell, final Operation<Float> original) {
        final float height = original.call(fluid, level, cell);
        final Entity entity = (Entity) (Object) this;
        if (!(entity.level() instanceof ServerLevel) || !fluid.is(FluidTags.WATER)) return height;
        // The first invocation is the parent loop; Sable's second, plot-space loop stays native.
        return VirtualCompartmentWater.serverParentWaterHeight(entity, cell, height);
    }

    @WrapOperation(method = "updateFluidOnEyes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState nautilus$filterServerParentEyeWater(final Level level, final BlockPos cell,
                                                          final Operation<FluidState> original) {
        final FluidState fluid = original.call(level, cell);
        final Entity entity = (Entity) (Object) this;
        return fluid.is(FluidTags.WATER)
                && VirtualCompartmentWater.serverOccludesParentWater(entity, entity.getEyePosition())
                ? Fluids.EMPTY.defaultFluidState() : fluid;
    }

}

