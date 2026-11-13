package com.create.nautilus.physics;

import com.create.nautilus.data.HullBreach;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * External-only samples shared by the pressure/flooding/pressure split of one carrier sub-step.
 * The dispatcher drops this object before ballast can change the carrier's rotation point.
 */
final class ExternalApertureSamples {
    private final ServerSubLevel carrier;
    private final WaterEnvironment.QueryContext water;
    private Map<Key, Sample> samples;

    ExternalApertureSamples(final ServerSubLevel carrier, final WaterEnvironment.QueryContext water) {
        this.carrier = carrier;
        this.water = water;
    }

    Sample sample(final HullBreach breach) {
        // A volumetric breach has no distinguished face; an open-top inlet always uses DOWN,
        // regardless of a saved/reconciled interiorDirection. Keep the packed position intact.
        final Kind kind = breach.atmosphericOpening() ? Kind.FACE : Kind.VOLUME;
        final Direction face = Direction.DOWN;
        final Key key = new Key(breach.position().asLong(), kind, face);
        final WaterQueryDiagnostics diagnostics = water.diagnostics();
        if (samples != null) {
            final Sample previous = samples.get(key);
            if (previous != null) {
                if (diagnostics != null) diagnostics.apertureSampleHits++;
                return previous;
            }
        }
        if (diagnostics != null) diagnostics.apertureSampleMisses++;

        final long customHeightQueriesBefore = water.customHeightQuerySerial();
        final BlockPos position = breach.position();
        final CompartmentWaterGeometry.ProjectedInterval aperture = kind == Kind.FACE
                ? CompartmentWaterGeometry.projectedFaceInterval(
                carrier.logicalPose(), new AABB(position), face)
                : CompartmentWaterGeometry.projectedCell(carrier, position);
        final OptionalDouble surface = kind == Kind.FACE
                ? WaterEnvironment.waterSurfaceIntersectingFace(carrier, position, face, water, aperture)
                : WaterEnvironment.waterSurfaceIntersectingCell(carrier, position, water, aperture);
        final Vector3d worldHole = WaterEnvironment.toWorld(carrier, new Vector3d(
                position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D));
        final Sample result = new Sample(aperture, surface,
                PressureEqualizer.ambientPressureKpa(carrier.getLevel(), worldHole));
        // Modded fluid heights can depend on mutable neighbouring state. Preserve every original
        // world-aware call for these samples; this guard is active with diagnostics disabled too.
        if (water.customHeightQuerySerial() == customHeightQueriesBefore) {
            if (samples == null) samples = new HashMap<>();
            samples.put(key, result);
        } else if (diagnostics != null) {
            diagnostics.apertureSampleCustomBypasses++;
        }
        return result;
    }

    private enum Kind { VOLUME, FACE }

    private record Key(long packedPosition, Kind kind, Direction face) {}

    record Sample(CompartmentWaterGeometry.ProjectedInterval aperture, OptionalDouble surface,
                  double ambientPressureKpa) {}
}
