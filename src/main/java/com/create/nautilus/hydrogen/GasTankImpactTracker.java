package com.create.nautilus.hydrogen;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.world.level.Level;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe bridge from Sable's physics callback to Minecraft server ticks. */
final class GasTankImpactTracker {
    private static final long IMPACT_EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final ConcurrentHashMap<Key, Impact> IMPACTS = new ConcurrentHashMap<>();

    static final BlockSubLevelCollisionCallback CALLBACK =
            (localPos, collidedPos, impactPosition, impactVelocity) -> {
                // Sable explicitly reports normal impact velocity as the last
                // parameter; the Vector3d is an impact position, not motion.
                // Keep only the largest hit until the tank's next server tick.
                final double speed = Math.abs(impactVelocity);
                final Level level = SubLevelPhysicsSystem.getCurrentlySteppingSystem().getLevel();
                final Key key = new Key(level, localPos.asLong());
                final long now = System.nanoTime();
                IMPACTS.merge(key, new Impact(speed, now), (previous, current) ->
                        now - previous.recordedAtNanos() > IMPACT_EXPIRY_NANOS
                                ? current
                                : new Impact(Math.max(previous.speed(), speed), now));
                return BlockSubLevelCollisionCallback.CollisionResult.NONE;
            };

    private GasTankImpactTracker() {
    }

    static double consume(final GasTankBlockEntity tank) {
        final Level tankLevel = tank.getLevel();
        if (tankLevel == null) {
            return 0.0D;
        }
        final SubLevel containing = Sable.HELPER.getContaining(tank);
        final Level parentLevel = containing == null ? tankLevel : containing.getLevel();
        final Impact impact = IMPACTS.remove(
                new Key(parentLevel, tank.getBlockPos().asLong()));
        return impact == null
                || System.nanoTime() - impact.recordedAtNanos() > IMPACT_EXPIRY_NANOS
                ? 0.0D : impact.speed();
    }

    /** Level identity separates dimensions and distinct server instances. */
    private record Key(Level level, long blockPos) {
    }

    private record Impact(double speed, long recordedAtNanos) {
    }
}
