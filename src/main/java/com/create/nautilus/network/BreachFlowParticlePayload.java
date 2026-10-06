package com.create.nautilus.network;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.BreachFlowParticleRenderer;
import com.create.nautilus.data.HullBreach;
import com.create.nautilus.physics.WaterEnvironment;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;

/** One directional visual pulse for signed water flow through a hull breach. */
public record BreachFlowParticlePayload(double x, double y, double z,
                                        double velocityX, double velocityY, double velocityZ,
                                        float intensity) implements CustomPacketPayload {
    public static final Type<BreachFlowParticlePayload> TYPE =
            new Type<>(CreateNautilus.id("breach_flow_particle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BreachFlowParticlePayload> STREAM_CODEC =
            StreamCodec.of(BreachFlowParticlePayload::write, BreachFlowParticlePayload::read);

    public BreachFlowParticlePayload {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityY)
                || !Double.isFinite(velocityZ) || !Float.isFinite(intensity)) {
            throw new IllegalArgumentException("Non-finite breach particle data");
        }
        intensity = Math.clamp(intensity, 0.0F, 1.0F);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void send(final ServerSubLevel carrier, final HullBreach breach) {
        final Vector3d origin = WaterEnvironment.toWorld(carrier, new Vector3d(
                breach.position().getX() + 0.5D,
                breach.position().getY() + 0.5D,
                breach.position().getZ() + 0.5D));
        final Vector3d direction = carrier.logicalPose().transformNormal(new Vector3d(
                breach.interiorDirection().getStepX(),
                breach.interiorDirection().getStepY(),
                breach.interiorDirection().getStepZ())).normalize();
        if (breach.currentFlowMbPerSecond() < 0.0D) {
            direction.negate();
        }
        final double absoluteFlow = Math.abs(breach.currentFlowMbPerSecond());
        final double speed = Math.clamp(0.04D + absoluteFlow / 5000.0D,
                0.04D, 0.45D);
        direction.mul(speed);
        final BreachFlowParticlePayload payload = new BreachFlowParticlePayload(
                origin.x, origin.y, origin.z,
                direction.x, direction.y, direction.z,
                (float) Math.clamp(absoluteFlow / 5000.0D, 0.0D, 1.0D));
        carrier.getLevel().players().stream()
                .filter(player -> player.distanceToSqr(origin.x, origin.y, origin.z)
                        <= 128.0D * 128.0D)
                .forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private static void write(final RegistryFriendlyByteBuf buffer,
                              final BreachFlowParticlePayload payload) {
        buffer.writeDouble(payload.x);
        buffer.writeDouble(payload.y);
        buffer.writeDouble(payload.z);
        buffer.writeDouble(payload.velocityX);
        buffer.writeDouble(payload.velocityY);
        buffer.writeDouble(payload.velocityZ);
        buffer.writeFloat(payload.intensity);
    }

    private static BreachFlowParticlePayload read(final RegistryFriendlyByteBuf buffer) {
        return new BreachFlowParticlePayload(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readFloat());
    }

    static void handleClient(final BreachFlowParticlePayload payload,
                             final IPayloadContext context) {
        BreachFlowParticleRenderer.accept(payload);
    }
}
