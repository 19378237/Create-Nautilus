package com.create.nautilus.mixin;

import com.create.nautilus.physics.CarrierSplitMigration;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/** The returned child UUID and exact assembly translation are required for inventory migration. */
@Mixin(value = SubLevelAssemblyHelper.class, remap = false)
public abstract class SableAssemblyMixin {
    @WrapMethod(method = "assembleBlocks", require = 1)
    private static ServerSubLevel createNautilus$transferSplitContents(
            final ServerLevel level, final BlockPos origin, final Iterable<BlockPos> blocks,
            final BoundingBox3ic bounds, final Operation<ServerSubLevel> original) {
        final CarrierSplitMigration.Capture capture = CarrierSplitMigration.capture(level, origin);
        CarrierSplitMigration.beginMovingBlocks();
        try {
            final ServerSubLevel child = original.call(level, origin, blocks, bounds);
            CarrierSplitMigration.record(capture, child);
            return child;
        } finally {
            CarrierSplitMigration.endMovingBlocks();
        }
    }
}
