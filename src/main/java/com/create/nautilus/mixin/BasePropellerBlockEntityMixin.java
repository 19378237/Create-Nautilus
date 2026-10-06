package com.create.nautilus.mixin;

import com.create.nautilus.kinetics.PropellerEfficiency;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the Nautilus thrust curve to Aeronautics' compact propellers. */
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity", priority = 1000)
public abstract class BasePropellerBlockEntityMixin {
    @Inject(method = "getThrust", at = @At("RETURN"), cancellable = true, require = 1)
    private void createNautilus$scaleThrust(final CallbackInfoReturnable<Double> callback) {
        final KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        callback.setReturnValue(callback.getReturnValueD()
                * PropellerEfficiency.thrustMultiplier(self));
    }

    @Inject(method = "getAirflow", at = @At("RETURN"), cancellable = true, require = 1)
    private void createNautilus$scaleAirflow(final CallbackInfoReturnable<Double> callback) {
        final KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        callback.setReturnValue(callback.getReturnValueD()
                * PropellerEfficiency.thrustMultiplier(self));
    }
}
