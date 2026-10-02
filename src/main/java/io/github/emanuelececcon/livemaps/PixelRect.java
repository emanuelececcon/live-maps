package io.github.emanuelececcon.livemaps;

/** A rectangle of map pixels, bounds inclusive. */
record PixelRect(int x0, int z0, int x1, int z1) {

    static final PixelRect WHOLE_MAP = new PixelRect(0, 0, MapArea.SIZE - 1, MapArea.SIZE - 1);

    int width() {
        return x1 - x0 + 1;
    }

    int height() {
        return z1 - z0 + 1;
    }
}
