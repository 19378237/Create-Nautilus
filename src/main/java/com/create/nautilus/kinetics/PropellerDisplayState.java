package com.create.nautilus.kinetics;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Transient server values carried by Create's ordinary block-entity update tags. */
public record PropellerDisplayState(double submergedFraction, double thrustMultiplier) {
    public static final PropellerDisplayState UNKNOWN = new PropellerDisplayState(-1.0D, 1.0D);
    private static final String KEY = "NautilusPropeller";

    public PropellerDisplayState update(final KineticBlockEntity blockEntity, final double submerged) {
        final double multiplier = PropellerEfficiency.thrustMultiplier(blockEntity, submerged);
        return Double.compare(submergedFraction, submerged) == 0
                && Double.compare(thrustMultiplier, multiplier) == 0
                ? this : new PropellerDisplayState(submerged, multiplier);
    }

    public void write(final CompoundTag tag, final boolean clientPacket) {
        if (!clientPacket) return;
        final CompoundTag display = new CompoundTag();
        display.putDouble("Submerged", submergedFraction);
        display.putDouble("Multiplier", thrustMultiplier);
        tag.put(KEY, display);
    }

    public static PropellerDisplayState read(final CompoundTag tag, final boolean clientPacket) {
        // This is derived display state, never an input restored from a saved ship or chunk.
        if (!clientPacket || !tag.contains(KEY, Tag.TAG_COMPOUND)) return UNKNOWN;
        final CompoundTag display = tag.getCompound(KEY);
        if (!display.contains("Submerged", Tag.TAG_DOUBLE)
                || !display.contains("Multiplier", Tag.TAG_DOUBLE)) return UNKNOWN;
        final double submerged = display.getDouble("Submerged");
        final double multiplier = display.getDouble("Multiplier");
        if (!Double.isFinite(submerged) || (submerged != -1.0D && (submerged < 0.0D || submerged > 1.0D))
                || !Double.isFinite(multiplier) || multiplier < 0.0D) return UNKNOWN;
        return new PropellerDisplayState(submerged, multiplier);
    }
}
