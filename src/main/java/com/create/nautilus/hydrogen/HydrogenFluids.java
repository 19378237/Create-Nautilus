package com.create.nautilus.hydrogen;

import com.create.nautilus.CreateNautilus;
import com.simibubi.create.content.fluids.VirtualFluid;
import com.tterrag.registrate.util.entry.FluidEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Registrate-backed definitions for every process gas.
 *
 * <p>{@link VirtualFluid} is Create's non-placeable Fluid implementation. It
 * retains standard FluidStack serialization but cannot create a world fluid
 * block. Transport is exposed only through Nautilus' dedicated gas capability,
 * not through Create's ordinary liquid-pipe network.</p>
 */
public final class HydrogenFluids {
    private static final ResourceLocation STILL =
            ResourceLocation.withDefaultNamespace("block/water_still");
    private static final ResourceLocation FLOW =
            ResourceLocation.withDefaultNamespace("block/water_flow");

    public static final FluidEntry<VirtualFluid> OXYGEN_GAS = gas(
            "oxygen_gas", "Oxygen", 2, 100);
    public static final FluidEntry<VirtualFluid> HYDROGEN_GAS = gas(
            "hydrogen_gas", "Hydrogen", 1, 80);
    public static final FluidEntry<VirtualFluid> COMPRESSED_OXYGEN_GAS = gas(
            "compressed_oxygen_gas", "Compressed Oxygen", 8, 180);
    public static final FluidEntry<VirtualFluid> COMPRESSED_HYDROGEN_GAS = gas(
            "compressed_hydrogen_gas", "Compressed Hydrogen", 4, 160);

    private HydrogenFluids() {
    }

    private static FluidEntry<VirtualFluid> gas(final String id, final String name,
                                                 final int density, final int viscosity) {
        return CreateNautilus.REGISTRATE.virtualFluid(id, STILL, FLOW)
                .properties(properties -> properties
                        .density(density)
                        .viscosity(viscosity)
                        .canPushEntity(false)
                        .canSwim(false)
                        .canDrown(false)
                        .pathType(null)
                        .adjacentPathType(null))
                .lang(name)
                .register();
    }

    /** Uses the data-driven tag first and exact built-ins as bootstrap fallback. */
    public static boolean isGas(final FluidStack stack) {
        return !stack.isEmpty() && (stack.is(GasTags.GAS) || isBuiltInGas(stack.getFluid()));
    }

    public static boolean isLowPressure(final FluidStack stack) {
        return is(stack, OXYGEN_GAS.get()) || is(stack, HYDROGEN_GAS.get());
    }

    public static boolean isCompressed(final FluidStack stack) {
        return is(stack, COMPRESSED_OXYGEN_GAS.get())
                || is(stack, COMPRESSED_HYDROGEN_GAS.get());
    }

    public static boolean isHydrogen(final FluidStack stack) {
        return is(stack, HYDROGEN_GAS.get())
                || is(stack, COMPRESSED_HYDROGEN_GAS.get());
    }

    public static boolean isLowPressureHydrogen(final FluidStack stack) {
        return is(stack, HYDROGEN_GAS.get());
    }

    public static Fluid compressedForm(final Fluid fluid) {
        if (fluid.isSame(OXYGEN_GAS.get())) {
            return COMPRESSED_OXYGEN_GAS.get();
        }
        if (fluid.isSame(HYDROGEN_GAS.get())) {
            return COMPRESSED_HYDROGEN_GAS.get();
        }
        return null;
    }

    public static Fluid expandedForm(final Fluid fluid) {
        if (fluid.isSame(COMPRESSED_OXYGEN_GAS.get())) {
            return OXYGEN_GAS.get();
        }
        if (fluid.isSame(COMPRESSED_HYDROGEN_GAS.get())) {
            return HYDROGEN_GAS.get();
        }
        return null;
    }

    private static boolean isBuiltInGas(final Fluid fluid) {
        return fluid.isSame(OXYGEN_GAS.get())
                || fluid.isSame(HYDROGEN_GAS.get())
                || fluid.isSame(COMPRESSED_OXYGEN_GAS.get())
                || fluid.isSame(COMPRESSED_HYDROGEN_GAS.get());
    }

    private static boolean is(final FluidStack stack, final Fluid fluid) {
        return !stack.isEmpty() && stack.getFluid().isSame(fluid);
    }
}
