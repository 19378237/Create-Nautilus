package com.create.nautilus.hydrogen;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, tick-scoped publication of boosted propeller positions.
 *
 * <p>Aeronautics may query thrust from code that must not walk Minecraft's
 * block-entity collection. The booster therefore discovers its neighbour in
 * its ordinary server tick and publishes only an immutable level/position
 * key. The propeller mixin performs one lock-free map lookup.</p>
 */
public final class PropellerBoostRegistry {
    private static final ConcurrentHashMap<Key, ConcurrentHashMap<Key, Long>> ACTIVE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Key, Key> TARGET_BY_SOURCE =
            new ConcurrentHashMap<>();
    private static volatile long currentServerTick;

    private PropellerBoostRegistry() {
    }

    public static void beginServerTick(final long serverTick) {
        currentServerTick = serverTick;
        // Bound stale keys from unloaded or destroyed sublevels. Running
        // boosters refresh their entry every tick and survive this cleanup.
        if ((serverTick & 0xFFL) == 0L) {
            for (final Key propeller : ACTIVE.keySet()) {
                ACTIVE.computeIfPresent(propeller, (key, sources) -> {
                    sources.entrySet().removeIf(entry -> {
                        if (entry.getValue() >= serverTick) {
                            return false;
                        }
                        TARGET_BY_SOURCE.remove(entry.getKey(), propeller);
                        return true;
                    });
                    return sources.isEmpty() ? null : sources;
                });
            }
        }
    }

    /** Compatibility path for callers that do not publish a distinct source. */
    public static void markActive(final KineticBlockEntity propeller) {
        markActive(propeller, propeller);
    }

    public static void markActive(final KineticBlockEntity propeller,
                                  final BlockEntity source) {
        final Level level = propeller.getLevel();
        final Level sourceLevel = source.getLevel();
        if (level != null && sourceLevel != null) {
            // One tick of grace makes the result independent of block-entity
            // tick order: a propeller may tick before or after its booster.
            final Key propellerKey = new Key(level, propeller.getBlockPos().asLong());
            final Key sourceKey = new Key(sourceLevel, source.getBlockPos().asLong());
            // A booster can only boost its current adjacent propeller. Remove
            // its previous publication without affecting other boosters.
            final Key previous = TARGET_BY_SOURCE.put(sourceKey, propellerKey);
            if (previous != null && !previous.equals(propellerKey)) {
                removeSourceFrom(sourceKey, previous);
            }
            ACTIVE.compute(propellerKey, (key, sources) -> {
                final ConcurrentHashMap<Key, Long> updated = sources == null
                        ? new ConcurrentHashMap<>() : sources;
                updated.put(sourceKey, currentServerTick + 1L);
                return updated;
            });
        }
    }

    /** Compatibility path for callers that do not publish a distinct source. */
    public static void markInactive(final KineticBlockEntity propeller) {
        markInactive(propeller, propeller);
    }

    /** Removes only this source, preserving any other active booster. */
    public static void markInactive(final KineticBlockEntity propeller,
                                    final BlockEntity source) {
        final Level level = propeller.getLevel();
        final Level sourceLevel = source.getLevel();
        if (level != null && sourceLevel != null) {
            final Key sourceKey = new Key(sourceLevel, source.getBlockPos().asLong());
            final Key propellerKey = new Key(level, propeller.getBlockPos().asLong());
            TARGET_BY_SOURCE.remove(sourceKey, propellerKey);
            removeSourceFrom(sourceKey, propellerKey);
        }
    }

    /** Immediately removes a source whose propeller is gone or no longer adjacent. */
    public static void markInactive(final BlockEntity source) {
        final Level sourceLevel = source.getLevel();
        if (sourceLevel != null) {
            final Key sourceKey = new Key(sourceLevel, source.getBlockPos().asLong());
            final Key propellerKey = TARGET_BY_SOURCE.remove(sourceKey);
            if (propellerKey != null) {
                removeSourceFrom(sourceKey, propellerKey);
            }
        }
    }

    public static boolean isActive(final KineticBlockEntity propeller) {
        final Level level = propeller.getLevel();
        if (level == null) {
            return false;
        }
        final Map<Key, Long> sources = ACTIVE.get(
                new Key(level, propeller.getBlockPos().asLong()));
        return sources != null
                && sources.values().stream().anyMatch(expiry -> expiry >= currentServerTick);
    }

    private static void removeSourceFrom(final Key source,
                                         final Key propeller) {
        ACTIVE.computeIfPresent(propeller, (key, sources) -> {
            sources.remove(source);
            return sources.isEmpty() ? null : sources;
        });
    }

    /** Level uses identity equality, which also separates Sable sublevels. */
    private record Key(Level level, long blockPos) {
    }
}
