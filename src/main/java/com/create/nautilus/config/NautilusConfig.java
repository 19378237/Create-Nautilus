package com.create.nautilus.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-authoritative settings which are intentionally exposed to players.
 *
 * <p>Values which are part of the simulation model, rather than gameplay
 * tuning knobs, are kept as fixed defaults in the accessors below. They are
 * deliberately not registered in the config spec, so old files cannot change
 * the physics accidentally and new files stay small and comprehensible.</p>
 */
public final class NautilusConfig {
    public static final ModConfigSpec SPEC;
    private static final Values VALUES;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        VALUES = new Values(builder);
        SPEC = builder.build();
    }

    private NautilusConfig() {
    }

    public static final double OPEN_TOP_AIR_DISPLACEMENT_EFFICIENCY = 1.0D;
    public static final double SEALED_AIR_DISPLACEMENT_EFFICIENCY = 1.0D;
    public static final double MINIMUM_USEFUL_AIR_FRACTION = 0.05D;
    public static final double FULL_AIR_EFFICIENCY_FRACTION = 0.25D;
    public static final double DRAG_COEFFICIENT = 0.8D;
    public static final double WATER_DENSITY = 1_000.0D;
    public static final double ADDED_MASS_COEFFICIENT = 0.5D;
    public static final double HEAVE_DAMPING_COEFFICIENT = 0.35D;
    public static final double FLOOD_SPEED = 0.05D;
    public static final double SURFACE_PRESSURE_KPA = 101.325D;
    public static final double PRESSURE_EQUALIZATION_RATE = 0.35D;
    public static final double WATER_CONSUMPTION_RATE = 50.0D;
    public static final double OXYGEN_PER_SU = 2.0D;
    public static final long ELECTROLYZER_CAPACITY_MB = 8_000L;
    public static final double SNORKEL_OXYGEN_RATE = 500.0D;
    public static final double OXYGEN_INTERFACE_RATE = 500.0D;
    public static final double GAS_TANK_CAPACITY_MULTIPLIER = 1.0D;
    public static final double COMPRESSOR_GAS_RATE = 1_000.0D;
    public static final double HYDROGEN_BOOSTER_CONSUMPTION_RATE = 15.0D;
    public static final double BOILER_HYDROGEN_CONSUMPTION_RATE = 25.0D;

    public static double buoyancyCoefficient() {
        return VALUES.buoyancyCoefficient.get();
    }

    public static int maximumCompartmentVolume() {
        return VALUES.maximumCompartmentVolume.get();
    }

    public static int compartmentScanIntervalTicks() {
        return VALUES.compartmentScanIntervalTicks.get();
    }

    public static double airDensityPerBlock() {
        return VALUES.airDensityPerBlock.get();
    }

    public static double oxygenConsumptionRate() {
        return VALUES.oxygenConsumptionRate.get();
    }

    public static double ballastTransferRate() {
        return VALUES.ballastTransferRate.get();
    }

    public static double propellerDeepThrustBonus() {
        return VALUES.propellerDeepThrustBonus.get();
    }

    public static double impactSpeedThreshold() {
        return VALUES.impactSpeedThreshold.get();
    }

    public static double hydrogenBoosterThrustBonus() {
        return VALUES.hydrogenBoosterThrustBonus.get();
    }

    public static double emergencyVentRate() {
        return VALUES.emergencyVentRate.get();
    }

    public static double maxFloodRateMbPerSecond() {
        return VALUES.maxFloodRateMbPerSecond.get();
    }

    /** Number of temporary Create heat levels supplied by one active booster. */
    public static int boilerHydrogenHeatLevels() {
        return VALUES.boilerHydrogenHeatLevels.get();
    }

    public static int boilerBoosterMaxCount() {
        return VALUES.boilerBoosterMaxCount.get();
    }

    private static final class Values {
        private final ModConfigSpec.DoubleValue buoyancyCoefficient;
        private final ModConfigSpec.IntValue maximumCompartmentVolume;
        private final ModConfigSpec.IntValue compartmentScanIntervalTicks;
        private final ModConfigSpec.DoubleValue airDensityPerBlock;
        private final ModConfigSpec.DoubleValue oxygenConsumptionRate;
        private final ModConfigSpec.DoubleValue ballastTransferRate;
        private final ModConfigSpec.DoubleValue propellerDeepThrustBonus;
        private final ModConfigSpec.DoubleValue impactSpeedThreshold;
        private final ModConfigSpec.DoubleValue hydrogenBoosterThrustBonus;
        private final ModConfigSpec.DoubleValue emergencyVentRate;
        private final ModConfigSpec.DoubleValue maxFloodRateMbPerSecond;
        private final ModConfigSpec.IntValue boilerHydrogenHeatLevels;
        private final ModConfigSpec.IntValue boilerBoosterMaxCount;

        private Values(final ModConfigSpec.Builder builder) {
            builder.comment("Create: Nautilus hydrodynamics settings")
                    .push("hydrodynamics");
            buoyancyCoefficient = builder
                    .comment("Global multiplier for the target water displacement.")
                    .translation("create_nautilus.config.buoyancyCoefficient")
                    .defineInRange("buoyancyCoefficient", 1.0D, 0.0D, 10.0D);
            builder.pop();

            builder.comment("Flooding and compartment detection settings")
                    .push("flooding");
            maximumCompartmentVolume = builder
                    .comment("Flood-fill safety limit in blocks; larger candidate spaces are treated as unsealed.")
                    .translation("create_nautilus.config.maximumCompartmentVolume")
                    .defineInRange("maximumCompartmentVolume", 131_072, 1_024, 16_777_216);
            compartmentScanIntervalTicks = builder
                    .comment("Ticks between full airtight-compartment voxel scans.")
                    .translation("create_nautilus.config.compartmentScanIntervalTicks")
                    .defineInRange("compartmentScanIntervalTicks", 20, 1, 1_200);
            maxFloodRateMbPerSecond = builder
                    .comment("Maximum water flow through one hull breach, in mB/s. This limits extreme-depth spikes without changing the normal hydraulic curve.")
                    .translation("create_nautilus.config.maxFloodRateMbPerSecond")
                    .defineInRange("maxFloodRateMbPerSecond", 8_000.0D, 1.0D, 1_000_000.0D);
            builder.pop();

            builder.comment("Closed-compartment oxygen settings; gas amounts use mB (one mB is treated as one litre).")
                    .push("oxygen");
            airDensityPerBlock = builder
                    .comment("Initial oxygen storage assigned per dry compartment block, in mB.")
                    .translation("create_nautilus.config.airDensityPerBlock")
                    .defineInRange("airDensityPerBlock", 200.0D, 0.0D, 100_000.0D);
            oxygenConsumptionRate = builder
                    .comment("Oxygen consumed by one player per second, in mB.")
                    .translation("create_nautilus.config.oxygenConsumptionRate")
                    .defineInRange("oxygenConsumptionRate", 100.0D, 0.0D, 100_000.0D);
            builder.pop();

            builder.comment("Mechanical power features")
                    .push("kinetics");
            ballastTransferRate = builder
                    .comment("Maximum ballast water moved at 256 RPM, in mB/s.")
                    .translation("create_nautilus.config.ballastTransferRate")
                    .defineInRange("ballastTransferRateV2", 1_000.0D, 0.0D, 1_000.0D);
            propellerDeepThrustBonus = builder
                    .comment("Maximum fractional thrust bonus above 70% carrier submersion.")
                    .translation("create_nautilus.config.propellerDeepThrustBonus")
                    .defineInRange("propellerDeepThrustBonus", 0.25D, 0.0D, 10.0D);
            builder.pop();

            builder.comment("Universal mechanical gas and hydrogen settings")
                    .push("hydrogen");
            impactSpeedThreshold = builder
                    .comment("Minimum collision-relative speed in m/s that can rupture a hydrogen gas tank.")
                    .translation("create_nautilus.config.impactSpeedThreshold")
                    .defineInRange("impactSpeedThreshold", 5.0D, 0.1D, 1_000.0D);
            hydrogenBoosterThrustBonus = builder
                    .comment("Fractional thrust bonus supplied to an adjacent Aeronautics propeller.")
                    .translation("create_nautilus.config.hydrogenBoosterThrustBonus")
                    .defineInRange("hydrogenBoosterThrustBonus", 0.30D, 0.0D, 10.0D);
            emergencyVentRate = builder
                    .comment("Gas released by a powered emergency vent in mB/s.")
                    .translation("create_nautilus.config.emergencyVentRate")
                    .defineInRange("emergencyVentRateV2", 64_000.0D, 1.0D, 4_000_000.0D);
            boilerHydrogenHeatLevels = builder
                    .comment("Temporary Create heat levels added by one active boiler hydrogen booster. The boiler ceiling remains 18.")
                    .translation("create_nautilus.config.boilerHydrogenHeatLevels")
                    .defineInRange("boilerHydrogenHeatLevels", 2, 1, 18);
            boilerBoosterMaxCount = builder
                    .comment("Maximum number of active adjacent boiler hydrogen boosters that contribute heat to one boiler.")
                    .translation("create_nautilus.config.boilerBoosterMaxCount")
                    .defineInRange("boilerBoosterMaxCount", 1, 1, 64);
            builder.pop();
        }
    }
}
