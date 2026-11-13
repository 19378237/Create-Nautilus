package com.create.nautilus.physics;

import com.create.nautilus.api.internal.NautilusStateStore;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusAttachments;
import com.create.nautilus.data.NautilusData;
import com.create.nautilus.hydrogen.HydrogenRiskEvents;
import com.create.nautilus.network.DryCompartmentSync;
import com.create.nautilus.network.CompartmentVisualSync;
import com.create.nautilus.network.OcclusionCells;
import com.create.nautilus.oxygen.OxygenConsumer;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/** One authoritative ordered callback for every Sable physics sub-step. */
public final class NautilusPhysicsDispatcher {
    private static boolean registered;
    private static final Map<CarrierState, DryCellCache> PHYSICAL_DRY_CELLS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private NautilusPhysicsDispatcher() {}
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        SableEventPlatform.INSTANCE.onPhysicsTick(NautilusPhysicsDispatcher::onPhysicsTick);
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            if (level instanceof ServerLevel serverLevel) {
                container.addObserver(new NautilusSubLevelObserver(serverLevel));
            }
        });
        // Virtual compartment water has no real FluidState, so NeoForge would otherwise treat
        // the player's eye fluid type as air and refill the breath bar. Route it through the same
        // LivingBreatheEvent used by real water to preserve one continuous vanilla air counter.
        // Run before equipment and effect providers (for example Create's diving helmet).
        // Nautilus first changes the virtual-water default from "air" to "cannot breathe";
        // later listeners can then grant breathing exactly as they do in real water.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                OxygenConsumer::onLivingBreathe);
        NeoForge.EVENT_BUS.addListener(FloodingHandler::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(FloodingHandler::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(HydrogenRiskEvents::onExplosion);
        NeoForge.EVENT_BUS.addListener(HydrogenRiskEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(NautilusPhysicsDispatcher::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(NautilusPhysicsDispatcher::onServerStopped);
    }
    private static void onPhysicsTick(final SubLevelPhysicsSystem system, final double dt) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) return;
        final com.create.nautilus.data.NautilusLevelData data =
                system.getLevel().getData(NautilusAttachments.SUBMARINE_DATA.get());
        CarrierSplitMigration.resolvePending(container, data);
        for (ServerSubLevel carrier : container.getAllSubLevels()) {
            if (carrier.isRemoved() || data.hasPendingSplit(carrier.getUniqueId())) continue;
            final RigidBodyHandle body = system.getPhysicsHandle(carrier);
            if (body == null) continue;
            final CarrierState state = NautilusData.carrier(carrier);
            // Break events are pre-change. Resolve their final topology before deciding whether
            // the periodic compartment scan should use a virtual breach membrane.
            final CompartmentSealer.ScanResult topologyProbe = FloodingHandler.resolvePendingBreaks(carrier, state);
            final long time = carrier.getLevel().getGameTime();
            final long lastScan = state.lastCompartmentScanTick();
            // Subtracting Long.MIN_VALUE from an ordinary world time wraps to
            // a negative number.  Test the sentinel explicitly so every newly
            // created/loaded carrier receives an immediate topology scan.
            // Hull damage is registered only from the committed Sable block-change hook or the
            // NeoForge break-event fallback. Inferring a removed block from adjacent exterior air
            // is not valid for open vessels and previously fabricated submerged bottom breaches
            // whenever an otherwise unrelated block state changed.
            final boolean scanned = state.needsTopologyRebuild()
                    || lastScan == Long.MIN_VALUE
                    || state.needsTopologyWarmupScan(time)
                    || time - lastScan >= NautilusConfig.compartmentScanIntervalTicks();
            if (scanned) {
                CompartmentSealer.rescanInto(carrier, state, topologyProbe);
            }
            // Real sources reserve their cells before pressure, flooding or ballast controllers
            // consume the virtual capacity; both mass and displacement use this same sample.
            final CompartmentFluidInventory.Snapshot internalWater =
                    CompartmentFluidInventory.measure(carrier, state);
            state.updateRealWaterSources(internalWater);
            // Strang-split the gas and liquid solvers. Updating pressure only before flooding left
            // one full substep of stale compressed-air pressure after water moved, which could
            // reverse the next hydraulic step and make the free surface pulse around equilibrium.
            final WaterEnvironment.QueryContext outsideWater =
                    new WaterEnvironment.QueryContext(carrier.getLevel(), state.lastConnectedWaterEntries());
            final WaterQueryDiagnostics previousDiagnostics = WaterQueryDiagnostics.begin(outsideWater.diagnostics());
            final long previousHydrostaticDiagnosticTick;
            final BuoyancyPhysics.Result buoyancy;
            try {
                // External samples are valid only during this split, before ballast mutates pose.
                {
                    final ExternalApertureSamples apertures = new ExternalApertureSamples(carrier, outsideWater);
                    PressureEqualizer.tick(carrier,state,dt * 0.5D,apertures);
                    FloodingHandler.tick(carrier,state,dt,apertures);
                    PressureEqualizer.tick(carrier,state,dt * 0.5D,apertures);
                }
                if (scanned) {
                    CompartmentVisualSync.syncTopology(carrier, state);
                }
                if (state.lastVisualSyncTick() != time) {
                    // Physical occlusion remains the complete tracked room because the entity-fluid
                    // mixins restore the exact continuous water level. Rendering is different: only
                    // the air cells at/above that level may hide the parent ocean. Publishing this
                    // once per game tick makes the occlusion boundary follow gradual flooding instead
                    // of keeping a room-sized hole until the next topology rescan.
                    DryCompartmentSync.syncNormalizedToTrackingPlayers(carrier,
                            physicalDryCells(carrier, state),
                            visualOcclusionCells(carrier, state));
                    CompartmentVisualSync.syncLevels(carrier, state);
                    state.setLastVisualSyncTick(time);
                }
                OxygenConsumer.tick(carrier,state,dt);
                HullDamageHandler.tick(carrier,state,dt);
                // Real plot water remains ordinary Minecraft/Create fluid. Sample it without mutating
                // the world, then use the same snapshot for both mass and displacement so the two
                // solvers cannot disagree during a fluid update.
                BallastMassSynchronizer.tick(system,carrier,state,internalWater);
                previousHydrostaticDiagnosticTick = state.lastHydrostaticDiagnosticTick();
                buoyancy = BuoyancyPhysics.tick(carrier,body,state,internalWater,dt,outsideWater);
                state.setLastConnectedWaterEntries(outsideWater.connectedWaterEntryCount());
                outsideWater.finishDiagnostics();
            } finally {
                WaterQueryDiagnostics.restore(previousDiagnostics);
            }
            WaterDragPhysics.tick(carrier,body,state,buoyancy.submersion(),dt);
            if (state.lastHydrostaticDiagnosticTick() != previousHydrostaticDiagnosticTick) {
                // Same 100-game-tick sample as the balance log. This is after Nautilus, before
                // Sable applies queued impulses and advances Rapier: constraints, contacts and
                // native water forces can still change the motion. Do not label these as net force.
                final Vector3d queuedLinearImpulse = new Vector3d();
                final Vector3d queuedAngularImpulse = new Vector3d();
                final Map<String, String> queuedByGroup = new java.util.LinkedHashMap<>();
                final var forceGroups = carrier.getQueuedForceGroups();
                if (forceGroups != null) {
                    forceGroups.forEach((group, queued) -> {
                        final var total = queued.getForceTotal();
                        queuedLinearImpulse.add(total.getLocalForce());
                        queuedAngularImpulse.add(total.getLocalTorque());
                        queuedByGroup.put(group.name().getString(),
                                "linear=" + total.getLocalForce() + ", angular=" + total.getLocalTorque());
                    });
                }
                com.create.nautilus.CreateNautilus.LOGGER.debug(
                        "Hydrostatic motion carrier {}: phase=postNautilusPreNative, dt={}, "
                                + "position={}, orientation={}, bodyLinearVelocity={}, bodyAngularVelocity={}, "
                                + "queuedLocalLinearImpulse={}, queuedLocalAngularImpulse={}, queuedByGroup={}",
                        state.carrierId(), dt, carrier.logicalPose().position(),
                        carrier.logicalPose().orientation(), body.getLinearVelocity(new Vector3d()),
                        body.getAngularVelocity(new Vector3d()), queuedLinearImpulse,
                        queuedAngularImpulse, queuedByGroup);
            }
            final Map<UUID,Double> oxygen = state.compartments().stream().collect(Collectors.toMap(CompartmentState::id,CompartmentState::oxygenFraction));
            final Map<UUID,Double> pressure = state.compartments().stream().collect(Collectors.toMap(CompartmentState::id,CompartmentState::internalPressureKpa));
            final Map<UUID,Double> water = state.compartments().stream().collect(Collectors.toMap(
                    CompartmentState::id,
                    room -> room.waterCapacityMb() <= 0.0D ? 0.0D
                            : Math.clamp(room.waterMb() / room.waterCapacityMb(), 0.0D, 1.0D)));
            NautilusStateStore.INSTANCE.publish(state.carrierId(), new NautilusStateStore.CarrierSnapshot(
                    buoyancy.submersion(), buoyancy.supplementalForce(),
                    state.breaches().stream().collect(Collectors.toMap(b->b.position(), b->b.currentFlowMbPerSecond())),
                    state.compartments().stream().filter(CompartmentState::sealed).map(CompartmentState::bounds).toList(),
                    oxygen, pressure, water, state.hullIntegrity()));
        }
    }

    /**
     * Complete tracked room mask, excluding real plot water. The entity-fluid mixins restore
     * continuous virtual water independently of this voxel-only parent-water occlusion.
     */
    static OcclusionCells physicalDryCells(final ServerSubLevel carrier, final CarrierState state) {
        final DryCellCache cached = PHYSICAL_DRY_CELLS.get(state);
        if (cached != null && cached.topologyRevision() == state.topologyRevision()
                && cached.fluidRevision() == state.fluidRevision()) return cached.cells();
        // Sable's region is voxel-only and therefore cannot represent a continuous horizontal
        // plane through a rotated cell. Occlude the parent ocean throughout the compartment;
        // VirtualCompartmentWaterMixin restores entity water state from the exact same surfaceY
        // used by rendering, eliminating the old half-block/diagonal physical water layer.
        final OcclusionCells.Builder builder = new OcclusionCells.Builder();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (final CompartmentState room : state.compartments()) {
            for (final long cell : room.cells()) {
                pos.set(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
                // Real plot water keeps vanilla entity-fluid behaviour.
                if (!carrier.getLevel().getFluidState(pos).is(FluidTags.WATER)) builder.add(cell);
            }
        }
        final OcclusionCells cells = builder.build();
        PHYSICAL_DRY_CELLS.put(state, new DryCellCache(state.topologyRevision(), state.fluidRevision(), cells));
        return cells;
    }

    private record DryCellCache(long topologyRevision, long fluidRevision, OcclusionCells cells) {}

    /**
     * Suppress parent-water meshes only in cells fully above the continuous virtual surface.
     * Cells crossing that surface retain the water mesh below the client's exact depth mask.
     */
    static OcclusionCells visualOcclusionCells(final ServerSubLevel carrier,
                                               final CarrierState state) {
        final OcclusionCells.Builder builder = new OcclusionCells.Builder();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (final CompartmentState room : state.compartments()) {
            final double fraction = room.waterCapacityMb() <= 0.0D ? 0.0D
                    : Math.clamp(room.waterMb() / room.waterCapacityMb(), 0.0D, 1.0D);
            if (fraction >= 1.0D - 1.0E-6D) continue;
            final CompartmentWaterGeometry.PreparedSurface prepared =
                    CompartmentWaterGeometry.preparedSurface(carrier, room);
            final double surfaceY = prepared.surface();
            for (final long cell : room.cells()) {
                pos.set(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
                // Real plot water owns its mesh; only cells fully above the virtual surface
                // suppress parent-water meshes. Crossing cells keep their water below the plane.
                if (!carrier.getLevel().getFluidState(pos).is(FluidTags.WATER)
                        && prepared.lowestWorldY(pos)
                                > surfaceY + 1.0E-5D) builder.add(cell);
            }
        }
        return builder.build();
    }

    private static void forgetRuntimeState(final UUID carrierId) {
        DryCompartmentSync.forget(carrierId);
        CompartmentVisualSync.forget(carrierId);
        NautilusStateStore.INSTANCE.remove(carrierId);
    }

    private static void clearRuntimeState() {
        PHYSICAL_DRY_CELLS.clear();
        DryCompartmentSync.clear();
        CompartmentVisualSync.clear();
        NautilusStateStore.INSTANCE.clear();
    }

    private static void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            HydrogenRiskEvents.clearLevel(serverLevel);
            // Runtime stores are process-wide because carrier UUIDs are the public key, but a
            // single dimension can unload while other dimensions remain active. Do not clear
            // every dimension's state here; evict only sublevels owned by this level. The global
            // clear belongs to ServerStopped.
            final ServerSubLevelContainer container =
                    SubLevelContainer.getContainer(serverLevel);
            if (container != null) {
                for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
                    forgetRuntimeState(subLevel.getUniqueId());
                }
            }
        }
    }

    private static void onServerStopped(final ServerStoppedEvent event) {
        HydrogenRiskEvents.clearAll();
        clearRuntimeState();
    }

    /**
     * Sable distinguishes a temporary unload from permanent removal before detaching a sub-level.
     * Runtime network/API state is invalid in both cases; persisted compartment data is deleted
     * only for the explicit permanent-removal reason.
     */
    private static final class NautilusSubLevelObserver implements SubLevelObserver {
        private final ServerLevel level;

        private NautilusSubLevelObserver(final ServerLevel level) {
            this.level = level;
        }

        @Override
        public void onSubLevelRemoved(final SubLevel subLevel,
                                      final SubLevelRemovalReason reason) {
            if (!(subLevel instanceof ServerSubLevel)) {
                return;
            }
            final UUID carrierId = subLevel.getUniqueId();
            CompartmentFluidInventory.forget((ServerSubLevel) subLevel);
            forgetRuntimeState(carrierId);
            final com.create.nautilus.data.NautilusLevelData data =
                    level.getData(NautilusAttachments.SUBMARINE_DATA.get());
            final CarrierState state = data.carrier(carrierId);
            PHYSICAL_DRY_CELLS.remove(state);
            state.clearRuntimeCachesOnUnload();
            if (reason == SubLevelRemovalReason.REMOVED) {
                data.removeCarrier(carrierId);
            }
        }
    }
}
