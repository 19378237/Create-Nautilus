package com.create.nautilus.content.submarine;

import com.create.nautilus.client.menu.BallastTankControllerMenu;
import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CompartmentState;
import com.create.nautilus.data.NautilusData;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Independent fore/aft ballast target controller.
 *
 * <p>Redstone is only an enable signal. Rotation direction selects the fluid
 * direction: positive speed admits water and negative speed expels it.</p>
 */
public final class BallastTankControllerBlockEntity extends KineticBlockEntity
        implements MenuProvider, BlockEntitySubLevelActor {
    private static final double FULL_RATE_RPM = 256.0D;

    private int fore = 50;
    private int aft = 50;
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(final int index) {
            return index == 0 ? fore : aft;
        }

        @Override
        public void set(final int index, final int value) {
            if (index == 0) {
                fore = Math.clamp(value, 0, 100);
            } else {
                aft = Math.clamp(value, 0, 100);
            }
        }

        @Override
        public int getCount() {
            return 2;
        }
    };

    public BallastTankControllerBlockEntity(final BlockEntityType<?> type,
                                            final BlockPos pos,
                                            final BlockState state) {
        super(type, pos, state);
    }

    public ContainerData menuData() {
        return data;
    }

    public void adjust(final int id) {
        if (id == 0) fore = Math.max(0, fore - 5);
        if (id == 1) fore = Math.min(100, fore + 5);
        if (id == 2) aft = Math.max(0, aft - 5);
        if (id == 3) aft = Math.min(100, aft + 5);
        setChanged();
    }

    @Override
    public void sable$tick(final ServerSubLevel subLevel) {
        final float speed = getSpeed();
        if (!level.hasNeighborSignal(worldPosition)
                || Math.abs(speed) < 1.0F
                || subLevel.getLevel().getGameTime() % 20L != 0L) {
            return;
        }

        final var rooms = NautilusData.carrier(subLevel).orderedCompartments();
        if (rooms.isEmpty()) {
            return;
        }

        // 64 RPM retains the old 250 mB/s rate; the curve reaches its hard
        // 1000 mB/s safety limit at 256 RPM and never exceeds it.
        final double step = NautilusConfig.ballastTransferRate()
                * Math.min(Math.abs(speed) / FULL_RATE_RPM, 1.0D);
        move(rooms.getFirst(), fore, step, speed > 0.0F);
        if (rooms.size() > 1) {
            move(rooms.getLast(), aft, step, speed > 0.0F);
        }
    }

    private static void move(final CompartmentState compartment,
                             final int targetPercent,
                             final double stepMb,
                             final boolean filling) {
        final double targetMb = compartment.waterCapacityMb()
                * targetPercent / 100.0D;
        if (filling) {
            compartment.addWater(Math.min(stepMb,
                    Math.max(0.0D, targetMb - compartment.waterMb())));
        } else {
            compartment.removeWater(Math.min(stepMb,
                    Math.max(0.0D, compartment.waterMb() - targetMb)));
        }
    }

    @Override
    protected void write(final CompoundTag tag,
                         final HolderLookup.Provider registries,
                         final boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("ForeTarget", fore);
        tag.putInt("AftTarget", aft);
    }

    @Override
    protected void read(final CompoundTag tag,
                        final HolderLookup.Provider registries,
                        final boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        fore = Math.clamp(tag.getInt("ForeTarget"), 0, 100);
        aft = Math.clamp(tag.getInt("AftTarget"), 0, 100);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(
                "block.create_nautilus.ballast_tank_controller");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(final int id,
                                                      final Inventory inventory,
                                                      final Player player) {
        return new BallastTankControllerMenu(id, inventory, this);
    }
}
