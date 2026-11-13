package com.create.nautilus.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class BallastTankControllerScreen
        extends AbstractContainerScreen<BallastTankControllerMenu> {
    public BallastTankControllerScreen(final BallastTankControllerMenu menu,
                                      final Inventory inventory, final Component title) {
        super(menu, inventory, title);
        imageHeight = 120;
    }

    @Override
    protected void init() {
        super.init();
        for (int id = 0; id < 4; id++) {
            final int button = id;
            addRenderableWidget(Button.builder(Component.literal(id % 2 == 0 ? "-" : "+"),
                            ignored -> minecraft.gameMode.handleInventoryButtonClick(
                                    menu.containerId, button))
                    .bounds(leftPos + 80 + (id % 2) * 24,
                            topPos + 28 + (id / 2) * 30, 20, 20).build());
        }
    }

    @Override
    protected void renderBg(final GuiGraphics graphics, final float partialTick,
                            final int mouseX, final int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff26343a);
    }

    @Override
    protected void renderLabels(final GuiGraphics graphics, final int mouseX, final int mouseY) {
        graphics.drawString(font, title, 8, 8, 0xffffff, false);
        graphics.drawString(font, Component.translatable(
                "create_nautilus.gui.ballast.fore", menu.fore()), 8, 34, 0x66ddff, false);
        graphics.drawString(font, Component.translatable(
                "create_nautilus.gui.ballast.aft", menu.aft()), 8, 64, 0x66ddff, false);
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY,
                       final float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
