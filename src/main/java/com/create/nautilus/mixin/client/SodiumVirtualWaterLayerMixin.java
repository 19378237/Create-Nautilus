package com.create.nautilus.mixin.client;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.client.render.ClientCompartmentVisualCache;
import com.create.nautilus.client.render.FloodedChamberRenderer;
import com.create.nautilus.client.render.IrisWaterMaterialBridge;
import com.create.nautilus.client.render.SodiumWaterOcclusionShaderBridge;
import com.create.nautilus.client.render.WaterMediumClassifier;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3d;
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
    private static boolean createNautilus$matrixLookupFailed;
    @Unique
    private static boolean createNautilus$passLogged;
    @Unique
    private static long createNautilus$lastPassLogNanos;
    @Unique
    private static boolean createNautilus$underwaterOutsideSkipLogged;

    /**
     * A camera in real parent-world water must see only the parent ocean material. Exact room-cell
     * membership deliberately avoids the quarter-block camera margin, which can otherwise turn an
     * outside eye beside glass into a tracked cabin for one frame.
     */
    @Unique
    private static boolean createNautilus$skipExternalParentWaterSubmission() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        final var cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
        final boolean externalParentWater = WaterMediumClassifier.parentWorldContains(
                minecraft.level, new Vector3d(
                        cameraPosition.x, cameraPosition.y, cameraPosition.z))
                && !ClientCompartmentVisualCache.isInsideTrackedRoomExact(cameraPosition);
        if (externalParentWater && !createNautilus$underwaterOutsideSkipLogged) {
            createNautilus$underwaterOutsideSkipLogged = true;
            CreateNautilus.LOGGER.info(
                    "[virtual-water] skipping external parent-water submission");
        }
        return externalParentWater;
    }

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
                || createNautilus$matrixLookupFailed) {
            return;
        }
        /*
         * Do not touch the terrain shader or its VAO for an outside camera already submerged in
         * real water. The same gate is applied to TAIL because its conservative room-bounds split
         * may still classify a camera outside a non-convex room as inside.
         */
        if (createNautilus$skipExternalParentWaterSubmission()) {
            return;
        }
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
            createNautilus$matrixLookupFailed = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to render virtual water outside the room", exception);
        }
    }

    @Inject(method = "drawChunkLayer", at = @At("TAIL"), require = 0)
    private void createNautilus$drawVirtualWaterInside(
            final RenderType renderType,
            @Coerce final Object matrices,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final CallbackInfo callback) {
        if (renderType != RenderType.translucent() || matrices == null
                || !FloodedChamberRenderer.hasVisibleWater()
                || createNautilus$matrixLookupFailed) {
            return;
        }
        if (createNautilus$skipExternalParentWaterSubmission()) {
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
            createNautilus$matrixLookupFailed = true;
            CreateNautilus.LOGGER.warn(
                    "Unable to render virtual water inside the room", exception);
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
            renderType.setupRenderState();
            final ShaderInstance shader = Objects.requireNonNull(
                    RenderSystem.getShader(), "terrain translucent shader");
            shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
                    modelViewMatrix, projectionMatrix, minecraft.getWindow());
            shader.apply();
            try {
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
                shader.clear();
                renderType.clearRenderState();
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
        if (modelView == null || projection == null) {
            modelView = matrices.getClass().getMethod("modelView");
            projection = matrices.getClass().getMethod("projection");
            createNautilus$modelView = modelView;
            createNautilus$projection = projection;
        }
        return new Matrix4f[]{
                new Matrix4f((Matrix4f) modelView.invoke(matrices)),
                new Matrix4f((Matrix4f) projection.invoke(matrices))
        };
    }

    @Unique
    private static boolean createNautilus$hookLogged;
}
