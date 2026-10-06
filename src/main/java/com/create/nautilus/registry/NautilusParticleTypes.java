package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 自定义气泡和尾迹粒子的 NeoForge 注册器。实际粒子类型在第五部分加入。 */
public final class NautilusParticleTypes {
    private static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, CreateNautilus.MOD_ID);

    private NautilusParticleTypes() {
    }

    public static void register(final IEventBus modEventBus) {
        PARTICLES.register(modEventBus);
    }
}

