package io.github.emanuelececcon.livemaps;

import java.util.BitSet;

/**
 * The pixels of one map that wait to be redrawn, grouped in square tiles so that a redraw step
 * takes nearby pixels together and reads each chunk once.
 */
final class DirtyPixels {

    private static final int SIZE = MapArea.SIZE;

    private final int tile;
    private final int tilesPerSide;
    private final BitSet pixels = new BitSet(SIZE * SIZE);
    private final BitSet tiles = new BitSet();

    /** Tiles span about 64 blocks (16 pixels at most), so one tile needs a handful of chunks. */
    DirtyPixels(int scale) {
        this.tile = Math.clamp(64 / scale, 4, 16);
        this.tilesPerSide = SIZE / tile;
    }

    void mark(PixelRect rect) {
        for (int z = rect.z0(); z <= rect.z1(); z++) {
            pixels.set(z * SIZE + rect.x0(), z * SIZE + rect.x1() + 1);
        }
        for (int tileZ = rect.z0() / tile; tileZ <= rect.z1() / tile; tileZ++) {
            for (int tileX = rect.x0() / tile; tileX <= rect.x1() / tile; tileX++) {
                tiles.set(tileZ * tilesPerSide + tileX);
            }
        }
    }

    void clear(PixelRect rect) {
        for (int z = rect.z0(); z <= rect.z1(); z++) {
            pixels.clear(z * SIZE + rect.x0(), z * SIZE + rect.x1() + 1);
        }
        for (int tileZ = rect.z0() / tile; tileZ <= rect.z1() / tile; tileZ++) {
            for (int tileX = rect.x0() / tile; tileX <= rect.x1() / tile; tileX++) {
                int index = tileZ * tilesPerSide + tileX;
                if (bounds(index) == null) {
                    tiles.clear(index);
                }
            }
        }
    }

    void clearTile(int index) {
        int x0 = (index % tilesPerSide) * tile;
        int z0 = (index / tilesPerSide) * tile;
        clear(new PixelRect(x0, z0, x0 + tile - 1, z0 + tile - 1));
    }

    void clearAll() {
        pixels.clear();
        tiles.clear();
    }

    boolean isEmpty() {
        return tiles.isEmpty();
    }

    int count() {
        return pixels.cardinality();
    }

    /** The next tile with dirty pixels at or after {@code from}, or -1. */
    int nextTile(int from) {
        return tiles.nextSetBit(from);
    }

    /** The smallest rectangle holding every dirty pixel of the tile, or null when it has none. */
    PixelRect bounds(int index) {
        int x0 = (index % tilesPerSide) * tile;
        int z0 = (index / tilesPerSide) * tile;
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int minZ = -1;
        int maxZ = -1;
        for (int z = z0; z < z0 + tile; z++) {
            int rowStart = z * SIZE + x0;
            int first = pixels.nextSetBit(rowStart);
            if (first < 0 || first >= rowStart + tile) {
                continue;
            }
            int last = pixels.previousSetBit(rowStart + tile - 1);
            minX = Math.min(minX, first - z * SIZE);
            maxX = Math.max(maxX, last - z * SIZE);
            if (minZ < 0) {
                minZ = z;
            }
            maxZ = z;
        }
        return maxZ < 0 ? null : new PixelRect(minX, minZ, maxX, maxZ);
    }
}
