package com.create.nautilus.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 挂在父 ServerLevel 上的可序列化 Attachment。
 *
 * <p>Sable 的 ServerSubLevel 不是 NeoForge Attachment holder，因此不能把附件
 * 直接装到载具对象上。这里按 carrier UUID -> compartment UUID 分层存储，既使用
 * 官方 Attachment 持久化，又不会把状态绑到可能被拆分/重建的方块实体。</p>
 */
public final class NautilusLevelData implements INBTSerializable<CompoundTag> {
    private final Map<UUID, CarrierState> carriers = new LinkedHashMap<>();

    public CarrierState carrier(final UUID id) {
        return carriers.computeIfAbsent(id, CarrierState::new);
    }

    public Collection<CarrierState> carriers() {
        return carriers.values();
    }

    public void removeCarrier(final UUID id) {
        carriers.remove(id);
    }

    @Override
    public CompoundTag serializeNBT(final HolderLookup.Provider provider) {
        final CompoundTag root = new CompoundTag();
        final ListTag list = new ListTag();
        carriers.values().forEach(carrier -> list.add(carrier.save()));
        root.put("carriers", list);
        return root;
    }

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag nbt) {
        carriers.clear();
        for (final Tag entry : nbt.getList("carriers", Tag.TAG_COMPOUND)) {
            final CarrierState carrier = CarrierState.load((CompoundTag) entry);
            carriers.put(carrier.carrierId(), carrier);
        }
    }
}

