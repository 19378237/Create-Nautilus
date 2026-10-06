package com.create.nautilus.hydrogen;

import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Marker capability used by the Nautilus gas network.
 *
 * <p>Gases remain {@code FluidStack}s so machines retain Create/NeoForge's
 * proven tank serialization and transfer semantics.  The capability itself is
 * deliberately different from {@link IFluidHandler}: Create's ordinary fluid
 * pipes query only the latter, so they cannot discover or transport gas from
 * Nautilus equipment.</p>
 */
public interface IGasHandler extends IFluidHandler {
}
