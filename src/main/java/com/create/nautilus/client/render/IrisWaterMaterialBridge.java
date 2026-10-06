package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional, reflection-only bridge that marks immediate terrain vertices as vanilla water.
 *
 * <p>Iris extends a {@code DefaultVertexFormat.BLOCK} buffer with a block/material id. Without
 * that id a shader pack sees Nautilus' dynamic surface as anonymous translucent geometry and
 * skips its water reflection, refraction and wave code. Keeping every Iris type behind
 * reflection avoids a hard dependency and leaves non-Iris clients on the normal render path.</p>
 */
public final class IrisWaterMaterialBridge {
    private static final Access ACCESS = findAccess();
    private static final PhaseAccess PHASE_ACCESS = findPhaseAccess();
    private static boolean materialBridgeDisabled;
    private static boolean phaseBridgeDisabled;

    private IrisWaterMaterialBridge() {
    }

    /**
     * Makes an immediate translucent draw use Iris' terrain-translucent gbuffer program.
     */
    public static boolean beginTerrainTranslucentPhase() {
        if (PHASE_ACCESS == null || phaseBridgeDisabled) {
            return false;
        }
        try {
            PHASE_ACCESS.setOverridePhase().invoke(null,
                    PHASE_ACCESS.terrainTranslucent());
            return true;
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            phaseBridgeDisabled = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to enter Iris terrain-translucent phase for virtual water",
                    exception);
            return false;
        }
    }

    /** Clears the optional Iris phase override installed by {@link #beginTerrainTranslucentPhase}. */
    public static void endTerrainTranslucentPhase() {
        if (PHASE_ACCESS == null || phaseBridgeDisabled) {
            return;
        }
        try {
            // Method.invoke is varargs; the explicit Object cast passes one null phase argument
            // instead of interpreting null as the complete argument array.
            PHASE_ACCESS.setOverridePhase().invoke(null, (Object) null);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            phaseBridgeDisabled = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to leave Iris terrain-translucent phase for virtual water",
                    exception);
        }
    }

    /** Begins one Iris block context; callers must pair a {@code true} result with {@link #end}. */
    static boolean begin(final VertexConsumer consumer, final BlockPos worldPos) {
        if (ACCESS == null || materialBridgeDisabled
                || !ACCESS.builderType().isInstance(consumer)) {
            return false;
        }
        try {
            final int waterId = waterMaterialId();
            if (waterId < 0) {
                return false;
            }
            // Iris uses byte 1 for fluids, matching its Sodium fluid-renderer integration.
            ACCESS.beginBlock().invoke(consumer, waterId, (byte) 1, (byte) 0,
                    worldPos.getX(), worldPos.getY(), worldPos.getZ());
            return true;
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            materialBridgeDisabled = true;
            CreateNautilus.LOGGER.warn("Unable to mark virtual water for Iris", exception);
            return false;
        }
    }

    static void end(final VertexConsumer consumer) {
        if (ACCESS == null || materialBridgeDisabled
                || !ACCESS.builderType().isInstance(consumer)) {
            return;
        }
        try {
            ACCESS.endBlock().invoke(consumer);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            materialBridgeDisabled = true;
            CreateNautilus.LOGGER.warn("Unable to close virtual-water Iris context", exception);
        }
    }

    /**
     * Returns the material id Iris assigned to vanilla water for the currently loaded shader
     * pack.  The value must not be cached: Iris rebuilds this table when shaders are reloaded and
     * shader packs are free to assign a different id.
     */
    static int waterMaterialId() {
        if (ACCESS == null) {
            return -1;
        }
        try {
            final Object idMapObject = ACCESS.getBlockStateIds().invoke(
                    ACCESS.settingsInstance().get(null));
            if (!(idMapObject instanceof final Object2IntMap<?> rawIds)) {
                return -1;
            }
            @SuppressWarnings("unchecked")
            final Object2IntMap<Object> ids = (Object2IntMap<Object>) rawIds;
            return ids.getOrDefault(
                    Fluids.WATER.defaultFluidState().createLegacyBlock(), -1);
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            return -1;
        }
    }

    private static Access findAccess() {
        try {
            final Class<?> builderType = Class.forName(
                    "net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder");
            final Method beginBlock = builderType.getMethod("beginBlock",
                    int.class, byte.class, byte.class,
                    int.class, int.class, int.class);
            final Method endBlock = builderType.getMethod("endBlock");

            final Class<?> settingsType = Class.forName(
                    "net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            final Field settingsInstance = settingsType.getField("INSTANCE");
            final Method getBlockStateIds = settingsType.getMethod("getBlockStateIds");
            return new Access(builderType, beginBlock, endBlock,
                    settingsInstance, getBlockStateIds);
        } catch (final ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static PhaseAccess findPhaseAccess() {
        try {
            final Class<?> phaseType = Class.forName(
                    "net.irisshaders.iris.pipeline.WorldRenderingPhase");
            @SuppressWarnings({"unchecked", "rawtypes"})
            final Object terrainTranslucent = Enum.valueOf(
                    (Class<? extends Enum>) phaseType.asSubclass(Enum.class),
                    "TERRAIN_TRANSLUCENT");
            final Class<?> programsType = Class.forName(
                    "net.irisshaders.iris.layer.GbufferPrograms");
            final Method setOverridePhase = programsType.getMethod(
                    "setOverridePhase", phaseType);
            return new PhaseAccess(setOverridePhase, terrainTranslucent);
        } catch (final ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private record Access(Class<?> builderType, Method beginBlock, Method endBlock,
                          Field settingsInstance, Method getBlockStateIds) {
    }

    private record PhaseAccess(Method setOverridePhase, Object terrainTranslucent) {
    }
}
