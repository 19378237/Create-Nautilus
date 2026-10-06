package com.create.nautilus.data;

import com.create.nautilus.CreateNautilus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** NeoForge 数据附件注册。 */
public final class NautilusAttachments {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateNautilus.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<NautilusLevelData>> SUBMARINE_DATA =
            ATTACHMENTS.register("submarine_data",
                    () -> AttachmentType.serializable(NautilusLevelData::new).build());

    private NautilusAttachments() {
    }

    public static void register(final IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }
}

