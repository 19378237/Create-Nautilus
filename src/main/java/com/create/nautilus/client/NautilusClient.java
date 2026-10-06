package com.create.nautilus.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ClientDryCompartmentCache;
import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import com.create.nautilus.client.render.FloodedChamberRenderer;
import com.create.nautilus.client.render.NautilusPartialModels;
import com.create.nautilus.client.render.ApertureMediumMaskRenderer;
import com.create.nautilus.client.render.RawDepthRenderer;
import com.create.nautilus.client.render.WetCompartmentFog;
import com.create.nautilus.client.render.WaterOcclusionPassBridge;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 客户端专属的第二个 NeoForge 入口。
 *
 * <p>NeoForge 21.1 已不再提供旧的 DistExecutor。与 Sable 2.0.3 和
 * Aeronautics 1.3.0 的官方源码一致，这里使用带 {@code dist = CLIENT} 的
 * 独立 {@link Mod} 入口，让加载器保证专用服务端永不链接客户端 GUI 类。</p>
 */
@Mod(value = CreateNautilus.MOD_ID, dist = Dist.CLIENT)
public final class NautilusClient {
    public NautilusClient(final IEventBus modEventBus, final ModContainer container) {
        NautilusPartialModels.init();
        registerConfigScreen(container);
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, subLevels) -> {
            if (level instanceof ClientLevel clientLevel) {
                subLevels.addObserver(new SubLevelObserver() {
                    @Override
                    public void onSubLevelRemoved(final SubLevel subLevel,
                                                  final SubLevelRemovalReason reason) {
                        ClientDryCompartmentCache.forget(clientLevel, subLevel.getUniqueId());
                    }
                });
            }
        });
        modEventBus.addListener(NautilusClient::registerReloadListeners);
        NeoForge.EVENT_BUS.addListener(NautilusItemTooltips::onItemTooltip);
        NeoForge.EVENT_BUS.addListener(ClientDryCompartmentCache::onClientLogout);
        NeoForge.EVENT_BUS.addListener(ClientDryCompartmentCache::onClientLevelUnload);
        NeoForge.EVENT_BUS.addListener(ClientDryCompartmentCache::onClientTick);
        NeoForge.EVENT_BUS.addListener(ApertureMediumMaskRenderer::onClientLogout);
        NeoForge.EVENT_BUS.addListener(ApertureMediumMaskRenderer::onClientLevelUnload);
        NeoForge.EVENT_BUS.addListener(ApertureMediumMaskRenderer::onClientChunkLoad);
        NeoForge.EVENT_BUS.addListener(ApertureMediumMaskRenderer::onClientChunkUnload);
        NeoForge.EVENT_BUS.addListener(NautilusClient::releaseRawDepthOnLogout);
        NeoForge.EVENT_BUS.addListener(NautilusClient::releaseRawDepthOnLevelUnload);
        NeoForge.EVENT_BUS.addListener(WetCompartmentFog::onRenderFog);
        NeoForge.EVENT_BUS.addListener(WetCompartmentFog::onFogColour);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                ApertureMediumMaskRenderer::onRenderLevel);
        /*
         * Submit virtual water early in the post-translucent level stage.  Each selected backend
         * owns the complete lifetime of its batch: the Colorwheel adapter writes and calls its
         * public draw(RenderType) in this same frame, while the vanilla fallback ends only its own
         * buffer batch.  Nothing is left building for a later frame.
         */
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                FloodedChamberRenderer::onRenderLevel);
    }

    private static void releaseRawDepthOnLogout(
            final ClientPlayerNetworkEvent.LoggingOut event) {
        RawDepthRenderer.destroy();
    }

    private static void releaseRawDepthOnLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) RawDepthRenderer.destroy();
    }

    private static void registerReloadListeners(final RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            ApertureMediumMaskRenderer.invalidateAll();
            WaterOcclusionPassBridge.onResourceReload();
            // RawDepthRenderer is shared by the unchanged no-shader/Sable mask path.  Its
            // lifecycle must not remain owned by the retired glass-medium renderer.
            RawDepthRenderer.reset();
        });
    }

    /**
     * 使用 NeoForge 21.1 提供的内置配置编辑器。它会读取已经注册到
     * ModContainer 的 {@code ModConfigSpec}，因此不需要自行维护第二套 GUI 值。
     */
    public static void registerConfigScreen(final ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
