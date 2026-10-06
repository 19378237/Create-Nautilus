package com.create.nautilus.client.render;

import com.create.nautilus.content.oxygen.SnorkelBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Renders the mechanically driven intake fan at the top of the snorkel. */
public final class SnorkelRenderer extends VerticalMachineRenderer<SnorkelBlockEntity> {
    public SnorkelRenderer(final BlockEntityRendererProvider.Context context) {
        super(context, NautilusPartialModels.SNORKEL_FAN);
    }
}
