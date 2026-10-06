package com.create.nautilus.api;

import com.create.nautilus.api.internal.NautilusAPIImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Create: Nautilus 的只读公开 API。
 *
 * <p>所有位置参数均使用载具 plot 的局部方块坐标；所有返回向量使用外部世界坐标。
 * 物理状态仅由服务端权威模拟发布，状态尚未产生时实现返回中性值。</p>
 */
public interface NautilusAPI {
    /** 获取全局 API 实例。附属模组应调用此方法，而不是自行 new 实现类。 */
    static NautilusAPI get() {
        return Holder.INSTANCE;
    }

    /** @return 载具整体浸没比例，保证位于 0.0 到 1.0 之间 */
    double getSubmersionDepth(PhysicallySimulatedEntity carrier);

    /** @return 指定局部破洞当前的带符号流速；正数进水，负数排水 */
    double getFloodSpeed(PhysicallySimulatedEntity carrier, BlockPos holePos);

    /**
     * @return 当前物理子步由 Nautilus 额外施加的浮力，单位 N，外部世界坐标；
     *         不包含 Sable 对实体方块施加的原生固体浮力
     */
    Vec3 getCurrentBuoyancy(PhysicallySimulatedEntity carrier);

    /** @return 给定局部包围盒是否与已确认的密封舱室精确对应 */
    boolean isCompartmentSealed(PhysicallySimulatedEntity carrier, AABB compartment);

    /**
     * Returns the oxygen fill ratio for one compartment. The value is clamped to {@code [0, 1]};
     * an unknown compartment returns zero. Storage and transfer APIs use mB, while this public
     * query deliberately exposes a capacity-independent level suitable for gauges and automation.
     */
    double getOxygenLevel(PhysicallySimulatedEntity carrier, UUID compartmentId);

    /** @return the latest simulated absolute pressure of a compartment, in kPa */
    double getInternalPressure(PhysicallySimulatedEntity carrier, UUID compartmentId);

    /** @return normalized hull integrity in {@code [0, 1]} (reserved for pressure-rated hulls) */
    double getHullIntegrity(PhysicallySimulatedEntity carrier);

    /** @return compartment flooding ratio in {@code [0, 1]} */
    double getCompartmentWaterLevel(PhysicallySimulatedEntity carrier, UUID compartmentId);

    /** Initialization-on-demand holder，避免 API 类加载时触发完整模组初始化。 */
    final class Holder {
        private static final NautilusAPI INSTANCE = NautilusAPIImpl.INSTANCE;

        private Holder() {
        }
    }
}
