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

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** One authoritative ordered callback for every Sable physics sub-step. */
public final class NautilusPhysicsDispatcher {
    private static boolean registered;
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
        NeoForge.EVENT_BUS.addListener(HydrogenRiskEvents::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(HydrogenRiskEvents::onLivingDestroyBlock);
        NeoForge.EVENT_BUS.addListener(HydrogenRiskEvents::onExplosion);
        NeoForge.EVENT_BUS.addListener(HydrogenRiskEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(NautilusPhysicsDispatcher::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(NautilusPhysicsDispatcher::onServerStopped);
    }
    private static void onPhysicsTick(final SubLevelPhysicsSystem system, final double dt) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(system.getLevel());
        if (container == null) return;
        for (ServerSubLevel carrier : container.getAllSubLevels()) {
            final RigidBodyHandle body = system.getPhysicsHandle(carrier);
            if (body == null) continue;
            final CarrierState state = NautilusData.carrier(carrier);
            // Break events are pre-change. Resolve their final topology before deciding whether
            // the periodic compartment scan should use a virtual breach membrane.
            FloodingHandler.resolvePendingBreaks(carrier, state);
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
                CompartmentSealer.rescanInto(carrier, state);
            }
            // Strang-split the gas and liquid solvers. Updating pressure only before flooding left
            // one full substep of stale compressed-air pressure after water moved, which could
            // reverse the next hydraulic step and make the free surface pulse around equilibrium.
            PressureEqualizer.tick(carrier,state,dt * 0.5D);
            FloodingHandler.tick(carrier,state,dt);
            PressureEqualizer.tick(carrier,state,dt * 0.5D);
            if (scanned) {
                CompartmentVisualSync.syncTopology(carrier, state);
            }
            if (state.lastVisualSyncTick() != time) {
                // Physical occlusion remains the complete tracked room because the entity-fluid
                // mixins restore the exact continuous water level. Rendering is different: only
                // the air cells at/above that level may hide the parent ocean. Publishing this
                // once per game tick makes the occlusion boundary follow gradual flooding instead
                // of keeping a room-sized hole until the next topology rescan.
                DryCompartmentSync.syncToTrackingPlayers(carrier,
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
            final CompartmentFluidInventory.Snapshot internalWater =
                    CompartmentFluidInventory.measure(carrier, state);
            BallastMassSynchronizer.tick(system,carrier,state,internalWater);
            final BuoyancyPhysics.Result buoyancy = BuoyancyPhysics.tick(
                    carrier,body,state,internalWater,dt);
            WaterDragPhysics.tick(carrier,body,state,buoyancy.submersion(),dt);
            final Map<UUID,Double> oxygen = state.compartments().stream().collect(Collectors.toMap(CompartmentState::id,CompartmentState::oxygenFraction));
            final Map<UUID,Double> pressure = state.compartments().stream().collect(Collectors.toMap(CompartmentState::id,CompartmentState::internalPressureKpa));
            final Map<UUID,Double> water = state.compartments().stream().collect(Collectors.toMap(
                    CompartmentState::id,
                    room -> Math.clamp(room.waterMb() / room.waterCapacityMb(), 0.0D, 1.0D)));
            NautilusStateStore.INSTANCE.publish(state.carrierId(), new NautilusStateStore.CarrierSnapshot(
                    buoyancy.submersion(), buoyancy.supplementalForce(),
                    state.breaches().stream().collect(Collectors.toMap(b->b.position(), b->b.currentFlowMbPerSecond())),
                    state.compartments().stream().filter(CompartmentState::sealed).map(CompartmentState::bounds).toList(),
                    oxygen, pressure, water, state.hullIntegrity()));
        }
    }

    /**
     * Converts continuous internal water volume into Sable's one-block physical
     * water-occlusion cells.  Air above the internal waterline remains dry even
     * in a breached room; cells below it regain ordinary water physics.
     */
    static long[] physicalDryCells(final ServerSubLevel carrier, final CarrierState state) {
        // Sable's region is voxel-only and therefore cannot represent a continuous horizontal
        // plane through a rotated cell. Occlude the parent ocean throughout the compartment;
        // VirtualCompartmentWaterMixin restores entity water state from the exact same surfaceY
        // used by rendering, eliminating the old half-block/diagonal physical water layer.
        return state.compartments().stream()
                .flatMap(room -> room.cells().stream())
                .map(BlockPos::of)
                // A real water block placed in the carrier must keep vanilla entity-fluid
                // behaviour. Only parent-ocean water is occluded by the dry-room mask.
                .filter(pos -> !carrier.getLevel().getFluidState(pos).is(FluidTags.WATER))
                .map(BlockPos::asLong)
                .mapToLong(Long::longValue)
                .distinct()
                .toArray();
    }

    /**
     * Client rendering removes the parent ocean from every tracked compartment voxel. Wet rooms
     * are then drawn exclusively by FloodedChamberRenderer at their gradual stored water level.
     */
    static long[] visualOcclusionCells(final ServerSubLevel carrier,
                                       final CarrierState state) {
        return state.compartments().stream()
                .flatMapToLong(room -> {
                    final double fraction = room.waterCapacityMb() <= 0.0D ? 0.0D
                            : Math.clamp(room.waterMb() / room.waterCapacityMb(), 0.0D, 1.0D);
                    if (fraction >= 1.0D - 1.0E-6D) {
                        return java.util.stream.LongStream.empty();
                    }
                    final double surfaceY = CompartmentWaterGeometry.surfaceY(carrier, room);
                    return room.cells().stream()
                            .map(BlockPos::of)
                            // A real fluid deliberately placed in the carrier plot owns its own
                            // mesh. It must never be mistaken for parent-ocean geometry merely
                            // because the same voxel also belongs to a tracked compartment.
                            .filter(pos -> !carrier.getLevel().getFluidState(pos)
                                    .is(FluidTags.WATER))
                            /*
                             * Only cells fully above the virtual surface are suppressed at the
                             * mesh level. The cell crossed by the plane keeps its real water
                             * volume: the client's depth mask (no-shader path) caps that volume
                             * at the exact plane, and block-level suppression must not delete the
                             * water below the plane inside the crossed cell.
                             */
                            .filter(pos -> CompartmentWaterGeometry.lowestWorldY(carrier, pos)
                                    > surfaceY + 1.0E-5D)
                            .mapToLong(BlockPos::asLong);
                })
                .distinct()
                .toArray();
    }

    private static void forgetRuntimeState(final UUID carrierId) {
        DryCompartmentSync.forget(carrierId);
        CompartmentVisualSync.forget(carrierId);
        NautilusStateStore.INSTANCE.remove(carrierId);
    }

    private static void clearRuntimeState() {
        DryCompartmentSync.clear();
        CompartmentVisualSync.clear();
        NautilusStateStore.INSTANCE.clear();
    }

    private static void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
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
            forgetRuntimeState(carrierId);
            if (reason == SubLevelRemovalReason.REMOVED) {
                level.getData(NautilusAttachments.SUBMARINE_DATA.get())
                        .removeCarrier(carrierId);
            }
        }
    }
}
