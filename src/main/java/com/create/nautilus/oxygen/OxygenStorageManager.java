package com.create.nautilus.oxygen;

import com.create.nautilus.api.PhysicallySimulatedEntity;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Authoritative oxygen facade over the carrier Attachment. */
public final class OxygenStorageManager {
    private OxygenStorageManager() {
    }

    public static double consumeOxygen(final PhysicallySimulatedEntity carrier,
                                       final UUID compartmentId, final double amountMb) {
        return consumeOxygen(carrier.subLevel(), compartmentId, amountMb);
    }

    public static double consumeOxygen(final ServerSubLevel carrier,
                                       final UUID compartmentId, final double amountMb) {
        return NautilusData.carrier(carrier).compartment(compartmentId)
                .map(compartment -> compartment.consumeOxygen(amountMb)).orElse(0.0D);
    }

    public static double addOxygen(final PhysicallySimulatedEntity carrier,
                                   final UUID compartmentId, final double amountMb) {
        return addOxygen(carrier.subLevel(), compartmentId, amountMb);
    }

    public static double addOxygen(final ServerSubLevel carrier,
                                   final UUID compartmentId, final double amountMb) {
        return NautilusData.carrier(carrier).compartment(compartmentId)
                .map(compartment -> compartment.addOxygen(amountMb)).orElse(0.0D);
    }

    public static Optional<CompartmentState> compartmentAt(final ServerSubLevel carrier,
                                                            final BlockPos plotPosition) {
        return NautilusData.carrier(carrier).compartmentAt(plotPosition)
                .filter(CompartmentState::sealed);
    }

    /** Fairly distributes oxygen among sealed, dry compartments with available capacity. */
    public static double addToDryCompartments(final ServerSubLevel carrier, final double amountMb) {
        double remaining = Math.max(0.0D, amountMb);
        List<CompartmentState> targets = NautilusData.carrier(carrier).compartments().stream()
                .filter(CompartmentState::isDry)
                .filter(c -> c.oxygenMb() + 1.0E-9D < c.capacityMb())
                .toList();

        while (remaining > 1.0E-9D && !targets.isEmpty()) {
            final double share = remaining / targets.size();
            double accepted = 0.0D;
            for (final CompartmentState target : targets) {
                accepted += target.addOxygen(share);
            }
            remaining -= accepted;
            if (accepted <= 1.0E-9D) {
                break;
            }
            targets = targets.stream()
                    .filter(c -> c.oxygenMb() + 1.0E-9D < c.capacityMb())
                    .toList();
        }
        return Math.max(0.0D, amountMb - remaining);
    }

    public static CarrierState state(final ServerSubLevel carrier) {
        return NautilusData.carrier(carrier);
    }
}
