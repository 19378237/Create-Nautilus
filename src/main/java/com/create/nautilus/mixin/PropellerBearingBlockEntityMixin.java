package com.create.nautilus.mixin;

import com.create.nautilus.kinetics.PropellerEfficiency;
import com.create.nautilus.kinetics.PropellerEnvironment;
import com.create.nautilus.kinetics.PropellerDisplayState;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the same curve to assembled sail propellers. */
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity", priority = 1000)
public abstract class PropellerBearingBlockEntityMixin implements PropellerEnvironment {
    @Shadow protected List<BlockPos> sailPositions;
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
        if (!(Sable.HELPER.getContaining(self.getLevel(), self.getBlockPos())
                instanceof final ServerSubLevel carrier)) {
            nautilus$publishEnvironment(self, -1.0D);
            return;
        }
        double wetArea = 0.0D;
        double totalArea = 0.0D;
        final Direction direction = getBlockDirection();
        // Mirror Aeronautics' layer bounds using its sail positions. Keeping
        // the shadow type to JDK/Minecraft classes also works when Aeronautics
        // is supplied as a nested runtime jar rather than a compile-time API.
        final Map<Integer, double[]> layers = new HashMap<>();
        for (final BlockPos sail : sailPositions) {
            final int offset = sail.getX() * direction.getStepX()
                    + sail.getY() * direction.getStepY() + sail.getZ() * direction.getStepZ();
            final double radius = Math.sqrt((double) sail.getX() * sail.getX()
                    + (double) sail.getY() * sail.getY() + (double) sail.getZ() * sail.getZ()
                    - (double) offset * offset);
            final double[] range = layers.computeIfAbsent(offset, key -> new double[]{radius, radius});
            range[0] = Math.min(range[0], radius);
            range[1] = Math.max(range[1], radius);
        }
        for (final var layer : layers.entrySet()) {
            final double inner = Math.max(0.0D, layer.getValue()[0] - 0.5D);
            final double outer = layer.getValue()[1] + 0.5D;
            final double fraction = PropellerEfficiency.sampleLayer(self, carrier,
                    direction, layer.getKey() + 1.0D, inner * inner, outer * outer);
            if (fraction < 0.0D) continue;
            final double area = outer * outer - inner * inner;
            wetArea += area * fraction;
            totalArea += area;
        }
        nautilus$publishEnvironment(self, totalArea > 0.0D ? wetArea / totalArea : -1.0D);
    }

    @Unique
    private void nautilus$publishEnvironment(final KineticBlockEntity self, final double submerged) {
        final PropellerDisplayState next = nautilus$environment.update(self, submerged);
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
    private void createNautilus$scaleBearingThrust(final CallbackInfoReturnable<Double> callback) {
        final KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        callback.setReturnValue(callback.getReturnValueD()
                * PropellerEfficiency.thrustMultiplier(self));
    }

    @Inject(method = "getAirflow", at = @At("RETURN"), cancellable = true, require = 1)
    private void createNautilus$scaleBearingAirflow(final CallbackInfoReturnable<Double> callback) {
        final KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        callback.setReturnValue(callback.getReturnValueD()
                * PropellerEfficiency.thrustMultiplier(self));
    }
}
