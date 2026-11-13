package com.create.nautilus.hydrogen;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/** NeoForge bridges for the two non-Sable rupture causes. */
public final class HydrogenRiskEvents {
    private HydrogenRiskEvents() {
    }

    public static void onServerTick(final ServerTickEvent.Pre event) {
        final long tick = event.getServer().getTickCount();
        PropellerBoostRegistry.beginServerTick(tick);
        if (tick % 20L == 0L) GasTankImpactTracker.expire(System.nanoTime());
        HydrogenExplosion.processPending();
    }

    public static void clearLevel(final Level level) {
        GasTankImpactTracker.clearLevel(level);
        PropellerBoostRegistry.clearLevel(level);
        HydrogenExplosion.clearLevel(level);
    }

    public static void clearAll() {
        GasTankImpactTracker.clearAll();
        PropellerBoostRegistry.clearAll();
        HydrogenExplosion.clearAll();
    }

    public static void onExplosion(final ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        // Detonate's ray hit list also exists for KEEP and TRIGGER_BLOCK.
        // Only destructive explosions may rupture inventories or queue a blast.
        final Explosion.BlockInteraction interaction = event.getExplosion().getBlockInteraction();
        if (interaction != Explosion.BlockInteraction.DESTROY
                && interaction != Explosion.BlockInteraction.DESTROY_WITH_DECAY) {
            return;
        }
        // Capture and rupture affected inventories while they still exist. Each
        // resulting blast is queued: neighbours can chain without recursively
        // calling explode, and only a bounded number emit in one server tick.
        // Sable's explosion rays already put actual carrier hits into this
        // same list in plot coordinates. Do not project the mixed world/plot
        // list again or infer hits from neighbouring cells: that bypasses the
        // explosion's resistance and shouldBlockExplode decisions.
        for (final var pos : List.copyOf(event.getAffectedBlocks())) {
            HydrogenExplosion.detonateAt(event.getLevel(), pos,
                    HydrogenExplosion.Trigger.EXTERNAL_EXPLOSION);
        }
    }
}
