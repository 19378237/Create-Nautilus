package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Extracts the real, texture-addressable aperture of one semantic hull-boundary block.
 *
 * <p>This class deliberately stops at immutable carrier-local geometry. It never applies a
 * camera transform and never draws the visible block again. The gate pass can consequently use
 * exactly one frozen carrier pose for both these vertices and the rest of the medium snapshot.
 * UVs remain block-atlas UVs from the baked quad, so the private gate shader can bind
 * {@link AtlasBatch#atlas()} and retain the transparent optical aperture while rejecting the
 * opaque frame.</p>
 *
 * <p>A boundary is first classified semantically. Only a boundary that is positively identified
 * as optical may return {@link Disposition#FAIL_OPEN} when its real aperture cannot be decoded.
 * An unclassified/custom membrane is treated as non-optical so the caller terminates its semantic
 * boundary search and preserves the shader pack's native result. In particular, this provider
 * never replaces a missing/custom model with a cube face.</p>
 */
final class OpticalApertureProvider {
    /** Explicit compatibility override. It is intentionally not the only optical classification. */
    static final TagKey<Block> HULL_GLASS = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(CreateNautilus.MOD_ID, "hull_glass"));
    private static final TagKey<Block> COMMON_GLASS_BLOCKS = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "glass_blocks"));
    private static final TagKey<Block> COMMON_GLASS_PANES = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "glass_panes"));

    private static final int BLOCK_STRIDE =
            DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
    private static final int POSITION_OFFSET =
            DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.POSITION) / Integer.BYTES;
    private static final int UV_OFFSET =
            DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.UV0) / Integer.BYTES;
    private static final float CUTOUT_ALPHA = 0.1F;
    private static final float NON_ZERO_ALPHA = 0.0F;
    private static final double LOCAL_POSITION_EPSILON = 1.0D / 1024.0D;
    private static final float UV_EPSILON = 1.0F / 65_536.0F;
    private static final double TRIANGLE_AREA_EPSILON = 1.0E-12D;
    private static final Direction[] AXIS_PROBES = {
            Direction.UP, Direction.EAST, Direction.SOUTH
    };

    private static final AtomicLong RESOURCE_GENERATION = new AtomicLong();
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private OpticalApertureProvider() {
    }

    /**
     * Proves water-excluding occupancy independently of a room-to-exterior optical event.
     * An internal glass junction can have solid neighbours on every opposing axis, yet its
     * full collision cube still cannot contain parent-world water. Reuse a reliable baked
     * extraction on any axis; connected models may omit their internal top/bottom faces.
     * Partial shapes and unavailable/custom models never acquire whole-cell occupancy.
     */
    static boolean isFullOpticalVolume(final ClientSubLevel carrier, final BlockPos position) {
        try {
            final ClientLevel level = carrier.getLevel();
            if (!level.hasChunkAt(position)) return false;
            final BlockState state = level.getBlockState(position);
            if (classifyState(state) != StateClassification.CONFIRMED_OPTICAL
                    || !state.isCollisionShapeFullBlock(level, position)) {
                return false;
            }
            for (final Direction direction : AXIS_PROBES) {
                try {
                    final Extraction extraction = extract(carrier, position, direction, state);
                    if (extraction.disposition() == Disposition.APERTURE
                            && extraction.fullCollisionBlock() && !extraction.batches().isEmpty()) {
                        return true;
                    }
                } catch (final RuntimeException | LinkageError ignored) {
                    // A missing axis does not invalidate a reliable extraction on another axis.
                }
            }
        } catch (final RuntimeException | LinkageError ignored) {
            // Occupancy is optional when the real block/model cannot be proved.
        }
        return false;
    }

    /**
     * Resolves one candidate membrane block for the requested room-to-exterior direction.
     *
     * <p>The returned positions are in the {@link ClientSubLevel}'s stable plot coordinate
     * system. Both model-face directions on the requested axis are queried; one-sided baked
     * models are therefore valid and the draw pass is responsible for disabling face culling.
     * The method is safe to call repeatedly in a frame: state/model-data fingerprints protect the
     * cache even when a neighbour-sensitive model changes before its explicit invalidation event
     * is delivered.</p>
     */
    static Extraction extract(final ClientSubLevel carrier,
                              final BlockPos candidate,
                              final Direction boundaryDirection,
                              final BlockState state) {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(boundaryDirection, "boundaryDirection");
        Objects.requireNonNull(state, "state");

        final ClientLevel level = carrier.getLevel();
        final BlockPos immutablePosition = candidate.immutable();
        final long generation = RESOURCE_GENERATION.get();
        final CacheKey key = new CacheKey(
                level, carrier.getUniqueId(), immutablePosition.asLong(), boundaryDirection);

        final StateClassification stateClassification = classifyState(state);
        if (stateClassification == StateClassification.KNOWN_OPAQUE) {
            return Extraction.opaque(
                    generation, immutablePosition, state, boundaryDirection,
                    state.getRenderShape() != RenderShape.MODEL
                            ? "non-model membrane is not explicitly optical"
                            : "occluding membrane is not explicitly optical");
        }

        final BakedModel model;
        final ModelData modelData;
        try {
            if (state.getRenderShape() != RenderShape.MODEL) {
                return Extraction.failOpen(
                        generation, immutablePosition, state, boundaryDirection,
                        "confirmed optical boundary is not a baked MODEL");
            }
            model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            if (model == null || model.isCustomRenderer()) {
                final String reason = model == null
                        ? "missing baked model"
                        : "custom block renderer has no reliable baked aperture";
                return stateClassification == StateClassification.CONFIRMED_OPTICAL
                        ? Extraction.failOpen(
                                generation, immutablePosition, state, boundaryDirection, reason)
                        : Extraction.opaque(
                                generation, immutablePosition, state, boundaryDirection,
                                "unclassified " + reason);
            }
            final ModelData baseData = level.getModelDataManager().getAt(immutablePosition);
            modelData = model.getModelData(level, immutablePosition, state, baseData);
            if (modelData == null) {
                return stateClassification == StateClassification.CONFIRMED_OPTICAL
                        ? Extraction.failOpen(
                                generation, immutablePosition, state, boundaryDirection,
                                "confirmed optical model returned null ModelData")
                        : Extraction.opaque(
                                generation, immutablePosition, state, boundaryDirection,
                                "model returned null ModelData before it could be classified");
            }
        } catch (final RuntimeException | LinkageError exception) {
            final String reason = "model lookup failed: "
                    + exception.getClass().getSimpleName();
            return stateClassification == StateClassification.CONFIRMED_OPTICAL
                    ? Extraction.failOpen(
                            generation, immutablePosition, state, boundaryDirection, reason)
                    : Extraction.opaque(
                            generation, immutablePosition, state, boundaryDirection,
                            "unclassified " + reason);
        }

        final long dataFingerprint = modelDataFingerprint(modelData);
        final NeighborhoodFingerprint neighborhood = neighborhoodFingerprint(
                level, immutablePosition);
        if (!neighborhood.reliable()) {
            /*
             * Connected glass can change both its quads and UVs from neighbour state/model data.
             * Decoding the centre model while any dependency is unknown would manufacture a
             * temporary aperture and allow it to combine with a different valid boundary.  We
             * still classify NEEDS_MODEL blocks below so a genuinely opaque membrane remains an
             * opaque terminator, but a confirmed aperture is conservatively failed open.
             */
            final Extraction uncached = build(
                    level, immutablePosition, boundaryDirection, state,
                    model, modelData, generation, stateClassification);
            return uncached.disposition() == Disposition.APERTURE
                    ? Extraction.failOpen(
                            generation, immutablePosition, state, boundaryDirection,
                            "neighbour state or ModelData required by the optical model is unavailable")
                    : uncached;
        }
        final CacheEntry cached = CACHE.get(key);
        if (cached != null
                && neighborhood.reliable()
                && cached.resourceGeneration() == generation
                && cached.state().equals(state)
                && cached.model() == model
                && cached.modelDataFingerprint() == dataFingerprint
                && cached.neighborhoodFingerprint() == neighborhood.value()) {
            return cached.extraction();
        }

        final Extraction extraction = build(
                level, immutablePosition, boundaryDirection, state,
                model, modelData, generation, stateClassification);
        CACHE.put(key, new CacheEntry(
                generation, state, model, dataFingerprint,
                neighborhood.value(), extraction));
        return extraction;
    }

    private static Extraction build(final ClientLevel level,
                                    final BlockPos position,
                                    final Direction boundaryDirection,
                                    final BlockState state,
                                    final BakedModel model,
                                    final ModelData modelData,
                                    final long generation,
                                    final StateClassification stateClassification) {
        final long seed = state.getSeed(position);
        final List<RenderType> renderTypes = new ArrayList<>();
        try {
            model.getRenderTypes(state, RandomSource.create(seed), modelData)
                    .forEach(renderTypes::add);
        } catch (final RuntimeException | LinkageError exception) {
            final String reason = "render-type lookup failed: "
                    + exception.getClass().getSimpleName();
            return stateClassification == StateClassification.CONFIRMED_OPTICAL
                    ? Extraction.failOpen(
                            generation, position, state, boundaryDirection, reason)
                    : Extraction.opaque(
                            generation, position, state, boundaryDirection,
                            "model could not be confirmed optical because " + reason);
        }

        final boolean translucentLayer = renderTypes.stream()
                .anyMatch(renderType -> renderType == RenderType.translucent());
        if (stateClassification != StateClassification.CONFIRMED_OPTICAL
                && !translucentLayer) {
            return Extraction.opaque(
                    generation, position, state, boundaryDirection,
                    "model is not an optical membrane");
        }

        /*
         * An empty set is the documented signal for models that expose all quads through the
         * nullable render-type query. A non-empty set, however, must consist solely of chunk
         * layers whose alpha contract is known to the gate shader. Partially replaying an
         * unknown multi-layer model would turn missing geometry into a false window.
         */
        final List<LayerQuery> layers = new ArrayList<>();
        if (renderTypes.isEmpty()) {
            layers.add(new LayerQuery(null, NON_ZERO_ALPHA));
        } else {
            for (final RenderType renderType : renderTypes) {
                final Float cutoff = alphaCutoff(renderType);
                if (cutoff == null) {
                    return Extraction.failOpen(
                            generation, position, state, boundaryDirection,
                            "unsupported chunk render layer: " + renderType);
                }
                layers.add(new LayerQuery(renderType, cutoff));
            }
        }

        final Map<QuadSignature, QuadSample> uniqueQuads = new LinkedHashMap<>();
        try {
            for (final LayerQuery layer : layers) {
                addQuads(uniqueQuads, model.getQuads(
                                state, boundaryDirection, RandomSource.create(seed),
                                modelData, layer.renderType()),
                        layer.alphaCutoff());
                addQuads(uniqueQuads, model.getQuads(
                                state, boundaryDirection.getOpposite(), RandomSource.create(seed),
                                modelData, layer.renderType()),
                        layer.alphaCutoff());
                addQuads(uniqueQuads, model.getQuads(
                                state, null, RandomSource.create(seed),
                                modelData, layer.renderType()),
                        layer.alphaCutoff());
            }
        } catch (final RuntimeException | LinkageError exception) {
            return Extraction.failOpen(
                    generation, position, state, boundaryDirection,
                    "quad extraction failed: " + exception.getClass().getSimpleName());
        }

        final Map<BatchKey, List<TexturedTriangle>> batchTriangles = new LinkedHashMap<>();
        for (final QuadSample sample : uniqueQuads.values()) {
            final BakedQuad quad = sample.quad();
            if (quad.getDirection().getAxis() != boundaryDirection.getAxis()) {
                continue;
            }
            final QuadVertices decoded = decodeQuad(position, quad);
            if (decoded == null) {
                return Extraction.failOpen(
                        generation, position, state, boundaryDirection,
                        "quad uses an unsupported vertex layout or invalid UV/position");
            }
            final TextureAtlasSprite sprite = quad.getSprite();
            if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                return Extraction.failOpen(
                        generation, position, state, boundaryDirection,
                        "quad uses the missing-texture sprite");
            }
            final BatchKey batchKey = new BatchKey(
                    sprite.atlasLocation(), sample.alphaCutoff());
            final List<TexturedTriangle> output = batchTriangles.computeIfAbsent(
                    batchKey, ignored -> new ArrayList<>());
            appendTriangle(output, decoded.first(), decoded.second(),
                    decoded.third(), quad.getDirection(), sprite.contents().name());
            appendTriangle(output, decoded.first(), decoded.third(),
                    decoded.fourth(), quad.getDirection(), sprite.contents().name());
        }

        if (batchTriangles.isEmpty()
                || batchTriangles.values().stream().allMatch(List::isEmpty)) {
            return Extraction.failOpen(
                    generation, position, state, boundaryDirection,
                    "no reliable baked quads on the semantic boundary axis");
        }

        final List<AtlasBatch> batches = batchTriangles.entrySet().stream()
                .map(entry -> new AtlasBatch(
                        entry.getKey().atlas(), entry.getKey().alphaCutoff(),
                        List.copyOf(entry.getValue())))
                .toList();
        boolean fullCollisionBlock = false;
        if (stateClassification == StateClassification.CONFIRMED_OPTICAL) {
            try {
                // Only an explicitly confirmed optical membrane may request unit-cell completion.
                // The collision shape is the authority for the synthetic outer faces; panes and
                // partial/custom shapes remain on their exact extracted triangles. A translucent
                // render layer alone is deliberately insufficient because arbitrary translucent
                // blocks are not necessarily hull glass.
                fullCollisionBlock = state.isCollisionShapeFullBlock(level, position);
            } catch (final RuntimeException | LinkageError ignored) {
                fullCollisionBlock = false;
            }
        }
        return Extraction.aperture(
                generation, position, state, boundaryDirection,
                List.copyOf(batches), fullCollisionBlock);
    }

    private static StateClassification classifyState(final BlockState state) {
        if (state.is(HULL_GLASS)
                || state.is(COMMON_GLASS_BLOCKS)
                || state.is(COMMON_GLASS_PANES)
                || state.getBlock() instanceof HalfTransparentBlock) {
            return StateClassification.CONFIRMED_OPTICAL;
        }
        if (state.getRenderShape() != RenderShape.MODEL || state.canOcclude()) {
            return StateClassification.KNOWN_OPAQUE;
        }
        return StateClassification.NEEDS_MODEL;
    }

    private static Float alphaCutoff(final RenderType renderType) {
        if (renderType == RenderType.cutout()
                || renderType == RenderType.cutoutMipped()
                || renderType == RenderType.tripwire()) {
            return CUTOUT_ALPHA;
        }
        if (renderType == RenderType.solid()
                || renderType == RenderType.translucent()) {
            // Even a solid-layer explicit override still samples the real texture. The private
            // gate interprets zero/partial alpha as the optical pane and alpha=1 as opaque frame.
            return NON_ZERO_ALPHA;
        }
        return null;
    }

    private static void addQuads(final Map<QuadSignature, QuadSample> output,
                                 final List<BakedQuad> quads,
                                 final float alphaCutoff) {
        if (quads == null) {
            throw new IllegalStateException("baked model returned a null quad list");
        }
        for (final BakedQuad quad : quads) {
            if (quad == null || quad.getSprite() == null) {
                throw new IllegalStateException("baked model returned a null quad/sprite");
            }
            output.putIfAbsent(new QuadSignature(quad, alphaCutoff),
                    new QuadSample(quad, alphaCutoff));
        }
    }

    private static QuadVertices decodeQuad(final BlockPos position,
                                           final BakedQuad quad) {
        final int[] packed = quad.getVertices();
        if (packed == null || packed.length != BLOCK_STRIDE * 4
                || POSITION_OFFSET < 0 || UV_OFFSET < 0
                || POSITION_OFFSET + 2 >= BLOCK_STRIDE
                || UV_OFFSET + 1 >= BLOCK_STRIDE) {
            return null;
        }
        final ApertureVertex[] vertices = new ApertureVertex[4];
        for (int vertex = 0; vertex < vertices.length; vertex++) {
            final int base = vertex * BLOCK_STRIDE;
            final double localX = Float.intBitsToFloat(packed[base + POSITION_OFFSET]);
            final double localY = Float.intBitsToFloat(packed[base + POSITION_OFFSET + 1]);
            final double localZ = Float.intBitsToFloat(packed[base + POSITION_OFFSET + 2]);
            final float u = Float.intBitsToFloat(packed[base + UV_OFFSET]);
            final float v = Float.intBitsToFloat(packed[base + UV_OFFSET + 1]);
            if (!Double.isFinite(localX) || !Double.isFinite(localY)
                    || !Double.isFinite(localZ) || !Float.isFinite(u) || !Float.isFinite(v)
                    || !insideModelCell(localX) || !insideModelCell(localY)
                    || !insideModelCell(localZ) || !insideAtlas(u, v)) {
                return null;
            }
            vertices[vertex] = new ApertureVertex(
                    position.getX() + localX,
                    position.getY() + localY,
                    position.getZ() + localZ,
                    u, v);
        }
        return new QuadVertices(vertices[0], vertices[1], vertices[2], vertices[3]);
    }

    private static boolean insideModelCell(final double coordinate) {
        return coordinate >= -LOCAL_POSITION_EPSILON
                && coordinate <= 1.0D + LOCAL_POSITION_EPSILON;
    }

    /**
     * Baked quads from Create's connected-texture model keep the original quad sprite while
     * rewriting the UVs to a neighbouring target sprite in the same block atlas.  Validating
     * against {@code quad.getSprite()} consequently rejects every connected framed-glass pane.
     * The gate binds the atlas, not an individual sprite, so the correct contract is the atlas
     * domain itself; the UV pair still must be finite and normalized to avoid sampling outside
     * the texture.
     */
    private static boolean insideAtlas(final float u, final float v) {
        return u >= -UV_EPSILON && u <= 1.0F + UV_EPSILON
                && v >= -UV_EPSILON && v <= 1.0F + UV_EPSILON;
    }

    private static void appendTriangle(final List<TexturedTriangle> textured,
                                       final ApertureVertex first,
                                       final ApertureVertex second,
                                       final ApertureVertex third,
                                       final Direction normal,
                                       final ResourceLocation sprite) {
        final double edgeAX = second.x() - first.x();
        final double edgeAY = second.y() - first.y();
        final double edgeAZ = second.z() - first.z();
        final double edgeBX = third.x() - first.x();
        final double edgeBY = third.y() - first.y();
        final double edgeBZ = third.z() - first.z();
        final double crossX = edgeAY * edgeBZ - edgeAZ * edgeBY;
        final double crossY = edgeAZ * edgeBX - edgeAX * edgeBZ;
        final double crossZ = edgeAX * edgeBY - edgeAY * edgeBX;
        if (crossX * crossX + crossY * crossY + crossZ * crossZ
                <= TRIANGLE_AREA_EPSILON) {
            return;
        }
        textured.add(new TexturedTriangle(first, second, third, normal, sprite));
    }

    static long modelDataFingerprint(final ModelData modelData) {
        long sum = 0x9E3779B97F4A7C15L;
        long xor = 0xD1B54A32D192ED03L;
        int count = 0;
        for (final ModelProperty<?> property : modelData.getProperties()) {
            final Object value = modelData.get(property);
            long component = Integer.toUnsignedLong(System.identityHashCode(property));
            component = mix64(component ^ Integer.toUnsignedLong(deepHashCode(value)));
            sum += component;
            xor ^= Long.rotateLeft(component, count & 63);
            count++;
        }
        return mix64(sum ^ Long.rotateLeft(xor, 23) ^ count);
    }

    /**
     * Captures the complete 3x3x3 model neighbourhood used by connected-texture models.
     *
     * <p>The explicit invalidation hook remains the fast path, but this fingerprint prevents a
     * stale aperture from being reused in the frame between a neighbour/model-data change and a
     * delayed or missing client notification. ModelData is included because Create connection
     * models commonly derive their baked quads from it rather than directly from BlockState.</p>
     */
    private static NeighborhoodFingerprint neighborhoodFingerprint(final ClientLevel level,
                                                                    final BlockPos center) {
        long ordered = 0x243F6A8885A308D3L;
        int ordinal = 0;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        try {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        cursor.set(
                                center.getX() + offsetX,
                                center.getY() + offsetY,
                                center.getZ() + offsetZ);
                        if (!level.hasChunkAt(cursor)) {
                            return new NeighborhoodFingerprint(0L, false);
                        }
                        final BlockState neighbourState = level.getBlockState(cursor);
                        final ModelData neighbourData = level.getModelDataManager().getAt(cursor);
                        long component = Integer.toUnsignedLong(
                                System.identityHashCode(neighbourState));
                        component ^= Long.rotateLeft(
                                Integer.toUnsignedLong(neighbourState.hashCode()), 17);
                        if (neighbourData != null) {
                            component ^= Long.rotateLeft(
                                    modelDataFingerprint(neighbourData), 31);
                        }
                        component ^= 0x9E3779B97F4A7C15L * (ordinal + 1L);
                        ordered = mix64(ordered ^ Long.rotateLeft(component, ordinal & 63));
                        ordinal++;
                    }
                }
            }
            return new NeighborhoodFingerprint(ordered, true);
        } catch (final RuntimeException | LinkageError exception) {
            // Extraction can still proceed, but this result must not enter/reuse the cache.
            return new NeighborhoodFingerprint(0L, false);
        }
    }

    private static int deepHashCode(final Object value) {
        if (value instanceof Object[] array) {
            return Arrays.deepHashCode(array);
        }
        if (value instanceof byte[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof short[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof int[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof long[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof char[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof float[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof double[] array) {
            return Arrays.hashCode(array);
        }
        if (value instanceof boolean[] array) {
            return Arrays.hashCode(array);
        }
        return Objects.hashCode(value);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** Invalidates this block and all neighbour-dependent model entries in its 3x3x3 cube. */
    static void invalidateAround(final ClientSubLevel carrier, final BlockPos changedPosition) {
        if (carrier == null || changedPosition == null) {
            return;
        }
        final ClientLevel level = carrier.getLevel();
        final UUID carrierId = carrier.getUniqueId();
        final int minX = changedPosition.getX() - 1;
        final int maxX = changedPosition.getX() + 1;
        final int minY = changedPosition.getY() - 1;
        final int maxY = changedPosition.getY() + 1;
        final int minZ = changedPosition.getZ() - 1;
        final int maxZ = changedPosition.getZ() + 1;
        CACHE.keySet().removeIf(key -> key.level() == level
                && key.carrierId().equals(carrierId)
                && BlockPos.getX(key.position()) >= minX
                && BlockPos.getX(key.position()) <= maxX
                && BlockPos.getY(key.position()) >= minY
                && BlockPos.getY(key.position()) <= maxY
                && BlockPos.getZ(key.position()) >= minZ
                && BlockPos.getZ(key.position()) <= maxZ);
    }

    /** Removes every cached aperture belonging to one carrier instance. */
    static void invalidateCarrier(final ClientSubLevel carrier) {
        if (carrier == null) {
            return;
        }
        final ClientLevel level = carrier.getLevel();
        final UUID carrierId = carrier.getUniqueId();
        CACHE.keySet().removeIf(key -> key.level() == level
                && key.carrierId().equals(carrierId));
    }

    static void invalidateCarrier(final ClientLevel level, final UUID carrierId) {
        if (level == null || carrierId == null) {
            return;
        }
        CACHE.keySet().removeIf(key -> key.level() == level
                && key.carrierId().equals(carrierId));
    }

    /**
     * Starts a new texture/model generation (resource reload, shader reload or dimension reset).
     * Cached atlas UVs may not cross this boundary.
     */
    static long invalidateResources() {
        final long generation = RESOURCE_GENERATION.incrementAndGet();
        CACHE.clear();
        return generation;
    }

    static void clear() {
        CACHE.clear();
    }

    static long resourceGeneration() {
        return RESOURCE_GENERATION.get();
    }

    static int cachedEntryCount() {
        return CACHE.size();
    }

    enum Disposition {
        APERTURE,
        OPAQUE,
        FAIL_OPEN
    }

    /** Complete immutable result for one candidate block/direction. */
    record Extraction(Disposition disposition,
                      List<AtlasBatch> batches,
                      boolean fullCollisionBlock,
                      long resourceGeneration,
                      BoundaryDiagnostic diagnostic) {
        Extraction {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(diagnostic, "diagnostic");
            batches = List.copyOf(batches);
            if (disposition == Disposition.APERTURE
                    && batches.isEmpty()) {
                throw new IllegalArgumentException("an aperture requires textured geometry");
            }
            if (disposition != Disposition.APERTURE
                    && !batches.isEmpty()) {
                throw new IllegalArgumentException("non-aperture result cannot contain geometry");
            }
            if (fullCollisionBlock && disposition != Disposition.APERTURE) {
                throw new IllegalArgumentException(
                        "only a confirmed aperture can request full-cell geometry");
            }
        }

        static Extraction aperture(final long generation,
                                   final BlockPos position,
                                   final BlockState state,
                                   final Direction boundaryDirection,
                                   final List<AtlasBatch> batches,
                                   final boolean fullCollisionBlock) {
            return new Extraction(
                    Disposition.APERTURE, batches, fullCollisionBlock, generation,
                    new BoundaryDiagnostic(position, state, boundaryDirection, ""));
        }

        static Extraction opaque(final long generation,
                                 final BlockPos position,
                                 final BlockState state,
                                 final Direction boundaryDirection,
                                 final String reason) {
            return new Extraction(
                    Disposition.OPAQUE, List.of(), false, generation,
                    new BoundaryDiagnostic(position, state, boundaryDirection, reason));
        }

        static Extraction failOpen(final long generation,
                                   final BlockPos position,
                                   final BlockState state,
                                   final Direction boundaryDirection,
                                   final String reason) {
            return new Extraction(
                    Disposition.FAIL_OPEN, List.of(), false, generation,
                    new BoundaryDiagnostic(position, state, boundaryDirection, reason));
        }

        boolean isAperture() {
            return disposition == Disposition.APERTURE;
        }

        /** Compatibility accessor retained for concise one-time diagnostics in callers. */
        String reason() {
            return diagnostic.reason();
        }
    }

    /** Exact semantic boundary context retained even when no geometry can be emitted. */
    record BoundaryDiagnostic(BlockPos position,
                              BlockState state,
                              Direction boundaryDirection,
                              String reason) {
        BoundaryDiagnostic {
            position = Objects.requireNonNull(position, "position").immutable();
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(boundaryDirection, "boundaryDirection");
            reason = reason == null ? "" : reason;
        }
    }

    /** One atlas binding and alpha contract; triangles may reference different sprites in it. */
    record AtlasBatch(ResourceLocation atlas,
                      float alphaCutoff,
                      List<TexturedTriangle> triangles) {
        AtlasBatch {
            Objects.requireNonNull(atlas, "atlas");
            if (!Float.isFinite(alphaCutoff) || alphaCutoff < 0.0F || alphaCutoff >= 1.0F) {
                throw new IllegalArgumentException("invalid alpha cutoff " + alphaCutoff);
            }
            triangles = List.copyOf(triangles);
            if (triangles.isEmpty()) {
                throw new IllegalArgumentException("empty atlas batch");
            }
        }

        /** Resolves after resource reload; no stale GL texture id is retained in the mesh cache. */
        int resolveTextureId() {
            return Minecraft.getInstance().getTextureManager().getTexture(atlas).getId();
        }
    }

    record ApertureVertex(double x, double y, double z, float u, float v) {
        ApertureVertex {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(u) || !Float.isFinite(v)) {
                throw new IllegalArgumentException("non-finite aperture vertex");
            }
        }

    }

    record TexturedTriangle(ApertureVertex first,
                            ApertureVertex second,
                            ApertureVertex third,
                            Direction modelNormal,
                            ResourceLocation sprite) {
        TexturedTriangle {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            Objects.requireNonNull(third, "third");
            Objects.requireNonNull(modelNormal, "modelNormal");
            Objects.requireNonNull(sprite, "sprite");
        }
    }

    private record LayerQuery(RenderType renderType, float alphaCutoff) {
    }

    private record QuadSample(BakedQuad quad, float alphaCutoff) {
    }

    private record QuadVertices(ApertureVertex first,
                                ApertureVertex second,
                                ApertureVertex third,
                                ApertureVertex fourth) {
    }

    private record BatchKey(ResourceLocation atlas, float alphaCutoff) {
    }

    private enum StateClassification {
        CONFIRMED_OPTICAL,
        KNOWN_OPAQUE,
        NEEDS_MODEL
    }

    private record NeighborhoodFingerprint(long value, boolean reliable) {
    }

    private record CacheEntry(long resourceGeneration,
                              BlockState state,
                              BakedModel model,
                              long modelDataFingerprint,
                              long neighborhoodFingerprint,
                              Extraction extraction) {
    }

    /** Identity-compares levels so identical carrier UUIDs cannot cross dimensions/worlds. */
    private static final class CacheKey {
        private final ClientLevel level;
        private final UUID carrierId;
        private final long position;
        private final Direction boundaryDirection;
        private final int hash;

        private CacheKey(final ClientLevel level,
                         final UUID carrierId,
                         final long position,
                         final Direction boundaryDirection) {
            this.level = level;
            this.carrierId = carrierId;
            this.position = position;
            this.boundaryDirection = boundaryDirection;
            int value = System.identityHashCode(level);
            value = 31 * value + carrierId.hashCode();
            value = 31 * value + Long.hashCode(position);
            this.hash = 31 * value + boundaryDirection.hashCode();
        }

        ClientLevel level() {
            return level;
        }

        UUID carrierId() {
            return carrierId;
        }

        long position() {
            return position;
        }

        @Override
        public boolean equals(final Object object) {
            return object instanceof final CacheKey other
                    && level == other.level
                    && carrierId.equals(other.carrierId)
                    && position == other.position
                    && boundaryDirection == other.boundaryDirection;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class QuadSignature {
        private final Direction direction;
        private final ResourceLocation atlas;
        private final ResourceLocation sprite;
        private final int alphaCutoffBits;
        private final int[] vertices;
        private final int hash;

        private QuadSignature(final BakedQuad quad, final float alphaCutoff) {
            direction = quad.getDirection();
            atlas = quad.getSprite().atlasLocation();
            sprite = quad.getSprite().contents().name();
            alphaCutoffBits = Float.floatToRawIntBits(alphaCutoff);
            vertices = quad.getVertices().clone();
            int value = direction.hashCode();
            value = 31 * value + atlas.hashCode();
            value = 31 * value + sprite.hashCode();
            value = 31 * value + alphaCutoffBits;
            hash = 31 * value + Arrays.hashCode(vertices);
        }

        @Override
        public boolean equals(final Object object) {
            return object instanceof final QuadSignature other
                    && direction == other.direction
                    && atlas.equals(other.atlas)
                    && sprite.equals(other.sprite)
                    && alphaCutoffBits == other.alphaCutoffBits
                    && Arrays.equals(vertices, other.vertices);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
