package com.create.nautilus.data;

import com.create.nautilus.config.NautilusConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3dc;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一个密封舱室的持久状态。
 *
 * <p>氧气和舱水均使用 mB；一 Minecraft 方块按一立方米处理。cells 只作为
 * 本次运行的精确成员缓存，舱室重新扫描后重建，因此不会把数十万坐标写入存档。</p>
 */
public final class CompartmentState {
    private final UUID id;
    private AABB bounds;
    private int volumeBlocks;
    private double oxygenMb;
    /**
     * Amount of gas expressed as the volume it would occupy at surface pressure.
     *
     * <p>This is deliberately separate from {@link #oxygenMb}. Breathing converts oxygen into
     * unmodelled exhaust gases and therefore must not make the whole cabin a vacuum. Flooding and
     * venting, on the other hand, change this value and consequently the cabin pressure.</p>
     */
    private double standardGasMb;
    private double waterMb;
    private double internalPressureKpa;
    private boolean sealed;
    private transient Set<Long> cells;

    public CompartmentState(final UUID id, final AABB bounds, final int volumeBlocks,
                            final Set<Long> cells) {
        this.id = Objects.requireNonNull(id, "id");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.volumeBlocks = Math.max(1, volumeBlocks);
        this.cells = Set.copyOf(Objects.requireNonNull(cells, "cells"));
        this.oxygenMb = capacityMb();
        this.standardGasMb = waterCapacityMb();
        this.internalPressureKpa = NautilusConfig.SURFACE_PRESSURE_KPA;
        this.sealed = true;
    }

    private CompartmentState(final UUID id) {
        this.id = id;
        this.bounds = new AABB(0, 0, 0, 1, 1, 1);
        this.volumeBlocks = 1;
        this.cells = Set.of();
        this.sealed = true;
    }

    public UUID id() {
        return id;
    }

    public AABB bounds() {
        return bounds;
    }

    public int volumeBlocks() {
        return volumeBlocks;
    }

    public double oxygenMb() {
        return oxygenMb;
    }

    public double standardGasMb() {
        return standardGasMb;
    }

    public double waterMb() {
        return waterMb;
    }

    public double internalPressureKpa() {
        return internalPressureKpa;
    }

    public boolean sealed() {
        return sealed;
    }

    /**
     * Exact plot-space cells from the latest flood-fill scan.
     *
     * <p>The collection is an immutable view and is intentionally transient: after loading a
     * world, bounds-based queries remain available until the coordinator performs its next scan.</p>
     */
    public Set<Long> cells() {
        return cells;
    }

    public double capacityMb() {
        return volumeBlocks * NautilusConfig.airDensityPerBlock();
    }

    public double waterCapacityMb() {
        return volumeBlocks * 1000.0D;
    }

    public double oxygenFraction() {
        return capacityMb() <= 0.0D ? 0.0D : Math.clamp(oxygenMb / capacityMb(), 0.0D, 1.0D);
    }

    public boolean isDry() {
        return sealed && waterMb < 1.0D;
    }

    public boolean contains(final BlockPos pos) {
        if (!cells.isEmpty()) {
            return cells.contains(pos.asLong());
        }
        return bounds.contains(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    public boolean contains(final Vector3dc pos) {
        final BlockPos blockPos = BlockPos.containing(pos.x(), pos.y(), pos.z());
        return contains(blockPos);
    }

    public double consumeOxygen(final double amountMb) {
        final double consumed = Math.min(oxygenMb, Math.max(0.0D, amountMb));
        oxygenMb -= consumed;
        return consumed;
    }

    public double addOxygen(final double amountMb) {
        final double accepted = Math.min(capacityMb() - oxygenMb, Math.max(0.0D, amountMb));
        oxygenMb += accepted;
        // Oxygen introduced by a generator/tank is real gas and therefore also contributes to
        // pressure. Consumption intentionally does not subtract standardGasMb: exhaled gases are
        // outside the scope of the oxygen model but still occupy volume.
        standardGasMb += accepted;
        return accepted;
    }

    public double addWater(final double amountMb) {
        final double accepted = Math.min(waterCapacityMb() - waterMb, Math.max(0.0D, amountMb));
        waterMb += accepted;
        return accepted;
    }

    public double removeWater(final double amountMb) {
        final double removed = Math.min(waterMb, Math.max(0.0D, amountMb));
        waterMb -= removed;
        return removed;
    }

    /**
     * Restores conserved contents after a topology rescan, merge, or split.
     * Geometry reconciliation computes the transferred shares before mutating any old room.
     */
    public void restoreContents(final double oxygenMb, final double standardGasMb,
                                final double waterMb) {
        this.oxygenMb = Math.clamp(
                Double.isFinite(oxygenMb) ? oxygenMb : 0.0D, 0.0D, capacityMb());
        this.standardGasMb = Math.max(0.0D,
                Double.isFinite(standardGasMb) ? standardGasMb : 0.0D);
        this.waterMb = Math.clamp(
                Double.isFinite(waterMb) ? waterMb : 0.0D, 0.0D, waterCapacityMb());
    }

    public void setStandardGasMb(final double amountMb) {
        standardGasMb = Math.max(0.0D, Double.isFinite(amountMb) ? amountMb : 0.0D);
    }

    public double addStandardGas(final double amountMb) {
        final double accepted = Math.max(0.0D, amountMb);
        standardGasMb += accepted;
        return accepted;
    }

    public double removeStandardGas(final double amountMb) {
        final double removed = Math.min(standardGasMb, Math.max(0.0D, amountMb));
        standardGasMb -= removed;
        return removed;
    }

    /**
     * Exchanges gas with the exterior while preserving a physically meaningful oxygen share.
     * Positive values admit ordinary air (configured oxygen density per 1000 mB); negative values
     * vent the cabin mixture and therefore remove oxygen in the same proportion as total gas.
     */
    public void exchangeStandardGas(final double deltaMb) {
        if (!Double.isFinite(deltaMb) || deltaMb == 0.0D) {
            return;
        }
        if (deltaMb > 0.0D) {
            standardGasMb += deltaMb;
            final double ambientOxygen = deltaMb * NautilusConfig.airDensityPerBlock() / 1000.0D;
            oxygenMb = Math.min(capacityMb(), oxygenMb + ambientOxygen);
            return;
        }

        final double oldGas = standardGasMb;
        if (oldGas <= 0.0D) {
            oxygenMb = 0.0D;
            return;
        }
        final double vented = Math.min(oldGas, -deltaMb);
        standardGasMb -= vented;
        oxygenMb *= standardGasMb / oldGas;
    }

    public void setInternalPressureKpa(final double pressure) {
        internalPressureKpa = Math.max(0.0D, Double.isFinite(pressure) ? pressure : 0.0D);
    }

    public void setSealed(final boolean sealed) {
        this.sealed = sealed;
    }

    /**
     * 舱室几何改变后保留实际气体/水量，而不是按比例凭空增减物质。
     */
    public void updateGeometry(final AABB newBounds, final int newVolume, final Set<Long> newCells) {
        this.bounds = Objects.requireNonNull(newBounds, "newBounds");
        this.volumeBlocks = Math.max(1, newVolume);
        this.cells = Set.copyOf(Objects.requireNonNull(newCells, "newCells"));
        this.oxygenMb = Math.min(oxygenMb, capacityMb());
        this.waterMb = Math.min(waterMb, waterCapacityMb());
        this.sealed = true;
    }

    public CompoundTag save() {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putDouble("min_x", bounds.minX);
        tag.putDouble("min_y", bounds.minY);
        tag.putDouble("min_z", bounds.minZ);
        tag.putDouble("max_x", bounds.maxX);
        tag.putDouble("max_y", bounds.maxY);
        tag.putDouble("max_z", bounds.maxZ);
        tag.putInt("volume", volumeBlocks);
        tag.putDouble("oxygen_mb", oxygenMb);
        tag.putDouble("standard_gas_mb", standardGasMb);
        tag.putDouble("water_mb", waterMb);
        tag.putDouble("pressure_kpa", internalPressureKpa);
        tag.putBoolean("sealed", sealed);
        return tag;
    }

    public static CompartmentState load(final CompoundTag tag) {
        final CompartmentState state = new CompartmentState(tag.getUUID("id"));
        state.bounds = new AABB(
                tag.getDouble("min_x"), tag.getDouble("min_y"), tag.getDouble("min_z"),
                tag.getDouble("max_x"), tag.getDouble("max_y"), tag.getDouble("max_z"));
        state.volumeBlocks = Math.max(1, tag.getInt("volume"));
        state.oxygenMb = Math.clamp(tag.getDouble("oxygen_mb"), 0.0D, state.capacityMb());
        state.waterMb = Math.clamp(tag.getDouble("water_mb"), 0.0D, state.waterCapacityMb());
        state.internalPressureKpa = Math.max(0.0D, tag.getDouble("pressure_kpa"));
        // Backward compatibility for saves created before pressure state had an explicit gas
        // inventory. Reconstruct the standard-volume amount from P*V = constant.
        if (tag.contains("standard_gas_mb")) {
            state.standardGasMb = Math.max(0.0D, tag.getDouble("standard_gas_mb"));
        } else {
            final double freeVolumeMb = Math.max(1.0D, state.waterCapacityMb() - state.waterMb);
            final double surfacePressure = Math.max(1.0E-6D, NautilusConfig.SURFACE_PRESSURE_KPA);
            state.standardGasMb = freeVolumeMb * state.internalPressureKpa / surfacePressure;
        }
        state.sealed = tag.getBoolean("sealed");
        return state;
    }
}
