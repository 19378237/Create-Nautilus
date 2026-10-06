package com.create.nautilus.mixin.client;

import com.create.nautilus.client.render.ClientDryCompartmentCache;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes WATER-tagged fluid geometry stored in a Sable carrier plot when the
 * server has explicitly classified that voxel as dry.
 *
 * <p>The earlier {@code BlockRenderDispatcher} entry is intentional:
 * NeoForge custom fluids may render themselves and bypass
 * {@code LiquidBlockRenderer#tesselate}. This hook therefore covers both
 * vanilla water and custom WATER-tagged fluids.</p>
 *
 * <p>Parent-world ocean geometry is handled separately by the lifecycle-managed Sable regions in
 * {@code DryCompartmentSync}; this mixin covers fluid states stored inside the carrier plot.</p>
 */
@Mixin(value = BlockRenderDispatcher.class, priority = 1000)
public abstract class BlockRenderDispatcherMixin {
    @Inject(
            method = "renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void createNautilus$skipDryCompartmentWater(
            final BlockPos pos,
            final BlockAndTintGetter level,
            final VertexConsumer consumer,
            final BlockState blockState,
            final FluidState fluidState,
            final CallbackInfo callback) {
        if (fluidState.is(FluidTags.WATER)
                && ClientDryCompartmentCache.shouldSuppressWater(level, pos)) {
            callback.cancel();
        }
    }
}
