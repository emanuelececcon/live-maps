package io.github.emanuelececcon.livemaps;

/**
 * Computes map pixels the way vanilla {@code MapItem.update} (26.3) does when a player carries the
 * map: per pixel the most common colour of its scale x scale block columns, shaded by the height
 * difference to the pixel north of it, or by the depth for water.
 */
final class PixelRenderer {

    /** A pixel left as it is because no terrain is available (chunk never generated). */
    static final int SKIP = -1;

    /** Vanilla MapColor ids the algorithm needs. */
    static final int STONE = 11;
    static final int WATER = 12;

    private static final int LOW = 0;
    private static final int NORMAL = 1;
    private static final int HIGH = 2;
    private static final int COLORS = 64;

    private PixelRenderer() {
    }

    /**
     * Returns the packed colours (base id * 4 + brightness) of the pixels in {@code rect}, row by
     * row, with {@link #SKIP} where nothing is known. The row north of the rectangle is sampled for
     * shading, so any rectangle gives the same pixels as drawing the whole map.
     */
    static int[] render(Terrain terrain, MapArea area, PixelRect rect) {
        int[] pixels = new int[rect.width() * rect.height()];
        Pixel pixel = new Pixel();
        for (int pixelX = rect.x0(); pixelX <= rect.x1(); pixelX++) {
            double northHeight = Double.NaN;
            for (int pixelZ = rect.z0() - 1; pixelZ <= rect.z1(); pixelZ++) {
                boolean known = sample(terrain, area.blockX(pixelX), area.blockZ(pixelZ), area.scale(), pixel);
                if (pixelZ >= rect.z0()) {
                    int index = (pixelZ - rect.z0()) * rect.width() + pixelX - rect.x0();
                    pixels[index] = known
                            ? pixel.color * 4 + brightness(pixel, northHeight, pixelX, pixelZ, area.scale())
                            : SKIP;
                }
                northHeight = known ? pixel.height : Double.NaN;
            }
        }
        return pixels;
    }

    /**
     * Vanilla shading. Without a known pixel to the north (unexplored edge) the pixel is compared
     * with itself, which only leaves the checkerboard dither.
     */
    static int brightness(Pixel pixel, double northHeight, int pixelX, int pixelZ, int scale) {
        int parity = (pixelX + pixelZ) & 1;
        if (pixel.color == WATER) {
            double shade = pixel.depth * 0.1 + parity * 0.2;
            return shade < 0.5 ? HIGH : shade > 0.9 ? LOW : NORMAL;
        }
        double north = Double.isNaN(northHeight) ? pixel.height : northHeight;
        double shade = (pixel.height - north) * 4.0 / (scale + 4) + (parity - 0.5) * 0.4;
        return shade > 0.6 ? HIGH : shade < -0.6 ? LOW : NORMAL;
    }

    /**
     * Samples the scale x scale columns of one pixel; false when none is available. Like vanilla,
     * ties between equally common colours go to the one met first (x outer, z inner), heights add
     * up as y / scale², and water depth is summed over the columns.
     */
    static boolean sample(Terrain terrain, int blockX, int blockZ, int scale, Pixel pixel) {
        pixel.reset();
        Column column = pixel.column;
        int area = scale * scale;
        int samples = 0;
        double height = 0;
        for (int dx = 0; dx < scale; dx++) {
            for (int dz = 0; dz < scale; dz++) {
                if (!column(terrain, blockX + dx, blockZ + dz, column)) {
                    continue;
                }
                samples++;
                height += (double) column.y / area;
                pixel.depth += column.depth;
                pixel.count(column.color);
            }
        }
        if (samples == 0) {
            return false;
        }
        pixel.height = samples == area ? height : height * area / samples;
        pixel.color = pixel.mostCommon();
        return true;
    }

    /** One block column, as vanilla reads it: first block with a map colour from the top. */
    static boolean column(Terrain terrain, int x, int z, Column out) {
        int surface = terrain.surfaceY(x, z);
        if (surface == Terrain.UNAVAILABLE) {
            return false;
        }
        int minY = terrain.minY();
        int y = surface + 1;
        out.depth = 0;
        if (y <= minY) {
            // An empty column: vanilla draws bedrock (stone colour) at the bottom of the world.
            out.y = y;
            out.color = STONE;
            return true;
        }
        int color;
        do {
            y--;
            color = terrain.color(x, y, z);
        } while (color == 0 && y > minY);
        if (y > minY && terrain.fluid(x, y, z)) {
            int below = y - 1;
            boolean fluid;
            do {
                fluid = terrain.fluid(x, below--, z);
                out.depth++;
            } while (below > minY && fluid);
            color = terrain.fluidSurfaceColor(x, y, z);
        }
        out.y = y;
        out.color = color;
        return true;
    }

    static final class Column {
        int y;
        int color;
        int depth;
    }

    /** The colours seen in one pixel, counted in the order they were met. */
    static final class Pixel {
        final Column column = new Column();
        private final int[] counts = new int[COLORS];
        private final int[] firstSeen = new int[COLORS];
        private int seen;
        int color;
        double height;
        int depth;

        void reset() {
            java.util.Arrays.fill(counts, 0);
            seen = 0;
            depth = 0;
        }

        void count(int colorId) {
            int id = colorId & (COLORS - 1);
            if (counts[id]++ == 0) {
                firstSeen[id] = seen++;
            }
        }

        int mostCommon() {
            int best = 0;
            int bestCount = -1;
            int bestSeen = Integer.MAX_VALUE;
            for (int id = 0; id < COLORS; id++) {
                int count = counts[id];
                if (count > bestCount || (count == bestCount && count > 0 && firstSeen[id] < bestSeen)) {
                    best = id;
                    bestCount = count;
                    bestSeen = count > 0 ? firstSeen[id] : Integer.MAX_VALUE;
                }
            }
            return best;
        }
    }
}
