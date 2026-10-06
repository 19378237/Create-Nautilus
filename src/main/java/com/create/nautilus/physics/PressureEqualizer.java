package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.HullBreach;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.OptionalDouble;

/**
 * Updates cabin gas pressure before water is moved through breaches.
 *
 * <p>The stored {@code standardGasMb} is a fixed-temperature ideal-gas proxy: it is the volume the
 * gas would occupy at configured surface pressure. Thus {@code P = P0 * Vstandard / Vfree}. An
 * underwater breach vents compressed cabin gas as bubbles toward ambient gas pressure, but water
 * cannot inject missing gas into a cabin; an opening exposed to air can exchange gas in either
 * direction. This prevents an artificial permanent air lock below the outside free surface.</p>
 */
public final class PressureEqualizer {
    private static final double MIN_FREE_GAS_MB = 1.0D;
    /** Keeps bubble venting ahead of the faster liquid solver without making it instantaneous. */
    private static final double UNDERWATER_VENT_RATE_SCALE = 4.0D;
    private static final double OPENING_AIR_CLEARANCE_METRES = 1.0E-4D;

    private PressureEqualizer() {
    }

    public static void tick(final ServerSubLevel carrier, final CarrierState state,
                            final double timeStepSeconds) {
        if (!(timeStepSeconds > 0.0D) || !Double.isFinite(timeStepSeconds)) {
            return;
        }

        final WaterEnvironment.QueryContext water =
                new WaterEnvironment.QueryContext(carrier.getLevel());
        for (final CompartmentState compartment : state.compartments()) {
            updateIdealPressure(compartment);

            for (final HullBreach breach : state.breaches()) {
                if (!breach.compartmentId().equals(compartment.id())) {
                    continue;
                }
                exchangeThroughBreach(carrier, compartment, breach, water, timeStepSeconds);
            }

            // Flooding reads this final value in the same Sable sub-step.
            updateIdealPressure(compartment);
        }
    }

    public static double pressureAt(final CompartmentState compartment) {
        final double freeGasMb = Math.max(MIN_FREE_GAS_MB,
                compartment.waterCapacityMb() - compartment.waterMb());
        return NautilusConfig.SURFACE_PRESSURE_KPA * compartment.standardGasMb() / freeGasMb;
    }

    /** Returns ambient plus hydrostatic pressure at a world-space point, in kPa. */
    public static double externalPressureKpa(final ServerLevel level, final Vector3dc worldPoint) {
        final OptionalDouble surface = WaterEnvironment.waterSurfaceY(level, worldPoint);
        final double ambient = ambientPressureKpa(level, worldPoint);
        if (surface.isEmpty() || surface.getAsDouble() <= worldPoint.y()) {
            return ambient;
        }

        final double gravity = DimensionPhysicsData.getGravity(level, worldPoint).length();
        final double depth = surface.getAsDouble() - worldPoint.y();
        // rho[kg/m3] * g[m/s2] * h[m] gives Pa; divide by 1000 for kPa.
        return ambient + NautilusConfig.WATER_DENSITY * gravity * depth / 1000.0D;
    }

    public static double ambientPressureKpa(final ServerLevel level, final Vector3dc worldPoint) {
        // Sable's dimension pressure is a dimension/altitude multiplier (1.0 at overworld sea
        // level), not kPa, so scale it by Nautilus' configured reference pressure.
        return NautilusConfig.SURFACE_PRESSURE_KPA
                * Math.max(0.0D, DimensionPhysicsData.getAirPressure(level, worldPoint));
    }

    private static void exchangeThroughBreach(final ServerSubLevel carrier,
                                               final CompartmentState compartment,
                                               final HullBreach breach,
                                               final WaterEnvironment.QueryContext water,
                                               final double timeStepSeconds) {
        final net.minecraft.core.Direction apertureFace = breach.atmosphericOpening()
                ? net.minecraft.core.Direction.DOWN : breach.interiorDirection();
        final Vector3d localHole = new Vector3d(
                breach.position().getX() + 0.5D,
                breach.position().getY() + 0.5D,
                breach.position().getZ() + 0.5D);
        final Vector3d worldHole = WaterEnvironment.toWorld(carrier, localHole);
        final OptionalDouble surface = breach.atmosphericOpening()
                ? WaterEnvironment.waterSurfaceIntersectingFace(carrier, breach.position(),
                apertureFace, water)
                : WaterEnvironment.waterSurfaceIntersectingCell(carrier, breach.position(), water);
        // A tilted open top can be partly wet and still have a dry route to atmosphere. Treat it
        // as fully submerged only after the sea covers the highest point of the actual inlet face.
        // This prevents a false vacuum/air lock while the low edge is admitting water.
        final double fullySubmergedHeight = breach.atmosphericOpening()
                ? CompartmentWaterGeometry.highestFaceWorldY(carrier, breach.position(),
                apertureFace)
                : CompartmentWaterGeometry.highestWorldY(carrier, breach.position());
        final boolean underwater = surface.isPresent()
                && surface.getAsDouble()
                > fullySubmergedHeight + OPENING_AIR_CLEARANCE_METRES;
        final double ambientPressure = ambientPressureKpa(carrier.getLevel(), worldHole);
        final double insidePressure = pressureAt(compartment);

        // Liquid cannot supply missing gas. It can, however, receive cabin gas which is squeezed
        // out as bubbles. Using the hydrostatic pressure at the breach as the gas target traps a
        // compressed air pocket and makes flooding stop with the cabin surface visibly below the
        // sea. The connected cabin's gas phase must instead vent toward ambient surface pressure.
        if (underwater && insidePressure <= ambientPressure) {
            return;
        }

        final double freeGasMb = Math.max(MIN_FREE_GAS_MB,
                compartment.waterCapacityMb() - compartment.waterMb());
        final double targetStandardGasMb = ambientPressure
                / Math.max(1.0E-6D, NautilusConfig.SURFACE_PRESSURE_KPA) * freeGasMb;
        if (!underwater) {
            // A hole with any dry path to atmosphere is not a throttled pressure vessel. Air moves
            // far faster than the liquid solver, so retain ambient pressure immediately. Gradual
            // 0.35/s equalisation here created an artificial air spring: a partly wetted open rim
            // compressed for one step, pushed water back the next step, and visibly twitched.
            compartment.exchangeStandardGas(
                    targetStandardGasMb - compartment.standardGasMb());
            return;
        }
        final double rate = Math.max(0.0D, NautilusConfig.PRESSURE_EQUALIZATION_RATE)
                * breach.areaSquareMetres()
                * UNDERWATER_VENT_RATE_SCALE;
        final double blend = 1.0D - Math.exp(-rate * timeStepSeconds);
        compartment.exchangeStandardGas((targetStandardGasMb - compartment.standardGasMb()) * blend);
    }

    private static void updateIdealPressure(final CompartmentState compartment) {
        compartment.setInternalPressureKpa(pressureAt(compartment));
    }
}
