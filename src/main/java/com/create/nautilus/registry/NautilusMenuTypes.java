package com.create.nautilus.registry;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.menu.BallastTankControllerMenu;
import com.create.nautilus.client.menu.BallastTankControllerScreen;
import com.tterrag.registrate.util.entry.MenuEntry;
import com.tterrag.registrate.builders.MenuBuilder.ForgeMenuFactory;

/** Registrate menu index. Screen factories are lazy and dedicated-server safe. */
public final class NautilusMenuTypes {
    public static final MenuEntry<BallastTankControllerMenu> BALLAST_TANK_CONTROLLER =
            CreateNautilus.REGISTRATE.menu("ballast_tank_controller",
                            (ForgeMenuFactory<BallastTankControllerMenu>) BallastTankControllerMenu::new, () -> BallastTankControllerScreen::new)
                    .register();

    private NautilusMenuTypes() {
    }

    public static void register() {
    }
}
