package com.create.nautilus.client.menu;

import com.create.nautilus.content.submarine.BallastTankControllerBlockEntity;
import com.create.nautilus.registry.NautilusBlocks;
import com.create.nautilus.registry.NautilusMenuTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

public final class BallastTankControllerMenu extends AbstractContainerMenu {
    private final ContainerData data;
    private final BallastTankControllerBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    /** Client factory: the server-side menu remains authoritative for validity. */
    public BallastTankControllerMenu(final MenuType<?> ignored, final int id,
                                     final Inventory inventory,
                                     final RegistryFriendlyByteBuf buffer) {
        this(id, inventory, blockEntityAt(inventory, buffer),
                ContainerLevelAccess.NULL);
    }

    /** Server factory used by the block entity's MenuProvider. */
    public BallastTankControllerMenu(final int id, final Inventory inventory,
                                     final BallastTankControllerBlockEntity blockEntity) {
        this(id, inventory, blockEntity, accessFor(blockEntity));
    }

    private BallastTankControllerMenu(final int id, final Inventory inventory,
                                      final BallastTankControllerBlockEntity blockEntity,
                                      final ContainerLevelAccess access) {
        super(NautilusMenuTypes.BALLAST_TANK_CONTROLLER.get(), id);
        this.blockEntity = blockEntity;
        this.access = access;
        data = blockEntity == null
                ? new SimpleContainerData(2) : blockEntity.menuData();
        addDataSlots(data);
    }

    public int fore() {
        return data.get(0);
    }

    public int aft() {
        return data.get(1);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int id) {
        if (blockEntity != null && id >= 0 && id < 4 && stillValid(player)) {
            blockEntity.adjust(id);
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(final Player player) {
        return AbstractContainerMenu.stillValid(access, player,
                NautilusBlocks.BALLAST_TANK_CONTROLLER.get());
    }

    private static BallastTankControllerBlockEntity blockEntityAt(
            final Inventory inventory, final RegistryFriendlyByteBuf buffer) {
        return inventory.player.level().getBlockEntity(buffer.readBlockPos())
                instanceof BallastTankControllerBlockEntity controller
                ? controller : null;
    }

    private static ContainerLevelAccess accessFor(
            final BallastTankControllerBlockEntity blockEntity) {
        return blockEntity == null || blockEntity.getLevel() == null
                ? ContainerLevelAccess.NULL
                : ContainerLevelAccess.create(blockEntity.getLevel(),
                blockEntity.getBlockPos());
    }
}
