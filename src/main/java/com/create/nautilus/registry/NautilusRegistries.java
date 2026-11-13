package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.data.NautilusAttachments;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;

/** 将所有注册入口集中在一个确定的初始化顺序中。 */
public final class NautilusRegistries {
    private NautilusRegistries() {
    }

    public static void register(final IEventBus modEventBus) {
        // ServerSubLevel is not an Attachment holder. The serialized carrier/compartment map is
        // therefore attached to its parent ServerLevel and keyed by the persistent carrier UUID.
        NautilusAttachments.register(modEventBus);

        // Registrate 条目通过静态字段定义；显式 bootstrap 可避免 JVM 延迟类加载
        // 导致某个索引类错过 NeoForge 的 RegisterEvent。
        NautilusFluids.register();
        NautilusBlocks.register();
        NautilusBlockEntityTypes.register();
        NautilusItems.register();
        NautilusEntityTypes.register();
        NautilusMenuTypes.register();
        NautilusSimulatedTab.registerItems();

        // Create 6.0.10 自己也对粒子和音效使用原生 DeferredRegister；这里沿用
        // 同一做法，Registrate 继续负责方块、物品、实体和菜单构建器。
        NautilusParticleTypes.register(modEventBus);
        NautilusSoundEvents.register(modEventBus);

        // Fluid handlers are capabilities in NeoForge 1.21, not legacy LazyOptionals.
        modEventBus.addListener(NautilusBlockEntityTypes::registerCapabilities);
        modEventBus.addListener(EventPriority.LOWEST, NautilusRegistries::checkCreateItemRegistration);
    }

    /** Observe public registry state before advancements; never access Create's private registrate. */
    private static void checkCreateItemRegistration(final RegisterEvent event) {
        if (!event.getRegistryKey().location().equals(Registries.ITEM.location())
                || !CreateNautilus.LOGGER.isDebugEnabled()) {
            return;
        }
        final boolean rootRegistrySame = BuiltInRegistries.REGISTRY.get(Registries.ITEM.location())
                == BuiltInRegistries.ITEM;
        for (final String path : new String[]{"mechanical_press", "chocolate_bucket", "honey_bucket"}) {
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("create", path);
            final ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
            final Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            final boolean holderPresent = BuiltInRegistries.ITEM.getHolder(key).isPresent();
            CreateNautilus.LOGGER.debug(
                    "[registry-check] ITEM registration: {} itemPresent={} holderPresent={} rootRegistrySame={}",
                    id, item != null, holderPresent, rootRegistrySame);
        }
    }
}
