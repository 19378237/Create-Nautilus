package com.create.nautilus;

import com.create.nautilus.api.NautilusAPI;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.network.NautilusNetwork;
import com.create.nautilus.physics.NautilusPhysicsDispatcher;
import com.create.nautilus.registry.NautilusRegistries;
import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Create: Nautilus 的 NeoForge 入口。
 *
 * <p>本类只负责装配模块：注册内容、注册配置并隔离客户端初始化。
 * 水密、进水和刚体受力等状态逻辑会放入各自的服务类，避免主类演变为
 * 无法测试的全局事件处理器。</p>
 */
@Mod(CreateNautilus.MOD_ID)
public final class CreateNautilus {
    public static final String MOD_ID = "create_nautilus";
    public static final String MOD_NAME = "Create: Nautilus";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 附属模组必须创建自己的 CreateRegistrate，不能复用 Create 的私有实例。
     * null 创造模式标签是刻意的：第一部分尚无玩家可见内容；第四部分添加
     * 方块后再创建并排序专用标签页。
     */
    // Nautilus items are deliberately assigned to Simulated/Aeronautics'
    // sectioned tab by NautilusSimulatedTab.  A Create default tab here would
    // duplicate every block in the main Create inventory page.
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID)
            // Registrate defaults to CreativeModeTabs.SEARCH, not to "no
            // tab". Simulated already populates both its display and search
            // collections, so retaining that fallback creates duplicate
            // ItemStacks when the inventory rebuilds. The Registrate method
            // explicitly treats null as "do not auto-assign a tab".
            .defaultCreativeTab((ResourceKey<CreativeModeTab>) null);

    public CreateNautilus(final IEventBus modEventBus, final ModContainer modContainer) {
        // Registrate 仍需接入本模组的事件总线，才能在 RegisterEvent 阶段提交条目。
        REGISTRATE.registerEventListeners(modEventBus);
        NautilusRegistries.register(modEventBus);
        modEventBus.addListener(NautilusNetwork::registerPayloads);
        NautilusPhysicsDispatcher.register();

        // COMMON 配置在客户端和专用服务端使用同一份物理参数。
        modContainer.registerConfig(ModConfig.Type.COMMON, NautilusConfig.SPEC);

        LOGGER.info("{} initialized; public API implementation: {}",
                MOD_NAME, NautilusAPI.get().getClass().getSimpleName());
    }

    public static ResourceLocation id(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
