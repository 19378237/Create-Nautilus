package com.create.nautilus.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Registers Nautilus custom payloads on the NeoForge mod event bus. */
public final class NautilusNetwork {
    private static final String PROTOCOL_VERSION = "5";
    private static boolean registered;

    private NautilusNetwork() {
    }

    public static synchronized void registerPayloads(final RegisterPayloadHandlersEvent event) {
        // RegisterPayloadHandlersEvent is normally delivered once. Keep this guard because adding
        // the same listener twice otherwise aborts NeoForge loading with an "already registered"
        // exception, and it makes the bootstrap resilient to accidental duplicate wiring.
        if (registered) {
            return;
        }
        registered = true;
        // These events invalidate the client's cache even if a UUID disappears and returns
        // between two server tracking snapshots. Ordinary Sable tracking is polled by syncLevels.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent lifecycle) ->
                CompartmentVisualSync.forgetPlayer(lifecycle.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent lifecycle) ->
                CompartmentVisualSync.forgetPlayer(lifecycle.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent lifecycle) ->
                CompartmentVisualSync.forgetPlayer(lifecycle.getEntity().getUUID()));
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(
                DryCompartmentSnapshotPayload.TYPE,
                DryCompartmentSnapshotPayload.STREAM_CODEC,
                DryCompartmentSnapshotPayload::handleClient);
        registrar.playToClient(
                CompartmentVisualSnapshotPayload.TYPE,
                CompartmentVisualSnapshotPayload.STREAM_CODEC,
                CompartmentVisualSnapshotPayload::handleClient);
        registrar.playToClient(
                CompartmentWaterLevelPayload.TYPE,
                CompartmentWaterLevelPayload.STREAM_CODEC,
                CompartmentWaterLevelPayload::handleClient);
        registrar.playToClient(
                BreachFlowParticlePayload.TYPE,
                BreachFlowParticlePayload.STREAM_CODEC,
                BreachFlowParticlePayload::handleClient);
    }
}
