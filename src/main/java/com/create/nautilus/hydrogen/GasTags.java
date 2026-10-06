package com.create.nautilus.hydrogen;

import com.create.nautilus.CreateNautilus;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

/** Public tag keys used by every Nautilus gas-capable machine. */
public final class GasTags {
    /**
     * Common contract for pipes, tanks and third-party integrations. A fluid
     * that is not in this tag must never enter a Nautilus gas-only inventory.
     */
    public static final TagKey<Fluid> GAS = TagKey.create(
            Registries.FLUID, CreateNautilus.id("gas"));

    private GasTags() {
    }
}
