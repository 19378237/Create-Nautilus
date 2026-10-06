package com.create.nautilus.mixin;

import com.create.nautilus.physics.FloodingHandler;
import dev.ryanhcode.sable.SableCommonEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks Sable's authoritative, already-committed plot block changes. */
@Mixin(value = SableCommonEvents.class, priority = 1000, remap = false)
public abstract class SableBlockChangeMixin {
    @Inject(method = "handleBlockChange", at = @At("RETURN"), require = 1)
    private static void createNautilus$afterCommittedBlockChange(
            final ServerLevel level, final LevelChunk chunk,
            final int x, final int y, final int z,
            final BlockState oldState, final BlockState newState,
            final CallbackInfo callback) {
        FloodingHandler.onCommittedBlockChange(level, new BlockPos(x, y, z),
                oldState, newState);
    }
}
