package com.create.nautilus.client.render;

import com.create.nautilus.network.BreachFlowParticlePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;

/** Client-only directional jet particles; sign/direction is calculated authoritatively. */
public final class BreachFlowParticleRenderer {
    private BreachFlowParticleRenderer() {
    }

    public static void accept(final BreachFlowParticlePayload payload) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // One or two particles every game tick looks continuous. Emitting up to ten particles
        // only every fourth tick made drainage appear as discrete bursts.
        final int count = Math.clamp((int) Math.ceil(payload.intensity() * 2.0F), 1, 2);
        for (int index = 0; index < count; index++) {
            final double spread = 0.10D + payload.intensity() * 0.12D;
            final double offsetX = (level.random.nextDouble() - 0.5D) * spread;
            final double offsetY = (level.random.nextDouble() - 0.5D) * spread;
            final double offsetZ = (level.random.nextDouble() - 0.5D) * spread;
            level.addParticle(index % 4 == 0 ? ParticleTypes.SPLASH : ParticleTypes.BUBBLE,
                    payload.x() + offsetX, payload.y() + offsetY, payload.z() + offsetZ,
                    payload.velocityX() + offsetX * 0.25D,
                    payload.velocityY() + offsetY * 0.25D,
                    payload.velocityZ() + offsetZ * 0.25D);
        }
    }
}
