package com.create.nautilus.kinetics;

import com.create.nautilus.api.NautilusAPI;
import com.create.nautilus.api.PhysicallySimulatedEntity;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.hydrogen.HydrogenBoosterBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

/** Hydrodynamic efficiency curve shared by both Aeronautics propeller families. */
public final class PropellerEfficiency {
    private PropellerEfficiency() {
    }

    public static boolean isAeronauticsPropeller(final KineticBlockEntity blockEntity) {
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            final String name = type.getName();
            if (name.equals("dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity")
                    || name.equals("dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity")) {
                return true;
            }
        }
        return false;
    }

    public static double thrustMultiplier(final KineticBlockEntity blockEntity) {
        final double submerged = carrierSubmersion(blockEntity);
        final double hydrogenMultiplier = HydrogenBoosterBlockEntity.hasActiveBooster(blockEntity)
                ? 1.0D + NautilusConfig.hydrogenBoosterThrustBonus() : 1.0D;
        if (submerged < 0.0D) {
            return hydrogenMultiplier;
        }
        if (submerged < 0.30D) {
            return 0.20D * hydrogenMultiplier;
        }
        if (submerged <= 0.70D) {
            return hydrogenMultiplier;
        }

        // Deeply submerged blades gain thrust, but cavitation prevents a
        // linear increase.  The asymptote is 1.25 times airborne thrust.
        final double t = (submerged - 0.70D) / 0.30D;
        return (1.0D + NautilusConfig.propellerDeepThrustBonus()
                * (1.0D - Math.exp(-3.0D * t))) * hydrogenMultiplier;
    }

    private static double carrierSubmersion(final KineticBlockEntity blockEntity) {
        if (blockEntity.getLevel() == null) {
            return -1.0D;
        }
        final SubLevel containing = Sable.HELPER.getContaining(
                blockEntity.getLevel(), blockEntity.getBlockPos());
        if (!(containing instanceof final ServerSubLevel carrier)) {
            return -1.0D;
        }
        // Use the public snapshot API instead of reaching into CarrierState;
        // add-ons therefore observe exactly the same efficiency input.
        return NautilusAPI.get().getSubmersionDepth(PhysicallySimulatedEntity.of(carrier));
    }
}
