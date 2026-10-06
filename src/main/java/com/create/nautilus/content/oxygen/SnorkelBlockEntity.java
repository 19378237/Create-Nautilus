package com.create.nautilus.content.oxygen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.oxygen.OxygenStorageManager;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/** Mechanically driven surface-air intake. */
public final class SnorkelBlockEntity extends KineticBlockEntity
        implements BlockEntitySubLevelActor {
    private static final int MIN_OPERATING_RPM = 64;
    private static final int FULL_EFFICIENCY_RPM = 256;

    public SnorkelBlockEntity(final BlockEntityType<?> type, final BlockPos pos,
                              final BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void sable$tick(final ServerSubLevel subLevel) {
        final double rpm = Math.abs(getSpeed());
        if (rpm < MIN_OPERATING_RPM
                || subLevel.getLevel().getGameTime() % 20L != 0L) {
            return;
        }

        final Vector3d top = subLevel.logicalPose().transformPosition(new Vector3d(
                worldPosition.getX() + 0.5D,
                worldPosition.getY() + 1.1D,
                worldPosition.getZ() + 0.5D));
        if (!subLevel.getLevel().getFluidState(
                BlockPos.containing(top.x(), top.y(), top.z())).isEmpty()) {
            return;
        }

        // 64 RPM supplies 25% of the configured rate; 256 RPM and above
        // supply the complete rate. The configured value remains the maximum
        // per-second oxygen output rather than being multiplied by RPM again.
        final double efficiency = Mth.clamp(
                rpm / FULL_EFFICIENCY_RPM, 0.0D, 1.0D);
        OxygenStorageManager.addToDryCompartments(subLevel,
                NautilusConfig.SNORKEL_OXYGEN_RATE * efficiency);
    }
}
