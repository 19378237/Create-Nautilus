package com.create.nautilus.mixin.hydrogen;

import com.create.nautilus.hydrogen.BoilerHydrogenBoosterBlockEntity;
import com.create.nautilus.config.NautilusConfig;
import com.simibubi.create.content.fluids.tank.BoilerData;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
public abstract class BoilerDataMixin implements BoilerHydrogenBoosterBlockEntity.NaturalHeat {
    @Unique private int createNautilus$previousHeat;
    @Unique private boolean createNautilus$previousPassive;
    @Unique private int createNautilus$naturalHeat = -1;

    @Override
    public int nautilus$naturalHeat(final FluidTankBlockEntity controller) {
        final BoilerData self = (BoilerData) (Object) this;
        if (createNautilus$naturalHeat < 0 || self.needsHeatLevelUpdate) {
            // A restored boiler or a pending source change must use the same
            // wrapped scan as Create, never a duplicate plot-only heater scan.
            if (self.updateTemperature(controller)) controller.notifyUpdate();
        }
        return createNautilus$naturalHeat;
    }

    @Inject(method = "clear", at = @At("RETURN"), require = 1)
    private void createNautilus$invalidateNaturalHeat(final CallbackInfo callback) {
        createNautilus$naturalHeat = -1;
    }

    @Inject(method = "updateTemperature", at = @At("HEAD"), require = 1)
    private void createNautilus$captureEffectiveHeat(final FluidTankBlockEntity controller,
                                                   final CallbackInfoReturnable<Boolean> callback) {
        final BoilerData self = (BoilerData) (Object) this;
        createNautilus$previousHeat = self.activeHeat;
        createNautilus$previousPassive = self.passiveHeat;
    }

    @Inject(method = "updateTemperature", at = @At("RETURN"),
            cancellable = true, require = 1)
    private void createNautilus$applySideMountedHydrogenHeat(
            final FluidTankBlockEntity controller,
            final CallbackInfoReturnable<Boolean> callback) {
        final BoilerData self = (BoilerData) (Object) this;
        createNautilus$naturalHeat = self.activeHeat;
        if (self.activeHeat >= BoilerHydrogenBoosterBlockEntity.MAX_HEAT_LEVEL) return;
        final int activeBoosters = BoilerHydrogenBoosterBlockEntity
                .activeAdjacentBoosterCount(controller);
        if (activeBoosters == 0) return;
        final int previous = self.activeHeat;
        final int effectiveBoosters = Math.min(activeBoosters,
                NautilusConfig.boilerBoosterMaxCount());
        self.activeHeat = Math.min(BoilerHydrogenBoosterBlockEntity.MAX_HEAT_LEVEL, previous
                + effectiveBoosters * NautilusConfig.boilerHydrogenHeatLevels());
        self.passiveHeat &= self.activeHeat == 0;
        callback.setReturnValue(self.activeHeat != createNautilus$previousHeat
                || self.passiveHeat != createNautilus$previousPassive);
    }
}
