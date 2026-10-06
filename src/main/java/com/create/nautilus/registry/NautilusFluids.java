package com.create.nautilus.registry;

import com.create.nautilus.hydrogen.HydrogenFluids;
import com.simibubi.create.content.fluids.VirtualFluid;
import com.tterrag.registrate.util.entry.FluidEntry;
import net.neoforged.neoforge.fluids.FluidStack;

/** Non-placeable process gases transported by Nautilus' gas network. */
public final class NautilusFluids {
    /* Compatibility aliases keep the already published oxygen API stable while the
       complete gas registry now lives in com.create.nautilus.hydrogen. */
    public static final FluidEntry<VirtualFluid> OXYGEN_GAS = HydrogenFluids.OXYGEN_GAS;
    public static final FluidEntry<VirtualFluid> HYDROGEN_GAS = HydrogenFluids.HYDROGEN_GAS;
    public static final FluidEntry<VirtualFluid> COMPRESSED_OXYGEN_GAS =
            HydrogenFluids.COMPRESSED_OXYGEN_GAS;
    public static final FluidEntry<VirtualFluid> COMPRESSED_HYDROGEN_GAS =
            HydrogenFluids.COMPRESSED_HYDROGEN_GAS;

    private NautilusFluids() {
    }

    public static boolean isOxygen(final FluidStack stack) {
        return !stack.isEmpty() && stack.getFluid().isSame(OXYGEN_GAS.get());
    }

    public static boolean isHydrogen(final FluidStack stack) {
        return !stack.isEmpty() && stack.getFluid().isSame(HYDROGEN_GAS.get());
    }

    public static boolean isGas(final FluidStack stack) {
        return HydrogenFluids.isGas(stack);
    }

    public static void register() {
    }
}
