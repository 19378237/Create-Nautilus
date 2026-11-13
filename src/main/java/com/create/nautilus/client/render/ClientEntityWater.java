package com.create.nautilus.client.render;

import com.create.nautilus.physics.ParentWaterContact;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/** Entity queries share the renderer's interpolated pose and revision-consistent water levels. */
public final class ClientEntityWater {
    private ClientEntityWater() { }

    public static double depthAt(final Entity entity, final Vec3 sample) {
        double deepest = 0.0D;
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(entity.level(),
                new BoundingBox3d(new AABB(sample, sample).inflate(0.05D)))) {
            if (!(candidate instanceof ClientSubLevel carrier)) continue;
            final Vector3d local = carrier.renderPose().transformPositionInverse(
                    new Vector3d(sample.x, sample.y, sample.z));
            deepest = Math.max(deepest,
                    ClientCompartmentVisualCache.waterDepthAt(carrier, local, sample.y));
        }
        return deepest;
    }

    public static double bodyDepth(final Entity entity) {
        double deepest = 0.0D;
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(entity.level(),
                new BoundingBox3d(entity.getBoundingBox()))) {
            if (candidate instanceof ClientSubLevel carrier) {
                deepest = Math.max(deepest, ClientCompartmentVisualCache.bodyWaterDepthAt(
                        carrier, entity.getBoundingBox()));
            }
        }
        return deepest;
    }

    public static boolean hasBodyCompartment(final Entity entity) {
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(entity.level(),
                new BoundingBox3d(entity.getBoundingBox()))) {
            if (candidate instanceof ClientSubLevel carrier
                    && ClientCompartmentVisualCache.hasBodyCompartmentAt(carrier, entity.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    /** Same exact parent/body subtraction as the server, using the client's known room revision. */
    public static float parentWaterHeight(final Entity entity, final BlockPos cell, final float nativeHeight) {
        final List<ParentWaterContact.OcclusionVolume> volumes = new ArrayList<>();
        for (final SubLevel candidate : Sable.HELPER.getAllIntersecting(entity.level(),
                new BoundingBox3d(entity.getBoundingBox()))) {
            if (!(candidate instanceof ClientSubLevel carrier)) continue;
            final var cells = ClientCompartmentVisualCache.entityOcclusionCells(carrier);
            if (cells != null) volumes.add(new ParentWaterContact.OcclusionVolume(carrier.renderPose(), cells));
        }
        return ParentWaterContact.height(entity.getBoundingBox(), cell, nativeHeight, volumes);
    }
}
