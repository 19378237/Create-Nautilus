package com.create.nautilus.physics;

import com.create.nautilus.data.CarrierState;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/** Extension seam for pressure-rated hull blocks; current generic hull retains its stored integrity. */
public final class HullDamageHandler {
    private HullDamageHandler() {}
    public static void tick(final ServerSubLevel carrier, final CarrierState state, final double dt) {
        state.setHullIntegrity(state.hullIntegrity());
    }
}
