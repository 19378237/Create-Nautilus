package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.FloodedChamberRenderer;
import com.create.nautilus.client.render.IrisWaterMaterialBridge;
import com.create.nautilus.client.render.SodiumWaterOcclusionShaderBridge;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Submits virtual compartment water beside Sable's real sublevel fluid buffers inside Sodium's
 * terrain-translucent pass, so Iris shader packs classify it as water.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer",
        remap = false, priority = 900)
public abstract class SodiumVirtualWaterLayerMixin {
    @Unique
    private static Method createNautilus$modelView;
    @Unique
    private static Method createNautilus$projection;
    @Unique
    private static Class<?> createNautilus$unsupportedMatrixClass;
    @Unique
    private static long createNautilus$lastFailureLogNanos;
    @Unique
    private static boolean createNautilus$passLogged;
    @Unique
    private static long createNautilus$lastPassLogNanos;

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), require = 0)
    private void createNautilus$drawVirtualWaterOutside(
            final RenderType renderType,
            @Coerce final Object matrices,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final CallbackInfo callback) {
        if (!createNautilus$hookLogged) {
            createNautilus$hookLogged = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] SodiumWorldRenderer.drawChunkLayer hook reached renderType={}",
                    renderType);
        }
        if (renderType != RenderType.translucent() || matrices == null
                || !FloodedChamberRenderer.hasVisibleWater()
                || matrices.getClass() == createNautilus$unsupportedMatrixClass) {
            return;
        }
        // Real parent water does not replace a compartment's virtual water. Both perspectives
        // submit it; the renderer limits hidden boundaries to an eye inside virtual water itself.
        try {
            final Matrix4f[] pair = createNautilus$matrices(matrices);
            SodiumWaterOcclusionShaderBridge.setVirtualWaterPass(true);
            try {
                createNautilus$drawVirtualWaterPass(
                        renderType, pair[0], pair[1],
                        cameraX, cameraY, cameraZ,
                        FloodedChamberRenderer.NativeSurfacePass.CAMERA_OUTSIDE_ROOM);
            } finally {
                SodiumWaterOcclusionShaderBridge.setVirtualWaterPass(false);
            }
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            createNautilus$logDrawFailure("outside", exception);
        }
    }

    // Sable's default-order TAIL draws glass first; our nearer room-side shell must follow it.
    @Inject(method = "drawChunkLayer", at = @At("TAIL"), require = 0, order = 1100)
    private void createNautilus$drawVirtualWaterInside(
            final RenderType renderType,
            @Coerce final Object matrices,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final CallbackInfo callback) {
        if (renderType != RenderType.translucent() || matrices == null
                || !FloodedChamberRenderer.hasVisibleWater()
                || matrices.getClass() == createNautilus$unsupportedMatrixClass) {
            return;
        }
        try {
            final Matrix4f[] pair = createNautilus$matrices(matrices);
            SodiumWaterOcclusionShaderBridge.setVirtualWaterPass(true);
            try {
                createNautilus$drawVirtualWaterPass(
                        renderType, pair[0], pair[1],
                        cameraX, cameraY, cameraZ,
                        FloodedChamberRenderer.NativeSurfacePass.CAMERA_INSIDE_ROOM);
            } finally {
                SodiumWaterOcclusionShaderBridge.setVirtualWaterPass(false);
            }
        } catch (final ReflectiveOperationException | RuntimeException exception) {
            createNautilus$logDrawFailure("inside", exception);
        }
    }

    @Unique
    private static void createNautilus$drawVirtualWaterPass(
            final RenderType renderType,
            final Matrix4f modelViewMatrix,
            final Matrix4f projectionMatrix,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final FloodedChamberRenderer.NativeSurfacePass pass) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final long now = System.nanoTime();
        if (!createNautilus$passLogged
                || now - createNautilus$lastPassLogNanos > 5_000_000_000L) {
            createNautilus$passLogged = true;
            createNautilus$lastPassLogNanos = now;
            CreateNautilus.LOGGER.info(
                    "[virtual-water] translucent hook reached pass={}", pass);
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return;
        }

        final boolean irisTerrainPhase =
                IrisWaterMaterialBridge.beginTerrainTranslucentPhase();
        try {
            ShaderInstance shader = null;
            try {
                renderType.setupRenderState();
                shader = Objects.requireNonNull(
                        RenderSystem.getShader(), "terrain translucent shader");
                shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
                        modelViewMatrix, projectionMatrix, minecraft.getWindow());
                shader.apply();
                final List<ClientSubLevel> subLevels = new ArrayList<>();
                for (final var subLevel : container.getAllSubLevels()) {
                    if (subLevel instanceof final ClientSubLevel clientSubLevel) {
                        subLevels.add(clientSubLevel);
                    }
                }
                FloodedChamberRenderer.drawInNativeFluidLayer(
                        subLevels, shader, modelViewMatrix, projectionMatrix,
                        cameraX, cameraY, cameraZ, pass);
            } finally {
                try {
                    if (shader != null) shader.clear();
                } finally {
                    renderType.clearRenderState();
                }
            }
        } finally {
            if (irisTerrainPhase) {
                IrisWaterMaterialBridge.endTerrainTranslucentPhase();
            }
        }
    }

    @Unique
    private static Matrix4f[] createNautilus$matrices(final Object matrices)
            throws ReflectiveOperationException {
        Method modelView = createNautilus$modelView;
        Method projection = createNautilus$projection;
        if (modelView == null || projection == null
                || modelView.getDeclaringClass() != matrices.getClass()) {
            try {
                modelView = matrices.getClass().getMethod("modelView");
                projection = matrices.getClass().getMethod("projection");
            } catch (final NoSuchMethodException exception) {
                // A fixed class signature cannot recover in this JVM. Draw/apply exceptions,
                // however, must never poison matrix compatibility for later frames or worlds.
                createNautilus$unsupportedMatrixClass = matrices.getClass();
                throw exception;
            }
            createNautilus$modelView = modelView;
            createNautilus$projection = projection;
        }
        return new Matrix4f[]{
                new Matrix4f((Matrix4f) modelView.invoke(matrices)),
                new Matrix4f((Matrix4f) projection.invoke(matrices))
        };
    }

    @Unique
    private static void createNautilus$logDrawFailure(final String pass,
                                                     final Exception exception) {
        final long now = System.nanoTime();
        if (now - createNautilus$lastFailureLogNanos < 5_000_000_000L) return;
        createNautilus$lastFailureLogNanos = now;
        CreateNautilus.LOGGER.warn(
                "Unable to render virtual water {} the room; later draws may retry", pass, exception);
    }

    @Unique
    private static boolean createNautilus$hookLogged;
}
