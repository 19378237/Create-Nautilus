package com.create.nautilus.hydrogen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

import java.util.function.Function;

/** Renders a real RPM-driven Create half shaft on a Nautilus gas machine. */
public final class HydrogenShaftRenderer<T extends KineticBlockEntity>
        extends KineticBlockEntityRenderer<T> {
    private final Function<T, Direction> shaftFace;

    public HydrogenShaftRenderer(final BlockEntityRendererProvider.Context context,
                                 final Function<T, Direction> shaftFace) {
        super(context);
        this.shaftFace = shaftFace;
    }

    @Override
    protected void renderSafe(final T blockEntity, final float partialTicks,
                              final PoseStack poseStack,
                              final MultiBufferSource buffers,
                              final int light, final int overlay) {
        final SuperByteBuffer shaft = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF, blockEntity.getBlockState(),
                shaftFace.apply(blockEntity));
        renderRotatingBuffer(blockEntity, shaft, poseStack,
                buffers.getBuffer(RenderType.solid()), light);
    }
}
