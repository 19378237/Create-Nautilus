package com.create.nautilus.api;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Nautilus 对“物理载具”的稳定公开视图。
 *
 * <p>经源码核对，Aeronautics 1.3.0 / Sable 2.0.x 中不存在名为
 * {@code PhysicallySimulatedEntity} 的上游类型；真实载具是 Sable 的
 * {@link ServerSubLevel}，刚体入口是 {@link RigidBodyHandle}。为保留设计要求中的
 * API 形状，同时不伪造上游包名，本接口明确包装真实 Sable 对象。</p>
 */
public interface PhysicallySimulatedEntity {
    /** 返回载具实际拥有的服务端 Sable 子世界。 */
    ServerSubLevel subLevel();

    /** Sable 持久化并在客户端/服务端保持一致的载具标识。 */
    default UUID id() {
        return Objects.requireNonNull(subLevel().getUniqueId(),
                "Sable sub-level has not received its persistent id yet");
    }

    /**
     * 获取当下刚体句柄。载具卸载或物理系统尚未就绪时允许返回 null，调用者
     * 不应跨 tick 缓存该句柄。
     */
    default @Nullable RigidBodyHandle rigidBody() {
        return RigidBodyHandle.of(subLevel());
    }

    static PhysicallySimulatedEntity of(final ServerSubLevel subLevel) {
        return new SableCarrier(Objects.requireNonNull(subLevel, "subLevel"));
    }

    /** 简单不可变适配器；record 的值语义也便于附属模组短期传递载具引用。 */
    record SableCarrier(ServerSubLevel subLevel) implements PhysicallySimulatedEntity {
        public SableCarrier {
            Objects.requireNonNull(subLevel, "subLevel");
        }
    }
}
