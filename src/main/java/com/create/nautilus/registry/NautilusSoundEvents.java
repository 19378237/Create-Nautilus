package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 水泵、进水和涡轮音效注册器。音频资源及具体条目在相应内容部分加入。 */
public final class NautilusSoundEvents {
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, CreateNautilus.MOD_ID);

    private NautilusSoundEvents() {
    }

    public static void register(final IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}

