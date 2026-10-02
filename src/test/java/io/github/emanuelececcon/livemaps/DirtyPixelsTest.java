package io.github.emanuelececcon.livemaps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DirtyPixelsTest {

    @Test
    void aSmallChangeIsRedrawnAsASmallRectangle() {
        DirtyPixels dirty = new DirtyPixels(1);
        dirty.mark(new PixelRect(20, 33, 20, 34));
        dirty.mark(new PixelRect(25, 40, 25, 40));
        int tile = dirty.nextTile(0);
        assertEquals(2 * 8 + 1, tile); // 16-pixel tiles: column 1, row 2
        assertEquals(new PixelRect(20, 33, 25, 40), dirty.bounds(tile));
        assertEquals(-1, dirty.nextTile(tile + 1));
    }

    @Test
    void aWholeMapSplitsIntoTilesThatClearOneByOne() {
        DirtyPixels dirty = new DirtyPixels(16); // 4-pixel tiles, 32 per side
        dirty.mark(PixelRect.WHOLE_MAP);
        assertEquals(128 * 128, dirty.count());
        assertEquals(new PixelRect(4, 0, 7, 3), dirty.bounds(1));
        dirty.clear(new PixelRect(0, 0, 3, 3));
        assertEquals(1, dirty.nextTile(0));
        assertNull(dirty.bounds(0));
        dirty.clear(PixelRect.WHOLE_MAP);
        assertTrue(dirty.isEmpty());
    }

    @Test
    void clearingPartOfATileKeepsTheRest() {
        DirtyPixels dirty = new DirtyPixels(1);
        dirty.mark(new PixelRect(0, 0, 15, 15));
        dirty.clear(new PixelRect(0, 0, 15, 7));
        assertEquals(new PixelRect(0, 8, 15, 15), dirty.bounds(0));
    }
}
