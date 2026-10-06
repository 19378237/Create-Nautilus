package com.create.nautilus.api.internal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理线程向公开 API 传递数据的最小边界。
 *
 * <p>这是内部 SPI 而不是持久化存档。第二、三部分会在舱室或刚体状态变化后
 * 发布新快照；卸载载具时必须调用 {@link #remove(UUID)}，避免保存失效引用。</p>
 */
@ApiStatus.Internal
public final class NautilusStateStore {
    public static final NautilusStateStore INSTANCE = new NautilusStateStore();

    private static final CarrierSnapshot EMPTY = new CarrierSnapshot(
            0.0D, Vec3.ZERO, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), 1.0D);

    private final Map<UUID, CarrierSnapshot> snapshots = new ConcurrentHashMap<>();

    private NautilusStateStore() {
    }

    public CarrierSnapshot snapshot(final UUID carrierId) {
        Objects.requireNonNull(carrierId, "carrierId");
        return snapshots.getOrDefault(carrierId, EMPTY);
    }

    public void publish(final UUID carrierId, final CarrierSnapshot snapshot) {
        snapshots.put(Objects.requireNonNull(carrierId, "carrierId"),
                Objects.requireNonNull(snapshot, "snapshot"));
    }

    public void remove(final UUID carrierId) {
        snapshots.remove(Objects.requireNonNull(carrierId, "carrierId"));
    }

    public void clear() {
        snapshots.clear();
    }

    /**
     * 单次物理状态的不可变值对象。BlockPos、Vec3 和 AABB 本身均不可变；集合在
     * 构造时复制，防止发布者在 API 读到一半时继续修改。
     */
    public record CarrierSnapshot(
            double submersionDepth,
            Vec3 buoyancy,
            Map<BlockPos, Double> floodSpeeds,
            List<AABB> sealedCompartments,
            Map<UUID, Double> oxygenLevels,
            Map<UUID, Double> internalPressuresKpa,
            Map<UUID, Double> waterLevels,
            double hullIntegrity
    ) {
        public CarrierSnapshot {
            if (!Double.isFinite(submersionDepth)) {
                throw new IllegalArgumentException("submersionDepth must be finite");
            }
            submersionDepth = Math.clamp(submersionDepth, 0.0D, 1.0D);
            buoyancy = Objects.requireNonNull(buoyancy, "buoyancy");
            floodSpeeds = Map.copyOf(Objects.requireNonNull(floodSpeeds, "floodSpeeds"));
            sealedCompartments = List.copyOf(
                    Objects.requireNonNull(sealedCompartments, "sealedCompartments"));
            oxygenLevels = Map.copyOf(Objects.requireNonNull(oxygenLevels, "oxygenLevels"));
            internalPressuresKpa = Map.copyOf(
                    Objects.requireNonNull(internalPressuresKpa, "internalPressuresKpa"));
            waterLevels = Map.copyOf(Objects.requireNonNull(waterLevels, "waterLevels"));
            if (!Double.isFinite(hullIntegrity)) {
                throw new IllegalArgumentException("hullIntegrity must be finite");
            }
            hullIntegrity = Math.clamp(hullIntegrity, 0.0D, 1.0D);
        }
    }
}
