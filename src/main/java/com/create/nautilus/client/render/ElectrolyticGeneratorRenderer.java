package com.create.nautilus.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.create.nautilus.content.oxygen.ElectrolyticGeneratorBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real-RPM bottom shaft and oxygen-production display for the electrolyzer. */
public final class ElectrolyticGeneratorRenderer
        extends KineticBlockEntityRenderer<ElectrolyticGeneratorBlockEntity> {
    private static final float PROGRESS_ANCHOR_Y = 5.34F / 16.0F;

    public ElectrolyticGeneratorRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(final ElectrolyticGeneratorBlockEntity blockEntity,
                              final float partialTicks,
                              final PoseStack poseStack,
                              final MultiBufferSource buffers,
                              final int light,
                              final int overlay) {
        // Do not delegate to KineticBlockEntityRenderer: its default path skips
        // rendering when Flywheel is available, but this add-on BE has no visual.
        final BlockState state = blockEntity.getBlockState();
        final SuperByteBuffer shaft = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF, state, Direction.DOWN);
        renderRotatingBuffer(blockEntity, shaft, poseStack,
                buffers.getBuffer(RenderType.solid()), light);

        final float progress = Mth.clamp(
                blockEntity.productionProgress(partialTicks), 0.0F, 1.0F);
        if (progress <= 0.0F) {
            return;
        }

        final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // Catnip's horizontalAngle assumes a SOUTH-authored partial; this one
        // faces NORTH (+180). The window's extra clockwise quarter-turn in
        // blockstate JSON subtracts 90 from Catnip's mathematical angle, so the
        // combined offset is +90. Keep the stem on the window, off the gas axis.
        final float yRotation = AngleHelper.horizontalAngle(facing) + 90.0F;

        // The window's internal stem extends upward from Y=5.34. Keep its
        // foot fixed while scaling only the stem height in model-local Y.
        // Facing rotation composes R * T(anchor) * S * T(-anchor).
        CachedBuffers.partial(NautilusPartialModels.ELECTROLYZER_PROGRESS, state)
                .rotateYCenteredDegrees(yRotation)
                .translate(0.0F, PROGRESS_ANCHOR_Y, 0.0F)
                .scale(1.0F, progress, 1.0F)
                .translate(0.0F, -PROGRESS_ANCHOR_Y, 0.0F)
                .light(light)
                .renderInto(poseStack, buffers.getBuffer(RenderType.cutoutMipped()));
    }
}
