package com.create.nautilus.client.render;

import com.create.nautilus.hydrogen.GasPipeBlock;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.AllSpriteShifts;
import com.simibubi.create.content.fluids.tank.FluidTankCTBehaviour;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.model.BakedModelWrapperWithData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Create-style neighbour data for our own tank shells and pipe attachments.
 * Generated faces reserve 0x10000..0x10fff for two six-bit connection masks:
 * low bits hide a part on any matching connection; high bits optionally require
 * a connection (used to reveal cap-edge patches after a corner is removed).
 * Tags are stripped before rendering; they never reach BlockColors as a tint.
 * A corner uses two bits, so the whole fitting vanishes at either joined edge.
 * Tank materials use Create's native CT tiles, preserving per-block texel size.
 */
public final class GasConnectionModel extends BakedModelWrapperWithData {
    private static final int TAG = 0x10000;
    private static final int DIRECTIONS = 0x3f;
    private static final ModelProperty<Integer> CONNECTIONS = new ModelProperty<>();
    private final boolean tank;

    private GasConnectionModel(final BakedModel model, final boolean tank) {
        super(model);
        this.tank = tank;
    }

    public static BakedModel tank(final BakedModel model) {
        return new GasConnectionModel(new CTModel(model, new FluidTankCTBehaviour(
                AllSpriteShifts.FLUID_TANK, AllSpriteShifts.FLUID_TANK_TOP,
                AllSpriteShifts.FLUID_TANK_INNER)), true);
    }

    public static BakedModel pipe(final BakedModel model) {
        return new GasConnectionModel(model, false);
    }

    @Override
    protected ModelData.Builder gatherModelData(final ModelData.Builder builder,
                                                final BlockAndTintGetter world,
                                                final BlockPos pos, final BlockState state,
                                                final ModelData blockEntityData) {
        int mask = 0;
        for (final Direction direction : Direction.values()) {
            final BlockPos otherPos = pos.relative(direction);
            final BlockState other = world.getBlockState(otherPos);
            final boolean connected;
            if (tank) {
                connected = direction.getAxis().isHorizontal()
                        && other.is(state.getBlock())
                        && ConnectivityHandler.isConnected(world, pos, otherPos);
            } else {
                // The second arm can be a visual fallback. Only an actual pipe
                // with a mutually open end removes the terminal/rim assembly.
                connected = state.getBlock() instanceof GasPipeBlock
                        && other.getBlock() instanceof GasPipeBlock
                        && GasPipeBlock.isOpenAt(state, direction)
                        && GasPipeBlock.isOpenAt(other, direction.getOpposite());
            }
            if (connected) mask |= 1 << direction.get3DDataValue();
        }
        return builder.with(CONNECTIONS, mask);
    }

    @Override
    public List<BakedQuad> getQuads(final BlockState state, final Direction side,
                                    final RandomSource random, final ModelData data,
                                    final RenderType renderType) {
        // As in Create's FluidTankModel, ownership (not vanilla neighbour face
        // occlusion) decides which complete exterior shell parts remain.
        if (side != null) return List.of();
        final int connected = data.has(CONNECTIONS) ? data.get(CONNECTIONS) : 0;
        final List<BakedQuad> result = new ArrayList<>();
        for (final Direction bucket : Direction.values()) {
            append(result, super.getQuads(state, bucket, random, data, renderType), connected);
        }
        append(result, super.getQuads(state, null, random, data, renderType), connected);
        return result;
    }

    private void append(final List<BakedQuad> target, final List<BakedQuad> source,
                        final int connected) {
        for (final BakedQuad quad : source) {
            final int tag = quad.getTintIndex();
            if ((tag & ~0xfff) != TAG) {
                target.add(quad);
                continue;
            }
            final int hide = tag & DIRECTIONS;
            final int requireAny = (tag >> 6) & DIRECTIONS;
            if ((hide & connected) != 0
                    || (requireAny != 0 && (requireAny & connected) == 0)) continue;
            // CT emits fresh quads per call; do not retain them in an identity cache.
            target.add(new BakedQuad(Arrays.copyOf(quad.getVertices(), quad.getVertices().length),
                    -1, quad.getDirection(), quad.getSprite(), quad.isShade()));
        }
    }

}
