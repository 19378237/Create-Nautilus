package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import com.tterrag.registrate.util.entry.BlockEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Places every Nautilus block item in the Aeronautics/Simulated submarine section. */
public final class NautilusSimulatedTab {
    private static final ResourceLocation SUBMARINE_SECTION =
            CreateNautilus.id("submarine");
    private static boolean registered;

    private NautilusSimulatedTab() {
    }

    public static synchronized void registerItems() {
        if (registered) {
            return;
        }
        registered = true;

        // SimulatedRegistrate normally records this mapping while an addon
        // registers its items. Nautilus uses CreateRegistrate as required by
        // Create, so bridge the public Simulated tab API explicitly.
        final List<BlockEntry<? extends Block>> entries = List.of(
                NautilusBlocks.ELECTROLYTIC_GENERATOR,
                NautilusBlocks.SNORKEL,
                NautilusBlocks.OXYGEN_INTERFACE,
                NautilusBlocks.BALLAST_TANK_CONTROLLER,
                NautilusBlocks.GAS_TANK,
                NautilusBlocks.GAS_PIPE,
                NautilusBlocks.GAS_PUMP,
                NautilusBlocks.COMPRESSOR,
                NautilusBlocks.PRESSURE_REGULATOR,
                NautilusBlocks.HYDROGEN_BOOSTER,
                NautilusBlocks.BOILER_HYDROGEN_BOOSTER,
                NautilusBlocks.EMERGENCY_VENT);
        bridgeToBundledSimulated(entries);
    }

    /**
     * Aeronautics distributes Simulated as a nested JarJar module, so its
     * otherwise-public registration class is intentionally absent from the
     * outer development artifact's compile classpath. Resolve those two public
     * collections at runtime instead of bundling a second Simulated copy.
     */
    @SuppressWarnings("unchecked")
    private static void bridgeToBundledSimulated(
            final List<BlockEntry<? extends Block>> entries) {
        try {
            final Class<?> registrate = Class.forName(
                    "dev.simulated_team.simulated.registrate.SimulatedRegistrate");
            final Map<ResourceLocation, ResourceLocation> sections =
                    (Map<ResourceLocation, ResourceLocation>) registrate
                            .getField("ITEM_TO_SECTION").get(null);
            final List<Supplier<Item>> tabItems = (List<Supplier<Item>>) registrate
                    .getField("TAB_ITEMS").get(null);
            for (final BlockEntry<? extends Block> entry : entries) {
                sections.put(entry.getId(), SUBMARINE_SECTION);
                tabItems.add(() -> entry.get().asItem());
            }
        } catch (final ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Aeronautics' bundled Simulated tab API is unavailable", exception);
        }
    }
}
