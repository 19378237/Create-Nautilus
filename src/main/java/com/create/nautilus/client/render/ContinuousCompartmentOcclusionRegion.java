package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.create.nautilus.physics.CompartmentWaterGeometry;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.SableClient;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.render.region.SimpleCulledRenderRegion;
import dev.ryanhcode.sable.render.region.SimpleCulledRenderRegionBuilder;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces Sable's integer-voxel render mesh with the exact dry part of each compartment.
 *
 * <p>Sable's public water-occlusion API intentionally accepts a
 * {@code BoundedBitVolume3i}. That is sufficient for ordinary block-shaped rooms, but Nautilus
 * stores a continuous water amount and may place its free surface halfway through a rotated
 * voxel. Sending that crossed voxel to Sable removed the parent ocean for the complete block and
 * produced the one-block transparent band visible around the cabin waterline. Omitting it merely
 * moved the error to the wet side.</p>
 *
 * <p>This region keeps Sable's normal container and close/far framebuffer lifecycle, but emits a
 * closed mesh whose lower boundary is clipped against the exact world-horizontal compartment
 * surface. It affects rendering only: the server-side physical occlusion volume and all real
 * {@code FluidState}s remain unchanged.</p>
 */
public final class ContinuousCompartmentOcclusionRegion extends SimpleCulledRenderRegion {
    private static final double EPSILON = 1.0E-6D;
    private static final int[][] CUBE_EDGES = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
    };
    private static final Face[] FACES = {
            new Face(Direction.NORTH, new int[]{0, 2, 3, 1}),
            new Face(Direction.EAST, new int[]{1, 3, 7, 5}),
            new Face(Direction.SOUTH, new int[]{5, 7, 6, 4}),
            new Face(Direction.WEST, new int[]{4, 6, 2, 0}),
            new Face(Direction.DOWN, new int[]{0, 1, 5, 4}),
            /*
             * Upper corners are 2, 3, 6 and 7. The previous 4, 5, 7, 6 sequence
             * described the SOUTH face a second time, leaving every dry volume
             * without a top face. Sable's close/far depth test then saw only
             * alternating side-wall intersections when viewed from above.
             */
            new Face(Direction.UP, new int[]{2, 6, 7, 3})
    };
    private static final Field CLIENT_RENDER_REGION = findClientRenderRegionField();
    private static final Field RENDERER_REGIONS = findRendererRegionsField();
    private static boolean installFailureLogged;
    private static boolean installSuccessLogged;
    private static boolean createNautilus$renderLogTick;
    private static final Set<ContinuousCompartmentOcclusionRegion> OWN_REGIONS =
            ConcurrentHashMap.newKeySet();

    private final UUID carrierId;

    private ContinuousCompartmentOcclusionRegion(final UUID carrierId) {
        // The superclass requires construction data, but render/build/free are all deliberately
        // overridden below. A harmless non-empty placeholder avoids depending on its internals.
        super(List.of(BlockPos.ZERO));
        this.carrierId = carrierId;
    }

    /**
     * Swaps the stock cube renderer stored inside Sable's client region for this continuous one.
     *
     * <p>Sable 2.0.3 exposes no custom-mesh registration point. The reflection is therefore kept
     * in one fail-open adapter: if an implementation changes, the original Sable region is left
     * installed and gameplay continues with coarse visual clipping instead of crashing.</p>
     */
    public static void install(final UUID carrierId, final WaterOcclusionRegion logicalRegion) {
        if (CLIENT_RENDER_REGION == null || RENDERER_REGIONS == null) {
            logInstallFailure(null);
            return;
        }
        SimpleCulledRenderRegion original = null;
        ContinuousCompartmentOcclusionRegion replacement = null;
        @SuppressWarnings("unchecked")
        Set<SimpleCulledRenderRegion> renderRegions = null;
        try {
            original = (SimpleCulledRenderRegion) CLIENT_RENDER_REGION.get(logicalRegion);
            if (original == null) {
                return;
            }
            renderRegions = (Set<SimpleCulledRenderRegion>) RENDERER_REGIONS.get(
                    SableClient.WATER_OCCLUSION_RENDERER);
            replacement = new ContinuousCompartmentOcclusionRegion(carrierId);

            SableClient.WATER_OCCLUSION_RENDERER.removeRegion(original);
            renderRegions.add(replacement);
            OWN_REGIONS.add(replacement);
            CLIENT_RENDER_REGION.set(logicalRegion, replacement);
            if (!installSuccessLogged) {
                installSuccessLogged = true;
                CreateNautilus.LOGGER.info(
                        "Continuous compartment waterline occlusion mesh is active");
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError exception) {
            // Restore the stock renderer if the swap failed after removing it.
            if (renderRegions != null && original != null) {
                renderRegions.remove(replacement);
                renderRegions.add(original);
                try {
                    CLIENT_RENDER_REGION.set(logicalRegion, original);
                } catch (final IllegalAccessException ignored) {
                    // The client will fail open on the next synchronized snapshot.
                }
            }
            logInstallFailure(exception);
        }
    }

    /**
     * Replays every Sable region into the framebuffer currently bound by the caller.
     *
     * <p>This is needed only when Sable's integer camera test says the eye is inside the complete
     * crossed voxel while the continuous water plane says it is actually in the wet half. The
     * close buffer must then be cleared back to one and rebuilt; merely changing the clear value
     * after Sable rendered would erase all legitimate entry faces.</p>
     */
    public static void renderAllRegistered(final Matrix4f modelView,
                                           final Matrix4f projectionMatrix)
            throws ReflectiveOperationException {
        // Copy before rendering so a packet-driven replacement cannot mutate the backing set
        // while this render-thread pass is iterating it.
        for (final ContinuousCompartmentOcclusionRegion region :
                List.copyOf(OWN_REGIONS)) {
            region.render(modelView, projectionMatrix);
        }
    }

    @Override
    public void render(final Matrix4f modelView, final Matrix4f projectionMatrix) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null || !(container.getSubLevel(carrierId)
                instanceof final ClientSubLevel carrier)) {
            return;
        }
        final List<ClientCompartmentVisualCache.RoomSnapshot> rooms =
                ClientCompartmentVisualCache.snapshots().get(carrierId);
        if (rooms == null || rooms.isEmpty()) {
            return;
        }

        final Vector3d camera = new Vector3d(
                minecraft.gameRenderer.getMainCamera().getPosition().x,
                minecraft.gameRenderer.getMainCamera().getPosition().y,
                minecraft.gameRenderer.getMainCamera().getPosition().z);
        /*
         * Build raw camera-relative positions and draw with the raw-GL path. Iris intercepts
         * ShaderInstance/BufferUploader and redirects the draw into its own gbuffer, which left
         * the close/far masks cleared; glDrawArrays bypasses every Minecraft/Iris/Veil hook.
         */
        final List<Float> positions = new ArrayList<>();
        for (final ClientCompartmentVisualCache.RoomSnapshot room : rooms) {
            emitRoom(positions, carrier, room, camera);
        }
        if (positions.isEmpty()) {
            return;
        }
        if (!createNautilus$renderLogTick) {
            createNautilus$renderLogTick = true;
            CreateNautilus.LOGGER.info(
                    "[occlusion] region render vertices={}",
                    positions.size() / 3);
        }
        // Vertices are already camera-relative. Sodium's model view has no absolute camera
        // translation, so retain its view-bob and hurt translation without adding the camera.
        final Matrix4f view = new Matrix4f(modelView);
        RawDepthRenderer.draw(positions, view, projectionMatrix);
    }

    private static void emitRoom(
            final List<Float> positions,
            final ClientSubLevel carrier,
            final ClientCompartmentVisualCache.RoomSnapshot room,
            final Vector3d camera) {
        /*
         * The mask is the complete sealed volume clipped at the world-horizontal virtual
         * surface: flooded cells below the plane keep their real parent-water volume, while the
         * plane itself forms a smooth lower cap of the mask. The close/far depth test then trims
         * boundary water at the exact hull faces and waterline, instead of a block-stepped
         * staircase of individual dry cells.
         */
        final double waterFraction = room.waterFraction();
        if (waterFraction >= 1.0D - 1.0E-5D) {
            // Fully flooded room: no dry air remains and the parent water renders throughout.
            return;
        }
        final double surfaceY = CompartmentWaterGeometry.surfaceY(
                carrier.renderPose(), room.cells(), room.bounds(), waterFraction);
        final Map<Long, Vector3d[]> cells = new HashMap<>();
        for (final BlockPos cell : room.cells()) {
            // Real water placed in the moving plot remains ordinary Minecraft/Create fluid and
            // owns its own renderer. It must never become part of the virtual dry mask.
            if (carrier.getLevel().getFluidState(cell).is(FluidTags.WATER)) {
                continue;
            }
            cells.put(cell.asLong(), worldCorners(carrier, cell));
        }

        for (final Map.Entry<Long, Vector3d[]> entry : cells.entrySet()) {
            final BlockPos cell = BlockPos.of(entry.getKey());
            final Vector3d[] corners = entry.getValue();
            boolean above = false;
            boolean below = false;
            for (final Vector3d corner : corners) {
                if (corner.y >= surfaceY - EPSILON) {
                    above = true;
                } else {
                    below = true;
                }
            }
            for (final Face face : FACES) {
                if (cells.containsKey(cell.relative(face.direction()).asLong())) {
                    continue;
                }
                final List<Vector3d> polygon = new ArrayList<>(4);
                for (final int index : face.corners()) {
                    polygon.add(corners[index]);
                }
                final List<Vector3d> clipped = clipAbove(polygon, surfaceY, waterFraction);
                if (clipped.size() >= 3) {
                    emitPolygon(positions, clipped, camera);
                }
            }
            if (above && below) {
                emitWaterlineCap(positions, corners, surfaceY, camera);
            }
        }
    }

    /** Clips one outward-wound cube face to the dry half-space {@code worldY >= surfaceY}. */
    private static List<Vector3d> clipAbove(final List<Vector3d> input,
                                            final double surfaceY,
                                            final double fraction) {
        if (fraction <= EPSILON || input.isEmpty()) {
            return input;
        }
        final List<Vector3d> output = new ArrayList<>(input.size() + 2);
        Vector3d previous = input.getLast();
        boolean previousInside = previous.y >= surfaceY - EPSILON;
        for (final Vector3d current : input) {
            final boolean currentInside = current.y >= surfaceY - EPSILON;
            if (currentInside != previousInside) {
                final double denominator = current.y - previous.y;
                if (Math.abs(denominator) > EPSILON) {
                    final double t = (surfaceY - previous.y) / denominator;
                    addUnique(output, new Vector3d(previous).lerp(current, t));
                }
            }
            if (currentInside) {
                addUnique(output, current);
            }
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    /** Emits the new horizontal lower face created where the water plane cuts this cube. */
    private static void emitWaterlineCap(final List<Float> positions,
                                         final Vector3d[] corners,
                                         final double surfaceY,
                                         final Vector3d camera) {
        final List<Vector3d> cap = new ArrayList<>(6);
        for (final int[] edge : CUBE_EDGES) {
            final Vector3d first = corners[edge[0]];
            final Vector3d second = corners[edge[1]];
            final double firstDelta = first.y - surfaceY;
            final double secondDelta = second.y - surfaceY;
            if (Math.abs(firstDelta) <= EPSILON) {
                addUnique(cap, first);
            }
            if (firstDelta * secondDelta < 0.0D) {
                final double t = firstDelta / (firstDelta - secondDelta);
                addUnique(cap, new Vector3d(first).lerp(second, t));
            }
            if (Math.abs(secondDelta) <= EPSILON) {
                addUnique(cap, second);
            }
        }
        if (cap.size() < 3) {
            return;
        }
        final Vector3d center = new Vector3d();
        cap.forEach(center::add);
        center.div(cap.size());
        cap.sort(Comparator.comparingDouble(point ->
                Math.atan2(point.z - center.z, point.x - center.x)));

        // The clipped dry volume is above the plane, so this cap's outward normal points down.
        final Vector3d normal = new Vector3d(cap.get(1)).sub(cap.get(0))
                .cross(new Vector3d(cap.get(2)).sub(cap.get(0)));
        if (normal.y > 0.0D) {
            java.util.Collections.reverse(cap);
        }
        emitPolygon(positions, cap, camera);
    }

    private static void emitPolygon(final List<Float> positions,
                                    final List<Vector3d> polygon,
                                    final Vector3d camera) {
        if (polygon.size() < 3) {
            return;
        }
        final Vector3d first = polygon.getFirst();
        for (int index = 1; index + 1 < polygon.size(); index++) {
            vertex(positions, first, camera);
            vertex(positions, polygon.get(index), camera);
            vertex(positions, polygon.get(index + 1), camera);
        }
    }

    private static void vertex(final List<Float> positions,
                               final Vector3d point,
                               final Vector3d camera) {
        positions.add((float) (point.x - camera.x));
        positions.add((float) (point.y - camera.y));
        positions.add((float) (point.z - camera.z));
    }

    private static Vector3d[] worldCorners(final ClientSubLevel carrier,
                                           final BlockPos cell) {
        final double x0 = cell.getX();
        final double y0 = cell.getY();
        final double z0 = cell.getZ();
        final double x1 = x0 + 1.0D;
        final double y1 = y0 + 1.0D;
        final double z1 = z0 + 1.0D;
        return new Vector3d[]{
                world(carrier, x0, y0, z0), world(carrier, x1, y0, z0),
                world(carrier, x0, y1, z0), world(carrier, x1, y1, z0),
                world(carrier, x0, y0, z1), world(carrier, x1, y0, z1),
                world(carrier, x0, y1, z1), world(carrier, x1, y1, z1)
        };
    }

    private static Vector3d world(final ClientSubLevel carrier,
                                  final double x, final double y, final double z) {
        return carrier.renderPose().transformPosition(new Vector3d(x, y, z));
    }

    private static void addUnique(final List<Vector3d> points, final Vector3d candidate) {
        if (points.stream().noneMatch(point ->
                point.distanceSquared(candidate) < 1.0E-12D)) {
            points.add(new Vector3d(candidate));
        }
    }

    @Override
    public void build() {
        // Geometry follows both the interpolated render pose and continuous water amount, so a
        // static superclass VBO would be stale immediately. render() builds the current mesh.
    }

    @Override
    public void free() {
        OWN_REGIONS.remove(this);
    }

    @Override
    public SimpleCulledRenderRegionBuilder createMeshBuilder(final int gridSize) {
        return new SimpleCulledRenderRegionBuilder(gridSize);
    }

    @Override
    public VertexFormat getVertexFormat() {
        return DefaultVertexFormat.POSITION;
    }

    private static Field findClientRenderRegionField() {
        try {
            final Class<?> type = Class.forName(
                    "dev.ryanhcode.sable.sublevel.water_occlusion."
                            + "ClientWaterOcclusionContainer$ClientWaterOcclusionRegion");
            final Field field = type.getDeclaredField("renderRegion");
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Field findRendererRegionsField() {
        try {
            final Field field = dev.ryanhcode.sable.render.water_occlusion
                    .WaterOcclusionRenderer.class.getDeclaredField("regions");
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void logInstallFailure(final Throwable cause) {
        if (installFailureLogged) {
            return;
        }
        installFailureLogged = true;
        if (cause == null) {
            CreateNautilus.LOGGER.warn(
                    "Sable does not expose the expected client water-occlusion region fields; "
                            + "continuous compartment clipping is unavailable");
        } else {
            CreateNautilus.LOGGER.warn(
                    "Unable to install continuous compartment water occlusion; "
                            + "retaining Sable's coarse voxel region", cause);
        }
    }

    private record Face(Direction direction, int[] corners) {
    }
}
