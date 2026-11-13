package com.create.nautilus.kinetics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.NautilusData;
import com.create.nautilus.hydrogen.HydrogenBoosterBlockEntity;
import com.create.nautilus.physics.CompartmentVoxelSemantics;
import com.create.nautilus.physics.CompartmentWaterGeometry;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/** Hydrodynamic efficiency curve shared by both Aeronautics propeller families. */
public final class PropellerEfficiency {
    private static final int RINGS = 2;
    private static final int ANGLES_PER_RING = 8;
    private static final double[] SAMPLE_COS = new double[RINGS * ANGLES_PER_RING];
    private static final double[] SAMPLE_SIN = new double[RINGS * ANGLES_PER_RING];

    static {
        for (int ring = 0; ring < RINGS; ring++) {
            for (int angle = 0; angle < ANGLES_PER_RING; angle++) {
                final int sample = ring * ANGLES_PER_RING + angle;
                final double theta = (angle + ring * 0.5D) * Math.PI / 4.0D;
                SAMPLE_COS[sample] = Math.cos(theta);
                SAMPLE_SIN[sample] = Math.sin(theta);
            }
        }
    }

    private PropellerEfficiency() {
    }

    public static boolean isAeronauticsPropeller(final KineticBlockEntity blockEntity) {
        for (Class<?> type = blockEntity.getClass(); type != null; type = type.getSuperclass()) {
            final String name = type.getName();
            if (name.equals("dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity")
                    || name.equals("dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity")) {
                return true;
            }
        }
        return false;
    }

    public static double thrustMultiplier(final KineticBlockEntity blockEntity) {
        if (blockEntity.getLevel() != null && blockEntity.getLevel().isClientSide) {
            return blockEntity instanceof PropellerEnvironment environment
                    ? environment.nautilus$displayThrustMultiplier() : 1.0D;
        }
        final double submerged = blockEntity instanceof PropellerEnvironment environment
                ? environment.nautilus$submergedFraction() : -1.0D;
        return thrustMultiplier(blockEntity, submerged);
    }

    static double thrustMultiplier(final KineticBlockEntity blockEntity, final double submerged) {
        final double hydrogenMultiplier = HydrogenBoosterBlockEntity.hasActiveBooster(blockEntity)
                ? 1.0D + NautilusConfig.hydrogenBoosterThrustBonus() : 1.0D;
        if (submerged < 0.0D) {
            return hydrogenMultiplier;
        }
        if (submerged < 0.30D) {
            return 0.20D * hydrogenMultiplier;
        }
        if (submerged <= 0.70D) {
            return hydrogenMultiplier;
        }

        // Deeply submerged blades gain thrust, but cavitation prevents a
        // linear increase.  The asymptote is 1.25 times airborne thrust.
        final double t = (submerged - 0.70D) / 0.30D;
        return (1.0D + NautilusConfig.propellerDeepThrustBonus()
                * (1.0D - Math.exp(-3.0D * t))) * hydrogenMultiplier;
    }

    /** Samples the swept annulus on the main thread; no live world queries run in thrust hooks. */
    public static double sampleLayer(final KineticBlockEntity blockEntity, final Direction facing,
                                     final double offset, final double innerSquared,
                                     final double outerSquared) {
        if (blockEntity.getLevel() == null || blockEntity.getLevel().isClientSide) return -1.0D;
        final SubLevel containing = Sable.HELPER.getContaining(
                blockEntity.getLevel(), blockEntity.getBlockPos());
        if (!(containing instanceof ServerSubLevel carrier)) return -1.0D;
        return sampleLayer(blockEntity, carrier, facing, offset, innerSquared, outerSquared);
    }

    /** Reuses the server carrier resolved once for all layers of an assembled propeller. */
    public static double sampleLayer(final KineticBlockEntity blockEntity, final ServerSubLevel carrier,
                                     final Direction facing, final double offset,
                                     final double innerSquared, final double outerSquared) {
        final Vec3 axis = Vec3.atLowerCornerOf(facing.getNormal());
        final Vec3 first = facing.getAxis() == Direction.Axis.Y
                ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        final Vec3 second = axis.cross(first);
        final Vec3 center = Vec3.atCenterOf(blockEntity.getBlockPos()).add(axis.scale(offset));
        int wet = 0;
        int openSamples = 0;
        for (int ring = 0; ring < RINGS; ring++) {
            final double radius = Math.sqrt(Math.max(0.0D,
                    innerSquared + (outerSquared - innerSquared) * (ring + 0.5D) / RINGS));
            for (int angle = 0; angle < ANGLES_PER_RING; angle++) {
                final int sample = ring * ANGLES_PER_RING + angle;
                final Vec3 local = center.add(first.scale(SAMPLE_COS[sample] * radius))
                        .add(second.scale(SAMPLE_SIN[sample] * radius));
                final BlockPos cell = BlockPos.containing(local);
                if (!blockEntity.getLevel().isLoaded(cell)
                        || CompartmentVoxelSemantics.isSolidMembrane(blockEntity.getLevel(), cell,
                        blockEntity.getLevel().getBlockState(cell))) continue;
                openSamples++;
                final Vec3 world = carrier.logicalPose().transformPosition(local);
                // Native plot water remains real water even outside a tracked room.
                final var localFluid = blockEntity.getLevel().getFluidState(cell);
                if (localFluid.is(FluidTags.WATER) && local.y
                        < cell.getY() + localFluid.getHeight(blockEntity.getLevel(), cell)) {
                    wet++;
                    continue;
                }
                final var room = NautilusData.carrier(carrier).compartmentAt(cell);
                if (room.isPresent()) {
                    // Source cells own their complete native fluid boundary. The
                    // continuous plane occupies only the remaining virtual domain.
                    if (room.get().waterMb() > 0.0D
                            && room.get().waterCells().contains(cell.asLong()) && world.y
                            < CompartmentWaterGeometry.surfaceY(carrier, room.get())) wet++;
                } else {
                    final BlockPos worldCell = BlockPos.containing(world);
                    final var fluid = carrier.getLevel().getFluidState(worldCell);
                    if (fluid.is(FluidTags.WATER) && world.y
                            < worldCell.getY() + fluid.getHeight(carrier.getLevel(), worldCell)) wet++;
                }
            }
        }
        return openSamples == 0 ? 0.0D : wet / (double) openSamples;
    }
}
