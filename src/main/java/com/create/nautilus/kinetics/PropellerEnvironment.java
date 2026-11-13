package com.create.nautilus.kinetics;

/** Main-thread sampling for physics callbacks and the server's synchronized display multiplier. */
public interface PropellerEnvironment {
    double nautilus$submergedFraction();

    default double nautilus$displayThrustMultiplier() {
        return 1.0D;
    }
}
