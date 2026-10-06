package com.create.nautilus.api.internal;

import com.create.nautilus.api.NautilusAPI;
import com.create.nautilus.api.PhysicallySimulatedEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.UUID;

/**
 * NautilusAPI 的基础快照实现。
 *
 * <p>本类不在查询线程中重新扫描载具。后续物理/进水组件会在固定时机向
 * {@link NautilusStateStore} 发布不可变快照，API 查询因而可以安全地来自其他线程，
 * 也不会把昂贵的体素洪水填充意外放进渲染循环。</p>
 */
public final class NautilusAPIImpl implements NautilusAPI {
    public static final NautilusAPIImpl INSTANCE = new NautilusAPIImpl(NautilusStateStore.INSTANCE);

    private final NautilusStateStore stateStore;

    public NautilusAPIImpl() {
        this(NautilusStateStore.INSTANCE);
    }

    NautilusAPIImpl(final NautilusStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    @Override
    public double getSubmersionDepth(final PhysicallySimulatedEntity carrier) {
        return snapshot(carrier).submersionDepth();
    }

    @Override
    public double getFloodSpeed(final PhysicallySimulatedEntity carrier, final BlockPos holePos) {
        Objects.requireNonNull(holePos, "holePos");
        return snapshot(carrier).floodSpeeds().getOrDefault(holePos, 0.0D);
    }

    @Override
    public Vec3 getCurrentBuoyancy(final PhysicallySimulatedEntity carrier) {
        return snapshot(carrier).buoyancy();
    }

    @Override
    public boolean isCompartmentSealed(final PhysicallySimulatedEntity carrier, final AABB compartment) {
        Objects.requireNonNull(compartment, "compartment");
        return snapshot(carrier).sealedCompartments().stream()
                .anyMatch(candidate -> sameBounds(candidate, compartment));
    }

    @Override
    public double getOxygenLevel(final PhysicallySimulatedEntity carrier, final UUID compartmentId) {
        Objects.requireNonNull(compartmentId, "compartmentId");
        return snapshot(carrier).oxygenLevels().getOrDefault(compartmentId, 0.0D);
    }

    @Override
    public double getInternalPressure(final PhysicallySimulatedEntity carrier, final UUID compartmentId) {
        Objects.requireNonNull(compartmentId, "compartmentId");
        return snapshot(carrier).internalPressuresKpa().getOrDefault(compartmentId, 0.0D);
    }

    @Override
    public double getHullIntegrity(final PhysicallySimulatedEntity carrier) {
        return snapshot(carrier).hullIntegrity();
    }

    @Override
    public double getCompartmentWaterLevel(final PhysicallySimulatedEntity carrier,
                                           final UUID compartmentId) {
        Objects.requireNonNull(compartmentId, "compartmentId");
        return snapshot(carrier).waterLevels().getOrDefault(compartmentId, 0.0D);
    }

    private NautilusStateStore.CarrierSnapshot snapshot(final PhysicallySimulatedEntity carrier) {
        Objects.requireNonNull(carrier, "carrier");
        return stateStore.snapshot(carrier.id());
    }

    private static boolean sameBounds(final AABB first, final AABB second) {
        final double epsilon = 1.0E-7D;
        return Math.abs(first.minX - second.minX) <= epsilon
                && Math.abs(first.minY - second.minY) <= epsilon
                && Math.abs(first.minZ - second.minZ) <= epsilon
                && Math.abs(first.maxX - second.maxX) <= epsilon
                && Math.abs(first.maxY - second.maxY) <= epsilon
                && Math.abs(first.maxZ - second.maxZ) <= epsilon;
    }
}
