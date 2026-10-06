package com.create.nautilus.client.render;

/**
 * Build-thread scope used to reserve one otherwise-unused Sodium material bit for water quads.
 *
 * <p>The marker must not depend on Iris' shader-pack block id. Existing Sodium chunk meshes can
 * survive a live pack switch, while the same water block is commonly mapped to a different id by
 * the new pack (for example Photon 10001 and Bliss 8). The material bit is baked by Nautilus and
 * therefore remains stable for the lifetime of the mesh.</p>
 */
public final class SodiumFluidMaterialMarker {
    public static final int WATER_MATERIAL_BIT = 1 << 3;
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private SodiumFluidMaterialMarker() {
    }

    public static void enterFluidRender(final boolean water) {
        final State state = STATE.get();
        // DefaultFluidRenderer is not recursive. Overwriting also self-heals a build thread after
        // an exceptional render which could not reach its RETURN injection.
        state.waterRender = water;
        state.renderActive = true;
        state.quadDepth = 0;
    }

    public static void exitFluidRender() {
        STATE.get().reset();
    }

    public static void enterFluidQuad() {
        STATE.get().quadDepth++;
    }

    public static void exitFluidQuad() {
        final State state = STATE.get();
        if (state.quadDepth <= 0) {
            state.reset();
            return;
        }
        state.quadDepth--;
    }

    public static boolean isWaterQuad() {
        final State state = STATE.get();
        return state.quadDepth > 0 && state.renderActive && state.waterRender;
    }

    private static final class State {
        private boolean renderActive;
        private boolean waterRender;
        private int quadDepth;

        private void reset() {
            renderActive = false;
            waterRender = false;
            quadDepth = 0;
        }
    }
}
