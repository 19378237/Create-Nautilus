package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version-gated lifecycle for the parent-world water occlusion pass.
 *
 * <p>This class is deliberately the only entry point used by optional Sodium/Colorwheel mixins.
 * Dry-compartment clipping needs matching shader sources, Sable depth buffers and an active
 * parent-world terrain draw.  Enabling only one of those parts corrupts ordinary ocean rendering,
 * so a missing class, unexpected version, failed uniform lookup or failed depth pass disables the
 * complete feature until the next shader-pipeline reload.</p>
 */
public final class WaterOcclusionPassBridge {
    private static final Map<String, String> REQUIRED_VERSIONS = requiredVersions();

    private static boolean versionsChecked;
    private static boolean versionsSupported;
    private static boolean disabledUntilReload;
    private static volatile PreparedTerrainPass preparedTerrainPass;
    private static boolean warningLogged;
    private static volatile boolean irisActiveStateChecked;
    private static volatile boolean cachedIrisShaderPackActive;
    private static boolean irisApiLookupDone;
    private static Method irisShaderPackQuery;
    private static Object irisApiInstance;

    private WaterOcclusionPassBridge() {
    }

    /** Called when Colorwheel creates a fresh source cache, including after shader reload. */
    public static synchronized void onColorwheelPipelineReload() {
        refreshIrisShaderPackState(false);
        SodiumWaterOcclusionShaderBridge.end();
        SodiumWaterOcclusionShaderBridge.onPipelineReset();
        ShaderVoxelMaskAtlas.discardPreparedFrame();
        // A shader-pipeline rebuild changes only linked programs and private GL resources.
        // Keeping the immutable aperture/event cache alive is essential: clearing it here would
        // renumber optical slots while the still-published voxel atlas retained the old layout.
        ApertureMediumMaskRenderer.invalidatePipeline();
        preparedTerrainPass = null;
        disabledUntilReload = false;
        warningLogged = false;
    }

    /**
     * Produces Sable's near/far hull textures from the matrices of the current Sodium draw.
     *
     * <p>The call is made at {@code SodiumWorldRenderer.drawChunkLayer} HEAD, before Sodium binds
     * the relevant terrain program.  A subsequent {@link #beginParentWaterPass()} may therefore
     * only enable clipping when this exact preparation succeeded.</p>
     */
    public static void prepareParentWaterPass(final Matrix4f modelView,
                                              final Matrix4f projection) {
        final var camera = net.minecraft.client.Minecraft.getInstance()
                .gameRenderer.getMainCamera().getPosition();
        prepareParentWaterPass(modelView, projection, camera.x, camera.y, camera.z);
    }

    /** Captures the exact camera origin Sodium used to build this parent-world chunk pass. */
    public static void prepareParentWaterPass(final Matrix4f modelView,
                                              final Matrix4f projection,
                                              final double cameraX,
                                              final double cameraY,
                                              final double cameraZ) {
        preparedTerrainPass = null;
        if (!isSupportedAndEnabled()) {
            return;
        }
        /*
         * Iris builds its exact atlas from the complete visual topology.  The legacy dry-region
         * cache intentionally omits cells crossed by the water plane, so using it as an Iris
         * gate would lose one-cell and partially flooded rooms.  Plain Sodium still requires a
         * Sable close/far region before preparing its depth buffers.
         */
        if (!cachedIrisShaderPackActive && !ClientDryCompartmentCache.hasOcclusionRegions()) {
            return;
        }
        final double effectiveCameraX = sodiumEffectiveCamera(cameraX);
        final double effectiveCameraY = sodiumEffectiveCamera(cameraY);
        final double effectiveCameraZ = sodiumEffectiveCamera(cameraZ);
        if (!SodiumWaterOcclusionShaderBridge.prepareDepthBuffers(
                modelView, projection, effectiveCameraX, effectiveCameraY, effectiveCameraZ)) {
            disableUntilReload("Sable did not produce valid dry-compartment depth buffers", null);
            return;
        }
        if (cachedIrisShaderPackActive) {
            // prepareDepthBuffers has just frozen the exact voxel-atlas room surfaces. Reuse that
            // authoritative solve for the glass event cache, then freeze the same water-pass
            // matrices; this avoids a second full room-cell bisection and an AFTER_SKY mismatch.
            ApertureMediumMaskRenderer.prepareParentTranslucent(
                    new Matrix4f(modelView), new Matrix4f(projection),
                    effectiveCameraX, effectiveCameraY, effectiveCameraZ);
        }
        /*
         * Sodium uploads these ChunkRenderMatrices only after ShaderChunkRenderer.begin returns.
         * Keep an immutable copy now so the later begin hook never samples the previous draw's
         * iris_* uniforms.
         */
        preparedTerrainPass = new PreparedTerrainPass(
                new Matrix4f(modelView), new Matrix4f(projection),
                effectiveCameraX, effectiveCameraY, effectiveCameraZ);
    }

    /** Enables the discard uniforms only around the prepared parent-world translucent draw. */
    public static void beginParentWaterPass() {
        SodiumWaterOcclusionShaderBridge.end();
        final PreparedTerrainPass prepared = preparedTerrainPass;
        preparedTerrainPass = null;
        if (prepared == null || !isSupportedAndEnabled()) {
            ShaderVoxelMaskAtlas.discardPreparedFrame();
            return;
        }
        /*
         * A missing uniform is recoverable on the very next frame (e.g. the program that is
         * current while Iris swaps shader packs). Never latch permanently on it: begin() already
         * logs once, and the exact voxel path re-engages automatically once the new pack's water
         * program carries the Nautilus uniforms.
         */
        SodiumWaterOcclusionShaderBridge.begin(
                prepared.modelView(), prepared.projection(),
                prepared.cameraX(), prepared.cameraY(), prepared.cameraZ());
    }

    /**
     * Called when Iris recreates the water program (shader pack switch or shader reload).
     *
     * <p>Unlike {@link #onColorwheelPipelineReload()} this performs no GL work and is therefore
     * safe to invoke from shader-compilation threads. It only clears one-shot log latches and the
     * permanent disable flag so the freshly compiled program is retried immediately.</p>
     */
    public static synchronized void onShaderProgramRecreated() {
        refreshIrisShaderPackState(false);
        SodiumWaterOcclusionShaderBridge.onPipelineReset();
        ShaderVoxelMaskAtlas.discardPreparedFrame();
        // This callback may run on Iris' shader compilation thread.  Do not tear down semantic
        // geometry or force a full atlas rebuild; queue only the pipeline-owned GL reset.
        ApertureMediumMaskRenderer.invalidatePipeline();
        preparedTerrainPass = null;
        disabledUntilReload = false;
        warningLogged = false;
    }

    /**
     * Resource reloads can recreate Sodium/Iris-facing resources without invoking the shader
     * pipeline callbacks. Clear a transient disable latch so the next parent-water pass retries;
     * this method does not perform GL work and is safe for the reload listener.
     */
    public static synchronized void onResourceReload() {
        disabledUntilReload = false;
        warningLogged = false;
        preparedTerrainPass = null;
    }

    /** Restores all texture/uniform state before another transparent producer is submitted. */
    public static void endParentWaterPass() {
        SodiumWaterOcclusionShaderBridge.end();
        ShaderVoxelMaskAtlas.discardPreparedFrame();
        preparedTerrainPass = null;
    }

    /** Called by optional mixins when a reflected renderer signature no longer matches. */
    public static synchronized void disableUntilReload(final String reason,
                                                       final Throwable cause) {
        SodiumWaterOcclusionShaderBridge.end();
        ShaderVoxelMaskAtlas.discardPreparedFrame();
        preparedTerrainPass = null;
        disabledUntilReload = true;
        if (warningLogged) {
            return;
        }
        warningLogged = true;
        final String message = "Create: Nautilus disabled dry-compartment ocean clipping for "
                + "the current shader pipeline: " + reason
                + ". Ordinary world water remains unmodified.";
        if (cause == null) {
            CreateNautilus.LOGGER.warn(message);
        } else {
            CreateNautilus.LOGGER.warn(message, cause);
        }
    }

    /** Exact compatibility target; unknown versions fail open rather than touching world water. */
    public static synchronized boolean isSupportedAndEnabled() {
        if (!versionsChecked) {
            versionsChecked = true;
            versionsSupported = validateVersions();
        }
        refreshShaderPackState();
        return versionsSupported && !disabledUntilReload;
    }

    /**
     * Resets the one-shot disable latch when Iris' shader-pack state flips.
     *
     * <p>{@link #disableUntilReload} is meant to last only until the next shader-pipeline
     * rebuild, but with Sodium/Iris/Colorwheel there is no vanilla resource-reload callback that
     * reliably fires when a pack is switched on or off. Without this reset the latch stayed set
     * after turning a pack off, so dry-compartment clipping never returned even though the
     * Sodium program (which does carry the Nautilus uniforms) was active again.</p>
     */
    public static synchronized void refreshShaderPackState() {
        refreshIrisShaderPackState(true);
    }

    private static void refreshIrisShaderPackState(final boolean resetAfterChange) {
        final boolean active = queryIrisShaderPackActive();
        final boolean changed = irisActiveStateChecked
                && active != cachedIrisShaderPackActive;
        irisActiveStateChecked = true;
        cachedIrisShaderPackActive = active;
        if (changed && resetAfterChange && disabledUntilReload) {
            CreateNautilus.LOGGER.info(
                    "Iris shader-pack state changed (shaderPackActive={}); retrying "
                            + "dry-compartment ocean clipping",
                    active);
            onColorwheelPipelineReload();
        }
    }

    private static boolean queryIrisShaderPackActive() {
        try {
            if (!irisApiLookupDone) {
                irisApiLookupDone = true;
                final Class<?> apiType = Class.forName(
                        "net.irisshaders.iris.api.v0.IrisApi");
                irisApiInstance = apiType.getMethod("getInstance").invoke(null);
                irisShaderPackQuery = apiType.getMethod("isShaderPackInUse");
            }
            return irisShaderPackQuery != null
                    && Boolean.TRUE.equals(irisShaderPackQuery.invoke(irisApiInstance));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Public read-only renderer state used to prevent shader mode from deleting chunk water. */
    public static boolean isShaderPackActive() {
        // Mesh workers must never perform Iris reflection. Render/resource hooks refresh this
        // value at pass and pipeline boundaries. Unknown must be treated as active: suppressing
        // parent water before the first render-thread refresh would be the unsafe direction.
        return !irisActiveStateChecked || cachedIrisShaderPackActive;
    }

    private static boolean validateVersions() {
        final ModList mods = ModList.get();
        for (final Map.Entry<String, String> entry : REQUIRED_VERSIONS.entrySet()) {
            final var container = mods.getModContainerById(entry.getKey());
            if (container.isEmpty()) {
                CreateNautilus.LOGGER.warn(
                        "Dry-compartment ocean clipping is unavailable: required mod {} is not "
                                + "loaded. Virtual water will use its safe render fallback.",
                        entry.getKey());
                return false;
            }
            final String actual = container.get().getModInfo().getVersion().toString();
            if (!entry.getValue().equals(actual)) {
                CreateNautilus.LOGGER.warn(
                        "Dry-compartment ocean clipping is unavailable for {} {} (verified "
                                + "version: {}). Ordinary world water will not be patched.",
                        entry.getKey(), actual, entry.getValue());
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> requiredVersions() {
        final Map<String, String> versions = new LinkedHashMap<>();
        versions.put("sable", "2.0.3");
        versions.put("sodium", "0.8.12-beta.2+mc1.21.1");
        versions.put("iris", "1.8.14-beta.1+mc1.21.1");
        versions.put("colorwheel", "1.2.9+mc1.21.1");
        versions.put("colorwheel_patcher", "1.0.5+mc1.21.1");
        return Map.copyOf(versions);
    }

    /**
     * Mirrors Sodium 0.8.12's {@code CameraTransform} fractional quantization exactly.
     *
     * <p>{@code getVertexPosition()} subtracts this effective origin, not the original camera
     * double. Using the same value for atlas descriptors and room water levels keeps half-open
     * voxel boundaries aligned down to the last representable camera-relative float.</p>
     */
    static double sodiumEffectiveCamera(final double value) {
        if (!Double.isFinite(value)) {
            return value;
        }
        final int integral = (int) value;
        final float fractional = (float) (value - integral);
        final float precisionModifier = Math.copySign(128.0F, fractional);
        final float quantizedFraction = (fractional + precisionModifier) - precisionModifier;
        return integral + (double) quantizedFraction;
    }

    /** Exact, current-draw inputs captured before Sodium binds the translucent program. */
    private record PreparedTerrainPass(Matrix4f modelView, Matrix4f projection,
                                       double cameraX, double cameraY, double cameraZ) {
    }
}
