package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;

/** Client-only dynamic model handles. PartialModel performs model-bake registration. */
public final class NautilusPartialModels {
    public static final PartialModel ELECTROLYZER_PROGRESS =
            block("electrolytic_generator/progress");
    public static final PartialModel SNORKEL_FAN = block("snorkel/fan");
    public static final PartialModel GAS_PUMP_GEAR = block("gas_pump/gear");

    private NautilusPartialModels() {
    }

    /**
     * Forces class initialization during the client mod construction phase,
     * before Minecraft freezes the model-bake registry. Without this call the
     * PartialModel fields are first touched by a renderer after baking and
     * Flywheel can only supply the purple/black missing-model cube.
     */
    public static void init() {
    }

    private static PartialModel block(final String path) {
        return PartialModel.of(CreateNautilus.id("block/" + path));
    }
}
