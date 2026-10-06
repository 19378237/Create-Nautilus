package com.create.nautilus.content.oxygen;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.content.NautilusGoggleTooltips;
import com.create.nautilus.registry.NautilusBlockEntityTypes;
import com.create.nautilus.registry.NautilusFluids;
import com.create.nautilus.hydrogen.GasCapabilities;
import com.create.nautilus.hydrogen.GasHandlerView;
import com.create.nautilus.hydrogen.IGasHandler;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import com.simibubi.create.foundation.fluid.FluidHelper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Mechanical electrolyzer with real integer-fluid/fractional-rate accounting. */
public final class ElectrolyticGeneratorBlockEntity extends KineticBlockEntity {
    /** One renderer cycle represents one thousand mB successfully inserted. */
    private static final long PRODUCTION_CYCLE_MB = 1_000L;
    /** Progress packets are throttled to keep production off the per-tick network path. */
    private static final long PRODUCTION_SYNC_INTERVAL_TICKS = 5L;
    /** The machine is mechanically stalled below this speed. */
    private static final int MIN_OPERATING_RPM = 64;
    /** The machine reaches its full configured conversion efficiency here. */
    private static final int FULL_EFFICIENCY_RPM = 256;
    private static final int WATER_CAPACITY = 4_000;

    public static int waterCapacity() {
        return WATER_CAPACITY;
    }

    public static int gasCapacity() {
        return safeCapacity(NautilusConfig.ELECTROLYZER_CAPACITY_MB);
    }

    private SmartFluidTankBehaviour waterInput;
    private SmartFluidTankBehaviour oxygenOutput;
    private SmartFluidTankBehaviour hydrogenOutput;
    private IFluidHandler unsidedHandler;

    private double waterRemainderMb;
    private double oxygenRemainderMb;

    /** Monotonic count of oxygen mB that actually entered the output tank. */
    private long producedOxygenTotalMb;
    /** Last server-side production state sent to clients (transient). */
    private long lastProductionSyncTick = Long.MIN_VALUE;
    private long lastSyncedProducedOxygenMb = Long.MIN_VALUE;
    private boolean lastSyncedProductionActive;
    private boolean productionActive;

    /** Client-only interpolation snapshot.  The total is unwrapped, so a cycle
     * boundary never causes a right-to-left interpolation. */
    private boolean clientProgressInitialized;
    private double clientProgressFromTotalMb;
    private double clientProgressTargetTotalMb;
    private long clientProgressSyncTick;

    public ElectrolyticGeneratorBlockEntity(final BlockEntityType<?> type,
                                            final BlockPos pos, final BlockState state) {
        super(type, pos, state);
    }

    public static void registerCapabilities(final RegisterCapabilitiesEvent event) {
        // Ordinary fluid pipes may feed only the water faces. Gas products are
        // published through the independent Nautilus gas capability below.
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK,
                NautilusBlockEntityTypes.ELECTROLYTIC_GENERATOR.get(),
                (be, side) -> be.waterHandlerFor(side));
        event.registerBlockEntity(GasCapabilities.HANDLER,
                NautilusBlockEntityTypes.ELECTROLYTIC_GENERATOR.get(),
                (be, side) -> be.gasHandlerFor(side));
    }

    @Override
    public void addBehaviours(final List<BlockEntityBehaviour> behaviours) {
        final int oxygenCapacity = gasCapacity();

        waterInput = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.INPUT,
                this, 1, WATER_CAPACITY, false).forbidExtraction();
        waterInput.getPrimaryHandler().setValidator(
                stack -> FluidHelper.isTag(stack, FluidTags.WATER));

        oxygenOutput = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.OUTPUT,
                this, 1, oxygenCapacity, false).forbidInsertion();
        oxygenOutput.getPrimaryHandler().setValidator(NautilusFluids::isOxygen);

        hydrogenOutput = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.TYPE,
                this, 1, oxygenCapacity, false).forbidInsertion();
        hydrogenOutput.getPrimaryHandler().setValidator(NautilusFluids::isHydrogen);

        behaviours.add(waterInput);
        behaviours.add(oxygenOutput);
        behaviours.add(hydrogenOutput);
        unsidedHandler = new CombinedTankWrapper(waterInput.getCapability(),
                oxygenOutput.getCapability(), hydrogenOutput.getCapability());
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }

        final double rpm = Math.abs(getSpeed());
        if (rpm < MIN_OPERATING_RPM) {
            publishProductionState(false);
            return;
        }

        final double efficiency = efficiencyAt(rpm);

        // The configuration is mB per RPM-second. Dividing by 20 converts it to
        // a game-tick amount while retaining the fractional part across ticks.
        final double oxygenThisTick = rpm * NautilusConfig.OXYGEN_PER_SU * efficiency / 20.0D;
        final int outputSpace = oxygenOutput.getPrimaryHandler().getSpace();
        final int hydrogenSpace = hydrogenOutput.getPrimaryHandler().getSpace();
        // One oxygen volume always creates two hydrogen volumes. Production is
        // atomic: either both products fit or neither water nor rotation work
        // is consumed. A full hydrogen cache therefore stops the machine.
        final int pairedOxygenSpace = Math.min(outputSpace, hydrogenSpace / 2);
        if (oxygenThisTick <= 0.0D || pairedOxygenSpace <= 0) {
            publishProductionState(false);
            return;
        }

        final double waterThisTick = NautilusConfig.WATER_CONSUMPTION_RATE / 20.0D;
        final boolean touchingExternalWater = isTouchingExternalWater();
        final int waterDue = (int) Math.floor(waterRemainderMb + waterThisTick);
        if (!touchingExternalWater && waterDue > 0) {
            final FluidStack simulated = waterInput.getPrimaryHandler().drain(waterDue, FluidAction.SIMULATE);
            if (simulated.getAmount() < waterDue) {
                publishProductionState(false);
                return;
            }
        }

        waterRemainderMb += waterThisTick;
        final int consumedWater = (int) Math.floor(waterRemainderMb);
        if (consumedWater > 0) {
            // An exterior water face is treated like Create's infinite open-water intake;
            // pipe-fed operation drains the actual input tank.
            if (!touchingExternalWater) {
                waterInput.getPrimaryHandler().drain(consumedWater, FluidAction.EXECUTE);
            }
            waterRemainderMb -= consumedWater;
        }

        oxygenRemainderMb += Math.min(oxygenThisTick, pairedOxygenSpace);
        final int oxygenMb = (int) Math.floor(oxygenRemainderMb);
        if (oxygenMb <= 0) {
            // Fractional production is retained in oxygenRemainderMb, but the
            // display only advances after a successful integer fill. Keep the
            // active state stable instead of sending a start/stop packet per mB.
            publishProductionState(true);
            return;
        }
        final int insertedOxygen = oxygenOutput.getPrimaryHandler().fill(
                new FluidStack(NautilusFluids.OXYGEN_GAS.get(), oxygenMb), FluidAction.EXECUTE);
        oxygenRemainderMb -= insertedOxygen;
        if (insertedOxygen > 0 && level.getGameTime() % 10L == 0L) {
            spawnOxygenProductionParticles();
        }

        if (insertedOxygen > 0) {
            addProducedOxygen(insertedOxygen);
            hydrogenOutput.getPrimaryHandler().fill(
                    new FluidStack(NautilusFluids.HYDROGEN_GAS.get(), insertedOxygen * 2),
                    FluidAction.EXECUTE);
        }
        publishProductionState(insertedOxygen > 0);
    }

    /**
     * Returns the current 0..1 oxygen production progress for the front display.
     * Server-side callers get the exact persisted value; clients interpolate the
     * unwrapped successful-fill counter between sparse update packets.
     */
    public float productionProgress(final float partialTicks) {
        if (level == null || !level.isClientSide) {
            return (float) (Math.floorMod(producedOxygenTotalMb, PRODUCTION_CYCLE_MB)
                    / (double) PRODUCTION_CYCLE_MB);
        }
        final double total = clientProgressTotal(partialTicks);
        double cyclePosition = total % PRODUCTION_CYCLE_MB;
        if (cyclePosition < 0.0D) {
            cyclePosition += PRODUCTION_CYCLE_MB;
        }
        return (float) (cyclePosition / PRODUCTION_CYCLE_MB);
    }

    private double clientProgressTotal(final float partialTicks) {
        if (!clientProgressInitialized) {
            return producedOxygenTotalMb;
        }
        final double now = level.getGameTime() + Mth.clamp(partialTicks, 0.0F, 1.0F);
        final double elapsed = Mth.clamp(now - clientProgressSyncTick,
                0.0D, (double) PRODUCTION_SYNC_INTERVAL_TICKS);
        final double span = clientProgressTargetTotalMb - clientProgressFromTotalMb;
        if (elapsed >= PRODUCTION_SYNC_INTERVAL_TICKS || span <= 0.0D) {
            return clientProgressTargetTotalMb;
        }
        return clientProgressFromTotalMb
                + span * elapsed / PRODUCTION_SYNC_INTERVAL_TICKS;
    }

    private void addProducedOxygen(final int insertedOxygen) {
        if (insertedOxygen <= 0) {
            return;
        }
        // Saturating arithmetic keeps the persisted counter valid even on an
        // exceptionally long-running world; normal worlds never approach this.
        producedOxygenTotalMb = Long.MAX_VALUE - producedOxygenTotalMb < insertedOxygen
                ? Long.MAX_VALUE
                : producedOxygenTotalMb + insertedOxygen;
        setChanged();
    }

    private void publishProductionState(final boolean active) {
        productionActive = active;
        if (level == null || level.isClientSide) {
            return;
        }

        final long now = level.getGameTime();
        final boolean cadenceElapsed = lastProductionSyncTick == Long.MIN_VALUE
                || now - lastProductionSyncTick >= PRODUCTION_SYNC_INTERVAL_TICKS;
        final boolean stateChanged = active != lastSyncedProductionActive;
        final boolean progressChanged = producedOxygenTotalMb != lastSyncedProducedOxygenMb;
        // A real start/stop transition is sparse (active remains true while
        // fractional mB accumulate), so publish it immediately.  Progress
        // packets remain cadence-limited and never use the per-tick path.
        if (stateChanged || (progressChanged && cadenceElapsed)) {
            lastProductionSyncTick = now;
            lastSyncedProducedOxygenMb = producedOxygenTotalMb;
            lastSyncedProductionActive = active;
            sendData();
        }
    }

    public IFluidHandler fluidHandlerFor(final @Nullable Direction side) {
        if (side == null) {
            return unsidedHandler;
        }
        final Direction front = getBlockState().getValue(ElectrolyticGeneratorBlock.HORIZONTAL_FACING);
        if (side == front) {
            return oxygenOutput.getCapability();
        }
        if (side == front.getOpposite()) {
            return hydrogenOutput.getCapability();
        }
        if (side == Direction.DOWN) {
            return null;
        }
        return waterInput.getCapability();
    }

    private IFluidHandler waterHandlerFor(final @Nullable Direction side) {
        if (side == null) {
            return waterInput.getCapability();
        }
        final Direction front = getBlockState().getValue(
                ElectrolyticGeneratorBlock.HORIZONTAL_FACING);
        return side != front && side != front.getOpposite() && side != Direction.DOWN
                ? waterInput.getCapability() : null;
    }

    private IGasHandler gasHandlerFor(final @Nullable Direction side) {
        final Direction front = getBlockState().getValue(
                ElectrolyticGeneratorBlock.HORIZONTAL_FACING);
        if (side == null) {
            return GasHandlerView.of(new CombinedTankWrapper(
                    oxygenOutput.getCapability(), hydrogenOutput.getCapability()));
        }
        if (side == front) {
            return GasHandlerView.of(oxygenOutput.getCapability());
        }
        if (side == front.getOpposite()) {
            return GasHandlerView.of(hydrogenOutput.getCapability());
        }
        return null;
    }

    public IFluidHandler oxygenHandler() {
        return oxygenOutput.getCapability();
    }

    public int oxygenAmountMb() {
        return oxygenOutput.getPrimaryHandler().getFluidAmount();
    }

    public int hydrogenAmountMb() {
        return hydrogenOutput.getPrimaryHandler().getFluidAmount();
    }

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return NautilusGoggleTooltips.addTanks(tooltip, unsidedHandler,
                "block.minecraft.water", "fluid_type.create_nautilus.oxygen_gas",
                "fluid_type.create_nautilus.hydrogen_gas");
    }

    private boolean isTouchingExternalWater() {
        final SubLevel containing = Sable.HELPER.getContaining(this);
        final Level parent = containing == null ? level : containing.getLevel();
        for (final Direction direction : Direction.values()) {
            if (direction == Direction.DOWN) {
                continue;
            }
            Vec3 sample = Vec3.atCenterOf(worldPosition)
                    .add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.51D));
            if (containing != null) {
                sample = containing.logicalPose().transformPosition(sample);
            }
            if (parent.getFluidState(BlockPos.containing(sample)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** A sparse cloud at the oxygen face makes ordinary-world production observable. */
    private void spawnOxygenProductionParticles() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Direction front = getBlockState().getValue(ElectrolyticGeneratorBlock.HORIZONTAL_FACING);
        final Vec3 outlet = Vec3.atCenterOf(worldPosition)
                .add(Vec3.atLowerCornerOf(front.getNormal()).scale(0.6D));
        serverLevel.sendParticles(ParticleTypes.CLOUD, outlet.x, outlet.y, outlet.z,
                1, 0.03D, 0.03D, 0.03D, 0.005D);
    }

    private static double efficiencyAt(final double rpm) {
        if (rpm < MIN_OPERATING_RPM) {
            return 0.0D;
        }
        // At the 64 RPM start threshold the generator works at 25% efficiency;
        // conversion then rises continuously until 256 RPM. Keeping the curve
        // continuous avoids a large one-tick production jump when a network
        // crosses the start threshold.
        return Mth.clamp(rpm / FULL_EFFICIENCY_RPM, 0.0D, 1.0D);
    }

    private static int safeCapacity(final long configured) {
        return (int) Math.clamp(configured, 1L, Integer.MAX_VALUE);
    }

    @Override
    protected void write(final CompoundTag tag, final HolderLookup.Provider registries,
                         final boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putDouble("WaterRemainder", waterRemainderMb);
        tag.putDouble("OxygenRemainder", oxygenRemainderMb);
        tag.putLong("ProducedOxygenTotal", producedOxygenTotalMb);
        tag.putBoolean("ProductionActive", productionActive);
    }

    @Override
    protected void read(final CompoundTag tag, final HolderLookup.Provider registries,
                        final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        waterRemainderMb = tag.getDouble("WaterRemainder");
        oxygenRemainderMb = tag.getDouble("OxygenRemainder");
        producedOxygenTotalMb = Math.max(0L, tag.getLong("ProducedOxygenTotal"));
        productionActive = tag.getBoolean("ProductionActive");

        if (clientPacket) {
            final double incomingTotal = producedOxygenTotalMb;
            if (!clientProgressInitialized) {
                clientProgressFromTotalMb = incomingTotal;
                clientProgressTargetTotalMb = incomingTotal;
                clientProgressSyncTick = level == null ? 0L : level.getGameTime();
                clientProgressInitialized = true;
            } else if (!productionActive) {
                // A confirmed stop/full-input packet must freeze at the value
                // the server actually reported, rather than finishing an old
                // interpolation after production has stopped.
                final double current = clientProgressTotal(0.0F);
                if (incomingTotal != clientProgressTargetTotalMb || current != incomingTotal) {
                    clientProgressFromTotalMb = incomingTotal;
                    clientProgressTargetTotalMb = incomingTotal;
                    clientProgressSyncTick = level == null ? 0L : level.getGameTime();
                }
            } else if (incomingTotal != clientProgressTargetTotalMb) {
                final double current = clientProgressTotal(0.0F);
                clientProgressFromTotalMb = current;
                clientProgressTargetTotalMb = Math.max(current, incomingTotal);
                clientProgressSyncTick = level == null ? 0L : level.getGameTime();
            }
        } else {
            // Force one fresh state packet after a server chunk load.
            lastProductionSyncTick = Long.MIN_VALUE;
            lastSyncedProducedOxygenMb = Long.MIN_VALUE;
        }
    }
}
