package com.create.nautilus.client.render;

import com.create.nautilus.hydrogen.GasPumpBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** Only the central cog rotates; the pipe collars and pump housing stay static. */
public final class GasPumpRenderer extends KineticBlockEntityRenderer<GasPumpBlockEntity> {
    public GasPumpRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final GasPumpBlockEntity blockEntity,
                              final float partialTicks,
                              final PoseStack poseStack,
                              final MultiBufferSource buffers,
                              final int light,
                              final int overlay) {
        // This BE does not register a Flywheel visual.  The inherited renderer
        // returns early when Flywheel is active, so render the one owned moving
        // part explicitly, just like the other Nautilus machines.
        final BlockState state = blockEntity.getBlockState();
        final SuperByteBuffer gear = CachedBuffers.partialFacing(
                NautilusPartialModels.GAS_PUMP_GEAR, state);
        renderRotatingBuffer(blockEntity, gear, poseStack,
                buffers.getBuffer(RenderType.solid()), light);
    }
}
