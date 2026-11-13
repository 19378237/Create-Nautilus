package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Particle types owned by Nautilus; ordinary Minecraft bubbles keep their native behavior. */
public final class NautilusParticleTypes {
    private static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, CreateNautilus.MOD_ID);
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> PROPELLER_BUBBLE =
            PARTICLES.register("propeller_bubble", () -> new SimpleParticleType(false));

    private NautilusParticleTypes() {
    }

    public static void register(final IEventBus modEventBus) {
        PARTICLES.register(modEventBus);
    }
}

