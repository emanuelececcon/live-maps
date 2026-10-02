package io.github.emanuelececcon.livemaps;

import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** {@link Terrain} over immutable chunk snapshots, so maps are drawn off the main thread. */
final class ChunkTerrain implements Terrain {

    private final Map<Long, ChunkSnapshot> chunks;
    private final int minY;
    private final int sections;
    private final BiomeTints tints;
    private long lastKey = Long.MIN_VALUE;
    private ChunkSnapshot last;

    /** {@code tints} is null when biome colours are off; then the snapshots need no biomes. */
    ChunkTerrain(Map<Long, ChunkSnapshot> chunks, int minY, int maxY, BiomeTints tints) {
        this.chunks = chunks;
        this.minY = minY;
        this.sections = (maxY - minY) >> 4;
        this.tints = tints;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int surfaceY(int x, int z) {
        ChunkSnapshot chunk = chunk(x, z);
        if (chunk == null) {
            return UNAVAILABLE;
        }
        // Snapshots only carry the MOTION_BLOCKING heightmap, which skips grass, flowers and other
        // blocks without collision. Maps use WORLD_SURFACE, so find the top block directly.
        int localX = x & 15;
        int localZ = z & 15;
        for (int section = sections - 1; section >= 0; section--) {
            if (chunk.isSectionEmpty(section)) {
                continue;
            }
            int bottom = minY + (section << 4);
            for (int y = bottom + 15; y >= bottom; y--) {
                if (!chunk.getBlockType(localX, y, localZ).isAir()) {
                    return y;
                }
            }
        }
        return minY - 1;
    }

    @Override
    public int color(int x, int y, int z) {
        ChunkSnapshot chunk = chunk(x, z);
        BlockData data = chunk.getBlockData(x & 15, y, z & 15);
        return tinted(chunk, data, x, y, z, MapColors.color(data));
    }

    @Override
    public boolean fluid(int x, int y, int z) {
        return MapColors.fluid(chunk(x, z).getBlockData(x & 15, y, z & 15));
    }

    @Override
    public int fluidSurfaceColor(int x, int y, int z) {
        ChunkSnapshot chunk = chunk(x, z);
        BlockData data = chunk.getBlockData(x & 15, y, z & 15);
        return MapColors.showsFluid(data) ? MapColors.fluidColor(data)
                : tinted(chunk, data, x, y, z, MapColors.color(data));
    }

    private int tinted(ChunkSnapshot chunk, BlockData data, int x, int y, int z, int color) {
        if (tints == null || color == 0) {
            return color;
        }
        Material type = data.getMaterial();
        return BiomeTints.tintable(type) ? tints.tint(type, chunk.getBiome(x & 15, y, z & 15), color) : color;
    }

    private ChunkSnapshot chunk(int x, int z) {
        long key = MapArea.chunkKey(x >> 4, z >> 4);
        if (key != lastKey) {
            last = chunks.get(key);
            lastKey = key;
        }
        return last;
    }
}
