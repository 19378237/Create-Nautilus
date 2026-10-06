package com.create.nautilus.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Shared renderer for a bottom shaft and an optional Y-axis mechanism. */
public abstract class VerticalMachineRenderer<T extends KineticBlockEntity>
        extends KineticBlockEntityRenderer<T> {
    private final PartialModel movingPart;

    protected VerticalMachineRenderer(final BlockEntityRendererProvider.Context context) {
        this(context, null);
    }

    protected VerticalMachineRenderer(final BlockEntityRendererProvider.Context context,
                                      final PartialModel movingPart) {
        super(context);
        this.movingPart = movingPart;
    }

    @Override
    protected void renderSafe(final T blockEntity,
                              final float partialTicks,
                              final PoseStack poseStack,
                              final MultiBufferSource buffers,
                              final int light,
                              final int overlay) {
        // Render explicitly rather than relying on Create's default renderer:
        // it intentionally returns early whenever Flywheel is active, while
        // these add-on block entities do not own a separate Flywheel visual.
        final SuperByteBuffer shaft = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF, blockEntity.getBlockState(), Direction.DOWN);
        renderRotatingBuffer(blockEntity, shaft, poseStack,
                buffers.getBuffer(RenderType.solid()), light);

        if (movingPart == null) {
            return;
        }

        final float angle = getAngleForBe(blockEntity, blockEntity.getBlockPos(), Direction.Axis.Y);
        kineticRotationTransform(CachedBuffers.partial(movingPart, blockEntity.getBlockState()),
                blockEntity, Direction.Axis.Y, angle, light)
                .renderInto(poseStack, buffers.getBuffer(RenderType.solid()));
    }
}
