package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.create.nautilus.physics.CompartmentWaterGeometry;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Draws an interpolated, world-anchored water surface for every flooded room.
 *
 * <p>With Sodium/Iris the surface is submitted inside the real terrain-translucent pass
 * (Sodium {@code drawChunkLayer}), marked with the vanilla water material id, so shader packs
 * treat it as water.  Without Sodium the same geometry is drawn through vanilla translucent.</p>
 */
public final class FloodedChamberRenderer {
    private static final ResourceLocation WATER_STILL =
            ResourceLocation.withDefaultNamespace("block/water_still");
    private static final int[][] BOX_EDGES = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
    };
    private static final Direction[] WATER_BOUNDARY_DIRECTIONS = {
            Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH, Direction.DOWN, Direction.UP
    };
    // Direction 3D ids: DOWN, UP, NORTH, SOUTH, WEST, EAST.
    private static final int[][] FACE_CORNER_INDICES = {
            {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1},
            {4, 5, 7, 6}, {0, 4, 6, 2}, {1, 3, 7, 5}
    };
    private static final boolean SODIUM_PRESENT = classPresent(
            "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
    private static boolean exteriorFilmLogged;
    private static int exteriorFilmFaceCount;
    private static long lastFilmLogNanos;
    private static java.util.Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>>
            cachedFrameSnapshots;
    private static long cachedSnapshotNanos;
    private static boolean meshLogged;
    private static long lastMeshLogNanos;
    private static boolean passRoomLogged;
    private static long lastPassRoomLogNanos;
    /**
     * Diagnostic switch (disabled): renders every virtual-water quad as solid magenta, bypassing
     * the water material, texture and alpha. Kept off; the pass-splitting fix was confirmed with
     * it enabled and the real water material is active again.
     */
    private static final boolean DIAGNOSTIC_MAGENTA = false;

    private FloodedChamberRenderer() {
    }

    /** A removed/replaced carrier must not survive in the short-lived frame snapshot. */
    static void invalidateSnapshots() {
        cachedFrameSnapshots = null;
    }

    /**
     * Frame-stable snapshot view. The virtual water is submitted twice per frame (Sodium HEAD
     * and TAIL); the water fraction is time-interpolated, so two fresh {@code snapshots()} calls
     * a few milliseconds apart would produce slightly different geometry and shimmer. Caching
     * for the duration of one frame gives both passes identical geometry.
     */
    private static java.util.Map<UUID, List<ClientCompartmentVisualCache.RoomSnapshot>>
    frameSnapshots() {
        final long now = System.nanoTime();
        if (cachedFrameSnapshots != null && now - cachedSnapshotNanos < 33_000_000L) {
            return cachedFrameSnapshots;
        }
        cachedSnapshotNanos = now;
        cachedFrameSnapshots = ClientCompartmentVisualCache.snapshots();
        return cachedFrameSnapshots;
    }

    /**
     * Whether any tracked room currently contains virtual water.
     *
     * <p>The Sodium submission hooks run on every translucent {@code drawChunkLayer}, and each
     * invocation performs {@code RenderType.setupRenderState()}, {@code shader.apply()} and a
     * {@code BufferUploader} draw.  With only dry rooms that work is pure GL state churn (and in
     * the Iris/Sodium pass it emits {@code GL_INVALID_OPERATION: Array object is not active}),
     * so dry-only frames skip the whole native-layer submission.</p>
     */
    public static boolean hasVisibleWater() {
        return frameSnapshots().values().stream()
                .flatMap(List::stream)
                .anyMatch(room -> room.waterFraction() > 1.0E-5D);
    }

    /**
     * Translucent water must be submitted on different sides of the carrier/parent-water draw.
     */
    public enum NativeSurfacePass {
        CAMERA_OUTSIDE_ROOM,
        CAMERA_INSIDE_ROOM
    }

    public static void onRenderLevel(final RenderLevelStageEvent event) {
        // Sodium has a materially different lifetime: Sable renders carrier section buffers from
        // SodiumWorldRenderer.drawChunkLayer(TAIL). Drawing here would be too late for Iris'
        // terrain-water gbuffer and external water would depth-occlude the virtual surface.
        if (SODIUM_PRESENT) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final var snapshots = frameSnapshots();
        if (level == null || snapshots.isEmpty()) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final TextureAtlasSprite sprite = minecraft
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER_STILL);
        final PoseStack poseStack = event.getPoseStack();
        final Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        final BufferBuilder vertices = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            final Matrix4f matrix = poseStack.last().pose();

            snapshots.forEach((carrierId, rooms) -> {
                if (!(container.getSubLevel(carrierId)
                        instanceof final ClientSubLevel carrier)) {
                    return;
                }
                rooms.forEach(room -> renderRoom(
                        vertices, matrix, sprite, carrier, room, false));
            });
        } finally {
            poseStack.popPose();
        }

        final MeshData mesh = vertices.build();
        if (mesh == null) {
            return;
        }

        // Vanilla fallback: no Iris/Sodium material bridge is involved.
        RenderType.translucent().draw(mesh);
    }

    /**
     * Emits Attachment-backed cabin water while Sodium/Iris is still rendering the native
     * terrain-translucent layer.
     */
    public static void drawInNativeFluidLayer(final Iterable<ClientSubLevel> subLevels,
                                              final ShaderInstance shader,
                                              final Matrix4f modelView,
                                              final Matrix4f projection,
                                              final double cameraX,
                                              final double cameraY,
                                              final double cameraZ,
                                              final NativeSurfacePass pass) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !hasVisibleWater()) {
            return;
        }
        final long now = System.nanoTime();

        // Only the patched Photon immediate material declares this draw-local switch. Keep
        // other shader packs and vanilla on their existing lighting path.
        final int materialProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final boolean roomLighting = materialProgram > 0
                && GL20C.glGetUniformLocation(materialProgram,
                        SodiumOcclusionShaderSource.VIRTUAL_WATER_UNIFORM) >= 0;
        final TextureAtlasSprite sprite = minecraft
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WATER_STILL);
        final BufferBuilder vertices = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
        /*
         * Sodium chunk vertices are camera-relative and the terrain shader owns view rotation.
         * Supplying absolute world vertices here would lose precision; multiplying them by the
         * complete model-view twice would instead make the surface drift with the camera.
         */
        final Matrix4f cameraTranslation = new Matrix4f().translation(
                (float) -cameraX, (float) -cameraY, (float) -cameraZ);
        final var snapshots = frameSnapshots();
        /*
         * The exterior water proxy stays disabled: its flat water-material quads are rendered as
         * visible triangles by the pack. The porthole fog is now driven by the composite patch
         * (raymarch to the back surface through glass) gated by NautilusDryCameraActive.
         */
        for (final ClientSubLevel carrier : subLevels) {
            final List<ClientCompartmentVisualCache.RoomSnapshot> rooms =
                    snapshots.get(carrier.getUniqueId());
            if (rooms == null) {
                continue;
            }
            int renderedRooms = 0;
            for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
                final boolean cameraInside = cameraInsideRoom(
                        carrier, room, cameraX, cameraY, cameraZ);
                if ((pass == NativeSurfacePass.CAMERA_INSIDE_ROOM) != cameraInside) {
                    continue;
                }
                renderedRooms++;
                renderRoom(vertices, cameraTranslation, sprite, carrier, room, roomLighting);
            }
            if (!passRoomLogged || now - lastPassRoomLogNanos > 5_000_000_000L) {
                passRoomLogged = true;
                lastPassRoomLogNanos = now;
                CreateNautilus.LOGGER.info(
                        "[virtual-water] pass={} carrier={} rooms={} rendered={} "
                                + "camera=({}, {}, {})",
                        pass, carrier.getUniqueId(), rooms.size(), renderedRooms,
                        cameraX, cameraY, cameraZ);
            }
        }

        final MeshData mesh = vertices.build();
        if (mesh == null) {
            return;
        }
        if (!meshLogged || now - lastMeshLogNanos > 5_000_000_000L) {
            meshLogged = true;
            lastMeshLogNanos = now;
            CreateNautilus.LOGGER.info(
                    "[virtual-water] mesh vertices={} pass={} rooms={}",
                    mesh.drawState().vertexCount(), pass, snapshots.size());
        }

        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection,
                minecraft.getWindow());
        shader.apply();
        /*
         * Re-assert the dry-compartment discard exemption immediately before the draw. The
         * Sodium hook sets NautilusVirtualWater before entering this method, but renderType
         * setup/apply can rebind and reset the terrain program's uniform state; without the flag
         * the OBB water discard culls every virtual-water fragment inside the dry hull and the
         * whole volume becomes invisible.
         */
        SodiumWaterOcclusionShaderBridge.setVirtualWaterPass(true);

        /*
         * A normal chunk-water top face is visible from both sides.  Some Iris packs enable face
         * culling inside their terrain-translucent program.  Temporarily disabling culling gives
         * one physical surface two-sided visibility without double blending.
         */
        final boolean cullWasEnabled = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        final int previousCullFace = GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE);
        final int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        final int previousArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            RenderSystem.disableCull();
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            /*
             * Sodium binds and unbinds its VAOs through a private state tracker, while
             * BufferUploader separately caches the last immediate VertexBuffer.  The TAIL
             * virtual-water draw therefore used to see the same cached BLOCK buffer, skip its
             * bind, and upload/draw with Sodium's VAO already unbound.  NVIDIA reports every one
             * of those draws as GL_INVALID_OPERATION: "Array object is not active".
             *
             * Invalidate the Java-side immediate-buffer cache before drawing, then invalidate it
             * again and restore the raw bindings on exit.  The second invalidation is essential:
             * restoring a caller-owned VAO behind BufferUploader's back must not leave it
             * believing that its own immediate VAO is still active on the next frame.
             */
            BufferUploader.invalidate();
            BufferUploader.drawWithShader(mesh);
        } finally {
            BufferUploader.invalidate();
            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousArrayBuffer);
            GL11C.glCullFace(previousCullFace);
            if (cullWasEnabled) {
                RenderSystem.enableCull();
                GL11C.glEnable(GL11C.GL_CULL_FACE);
            } else {
                RenderSystem.disableCull();
                GL11C.glDisable(GL11C.GL_CULL_FACE);
            }
        }
    }

    /**
     * Emits a water-medium proxy just outside every exterior face of a dry compartment: a
     * water-material quad one wall block beyond the room bounds, only where the parent world
     * fluid beyond it is water. Marking the quad with the vanilla water material makes Iris and
     * the active shader pack treat it as native water, so the porthole view gets the pack's own
     * refraction, tint, distance fog and light attenuation while the cabin interior stays dry
     * and untouched.
     */
    private static void drawExteriorWaterProxy(final VertexConsumer vertices,
                                               final Matrix4f matrix,
                                               final TextureAtlasSprite sprite) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return;
        }
        int emitted = 0;
        int skippedCarriers = 0;
        int roomCount = 0;
        for (final var carrierEntry : ClientCompartmentVisualCache.snapshots().entrySet()) {
            if (!(container.getSubLevel(carrierEntry.getKey())
                    instanceof final ClientSubLevel carrier)) {
                skippedCarriers++;
                continue;
            }
            for (final ClientCompartmentVisualCache.RoomSnapshot room : carrierEntry.getValue()) {
                roomCount++;
                if (room.waterFraction() > 1.0E-5D) {
                    continue;
                }
                emitted += emitRoomWaterProxy(vertices, matrix, sprite, carrier, room);
            }
        }
        final long now = System.nanoTime();
        if (now - lastFilmLogNanos > 5_000_000_000L) {
            lastFilmLogNanos = now;
            CreateNautilus.LOGGER.info(
                    "[film] exterior water proxy faces emitted: {} "
                            + "(carriersSkipped={}, rooms={})",
                    emitted, skippedCarriers, roomCount);
        }
    }

    private static int emitRoomWaterProxy(final VertexConsumer vertices,
                                          final Matrix4f matrix,
                                    final TextureAtlasSprite sprite,
                                    final ClientSubLevel carrier,
                                    final ClientCompartmentVisualCache.RoomSnapshot room) {
        final Minecraft minecraft = Minecraft.getInstance();
        final AABB bounds = room.bounds();
        int emitted = 0;
        for (final Direction direction : Direction.values()) {
            final int nx = direction.getStepX();
            final int ny = direction.getStepY();
            final int nz = direction.getStepZ();
            /*
             * One wall block plus a small epsilon puts the proxy just outside the glass, in the
             * parent water volume, so it is visible through the porthole but never inside the
             * cabin and never inside the dry-compartment OBB.
             */
            final double wallOffset = 1.02D;
            final double plane;
            if (nx != 0) {
                plane = nx > 0 ? bounds.maxX + wallOffset : bounds.minX - wallOffset;
            } else if (ny != 0) {
                plane = ny > 0 ? bounds.maxY + wallOffset : bounds.minY - wallOffset;
            } else {
                plane = nz > 0 ? bounds.maxZ + wallOffset : bounds.minZ - wallOffset;
            }

            final int axisA;
            final int axisB;
            if (nx != 0) {
                axisA = 1;
                axisB = 2;
            } else if (ny != 0) {
                axisA = 0;
                axisB = 2;
            } else {
                axisA = 0;
                axisB = 1;
            }
            final double minA = axisA == 0 ? bounds.minX : axisA == 1 ? bounds.minY : bounds.minZ;
            final double maxA = axisA == 0 ? bounds.maxX : axisA == 1 ? bounds.maxY : bounds.maxZ;
            final double minB = axisB == 0 ? bounds.minX : axisB == 1 ? bounds.minY : bounds.minZ;
            final double maxB = axisB == 0 ? bounds.maxX : axisB == 1 ? bounds.maxY : bounds.maxZ;
            for (int a = (int) minA; a < (int) maxA; a++) {
                for (int b = (int) minB; b < (int) maxB; b++) {
                    final Vector3d sampleLocal = proxySample(nx, ny, nz, axisA, axisB,
                            plane, a, b);
                    final Vector3d sampleWorldCamRel = carrier.renderPose().transformPosition(
                            new Vector3d(sampleLocal));
                    final BlockPos worldPos = BlockPos.containing(
                            sampleWorldCamRel.x, sampleWorldCamRel.y, sampleWorldCamRel.z);
                    if (!minecraft.level.getFluidState(worldPos).is(FluidTags.WATER)) {
                        continue;
                    }
                    final int tint = BiomeColors.getAverageWaterColor(minecraft.level, worldPos);
                    final int red = tint >> 16 & 0xFF;
                    final int green = tint >> 8 & 0xFF;
                    final int blue = tint & 0xFF;
                    /*
                     * Light the proxy from the sublevel interior (the cabin is lit), not from the
                     * parent world underwater position, which made the water-material quads
                     * render as black triangles.
                     */
                    final int packedLight = carrier.scaleLightColor(
                            LevelRenderer.getLightColor(carrier.getLevel(),
                                    BlockPos.containing(sampleLocal.x,
                                            sampleLocal.y, sampleLocal.z)));
                    final Vector3d normalWorld = carrier.renderPose().transformNormal(
                            new Vector3d(nx, ny, nz));
                    final Vector3d[] world = proxyCorners(nx, ny, nz, axisA, axisB,
                            plane, a, b, carrier);
                    final boolean irisContext = IrisWaterMaterialBridge.begin(vertices, worldPos);
                    try {
                        triangleWithNormal(vertices, matrix,
                                world[0], sheetU(sprite, world[0].x), sheetV(sprite, world[0].z),
                                world[1], sheetU(sprite, world[1].x), sheetV(sprite, world[1].z),
                                world[2], sheetU(sprite, world[2].x), sheetV(sprite, world[2].z),
                                red, green, blue, packedLight, normalWorld);
                        triangleWithNormal(vertices, matrix,
                                world[0], sheetU(sprite, world[0].x), sheetV(sprite, world[0].z),
                                world[2], sheetU(sprite, world[2].x), sheetV(sprite, world[2].z),
                                world[3], sheetU(sprite, world[3].x), sheetV(sprite, world[3].z),
                                red, green, blue, packedLight, normalWorld);
                    } finally {
                        if (irisContext) {
                            IrisWaterMaterialBridge.end(vertices);
                        }
                    }
                    emitted++;
                }
            }
        }
        return emitted;
    }

    private static Vector3d proxySample(final int nx, final int ny, final int nz,
                                        final int axisA, final int axisB,
                                        final double plane, final int a, final int b) {
        final double aValue = a + 0.5D;
        final double bValue = b + 0.5D;
        final double x = nx != 0 ? plane + nx * 0.5D
                : axisA == 0 ? aValue : bValue;
        final double y = ny != 0 ? plane + ny * 0.5D
                : axisA == 1 ? aValue : bValue;
        final double z = nz != 0 ? plane + nz * 0.5D
                : axisA == 2 ? aValue : bValue;
        return new Vector3d(x, y, z);
    }

    private static Vector3d[] proxyCorners(final int nx, final int ny, final int nz,
                                           final int axisA, final int axisB,
                                           final double plane, final int a, final int b,
                                           final ClientSubLevel carrier) {
        final Vector3d[] local = new Vector3d[4];
        int index = 0;
        for (int da = 0; da <= 1; da++) {
            for (int db = 0; db <= 1; db++) {
                final double aValue = a + da;
                final double bValue = b + db;
                final double x = nx != 0 ? plane
                        : axisA == 0 ? aValue : bValue;
                final double y = ny != 0 ? plane
                        : axisA == 1 ? aValue : bValue;
                final double z = nz != 0 ? plane
                        : axisA == 2 ? aValue : bValue;
                local[index++] = new Vector3d(x, y, z);
            }
        }
        final Vector3d[] world = new Vector3d[4];
        for (int i = 0; i < 4; i++) {
            world[i] = carrier.renderPose().transformPosition(local[i]);
        }
        return world;
    }

    private static float sheetU(final TextureAtlasSprite sprite, final double worldX) {
        final double t = worldX - Math.floor(worldX);
        return (float) (sprite.getU0() + (sprite.getU1() - sprite.getU0()) * t);
    }

    private static float sheetV(final TextureAtlasSprite sprite, final double worldZ) {
        final double t = worldZ - Math.floor(worldZ);
        return (float) (sprite.getV0() + (sprite.getV1() - sprite.getV0()) * t);
    }

    private static boolean cameraInsideRoom(
            final ClientSubLevel carrier,
            final ClientCompartmentVisualCache.RoomSnapshot room,
            final double cameraX,
            final double cameraY,
            final double cameraZ) {
        final Vector3d local = carrier.renderPose().transformPositionInverse(
                new Vector3d(cameraX, cameraY, cameraZ));
        /*
         * The pass only controls whether this translucent surface is submitted before or after
         * parent water. A strict voxel lookup can alternate at an interpolated cell boundary and
         * used to send the same coplanar mesh through both Sodium hooks. The room bounds are the
         * stable conservative classification needed here; actual water geometry still comes only
         * from room.cells().
         */
        return room.bounds().contains(local.x, local.y, local.z);
    }

    /** Use the same room-cell light and external skylight scaling as Sable's cabin blocks. */
    private static int roomWaterLight(final ClientSubLevel carrier, final AABB cell) {
        final BlockPos localCell = BlockPos.containing(cell.minX, cell.minY, cell.minZ);
        return carrier.scaleLightColor(LevelRenderer.getLightColor(carrier.getLevel(), localCell));
    }

    private static void renderRoom(final VertexConsumer vertices,
                                   final Matrix4f matrix,
                                   final TextureAtlasSprite sprite,
                                   final ClientSubLevel carrier,
                                   final ClientCompartmentVisualCache.RoomSnapshot room,
                                   final boolean roomLighting) {
        final double fraction = room.waterFraction();
        if (fraction <= 0.0D || room.cells().isEmpty()) return;

        // Keep snapshot order for translucent blending. One set of transformed corners serves
        // the free surface and every boundary; adjacent cells never need to be transformed again.
        final Long2ObjectLinkedOpenHashMap<CellGeometry> cells =
                new Long2ObjectLinkedOpenHashMap<>(room.cells().size());
        double highestY = Double.NEGATIVE_INFINITY;
        for (final BlockPos cell : room.cells()) {
            final AABB bounds = new AABB(cell);
            final Vector3d[] corners = localBoxCorners(bounds);
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < corners.length; index++) {
                corners[index] = carrier.renderPose().transformPosition(corners[index]);
                minimumY = Math.min(minimumY, corners[index].y);
                maximumY = Math.max(maximumY, corners[index].y);
            }
            cells.put(cell.asLong(), new CellGeometry(bounds, corners, minimumY, maximumY));
            highestY = Math.max(highestY, maximumY);
        }

        final boolean fullyFlooded = fraction >= 1.0D - 1.0E-5D;
        final double worldSurfaceY = fullyFlooded ? highestY
                : CompartmentWaterGeometry.surfaceY(
                        carrier.renderPose(), room.cells(), room.bounds(), fraction);
        if (!fullyFlooded) {
            for (final CellGeometry cell : cells.values()) {
                // Half-open ownership avoids duplicate caps on an exactly horizontal cell seam.
                if (worldSurfaceY <= cell.minimumY() + 1.0E-6D
                        || worldSurfaceY > cell.maximumY() + 1.0E-6D) continue;
                final List<Vector3d> polygon = surfacePolygon(cell.corners(), worldSurfaceY);
                emitWaterPolygon(vertices, matrix, sprite, carrier, cell.bounds(), polygon,
                        new Vector3d(0.0D, 1.0D, 0.0D), surfaceUvAxis(cell.corners()), roomLighting);
            }
        }
        renderRoomBoundaryFaces(vertices, matrix, sprite, carrier, cells,
                worldSurfaceY, fullyFlooded, roomLighting);
    }

    private record CellGeometry(AABB bounds, Vector3d[] corners,
                                double minimumY, double maximumY) {
    }

    private static Vector3d[] localBoxCorners(final AABB bounds) {
        return new Vector3d[]{
                new Vector3d(bounds.minX, bounds.minY, bounds.minZ),
                new Vector3d(bounds.maxX, bounds.minY, bounds.minZ),
                new Vector3d(bounds.minX, bounds.maxY, bounds.minZ),
                new Vector3d(bounds.maxX, bounds.maxY, bounds.minZ),
                new Vector3d(bounds.minX, bounds.minY, bounds.maxZ),
                new Vector3d(bounds.maxX, bounds.minY, bounds.maxZ),
                new Vector3d(bounds.minX, bounds.maxY, bounds.maxZ),
                new Vector3d(bounds.maxX, bounds.maxY, bounds.maxZ)
        };
    }

    /** A horizontal slice of a transformed cell, wound toward world +Y. */
    static List<Vector3d> surfacePolygon(final Vector3d[] corners, final double worldSurfaceY) {
        final List<Vector3d> polygon = new ArrayList<>(6);
        for (final int[] edge : BOX_EDGES) {
            final Vector3d first = corners[edge[0]];
            final Vector3d second = corners[edge[1]];
            final double firstDelta = first.y - worldSurfaceY;
            final double secondDelta = second.y - worldSurfaceY;
            if (Math.abs(firstDelta) < 1.0E-7D) addUnique(polygon, first);
            if (firstDelta * secondDelta < 0.0D) {
                final double t = firstDelta / (firstDelta - secondDelta);
                final Vector3d intersection = new Vector3d(first).lerp(second, t);
                intersection.y = worldSurfaceY;
                addUnique(polygon, intersection);
            }
            if (Math.abs(secondDelta) < 1.0E-7D) addUnique(polygon, second);
        }
        if (polygon.size() < 3) return polygon;
        final Vector3d center = polygonCenter(polygon);
        polygon.sort(Comparator.comparingDouble((Vector3d point) ->
                Math.atan2(point.z - center.z, point.x - center.x)).reversed());
        return polygon;
    }

    /**
     * Project the free-surface UVs along the most perpendicular local axis. Local XZ alone
     * collapses to a line when the carrier is on its side, producing a zero Iris tangent.
     */
    static Direction.Axis surfaceUvAxis(final Vector3d[] corners) {
        final double x = Math.abs(corners[1].y - corners[0].y);
        final double y = Math.abs(corners[2].y - corners[0].y);
        final double z = Math.abs(corners[4].y - corners[0].y);
        if (y >= x && y >= z) return Direction.Axis.Y;
        return x >= z ? Direction.Axis.X : Direction.Axis.Z;
    }

    /** All six local shell directions matter once a carrier tilts or turns upside down. */
    private static void renderRoomBoundaryFaces(
            final VertexConsumer vertices, final Matrix4f matrix, final TextureAtlasSprite sprite,
            final ClientSubLevel carrier, final Long2ObjectLinkedOpenHashMap<CellGeometry> cells,
            final double worldSurfaceY, final boolean fullyFlooded, final boolean roomLighting) {
        final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (final var entry : cells.long2ObjectEntrySet()) {
            final CellGeometry cell = entry.getValue();
            if (cell.minimumY() >= worldSurfaceY - 1.0E-6D) continue;
            final BlockPos position = BlockPos.of(entry.getLongKey());
            for (final Direction direction : WATER_BOUNDARY_DIRECTIONS) {
                neighbour.set(position).move(direction);
                // The same world-horizontal half-space applies to both cells. Even when a
                // neighbour is dry, a shared room face cannot contain positive-area exterior water.
                if (cells.containsKey(neighbour.asLong())) continue;
                if (carrier.getLevel().getBlockState(neighbour).canOcclude()) continue;

                final List<Vector3d> face = orderedFaceCorners(cell.corners(), direction);
                if (!fullyFlooded && isSurfaceCoplanar(face, worldSurfaceY)) continue;
                if (direction.getAxis() != Direction.Axis.Y) {
                    // Preserve the existing small side inset against translucent glass.
                    final int axisCorner = direction.getAxis() == Direction.Axis.X ? 1 : 4;
                    final Vector3d inset = new Vector3d(cell.corners()[axisCorner])
                            .sub(cell.corners()[0])
                            .mul(-0.02D * direction.getAxisDirection().getStep());
                    face.forEach(point -> point.add(inset));
                }
                final List<Vector3d> polygon = clipBelowPlane(face, worldSurfaceY);
                final Vector3d normal = carrier.renderPose().transformNormal(new Vector3d(
                        direction.getStepX(), direction.getStepY(), direction.getStepZ()));
                emitWaterPolygon(vertices, matrix, sprite, carrier, cell.bounds(), polygon,
                        normal, direction.getAxis(), roomLighting);
            }
        }
    }

    /** Only a face lying wholly on the free surface can duplicate its cap. */
    static boolean isSurfaceCoplanar(final List<Vector3d> face, final double worldSurfaceY) {
        if (face.size() < 3) return false;
        for (final Vector3d point : face) {
            if (Math.abs(point.y - worldSurfaceY) > 1.0E-6D) return false;
        }
        return true;
    }

    private static Vector3d polygonCenter(final List<Vector3d> polygon) {
        final Vector3d center = new Vector3d();
        polygon.forEach(center::add);
        return center.div(polygon.size());
    }

    /**
     * One common material path for every boundary and the horizontal free surface. Transform
     * each unique vertex to local UV space once, not twice for every triangle that shares it.
     */
    private static void emitWaterPolygon(
            final VertexConsumer vertices, final Matrix4f matrix, final TextureAtlasSprite sprite,
            final ClientSubLevel carrier, final AABB cell, final List<Vector3d> polygon,
            final Vector3d normal, final Direction.Axis uvAxis, final boolean roomLighting) {
        if (polygon.size() < 3) return;
        final Vector3d center = polygonCenter(polygon);
        final Vector3d localCenter = carrier.renderPose().transformPositionInverse(new Vector3d(center));
        final BlockPos localLightPosition = BlockPos.containing(localCenter.x, localCenter.y, localCenter.z);
        final BlockPos worldPosition = BlockPos.containing(center.x, center.y, center.z);
        final ClientLevel parentLevel = Minecraft.getInstance().level;
        final int tint = parentLevel == null
                ? BiomeColors.getAverageWaterColor(carrier.getLevel(), localLightPosition)
                : BiomeColors.getAverageWaterColor(parentLevel, worldPosition);
        final int red = DIAGNOSTIC_MAGENTA ? 255 : tint >> 16 & 0xFF;
        final int green = DIAGNOSTIC_MAGENTA ? 0 : tint >> 8 & 0xFF;
        final int blue = DIAGNOSTIC_MAGENTA ? 255 : tint & 0xFF;
        final int packedLight = roomLighting
                ? roomWaterLight(carrier, cell) : parentLevel == null
                ? carrier.scaleLightColor(LevelRenderer.getLightColor(carrier.getLevel(), localLightPosition))
                : LevelRenderer.getLightColor(parentLevel, worldPosition);

        final float[] uv = new float[(polygon.size() + 1) * 2];
        putWaterUv(sprite, cell, uvAxis, localCenter, uv, 0);
        for (int index = 0; index < polygon.size(); index++) {
            final Vector3d local = carrier.renderPose().transformPositionInverse(
                    new Vector3d(polygon.get(index)));
            putWaterUv(sprite, cell, uvAxis, local, uv, index + 1);
        }
        final boolean irisContext = !DIAGNOSTIC_MAGENTA
                && IrisWaterMaterialBridge.begin(vertices, worldPosition);
        try {
            for (int index = 0; index < polygon.size(); index++) {
                final int next = (index + 1) % polygon.size();
                triangleWithNormal(vertices, matrix,
                        center, uv[0], uv[1],
                        polygon.get(index), uv[(index + 1) * 2], uv[(index + 1) * 2 + 1],
                        polygon.get(next), uv[(next + 1) * 2], uv[(next + 1) * 2 + 1],
                        red, green, blue, packedLight, normal, 255);
            }
        } finally {
            if (irisContext) IrisWaterMaterialBridge.end(vertices);
        }
    }

    private static void putWaterUv(final TextureAtlasSprite sprite, final AABB cell,
                                   final Direction.Axis axis, final Vector3d local,
                                   final float[] uv, final int vertex) {
        final double u = axis == Direction.Axis.X ? local.z - cell.minZ : local.x - cell.minX;
        final double v = axis == Direction.Axis.Y ? local.z - cell.minZ : local.y - cell.minY;
        uv[vertex * 2] = (float) (sprite.getU0()
                + (sprite.getU1() - sprite.getU0()) * Math.clamp(u, 0.0D, 1.0D));
        uv[vertex * 2 + 1] = (float) (sprite.getV0()
                + (sprite.getV1() - sprite.getV0()) * Math.clamp(v, 0.0D, 1.0D));
    }

    /** Local corners of a box face, wound toward its declared outward normal. */
    static List<Vector3d> orderedLocalFaceCorners(final AABB cell,
                                                 final Direction direction,
                                                 final double inset) {
        final List<Vector3d> face = orderedFaceCorners(localBoxCorners(cell), direction);
        if (inset != 0.0D) {
            final Vector3d shift = new Vector3d(
                    direction.getStepX(), direction.getStepY(), direction.getStepZ()).mul(-inset);
            face.forEach(point -> point.add(shift));
        }
        return face;
    }

    private static List<Vector3d> orderedFaceCorners(final Vector3d[] corners,
                                                    final Direction direction) {
        // Index order matches localBoxCorners/BOX_EDGES; copying keeps the shared cell immutable.
        final int[] indices = FACE_CORNER_INDICES[direction.get3DDataValue()];
        final List<Vector3d> face = new ArrayList<>(4);
        for (final int index : indices) face.add(new Vector3d(corners[index]));
        return face;
    }

    /** Sutherland-Hodgman clip of an ordered face polygon below a world-Y plane. */
    static List<Vector3d> clipBelowPlane(final List<Vector3d> corners, final double planeY) {
        final List<Vector3d> output = new ArrayList<>(corners.size() + 2);
        for (int index = 0; index < corners.size(); index++) {
            final Vector3d current = corners.get(index);
            final Vector3d next = corners.get((index + 1) % corners.size());
            final boolean currentBelow = current.y <= planeY;
            final boolean nextBelow = next.y <= planeY;
            if (currentBelow) addUnique(output, current);
            if (currentBelow != nextBelow) {
                final double t = (planeY - current.y) / (next.y - current.y);
                final Vector3d intersection = new Vector3d(current).lerp(next, t);
                intersection.y = planeY;
                addUnique(output, intersection);
            }
        }
        return output;
    }

    /**
     * Emits a thin water-material film just outside the hull faces of a dry compartment.
     *
     * <p>In the parent world the hull volume is one continuous body of water: Sodium does not
     * emit faces between two water blocks, so through the cabin glass there is no water geometry
     * at all and the shader pack has nothing to apply its volume fog to. This film restores one
     * water-material plane per exterior face (marked with the vanilla water material through the
     * Iris bridge), giving the "layer of water" look through the glass while the cabin interior
     * stays dry and clear. Faces whose outward neighbour is not parent water (or that are buried
     * inside solid walls) are skipped or depth-occluded automatically.</p>
     */
    private static void drawExteriorFilm(final VertexConsumer vertices,
                                         final Matrix4f matrix,
                                         final TextureAtlasSprite sprite,
                                         final ClientSubLevel carrier,
                                         final ClientCompartmentVisualCache.RoomSnapshot room) {
        if (room.waterFraction() > 1.0E-5D) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final AABB bounds = room.bounds();
        final double offset = 0.05D;
        for (final Direction direction : Direction.values()) {
            final int nx = direction.getStepX();
            final int ny = direction.getStepY();
            final int nz = direction.getStepZ();
            final double plane;
            if (nx != 0) {
                plane = nx > 0 ? bounds.maxX + offset : bounds.minX - offset;
            } else if (ny != 0) {
                plane = ny > 0 ? bounds.maxY + offset : bounds.minY - offset;
            } else {
                plane = nz > 0 ? bounds.maxZ + offset : bounds.minZ - offset;
            }

            final double midX = (bounds.minX + bounds.maxX) * 0.5D;
            final double midY = (bounds.minY + bounds.maxY) * 0.5D;
            final double midZ = (bounds.minZ + bounds.maxZ) * 0.5D;
            final Vector3d sampleLocal = new Vector3d(
                    nx != 0 ? plane + nx : midX,
                    ny != 0 ? plane + ny : midY,
                    nz != 0 ? plane + nz : midZ);
            final Vector3d sampleWorld = carrier.renderPose().transformPosition(sampleLocal);
            if (!minecraft.level.getFluidState(BlockPos.containing(
                    sampleWorld.x, sampleWorld.y, sampleWorld.z)).is(FluidTags.WATER)) {
                continue;
            }

            final Vector3d[] corners = new Vector3d[4];
            int index = 0;
            for (int a = 0; a <= 1; a++) {
                for (int b = 0; b <= 1; b++) {
                    final double x;
                    final double y;
                    final double z;
                    if (nx != 0) {
                        x = plane;
                        y = a == 0 ? bounds.minY : bounds.maxY;
                        z = b == 0 ? bounds.minZ : bounds.maxZ;
                    } else if (ny != 0) {
                        x = a == 0 ? bounds.minX : bounds.maxX;
                        y = plane;
                        z = b == 0 ? bounds.minZ : bounds.maxZ;
                    } else {
                        x = a == 0 ? bounds.minX : bounds.maxX;
                        y = b == 0 ? bounds.minY : bounds.maxY;
                        z = plane;
                    }
                    corners[index++] = new Vector3d(x, y, z);
                }
            }

            final Vector3d centerWorld = carrier.renderPose().transformPosition(
                    new Vector3d(midX, midY, midZ));
            final Vector3d normalWorld = carrier.renderPose().transformNormal(
                    new Vector3d(nx, ny, nz));
            final BlockPos worldPos = BlockPos.containing(
                    centerWorld.x, centerWorld.y, centerWorld.z);
            final int tint = BiomeColors.getAverageWaterColor(minecraft.level,
                    BlockPos.containing(sampleWorld.x, sampleWorld.y, sampleWorld.z));
            final int red = tint >> 16 & 0xFF;
            final int green = tint >> 8 & 0xFF;
            final int blue = tint & 0xFF;
            final int packedLight = carrier.scaleLightColor(
                    LevelRenderer.getLightColor(carrier.getLevel(),
                            BlockPos.containing(midX, midY, midZ)));

            final Vector3d[] world = new Vector3d[4];
            for (int i = 0; i < 4; i++) {
                world[i] = carrier.renderPose().transformPosition(corners[i]);
            }

            /*
             * Draw as an ordinary translucent sheet with an explicit alpha. Marking the film as
             * pack water is unreliable here: the pack's water absorption is computed from the
             * opaque depth behind the fragment, which is effectively zero in this scene, so a
             * water-marked quad ends up with alpha ~0.01 and is invisible.
             */
            triangleWithNormal(vertices, matrix,
                    world[0], textureU(sprite, carrier, bounds, world[0]),
                    textureV(sprite, carrier, bounds, world[0]),
                    world[1], textureU(sprite, carrier, bounds, world[1]),
                    textureV(sprite, carrier, bounds, world[1]),
                    world[2], textureU(sprite, carrier, bounds, world[2]),
                    textureV(sprite, carrier, bounds, world[2]),
                    red, green, blue, packedLight, normalWorld, 140);
            triangleWithNormal(vertices, matrix,
                    world[0], textureU(sprite, carrier, bounds, world[0]),
                    textureV(sprite, carrier, bounds, world[0]),
                    world[2], textureU(sprite, carrier, bounds, world[2]),
                    textureV(sprite, carrier, bounds, world[2]),
                    world[3], textureU(sprite, carrier, bounds, world[3]),
                    textureV(sprite, carrier, bounds, world[3]),
                    red, green, blue, packedLight, normalWorld, 140);
            exteriorFilmFaceCount++;
            if (!exteriorFilmLogged) {
                exteriorFilmLogged = true;
                CreateNautilus.LOGGER.info(
                        "[film] exterior water film active: first face at world=({}, {}, {})",
                        sampleWorld.x, sampleWorld.y, sampleWorld.z);
            }
        }
        if (exteriorFilmFaceCount > 0
                && (exteriorFilmFaceCount & 0x3F) == 0L) {
            CreateNautilus.LOGGER.info(
                    "[film] exterior water film faces emitted so far: {}",
                    exteriorFilmFaceCount);
        }
    }

    private static void triangleWithNormal(final VertexConsumer vertices,
                                           final Matrix4f matrix,
                                           final Vector3d first, final float firstU,
                                           final float firstV,
                                           final Vector3d second, final float secondU,
                                           final float secondV,
                                           final Vector3d third, final float thirdU,
                                           final float thirdV,
                                           final int red, final int green, final int blue,
                                           final int packedLight,
                                           final Vector3d normal) {
        triangleWithNormal(vertices, matrix, first, firstU, firstV,
                second, secondU, secondV, third, thirdU, thirdV,
                red, green, blue, packedLight, normal, 255);
    }

    private static void triangleWithNormal(final VertexConsumer vertices,
                                           final Matrix4f matrix,
                                           final Vector3d first, final float firstU,
                                           final float firstV,
                                           final Vector3d second, final float secondU,
                                           final float secondV,
                                           final Vector3d third, final float thirdU,
                                           final float thirdV,
                                           final int red, final int green, final int blue,
                                           final int packedLight,
                                           final Vector3d normal,
                                           final int alpha) {
        vertexWithNormal(vertices, matrix, first, firstU, firstV,
                red, green, blue, packedLight, normal, alpha);
        vertexWithNormal(vertices, matrix, second, secondU, secondV,
                red, green, blue, packedLight, normal, alpha);
        vertexWithNormal(vertices, matrix, third, thirdU, thirdV,
                red, green, blue, packedLight, normal, alpha);
    }

    private static void vertexWithNormal(final VertexConsumer vertices,
                                         final Matrix4f matrix,
                                         final Vector3d point,
                                         final float u, final float v,
                                         final int red, final int green, final int blue,
                                         final int packedLight,
                                         final Vector3d normal) {
        vertexWithNormal(vertices, matrix, point, u, v,
                red, green, blue, packedLight, normal, 255);
    }

    private static void vertexWithNormal(final VertexConsumer vertices,
                                         final Matrix4f matrix,
                                         final Vector3d point,
                                         final float u, final float v,
                                         final int red, final int green, final int blue,
                                         final int packedLight,
                                         final Vector3d normal,
                                         final int alpha) {
        vertices.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setLight(packedLight)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static void addUnique(final List<Vector3d> points, final Vector3d candidate) {
        if (points.stream().noneMatch(existing -> existing.distanceSquared(candidate) < 1.0E-10D)) {
            points.add(new Vector3d(candidate));
        }
    }

    private static float textureU(final TextureAtlasSprite sprite,
                                  final ClientSubLevel carrier,
                                  final AABB cellBounds,
                                  final Vector3d worldPoint) {
        // Use the complete raw sprite edge. Pulling every cell toward the sprite centre exposes a
        // block-wide grid when the free surface is seen from below.
        final Vector3d local = carrier.renderPose().transformPositionInverse(
                new Vector3d(worldPoint));
        final double t = Math.clamp(local.x - cellBounds.minX, 0.0D, 1.0D);
        return (float) (sprite.getU0() + (sprite.getU1() - sprite.getU0()) * t);
    }

    private static float textureV(final TextureAtlasSprite sprite,
                                  final ClientSubLevel carrier,
                                  final AABB cellBounds,
                                  final Vector3d worldPoint) {
        final Vector3d local = carrier.renderPose().transformPositionInverse(
                new Vector3d(worldPoint));
        final double t = Math.clamp(local.z - cellBounds.minZ, 0.0D, 1.0D);
        return (float) (sprite.getV0() + (sprite.getV1() - sprite.getV0()) * t);
    }

    private static Vector3d world(final ClientSubLevel carrier,
                                  final double x, final double y, final double z) {
        return carrier.renderPose().transformPosition(new Vector3d(x, y, z));
    }

    private static void vertex(final VertexConsumer vertices,
                               final Matrix4f matrix,
                               final Vector3d point,
                               final float u,
                               final float v,
                               final int red,
                               final int green,
                               final int blue,
                               final int packedLight,
                               final float normalY) {
        vertices.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .setColor(red, green, blue, 255)
                .setUv(u, v)
                .setLight(packedLight)
                .setNormal(0.0F, normalY, 0.0F);
    }

    private static void triangle(final VertexConsumer vertices,
                                 final Matrix4f matrix,
                                 final Vector3d first,
                                 final float firstU,
                                 final float firstV,
                                 final Vector3d second,
                                 final float secondU,
                                 final float secondV,
                                 final Vector3d third,
                                 final float thirdU,
                                 final float thirdV,
                                 final int red,
                                 final int green,
                                 final int blue,
                                 final int packedLight,
                                 final float normalY) {
        vertex(vertices, matrix, first, firstU, firstV,
                red, green, blue, packedLight, normalY);
        vertex(vertices, matrix, second, secondU, secondV,
                red, green, blue, packedLight, normalY);
        vertex(vertices, matrix, third, thirdU, thirdV,
                red, green, blue, packedLight, normalY);
    }

    private static boolean classPresent(final String name) {
        try {
            Class.forName(name, false, FloodedChamberRenderer.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
