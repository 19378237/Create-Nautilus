package com.create.nautilus.hydrogen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Actively drains any Nautilus gas from an adjacent capability while powered.
 * It also exposes a gas input so a gas pump can feed it from a remote tank.
 */
public final class EmergencyVentBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation {
    private static final int BUFFER_CAPACITY = 1_000;

    public static int bufferCapacity() {
        return BUFFER_CAPACITY;
    }
    private SmartFluidTankBehaviour buffer;
    private double ventRemainder;

    public EmergencyVentBlockEntity(final BlockEntityType<?> type,
                                    final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(GasCapabilities.HANDLER,
                NautilusBlockEntityTypes.EMERGENCY_VENT.get(),
                (be, side) -> side == be.exhaustFace()
                        ? null : GasHandlerView.of(be.buffer.getCapability()));
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        buffer = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.INPUT,
                this, 1, BUFFER_CAPACITY, false).forbidExtraction();
        buffer.getPrimaryHandler().setValidator(HydrogenFluids::isGas);
        behaviours.add(buffer);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide
                || !level.hasNeighborSignal(worldPosition)) {
            return;
        }

        ventRemainder += NautilusConfig.emergencyVentRate() / 20.0D;
        final int due = (int) Math.floor(ventRemainder);
        int budget = due;
        if (budget <= 0) {
            return;
        }

        // Prefer the internal buffer, then pull directly from an attached tank.
        int released = buffer.getPrimaryHandler().drain(budget,
                IFluidHandler.FluidAction.EXECUTE).getAmount();
        budget -= released;
        if (budget > 0) {
            released += drainAdjacentGas(budget);
        }
        ventRemainder -= released;
        if (released < due) {
            // Preserve fractional throughput without banking whole millibuckets
            // while no gas is available. Otherwise an idle powered vent can
            // accumulate an unbounded burst and empty a newly attached tank in
            // one tick.
            ventRemainder = Math.min(ventRemainder, Math.nextDown(1.0D));
        }
        if (released > 0) {
            spawnExhaust(released);
        }
    }

    private int drainAdjacentGas(final int maximum) {
        for (final Direction direction : Direction.values()) {
            if (direction == exhaustFace()) {
                continue;
            }
            final IGasHandler handler = level.getCapability(GasCapabilities.HANDLER,
                    worldPosition.relative(direction), direction.getOpposite());
            if (handler == null) {
                continue;
            }
            final FluidStack simulated = handler.drain(maximum,
                    IFluidHandler.FluidAction.SIMULATE);
            if (HydrogenFluids.isGas(simulated)) {
                return handler.drain(simulated.getAmount(),
                        IFluidHandler.FluidAction.EXECUTE).getAmount();
            }
        }
        return 0;
    }

    private void spawnExhaust(final int released) {
        final SubLevel containing = Sable.HELPER.getContaining(this);
        final Level parent = containing == null ? level : containing.getLevel();
        if (!(parent instanceof ServerLevel serverLevel)) {
            return;
        }
        final Direction face = exhaustFace();
        Vec3 outlet = Vec3.atCenterOf(worldPosition)
                .add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.55D));
        if (containing != null) {
            outlet = containing.logicalPose().transformPosition(outlet);
        }
        final boolean underwater = serverLevel.getFluidState(BlockPos.containing(outlet))
                .is(FluidTags.WATER);
        final int count = Math.clamp(released / 10, 4, 40);
        serverLevel.sendParticles(underwater ? ParticleTypes.BUBBLE : ParticleTypes.CLOUD,
                outlet.x, outlet.y, outlet.z, count,
                0.12D, 0.12D, 0.12D, 0.04D);
    }

    private Direction exhaustFace() {
        return getBlockState().getValue(EmergencyVentBlock.FACING);
    }

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip,
                                      final boolean isPlayerSneaking) {
        return containedFluidTooltip(tooltip, isPlayerSneaking, buffer.getCapability());
    }
}
