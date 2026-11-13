package com.create.nautilus.physics;

import com.create.nautilus.data.CarrierState;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/** Server-authoritative exact-height queries for virtual compartment water. */
public final class VirtualCompartmentWater {
    private VirtualCompartmentWater() {
    }

    /** Includes compartment contact at the body's edge even when all centre samples lie outside. */
    public static boolean serverHasBodyCompartment(final Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        final AABB body = entity.getBoundingBox();
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(body))) {
            if (!(candidate instanceof ServerSubLevel carrier)) continue;
            final CarrierState state = NautilusData.carrier(carrier);
            if (state == null) continue;
            for (final CompartmentState room : state.compartments()) {
                if (CompartmentWaterContact.depth(carrier.logicalPose(), body, body.maxY,
                        cell -> room.contains(cell) && carrier.getLevel().hasChunkAt(cell)) > 0.0D) return true;
            }
        }
        return false;
    }

    /** Every tracked room owns parent-water occlusion; its virtual/real water supplies wetness. */
    public static boolean serverOccludesParentWater(final Entity entity, final Vec3 worldSample) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(level,
                new BoundingBox3d(new AABB(worldSample, worldSample).inflate(0.05D)))) {
            if (!(candidate instanceof ServerSubLevel carrier)) continue;
            final CarrierState state = NautilusData.carrier(carrier);
            if (state == null) continue;
            final Vector3d local = carrier.logicalPose().transformPositionInverse(
                    new Vector3d(worldSample.x, worldSample.y, worldSample.z));
            final var room = state.compartmentAt(local);
            if (room.isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Parent-loop water height after subtracting the exact union of intersecting room voxels. */
    public static float serverParentWaterHeight(final Entity entity, final BlockPos cell,
                                               final float nativeHeight) {
        if (!(entity.level() instanceof ServerLevel level)) return nativeHeight;
        final List<ParentWaterContact.OcclusionVolume> volumes = new ArrayList<>();
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(level,
                new BoundingBox3d(entity.getBoundingBox()))) {
            if (!(candidate instanceof ServerSubLevel carrier)) continue;
            final CarrierState state = NautilusData.carrier(carrier);
            if (state == null || state.compartments().isEmpty()) continue;
            volumes.add(new ParentWaterContact.OcclusionVolume(carrier.logicalPose(),
                    pos -> carrier.getLevel().hasChunkAt(pos) && state.compartmentAt(pos).isPresent()));
        }
        return ParentWaterContact.height(entity.getBoundingBox(), cell, nativeHeight, volumes);
    }

    /** Uses the whole body, including a wet voxel touched only along an entity's edge. */
    public static double serverBodyDepth(final Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return 0.0D;
        double deepest = 0.0D;
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(level,
                new BoundingBox3d(entity.getBoundingBox()))) {
            if (!(candidate instanceof ServerSubLevel carrier)) continue;
            final CarrierState state = NautilusData.carrier(carrier);
            if (state == null) continue;
            for (final CompartmentState room : state.compartments()) {
                if (room.waterMb() < 1.0D) continue;
                deepest = Math.max(deepest, CompartmentWaterContact.depth(carrier.logicalPose(),
                        entity.getBoundingBox(), CompartmentWaterGeometry.surfaceY(carrier, room),
                        cell -> room.waterCells().contains(cell.asLong())
                                && carrier.getLevel().hasChunkAt(cell)));
            }
        }
        return deepest;
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
            // Real sources keep their native fluid boundary; the continuous field only occupies
            // the remaining virtual-water cells, even when its plane crosses a source cell.
            if (room.isEmpty() || room.get().waterMb() < 1.0D
                    || !room.get().waterCells().contains(BlockPos.containing(
                    local.x(), local.y(), local.z()).asLong())) {
                continue;
            }
            final double surface = CompartmentWaterGeometry.surfaceY(carrier, room.get());
            deepest = Math.max(deepest, surface - worldSample.y);
        }
        return Math.max(0.0D, deepest);
    }
}
