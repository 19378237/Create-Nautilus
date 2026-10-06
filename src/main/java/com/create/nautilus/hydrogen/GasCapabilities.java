package com.create.nautilus.hydrogen;

import com.create.nautilus.CreateNautilus;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.jetbrains.annotations.Nullable;

/** Capability keys owned by the independent Nautilus gas network. */
public final class GasCapabilities {
    public static final BlockCapability<IGasHandler, @Nullable Direction> HANDLER =
            BlockCapability.createSided(CreateNautilus.id("gas_handler"), IGasHandler.class);

    private GasCapabilities() {
    }
}
