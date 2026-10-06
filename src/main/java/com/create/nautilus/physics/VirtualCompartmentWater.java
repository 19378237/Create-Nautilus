package com.create.nautilus.physics;

import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Server-authoritative exact-height queries for virtual compartment water. */
public final class VirtualCompartmentWater {
    private VirtualCompartmentWater() {
    }

    /** Returns metres below an internal free surface, or zero when the sample is dry. */
    public static double serverDepthAt(final Entity entity, final Vec3 worldSample) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return 0.0D;
        }
        final AABB probe = new AABB(worldSample, worldSample).inflate(0.05D);
        double deepest = 0.0D;
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(
                level, new BoundingBox3d(probe))) {
            if (!(candidate instanceof ServerSubLevel carrier)) {
                continue;
            }
            final Vector3d local = carrier.logicalPose().transformPositionInverse(
                    new Vector3d(worldSample.x, worldSample.y, worldSample.z));
            final CarrierState state = NautilusData.carrier(carrier);
            final java.util.Optional<CompartmentState> room = state.compartmentAt(local);
            if (room.isEmpty() || room.get().waterMb() < 1.0D) {
                continue;
            }
            final double surface = CompartmentWaterGeometry.surfaceY(carrier, room.get());
            deepest = Math.max(deepest, surface - worldSample.y);
        }
        return Math.max(0.0D, deepest);
    }
}
