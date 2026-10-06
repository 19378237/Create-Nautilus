package com.create.nautilus.mixin.hydrogen;

import com.create.nautilus.hydrogen.BoilerHydrogenBoosterBlockEntity;
import com.create.nautilus.config.NautilusConfig;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One narrow compatibility point for a side-mounted hydrogen boiler booster.
 *
 * <p>Create's public boiler-heater API samples only blocks directly below the
 * tank. A side attachment therefore cannot participate through that API. This
 * hook runs after Create has completed its own heat scan and adds the configured
 * effective levels, preserving Create's level-18 ceiling and every native
 * goggle/engine calculation that reads {@link BoilerData#activeHeat}.</p>
 */
@Mixin(value = BoilerData.class, priority = 1000)
public abstract class BoilerDataMixin {
    @Inject(method = "updateTemperature", at = @At("RETURN"),
            cancellable = true, require = 1)
    private void createNautilus$applySideMountedHydrogenHeat(
            final FluidTankBlockEntity controller,
            final CallbackInfoReturnable<Boolean> callback) {
        final BoilerData self = (BoilerData) (Object) this;
        final int activeBoosters = BoilerHydrogenBoosterBlockEntity
                .activeAdjacentBoosterCount(controller);
        if (self.activeHeat >= BoilerHydrogenBoosterBlockEntity.MAX_HEAT_LEVEL
                || activeBoosters == 0) {
            return;
        }
        final int previous = self.activeHeat;
        final int effectiveBoosters = Math.min(activeBoosters,
                NautilusConfig.boilerBoosterMaxCount());
        self.activeHeat = Math.min(BoilerHydrogenBoosterBlockEntity.MAX_HEAT_LEVEL, previous
                + effectiveBoosters * NautilusConfig.boilerHydrogenHeatLevels());
        if (self.activeHeat != previous && !callback.getReturnValue()) {
            callback.setReturnValue(true);
        }
    }
}
