package com.create.nautilus.oxygen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import com.create.nautilus.physics.VirtualCompartmentWater;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectUtil;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import org.joml.Vector3d;

/**
 * Consumes stored compartment oxygen and connects Nautilus water to vanilla breathing.
 *
 * <p>There is deliberately only one authoritative breath counter: the entity's vanilla
 * {@code airSupply}.  Real water already changes that counter through NeoForge's
 * {@link LivingBreatheEvent}; virtual compartment water joins the same event below.  Keeping
 * both sources on this path means that walking from real water into virtual water (or back)
 * cannot refill or restart the breath bar for even one source-specific code path.</p>
 */
public final class OxygenConsumer {
    private OxygenConsumer() {}

    public static void tick(final ServerSubLevel carrier, final CarrierState state, final double dt) {
        for (ServerPlayer player : carrier.getLevel().players()) {
            if (player.isCreative() || player.isSpectator() || player.getAbilities().invulnerable) continue;
            if (Sable.HELPER.getTrackingOrVehicleSubLevel(player) != carrier) continue;
            final Vector3d local = carrier.logicalPose().transformPositionInverse(
                    new Vector3d(player.getX(), player.getEyeY(), player.getZ()));
            final var compartment = state.compartmentAt(local);
            if (compartment.isEmpty()) continue;
            final CompartmentState room = compartment.get();
            // Open-top displacement envelopes are not life-support compartments. Their air space
            // exists only for hull buoyancy and flooding geometry, so players inside neither
            // consume stored oxygen nor receive low-oxygen effects. Once water reaches their eyes,
            // onLivingBreathe handles the virtual surface through vanilla's air-supply path.
            final boolean openTop = state.breaches().stream().anyMatch(breach ->
                    breach.atmosphericOpening()
                            && breach.compartmentId().equals(room.id()));
            if (openTop) continue;
            room.consumeOxygen(NautilusConfig.oxygenConsumptionRate() * dt);
        }
    }

    /**
     * Extends NeoForge's normal breath decision to Nautilus' non-block virtual water.
     *
     * <p>The entity mixins publish both body and eye water FluidTypes. This event also handles
     * exhausted dry rooms and preserves the same native breath counter across real and virtual
     * water. NeoForge retains ownership of respiration/oxygen-bonus rolls, drowning and damage.</p>
     */
    public static void onLivingBreathe(final LivingBreatheEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // A real non-water eye fluid keeps its own breath rules, even where the virtual plane
        // geometrically overlaps its voxel. The eye restorer follows this same precedence.
        if (!player.getEyeInFluidType().isAir()
                && player.getEyeInFluidType() != NeoForgeMod.WATER_TYPE.value()) return;

        final boolean virtualWaterAtEyes = VirtualCompartmentWater.serverDepthAt(
                player, player.getEyePosition()) > 0.0D;
        final boolean airlessCompartment = isInsideAirlessCompartment(player);
        if (!virtualWaterAtEyes && !airlessCompartment) return;

        // Match the exemptions used by vanilla water. In particular, creative/spectator players
        // and players with Water Breathing must not drown merely because this water surface is
        // represented by a compartment attachment instead of a FluidState.
        if (!player.canDrownInFluidType(NeoForgeMod.WATER_TYPE.value())
                || MobEffectUtil.hasWaterBreathing(player)
                || player.getAbilities().invulnerable) {
            // Match CommonHooks' zero-refill rule for water exemptions, also in airless rooms.
            event.setCanBreathe(true);
            event.setRefillAirAmount(0);
            return;
        }

        // Do not write airSupply here. CommonHooks has already calculated the vanilla consume
        // amount (including the oxygen-bonus/respiration attribute) and will apply it after this
        // event. Because real water uses this exact branch too, crossing the boundary between
        // real and virtual water preserves one continuous counter with no reset tick.
        event.setCanBreathe(false);
    }

    /** Returns whether the player's eyes occupy a sealed room whose stored oxygen is exhausted. */
    private static boolean isInsideAirlessCompartment(final ServerPlayer player) {
        final var tracked = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(tracked instanceof ServerSubLevel carrier)) return false;

        final CarrierState state = NautilusData.carrier(carrier);
        final Vector3d localEye = carrier.logicalPose().transformPositionInverse(
                new Vector3d(player.getX(), player.getEyeY(), player.getZ()));
        final var compartment = state.compartmentAt(localEye);
        if (compartment.isEmpty()) return false;

        final CompartmentState room = compartment.get();
        final boolean openTop = state.breaches().stream().anyMatch(breach ->
                breach.atmosphericOpening()
                        && breach.compartmentId().equals(room.id()));
        return !openTop && room.oxygenMb() <= 0.0D;
    }
}
