package com.create.nautilus.client;

import com.create.nautilus.content.oxygen.ElectrolyticGeneratorBlockEntity;
import com.create.nautilus.hydrogen.BoilerHydrogenBoosterBlockEntity;
import com.create.nautilus.hydrogen.CompressorBlockEntity;
import com.create.nautilus.hydrogen.EmergencyVentBlockEntity;
import com.create.nautilus.hydrogen.GasTankBlockEntity;
import com.create.nautilus.hydrogen.HydrogenBoosterBlockEntity;
import com.create.nautilus.hydrogen.PressureRegulatorBlockEntity;
import com.create.nautilus.registry.NautilusBlocks;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/** Adds stable machine capacities to block-item tooltips, including JEI's item tooltip view. */
public final class NautilusItemTooltips {
    private NautilusItemTooltips() {
    }

    /**
     * Item tooltips are also consumed by JEI and do not require a JEI API dependency.
     * The event can be fired without a player, so this handler deliberately reads only the
     * item stack and machine capacities. Gas-tank capacity follows Create's server
     * configuration and is shown only once that configuration has been loaded/synced.
     */
    public static void onItemTooltip(final ItemTooltipEvent event) {
        final ItemStack stack = event.getItemStack();
        final List<Component> tooltip = event.getToolTip();
        if (stack.is(NautilusBlocks.ELECTROLYTIC_GENERATOR.get().asItem())) {
            add(tooltip, "create_nautilus.tooltip.capacity.water",
                    ElectrolyticGeneratorBlockEntity.waterCapacity());
            add(tooltip, "create_nautilus.tooltip.capacity.oxygen",
                    ElectrolyticGeneratorBlockEntity.gasCapacity());
            add(tooltip, "create_nautilus.tooltip.capacity.hydrogen",
                    ElectrolyticGeneratorBlockEntity.gasCapacity());
        } else if (stack.is(NautilusBlocks.COMPRESSOR.get().asItem())) {
            add(tooltip, "create_nautilus.tooltip.capacity.compressor_input",
                    CompressorBlockEntity.internalCapacity());
            add(tooltip, "create_nautilus.tooltip.capacity.compressor_output",
                    CompressorBlockEntity.internalCapacity());
        } else if (stack.is(NautilusBlocks.PRESSURE_REGULATOR.get().asItem())) {
            add(tooltip, "create_nautilus.tooltip.capacity.regulator_input",
                    PressureRegulatorBlockEntity.capacity());
            add(tooltip, "create_nautilus.tooltip.capacity.regulator_output",
                    PressureRegulatorBlockEntity.capacity());
        } else if (stack.is(NautilusBlocks.HYDROGEN_BOOSTER.get().asItem())) {
            add(tooltip, "create_nautilus.tooltip.capacity.hydrogen",
                    HydrogenBoosterBlockEntity.capacity());
        } else if (stack.is(NautilusBlocks.BOILER_HYDROGEN_BOOSTER.get().asItem())) {
            add(tooltip, "create_nautilus.tooltip.capacity.hydrogen",
                    BoilerHydrogenBoosterBlockEntity.capacity());
        } else if (stack.is(NautilusBlocks.EMERGENCY_VENT.get().asItem())) {
            add(tooltip, "create_nautilus.tooltip.capacity.vent_buffer",
                    EmergencyVentBlockEntity.bufferCapacity());
        } else if (stack.is(NautilusBlocks.GAS_TANK.get().asItem())
                && AllConfigs.server().specification.isLoaded()) {
            add(tooltip, "create_nautilus.tooltip.capacity.gas_tank_per_block",
                    GasTankBlockEntity.capacityPerBlock());
        }
    }

    private static void add(final List<Component> tooltip, final String key, final int capacity) {
        tooltip.add(Component.translatable(key,
                CreateLang.number(capacity).style(ChatFormatting.GOLD).component())
                .withStyle(ChatFormatting.GRAY));
    }
}
