package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.content.oxygen.*;
import com.create.nautilus.content.submarine.BallastTankControllerBlockEntity;
import com.create.nautilus.hydrogen.*;
import com.simibubi.create.content.fluids.tank.FluidTankRenderer;
import com.create.nautilus.client.render.ElectrolyticGeneratorRenderer;
import com.create.nautilus.client.render.GasPumpRenderer;
import com.create.nautilus.client.render.SnorkelRenderer;
import com.create.nautilus.client.render.BallastControllerRenderer;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Registrate block-entity index and NeoForge fluid capability bridge. */
public final class NautilusBlockEntityTypes {
    public static final BlockEntityEntry<ElectrolyticGeneratorBlockEntity> ELECTROLYTIC_GENERATOR = CreateNautilus.REGISTRATE
            .blockEntity("electrolytic_generator", ElectrolyticGeneratorBlockEntity::new)
            .validBlocks(NautilusBlocks.ELECTROLYTIC_GENERATOR)
            .renderer(() -> ElectrolyticGeneratorRenderer::new).register();
    public static final BlockEntityEntry<SnorkelBlockEntity> SNORKEL = CreateNautilus.REGISTRATE
            .blockEntity("snorkel", SnorkelBlockEntity::new).validBlocks(NautilusBlocks.SNORKEL)
            .renderer(() -> SnorkelRenderer::new).register();
    public static final BlockEntityEntry<OxygenInterfaceBlockEntity> OXYGEN_INTERFACE = CreateNautilus.REGISTRATE
            .blockEntity("oxygen_interface", OxygenInterfaceBlockEntity::new).validBlocks(NautilusBlocks.OXYGEN_INTERFACE).register();
    public static final BlockEntityEntry<BallastTankControllerBlockEntity> BALLAST_TANK_CONTROLLER = CreateNautilus.REGISTRATE
            .blockEntity("ballast_tank_controller", BallastTankControllerBlockEntity::new).validBlocks(NautilusBlocks.BALLAST_TANK_CONTROLLER)
            .renderer(() -> BallastControllerRenderer::new).register();
    public static final BlockEntityEntry<GasTankBlockEntity> GAS_TANK = CreateNautilus.REGISTRATE
            .blockEntity("gas_tank", GasTankBlockEntity::new)
            .validBlocks(NautilusBlocks.GAS_TANK)
            .renderer(() -> FluidTankRenderer::new)
            .register();
    public static final BlockEntityEntry<GasPipeBlockEntity> GAS_PIPE = CreateNautilus.REGISTRATE
            .blockEntity("gas_pipe", GasPipeBlockEntity::new)
            .validBlocks(NautilusBlocks.GAS_PIPE)
            .register();
    public static final BlockEntityEntry<GasPumpBlockEntity> GAS_PUMP = CreateNautilus.REGISTRATE
            .blockEntity("gas_pump", GasPumpBlockEntity::new)
            .validBlocks(NautilusBlocks.GAS_PUMP)
            .renderer(() -> GasPumpRenderer::new)
            .register();
    public static final BlockEntityEntry<CompressorBlockEntity> COMPRESSOR = CreateNautilus.REGISTRATE
            .blockEntity("compressor", CompressorBlockEntity::new)
            .validBlocks(NautilusBlocks.COMPRESSOR)
            .renderer(() -> context -> new HydrogenShaftRenderer<>(context, be -> net.minecraft.core.Direction.DOWN))
            .register();
    public static final BlockEntityEntry<PressureRegulatorBlockEntity> PRESSURE_REGULATOR = CreateNautilus.REGISTRATE
            .blockEntity("pressure_regulator", PressureRegulatorBlockEntity::new)
            .validBlocks(NautilusBlocks.PRESSURE_REGULATOR)
            .register();
    public static final BlockEntityEntry<HydrogenBoosterBlockEntity> HYDROGEN_BOOSTER = CreateNautilus.REGISTRATE
            .blockEntity("hydrogen_booster", HydrogenBoosterBlockEntity::new)
            .validBlocks(NautilusBlocks.HYDROGEN_BOOSTER)
            .renderer(() -> context -> new HydrogenShaftRenderer<>(context,
                    be -> be.getBlockState().getValue(HydrogenBoosterBlock.FACING).getOpposite()))
            .register();
    public static final BlockEntityEntry<BoilerHydrogenBoosterBlockEntity> BOILER_HYDROGEN_BOOSTER = CreateNautilus.REGISTRATE
            .blockEntity("boiler_hydrogen_booster", BoilerHydrogenBoosterBlockEntity::new)
            .validBlocks(NautilusBlocks.BOILER_HYDROGEN_BOOSTER)
            .renderer(() -> context -> new HydrogenShaftRenderer<>(context,
                    be -> be.getBlockState().getValue(BoilerHydrogenBoosterBlock.FACING).getOpposite()))
            .register();
    public static final BlockEntityEntry<EmergencyVentBlockEntity> EMERGENCY_VENT = CreateNautilus.REGISTRATE
            .blockEntity("emergency_vent", EmergencyVentBlockEntity::new)
            .validBlocks(NautilusBlocks.EMERGENCY_VENT)
            .register();

    private NautilusBlockEntityTypes() {}
    public static void register() {}
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        ElectrolyticGeneratorBlockEntity.registerCapabilities(event);
        event.registerBlockEntity(GasCapabilities.HANDLER, OXYGEN_INTERFACE.get(),
                (be, side) -> be.gasHandler());
        event.registerBlockEntity(GasCapabilities.HANDLER, GAS_TANK.get(),
                (be, side) -> be.gasHandler());
        CompressorBlockEntity.registerCapabilities(event);
        PressureRegulatorBlockEntity.registerCapabilities(event);
        HydrogenBoosterBlockEntity.registerCapabilities(event);
        BoilerHydrogenBoosterBlockEntity.registerCapabilities(event);
        EmergencyVentBlockEntity.registerCapabilities(event);
    }
}
