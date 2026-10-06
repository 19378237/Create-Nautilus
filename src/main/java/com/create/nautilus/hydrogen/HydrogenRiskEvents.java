package com.create.nautilus.hydrogen;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** NeoForge bridges for the two non-Sable rupture causes. */
public final class HydrogenRiskEvents {
    private HydrogenRiskEvents() {
    }

    public static void onServerTick(final ServerTickEvent.Pre event) {
        final long tick = event.getServer().getTickCount();
        PropellerBoostRegistry.beginServerTick(tick);
    }

    public static void onBlockBroken(final BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof Level level)
                || level.isClientSide) {
            return;
        }
        HydrogenExplosion.detonateAt(level, event.getPos(),
                HydrogenExplosion.Trigger.DIRECT_DESTRUCTION);
    }

    public static void onLivingDestroyBlock(final LivingDestroyBlockEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide) {
            return;
        }
        HydrogenExplosion.detonateAt(event.getEntity().level(), event.getPos(),
                HydrogenExplosion.Trigger.DIRECT_DESTRUCTION);
    }

    public static void onExplosion(final ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        // A hydrogen blast creates another ExplosionEvent. Immediate handling
        // is intentional: adjacent tanks still exist at this point and can
        // therefore chain before the outer explosion removes their blocks.
        for (final var pos : List.copyOf(event.getAffectedBlocks())) {
            HydrogenExplosion.detonateAt(event.getLevel(), pos,
                    HydrogenExplosion.Trigger.EXTERNAL_EXPLOSION);
        }
        detonateAffectedCarrierTanks(event);
    }

    /**
     * Vanilla's affected-block list is expressed in the parent world. Convert
     * those cells into every Sable carrier's local plot so TNT and torpedoes
     * can rupture tanks mounted on a physicalized vessel as well.
     */
    private static void detonateAffectedCarrierTanks(final ExplosionEvent.Detonate event) {
        final SubLevelContainer rawContainer =
                SubLevelContainer.getContainer(event.getLevel());
        if (!(rawContainer instanceof ServerSubLevelContainer container)
                || event.getAffectedBlocks().isEmpty()) {
            return;
        }
        final Set<GasTankBlockEntity> hitTanks = new HashSet<>();
        for (final ServerSubLevel carrier : container.getAllSubLevels()) {
            for (final BlockPos worldCell : event.getAffectedBlocks()) {
                final Vec3 localPoint = carrier.logicalPose()
                        .transformPositionInverse(Vec3.atCenterOf(worldCell));
                final BlockPos localCell = BlockPos.containing(localPoint);
                // Rotation can place a world-cell centre just across a local
                // voxel boundary; inspect its immediate neighbours as well.
                for (final BlockPos candidate : BlockPos.betweenClosed(
                        localCell.offset(-1, -1, -1), localCell.offset(1, 1, 1))) {
                    if (carrier.getLevel().getBlockEntity(candidate)
                            instanceof GasTankBlockEntity tank) {
                        hitTanks.add(tank);
                    }
                }
            }
        }
        for (final GasTankBlockEntity tank : hitTanks) {
            HydrogenExplosion.detonateTank(tank,
                    HydrogenExplosion.Trigger.EXTERNAL_EXPLOSION);
        }
    }
}
