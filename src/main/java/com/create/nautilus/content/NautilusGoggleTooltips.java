package com.create.nautilus.content;

import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

/** Create's tank layout for machines whose empty input/output tanks must remain visible. */
public final class NautilusGoggleTooltips {
    private NautilusGoggleTooltips() {
    }

    /** The empty-tank labels follow the handler's fixed tank order. */
    public static boolean addTanks(final List<Component> tooltip, final IFluidHandler handler,
                                   final String... emptyTankNameKeys) {
        if (handler == null || handler.getTanks() == 0) {
            return false;
        }

        CreateLang.translate("gui.goggles.fluid_container").forGoggles(tooltip);
        final LangBuilder millibuckets = CreateLang.translate("generic.unit.millibuckets");
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            final FluidStack fluid = handler.getFluidInTank(tank);
            final LangBuilder name = fluid.isEmpty()
                    ? CreateLang.builder().add(Component.translatable(emptyTankNameKeys[tank]))
                    : CreateLang.fluidName(fluid);
            name.style(ChatFormatting.GRAY).forGoggles(tooltip, 1);
            // Match containedFluidTooltip's colours, number formatting and indentation.
            CreateLang.builder()
                    .add(CreateLang.number(fluid.getAmount()).add(millibuckets)
                            .style(ChatFormatting.GOLD))
                    .text(ChatFormatting.GRAY, " / ")
                    .add(CreateLang.number(handler.getTankCapacity(tank)).add(millibuckets)
                            .style(ChatFormatting.DARK_GRAY))
                    .forGoggles(tooltip, 1);
        }
        return true;
    }
}
