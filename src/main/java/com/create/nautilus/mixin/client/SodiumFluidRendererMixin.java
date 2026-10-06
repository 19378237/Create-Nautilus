package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientDryCompartmentCache;
import com.create.nautilus.client.render.SodiumFluidMaterialMarker;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the short interval in which Sodium encodes one fluid quad. */
@Pseudo
@Mixin(targets =
        "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer",
        remap = false)
public abstract class SodiumFluidRendererMixin {
    /**
     * Removes parent-ocean quads from dry compartment cells while Sodium builds chunk meshes.
     * This is renderer-agnostic: Iris and Colorwheel consume the same Sodium chunk geometry, so
     * the cutout also works while a shader pack is active.
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void createNautilus$skipDryCompartmentWater(
            @Coerce final Object level,
            final BlockState blockState,
            final FluidState fluidState,
            final BlockPos pos,
            @Coerce final Object offset,
            @Coerce final Object collector,
            @Coerce final Object meshBuilder,
            @Coerce final Object material,
            @Coerce final Object colorProvider,
            final TextureAtlasSprite[] sprites,
            final CallbackInfo callback) {
        if (fluidState.is(FluidTags.WATER)
                && !createNautilus$shaderPackActive()
                && ClientDryCompartmentCache.isParentWaterBlockSuppressed(pos)) {
            // Also self-heal a build thread if a previous exceptional render missed RETURN.
            SodiumFluidMaterialMarker.exitFluidRender();
            callback.cancel();
            return;
        }
        SodiumFluidMaterialMarker.enterFluidRender(fluidState.is(FluidTags.WATER));
    }

    /** Balances the per-render water identity used while {@code writeQuad} resolves Material.bits. */
    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void createNautilus$exitFluidRender(
            @Coerce final Object level,
            final BlockState blockState,
            final FluidState fluidState,
            final BlockPos pos,
            @Coerce final Object offset,
            @Coerce final Object collector,
            @Coerce final Object meshBuilder,
            @Coerce final Object material,
            @Coerce final Object colorProvider,
            final TextureAtlasSprite[] sprites,
            final CallbackInfo callback) {
        SodiumFluidMaterialMarker.exitFluidRender();
    }

    /**
     * A shader pack has an exact fragment mask.  Cancelling its source water block here would
     * permanently remove every pixel in that block before the atlas can classify it.
     */
    private static boolean createNautilus$shaderPackActive() {
        return WaterOcclusionPassBridge.isShaderPackActive();
    }

    @Inject(method = "writeQuad", at = @At("HEAD"), require = 0)
    private void createNautilus$enterFluidQuad(final CallbackInfo callback) {
        SodiumFluidMaterialMarker.enterFluidQuad();
    }

    @Inject(method = "writeQuad", at = @At("RETURN"), require = 0)
    private void createNautilus$exitFluidQuad(final CallbackInfo callback) {
        SodiumFluidMaterialMarker.exitFluidQuad();
    }
}
