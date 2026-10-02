package io.github.emanuelececcon.livemaps;

import java.util.Set;
import java.util.UUID;
import org.bukkit.map.MapView;

/**
 * Where the 128x128 pixels of a map lie in the world. Uses the integer maths of vanilla
 * {@code MapItem.update}, including Java's truncating division of the centre by the scale.
 *
 * @param scale blocks per pixel side: 1, 2, 4, 8 or 16
 */
record MapArea(UUID worldId, int centerX, int centerZ, int scale) {

    static final int SIZE = 128;

    /** The area of a map whose world is loaded. */
    static MapArea of(MapView view) {
        return new MapArea(view.getWorld().getUID(), view.getCenterX(), view.getCenterZ(),
                1 << view.getScale().getValue());
    }

    /** World X of the first block column drawn into pixel column {@code pixelX}. */
    int blockX(int pixelX) {
        return (centerX / scale + pixelX - SIZE / 2) * scale;
    }

    /** World Z of the first block row drawn into pixel row {@code pixelZ}. */
    int blockZ(int pixelZ) {
        return (centerZ / scale + pixelZ - SIZE / 2) * scale;
    }

    int pixelX(int blockX) {
        return Math.floorDiv(blockX - blockX(0), scale);
    }

    int pixelZ(int blockZ) {
        return Math.floorDiv(blockZ - blockZ(0), scale);
    }

    /**
     * True when the block column is drawn on the map or shades its first row: every pixel is
     * shaded against the one north of it, so the row just north of the map counts too.
     */
    boolean shows(int blockX, int blockZ) {
        int pixelX = pixelX(blockX);
        int pixelZ = pixelZ(blockZ);
        return pixelX >= 0 && pixelX < SIZE && pixelZ >= -1 && pixelZ < SIZE;
    }

    boolean showsChunk(int chunkX, int chunkZ) {
        return (chunkX << 4) + 15 >= blockX(0) && chunkX << 4 < blockX(SIZE)
                && (chunkZ << 4) + 15 >= blockZ(-1) && chunkZ << 4 < blockZ(SIZE);
    }

    /** The pixels whose colour can change when blocks inside the box change; null when none can. */
    PixelRect affected(int minX, int minZ, int maxX, int maxZ) {
        int x0 = Math.max(0, pixelX(minX));
        int x1 = Math.min(SIZE - 1, pixelX(maxX));
        int z0 = Math.max(0, pixelZ(minZ));
        // The row south of a change is shaded against it, so it can change too.
        int z1 = Math.min(SIZE - 1, pixelZ(maxZ) + 1);
        return x0 > x1 || z0 > z1 ? null : new PixelRect(x0, z0, x1, z1);
    }

    PixelRect affectedByChunk(int chunkX, int chunkZ) {
        return affected(chunkX << 4, chunkZ << 4, (chunkX << 4) + 15, (chunkZ << 4) + 15);
    }

    /** Adds the keys of every chunk needed to draw the pixels, including the row north of them. */
    void chunksFor(PixelRect rect, Set<Long> out) {
        int minChunkX = blockX(rect.x0()) >> 4;
        int maxChunkX = (blockX(rect.x1() + 1) - 1) >> 4;
        int minChunkZ = blockZ(rect.z0() - 1) >> 4;
        int maxChunkZ = (blockZ(rect.z1() + 1) - 1) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                out.add(chunkKey(chunkX, chunkZ));
            }
        }
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }
}
