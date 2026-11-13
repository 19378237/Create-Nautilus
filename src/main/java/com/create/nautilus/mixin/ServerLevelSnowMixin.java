package com.create.nautilus.mixin;

import com.create.nautilus.config.NautilusConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only suppresses weather-driven snow creation and thickening in Sable plots. */
@Mixin(value = ServerLevel.class, priority = 900)
public abstract class ServerLevelSnowMixin {
    @WrapOperation(method = "tickPrecipitation", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"),
            require = 1)
    private boolean createNautilus$preventCarrierSnow(
            final Biome biome, final LevelReader level, final BlockPos pos,
            final Operation<Boolean> original) {
        // Sable carriers share the parent ServerLevel; their plot coordinates identify ownership.
        if (NautilusConfig.preventNaturalSnowOnCarriers()
                && level instanceof final ServerLevel serverLevel
                && Sable.HELPER.getContaining(serverLevel, pos) instanceof ServerSubLevel) {
            return false;
        }
        return original.call(biome, level, pos);
    }

    @Dynamic("Added by ByePregen 1.0.x's ServerLevelWeatherTickMixin")
    @Inject(method = "byepregen$snowCanSurvive(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void createNautilus$preventByePregenCarrierSnow(
            final BlockPos pos, final BlockState support,
            final CallbackInfoReturnable<Boolean> callback) {
        // ByePregen 1.0.x bypasses vanilla precipitation; current versions use the native hook above.
        if (NautilusConfig.preventNaturalSnowOnCarriers()
                && Sable.HELPER.getContaining((ServerLevel) (Object) this, pos) instanceof ServerSubLevel) {
            callback.setReturnValue(false);
        }
    }
}
