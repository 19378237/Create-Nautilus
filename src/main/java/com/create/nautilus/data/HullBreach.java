package com.create.nautilus.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 一个与具体舱室相连的破洞。流速为带符号 mB/s，正数进水、负数排水。 */
public final class HullBreach {
    private final BlockPos position;
    private UUID compartmentId;
    /** Direction from the removed hull voxel toward the compartment interior. */
    private Direction interiorDirection;
    private double areaSquareMetres;
    /** True for a detected open-top vent rather than a destroyed hull block. */
    private final boolean atmosphericOpening;
    private double currentFlowMbPerSecond;

    public HullBreach(final BlockPos position, final UUID compartmentId,
                      final Direction interiorDirection, final double areaSquareMetres) {
        this(position, compartmentId, interiorDirection, areaSquareMetres, false);
    }

    public HullBreach(final BlockPos position, final UUID compartmentId,
                      final Direction interiorDirection, final double areaSquareMetres,
                      final boolean atmosphericOpening) {
        this.position = position.immutable();
        this.compartmentId = compartmentId;
        this.interiorDirection = interiorDirection;
        this.areaSquareMetres = Math.max(0.01D, areaSquareMetres);
        this.atmosphericOpening = atmosphericOpening;
    }

    public BlockPos position() { return position; }
    public UUID compartmentId() { return compartmentId; }
    public Direction interiorDirection() { return interiorDirection; }
    public double areaSquareMetres() { return areaSquareMetres; }
    public boolean atmosphericOpening() { return atmosphericOpening; }
    public double currentFlowMbPerSecond() { return currentFlowMbPerSecond; }

    public void setCompartmentId(final UUID id) { compartmentId = id; }
    public void setInteriorDirection(final Direction direction) { interiorDirection = direction; }
    public void setCurrentFlowMbPerSecond(final double flow) { currentFlowMbPerSecond = flow; }

    public CompoundTag save() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("x", position.getX());
        tag.putInt("y", position.getY());
        tag.putInt("z", position.getZ());
        tag.putUUID("compartment", compartmentId);
        tag.putString("interior_direction", interiorDirection.getName());
        tag.putDouble("area", areaSquareMetres);
        tag.putBoolean("atmospheric_opening", atmosphericOpening);
        return tag;
    }

    public static HullBreach load(final CompoundTag tag) {
        final Direction direction = tag.contains("interior_direction")
                ? Direction.byName(tag.getString("interior_direction"))
                : Direction.NORTH;
        return new HullBreach(new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                tag.getUUID("compartment"),
                direction == null ? Direction.NORTH : direction,
                tag.getDouble("area"), tag.getBoolean("atmospheric_opening"));
    }
}
