package com.create.nautilus.client.render;

import com.create.nautilus.CreateNautilus;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Keeps Prism's opaque glass frames in the depth copied before parent-world water is drawn. */
public final class PrismGlassRenderCompatibility {
    private static final List<String> CASINGS = List.of(
            "andesite_glass_casing", "andesite_clear_glass_casing",
            "brass_glass_casing", "brass_clear_glass_casing",
            "steel_glass_casing", "steel_clear_glass_casing");
    private static final ChunkRenderTypeSet CUTOUT = ChunkRenderTypeSet.of(RenderType.cutoutMipped());
    private static final Direction[] SIDES = Direction.values();

    private PrismGlassRenderCompatibility() { }

    /** Uses only this bake's models/sprites: no world, GL state or persistent sprite references. */
    public static void onModelsBaked(final ModelEvent.ModifyBakingResult event) {
        final List<String> adapted = new ArrayList<>();
        final List<String> retained = new ArrayList<>();
        // Prism's six casing blocks have no state properties. Use their exact block-model keys;
        // do not enumerate or replaceAll a lazy model registry. ModernFix probes replaceAll with
        // a null model before deciding whether to load it, so a safe null fallback skips the fix.
        for (final String casing : CASINGS) {
            final ResourceLocation id = ResourceLocation.fromNamespaceAndPath("createprism", casing);
            final ModelResourceLocation location = new ModelResourceLocation(id, "");
            if (!event.getModels().containsKey(location)) continue;
            final BakedModel model = event.getModels().get(location);
            if (model == null) continue;
            final BakedModel replacement = adapt(model, id, event.getTextureGetter());
            if (replacement != model) {
                event.getModels().put(location, replacement);
            }
            if (replacement instanceof CasingModel) {
                adapted.add(casing);
            } else {
                retained.add(casing);
            }
        }
        if (!adapted.isEmpty() || !retained.isEmpty()) {
            CreateNautilus.LOGGER.info("[prism-glass] bake cutout={} original={}", adapted, retained);
        }
    }

    static BakedModel adapt(final BakedModel model, final ResourceLocation id,
                            final Function<Material, TextureAtlasSprite> sprites) {
        if (model == null || model instanceof CasingModel) return model;
        final ResourceLocation base = ResourceLocation.fromNamespaceAndPath("createprism", "block/" + id.getPath());
        final ResourceLocation connected = ResourceLocation.fromNamespaceAndPath("createprism", "block/" + id.getPath() + "_connected");
        final TextureAtlasSprite baseSprite = sprites.apply(new Material(InventoryMenu.BLOCK_ATLAS, base));
        final TextureAtlasSprite connectedSprite = sprites.apply(new Material(InventoryMenu.BLOCK_ATLAS, connected));
        // CT rewrites UVs while retaining the original quad sprite. Inspect its target too, and
        // inspect the whole source image so later animation frames cannot hide partial alpha.
        if (!baseSprite.contents().name().equals(base) || !connectedSprite.contents().name().equals(connected)
                || !binaryAlpha(baseSprite) || !binaryAlpha(connectedSprite)) return model;

        // A resource pack may replace the cube with a model using other textures. Its render
        // layers remain untouched; only the known base/CT model participates in this fix.
        boolean hasQuads = false;
        final RandomSource random = RandomSource.create(0L);
        try {
            for (int side = 0; side <= SIDES.length; side++) {
                random.setSeed(0L);
                final List<BakedQuad> quads = model.getQuads(null,
                        side == SIDES.length ? null : SIDES[side],
                        random, ModelData.EMPTY, RenderType.translucent());
                for (final BakedQuad quad : quads) {
                    final ResourceLocation sprite = quad.getSprite().contents().name();
                    if (!sprite.equals(base) && !sprite.equals(connected)) return model;
                    hasQuads = true;
                }
            }
        } catch (final RuntimeException unsupportedModel) {
            // Unlike Prism's cube/CT model, a replacement loader may require a real block state.
            // Do not turn an optional classification into a resource-reload failure.
            return model;
        }
        return hasQuads ? new CasingModel(model) : model;
    }

    private static boolean binaryAlpha(final TextureAtlasSprite sprite) {
        final NativeImage image = sprite.contents().getOriginalImage();
        // Generated mip levels naturally contain partial alpha; they are not source material.
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int alpha = image.getPixelRGBA(x, y) >>> 24;
                if (alpha != 0 && alpha != 255) return false;
            }
        }
        return true;
    }

    private static boolean translucentOnly(final ChunkRenderTypeSet layers) {
        final var iterator = layers.iterator();
        return iterator.hasNext() && iterator.next() == RenderType.translucent() && !iterator.hasNext();
    }

    private static final class CasingModel extends BakedModelWrapper<BakedModel> {
        private CasingModel(final BakedModel original) { super(original); }

        @Override
        public ChunkRenderTypeSet getRenderTypes(final BlockState state, final RandomSource random,
                                                 final ModelData data) {
            final ChunkRenderTypeSet layers = super.getRenderTypes(state, random, data);
            return translucentOnly(layers) ? CUTOUT : layers;
        }

        @Override
        public List<BakedQuad> getQuads(final BlockState state, final Direction side,
                                        final RandomSource random, final ModelData data,
                                        final RenderType layer) {
            final RenderType originalLayer = layer == RenderType.cutoutMipped()
                    && translucentOnly(super.getRenderTypes(state, random, data))
                    ? RenderType.translucent() : layer;
            return super.getQuads(state, side, random, data, originalLayer);
        }
    }
}
