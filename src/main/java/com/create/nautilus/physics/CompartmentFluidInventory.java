package com.create.nautilus.physics;

import com.create.nautilus.config.NautilusConfig;
import com.create.nautilus.data.CarrierState;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Measures real Minecraft water retained on a physical carrier.
 *
 * <p>Real water and Nautilus compartment water are deliberately different state. Compartment
 * water remains a continuous Attachment quantity in mB because flooding, pumps and the local
 * free-surface renderer need sub-block precision. A real water source is already a one-cubic-metre
 * world object, so converting it to 1000 mB and merging it into the Attachment would spread one
 * local source across the whole room and move the synthetic waterline. Instead, each source is
 * represented directly by a mass at its own block position.</p>
 *
 * <p>This scanner is read-only: it never places, removes or replaces fluids. Create pipes, pumps,
 * hose pulleys, boilers and vanilla infinite-water rules therefore continue to see ordinary water
 * blocks. Flowing states are not independent stored mass; counting them would let one source fan
 * out into several cubic metres. Waterlogged blocks are also excluded because their block state is
 * not a full liquid cube.</p>
 */
public final class CompartmentFluidInventory {
    private static final Map<ServerSubLevel, CachedSnapshot> CACHE = new WeakHashMap<>();

    private CompartmentFluidInventory() {
    }

    /** Samples the carrier plot at most once per game tick, even with several physics sub-steps. */
    public static Snapshot measure(final ServerSubLevel carrier, final CarrierState state) {
        final long gameTime = carrier.getLevel().getGameTime();
        synchronized (CACHE) {
            final CachedSnapshot cached = CACHE.get(carrier);
            if (cached != null && cached.gameTime() == gameTime
                    && cached.topologyRevision() == state.topologyRevision()) {
                return cached.snapshot();
            }
        }

        final Map<Long, SourceWater> sources = new LinkedHashMap<>();
        double totalNativeMass = 0.0D;
        final BoundingBox3ic bounds = carrier.getPlot().getBoundingBox();
        if (bounds != null) {
            final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            final double nativeMassPerSource = NautilusConfig.WATER_DENSITY / 1000.0D;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        final BlockPos pos = cursor.set(x, y, z);
                        final BlockState block = carrier.getLevel().getBlockState(pos);
                        final FluidState fluid = block.getFluidState();
                        if (!block.liquid() || !fluid.is(FluidTags.WATER)
                                || !fluid.isSource()) {
                            continue;
                        }

                        final BlockPos immutable = pos.immutable();
                        final SourceWater source = new SourceWater(
                                immutable, 1.0D, nativeMassPerSource);
                        sources.put(immutable.asLong(), source);
                        totalNativeMass += nativeMassPerSource;
                    }
                }
            }
        }

        final Snapshot measured = new Snapshot(Map.copyOf(sources), totalNativeMass);
        synchronized (CACHE) {
            CACHE.put(carrier, new CachedSnapshot(
                    gameTime, state.topologyRevision(), measured));
        }
        return measured;
    }

    /** One full source-water voxel in carrier-local coordinates. */
    public record SourceWater(BlockPos position,
                              double volumeCubicMetres,
                              double nativeMass) {
    }

    /** Immutable per-tick snapshot shared by mass and buoyancy calculations. */
    public record Snapshot(Map<Long, SourceWater> sources,
                           double totalNativeMass) {
        public boolean containsSource(final BlockPos position) {
            return sources.containsKey(position.asLong());
        }

        public int sourceCount() {
            return sources.size();
        }
    }

    private record CachedSnapshot(long gameTime,
                                  long topologyRevision,
                                  Snapshot snapshot) {
    }
}
