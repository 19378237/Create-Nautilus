package com.create.nautilus.mixin;

import com.create.nautilus.kinetics.PropellerEfficiency;
import com.create.nautilus.kinetics.PropellerEnvironment;
import com.create.nautilus.kinetics.PropellerDisplayState;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the Nautilus thrust curve to Aeronautics' compact propellers. */
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity", priority = 1000)
public abstract class BasePropellerBlockEntityMixin implements PropellerEnvironment {
    @Shadow public abstract float getRadius();
    @Shadow public abstract float getOffset();
    @Shadow public abstract Direction getBlockDirection();
    @Unique private volatile PropellerDisplayState nautilus$environment = PropellerDisplayState.UNKNOWN;

    @Override
    public double nautilus$submergedFraction() { return nautilus$environment.submergedFraction(); }

    @Override
    public double nautilus$displayThrustMultiplier() { return nautilus$environment.thrustMultiplier(); }

    @Inject(method = "tick", at = @At("RETURN"), require = 1)
    private void createNautilus$sampleLocalWater(final CallbackInfo callback) {
        final KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        final PropellerDisplayState next = nautilus$environment.update(self,
                PropellerEfficiency.sampleLayer(self,
                        getBlockDirection(), getOffset(), 0.0D, getRadius() * getRadius()));
        if (next != nautilus$environment) {
            nautilus$environment = next;
            self.sendData();
        }
    }

    @Inject(method = "write", at = @At("RETURN"), require = 1)
    private void createNautilus$writeEnvironment(final CompoundTag tag,
                                                final HolderLookup.Provider registries,
                                                final boolean clientPacket, final CallbackInfo callback) {
        nautilus$environment.write(tag, clientPacket);
    }

    @Inject(method = "read", at = @At("RETURN"), require = 1)
    private void createNautilus$readEnvironment(final CompoundTag tag,
                                               final HolderLookup.Provider registries,
                                               final boolean clientPacket, final CallbackInfo callback) {
        nautilus$environment = PropellerDisplayState.read(tag, clientPacket);
    }

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
