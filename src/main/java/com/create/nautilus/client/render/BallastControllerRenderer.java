package com.create.nautilus.client.render;

import com.create.nautilus.content.submarine.BallastTankControllerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Only the bottom input shaft rotates; the control lever stays stationary. */
public final class BallastControllerRenderer
        extends VerticalMachineRenderer<BallastTankControllerBlockEntity> {
    public BallastControllerRenderer(final BlockEntityRendererProvider.Context context) {
        super(context);
    }
}
