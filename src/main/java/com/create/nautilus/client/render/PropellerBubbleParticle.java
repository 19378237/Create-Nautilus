package com.create.nautilus.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.BubbleParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.phys.Vec3;

/** Vanilla bubble appearance/motion, with the propeller wake's actual water-domain lifetime. */
public final class PropellerBubbleParticle extends BubbleParticle {
    private PropellerBubbleParticle(final ClientLevel level,
                                     final double x, final double y, final double z,
                                     final double xd, final double yd, final double zd) {
        super(level, x, y, z, xd, yd, zd);
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (lifetime-- <= 0) {
            remove();
            return;
        }
        yd += 0.002D;
        move(xd, yd, zd);
        xd *= 0.85F;
        yd *= 0.85F;
        zd *= 0.85F;
        // BubbleParticle.tick checks only a parent FluidState. Keep its exact motion/lifetime
        // here while allowing this effect's real plot water and continuous compartment water.
        if (!UnderwaterKineticEffects.containsWater(level, new Vec3(x, y, z))) remove();
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(final SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(final SimpleParticleType type, final ClientLevel level,
                                        final double x, final double y, final double z,
                                        final double xd, final double yd, final double zd) {
            final PropellerBubbleParticle particle = new PropellerBubbleParticle(level, x, y, z, xd, yd, zd);
            particle.pickSprite(sprites);
            return particle;
        }
    }
}
