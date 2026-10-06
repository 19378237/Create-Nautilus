package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.GasConnectionModel;
import com.create.nautilus.content.oxygen.ElectrolyticGeneratorBlock;
import com.create.nautilus.content.oxygen.OxygenInterfaceBlock;
import com.create.nautilus.content.oxygen.SnorkelBlock;
import com.create.nautilus.content.submarine.BallastTankControllerBlock;
import com.create.nautilus.hydrogen.*;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.AllCreativeModeTabs;
import com.simibubi.create.content.fluids.tank.FluidTankItem;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntry;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** Registrate block index for the mechanical oxygen and submarine facilities. */
public final class NautilusBlocks {
    private static final ResourceKey<CreativeModeTab> SIMULATED_CREATIVE_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    ResourceLocation.fromNamespaceAndPath("simulated", "main_tab"));
    private static final double MACHINE_STRESS_IMPACT = 4.0D;
    private static final double CONTROL_STRESS_IMPACT = 2.0D;
    private static final double GAS_MACHINE_STRESS_IMPACT = 4.0D;

    public static final BlockEntry<ElectrolyticGeneratorBlock> ELECTROLYTIC_GENERATOR =
            CreateNautilus.REGISTRATE.block("electrolytic_generator", ElectrolyticGeneratorBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    // Create's CStress transformer rejects non-Create namespaces. Add-ons use
                    // the public BlockStressValues registry instead.
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> MACHINE_STRESS_IMPACT))
                    // The imported inspection pane uses partial alpha, not a binary cutout.
                    .addLayer(() -> RenderType::translucent)
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<SnorkelBlock> SNORKEL =
            CreateNautilus.REGISTRATE.block("snorkel", SnorkelBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> CONTROL_STRESS_IMPACT))
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<OxygenInterfaceBlock> OXYGEN_INTERFACE =
            CreateNautilus.REGISTRATE.block("oxygen_interface", OxygenInterfaceBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .addLayer(() -> RenderType::cutoutMipped)
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<BallastTankControllerBlock> BALLAST_TANK_CONTROLLER =
            CreateNautilus.REGISTRATE.block("ballast_tank_controller", BallastTankControllerBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> MACHINE_STRESS_IMPACT))
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<GasTankBlock> GAS_TANK =
            CreateNautilus.REGISTRATE.block("gas_tank", GasTankBlock::new)
                    .properties(properties -> machineProperties(properties)
                            .isRedstoneConductor((state, level, pos) -> true))
                    .onRegister(CreateRegistrate.blockModel(() -> GasConnectionModel::tank))
                    // Clear windows have no coloured translucent overlay.
                    .addLayer(() -> RenderType::cutoutMipped)
                    .item(FluidTankItem::new)
                    .removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<GasPipeBlock> GAS_PIPE =
            CreateNautilus.REGISTRATE.block("gas_pipe", GasPipeBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    // Same body/attachment split as Create, but using gas-network
                    // neighbour data rather than FluidTransportBehaviour.
                    .onRegister(CreateRegistrate.blockModel(() -> GasConnectionModel::pipe))
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<GasPumpBlock> GAS_PUMP =
            CreateNautilus.REGISTRATE.block("gas_pump", GasPumpBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> GAS_MACHINE_STRESS_IMPACT))
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<CompressorBlock> COMPRESSOR =
            CreateNautilus.REGISTRATE.block("compressor", CompressorBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> GAS_MACHINE_STRESS_IMPACT))
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<PressureRegulatorBlock> PRESSURE_REGULATOR =
            CreateNautilus.REGISTRATE.block("pressure_regulator", PressureRegulatorBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<HydrogenBoosterBlock> HYDROGEN_BOOSTER =
            CreateNautilus.REGISTRATE.block("hydrogen_booster", HydrogenBoosterBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> GAS_MACHINE_STRESS_IMPACT))
                    .addLayer(() -> RenderType::cutoutMipped)
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<BoilerHydrogenBoosterBlock> BOILER_HYDROGEN_BOOSTER =
            CreateNautilus.REGISTRATE.block("boiler_hydrogen_booster", BoilerHydrogenBoosterBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .onRegister(block -> BlockStressValues.IMPACTS.register(block,
                            () -> GAS_MACHINE_STRESS_IMPACT))
                    .addLayer(() -> RenderType::cutoutMipped)
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    public static final BlockEntry<EmergencyVentBlock> EMERGENCY_VENT =
            CreateNautilus.REGISTRATE.block("emergency_vent", EmergencyVentBlock::new)
                    .properties(NautilusBlocks::machineProperties)
                    .item().removeTab(CreativeModeTabs.SEARCH)
                    .removeTab(AllCreativeModeTabs.BASE_CREATIVE_TAB.getKey())
                    .removeTab(SIMULATED_CREATIVE_TAB).build()
                    .register();

    private NautilusBlocks() {
    }

    private static BlockBehaviour.Properties machineProperties(final BlockBehaviour.Properties properties) {
        return properties.mapColor(MapColor.METAL)
                .strength(3.0F, 6.0F)
                .sound(SoundType.COPPER)
                .noOcclusion();
    }

    /** Forces class loading; entries themselves are submitted by Registrate. */
    public static void register() {
    }
}
