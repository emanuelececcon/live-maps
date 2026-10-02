package io.github.emanuelececcon.livemaps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapAreaTest {

    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void scaleOneMapStartsHalfAMapWestAndNorthOfItsCentre() {
        MapArea area = new MapArea(WORLD, -216, -353, 1);
        assertEquals(-280, area.blockX(0));
        assertEquals(-153, area.blockX(127));
        assertEquals(-417, area.blockZ(0));
        assertEquals(0, area.pixelX(-280));
        assertEquals(127, area.pixelZ(-290));
    }

    @Test
    void centreIsDividedLikeVanillaTruncatingTowardsZero() {
        // -63 / 2 is -31 in Java (not -32), so the first column is (-31 - 64) * 2.
        MapArea area = new MapArea(WORLD, -63, 0, 2);
        assertEquals(-190, area.blockX(0));
        assertEquals(0, area.pixelX(-190));
        assertEquals(0, area.pixelX(-189));
        assertEquals(-1, area.pixelX(-191));
    }

    @Test
    void theRowNorthOfTheMapCountsBecauseItShadesRowZero() {
        MapArea area = new MapArea(WORLD, 0, 0, 1);
        assertTrue(area.shows(0, -65));
        assertFalse(area.shows(0, -66));
        assertFalse(area.shows(64, 0));
        assertEquals(new PixelRect(64, 0, 64, 0), area.affected(0, -65, 0, -65));
    }

    @Test
    void aChangeAlsoTouchesThePixelSouthOfIt() {
        MapArea area = new MapArea(WORLD, 0, 0, 1);
        assertEquals(new PixelRect(64, 64, 64, 65), area.affected(0, 0, 0, 0));
        assertEquals(new PixelRect(0, 127, 0, 127), area.affected(-64, 63, -64, 63));
        assertNull(area.affected(64, 0, 70, 0));
    }

    @Test
    void wholeScaleOneMapNeedsItsChunksPlusTheRowNorthOfIt() {
        MapArea area = new MapArea(WORLD, 0, 0, 1);
        Set<Long> chunks = new HashSet<>();
        area.chunksFor(PixelRect.WHOLE_MAP, chunks);
        assertEquals(8 * 9, chunks.size());
        assertTrue(chunks.contains(MapArea.chunkKey(-4, -5)));
        assertTrue(chunks.contains(MapArea.chunkKey(3, 3)));
    }

    @Test
    void chunkKeysRoundTripNegativeCoordinates() {
        long key = MapArea.chunkKey(-17, 42);
        assertEquals(-17, MapArea.chunkX(key));
        assertEquals(42, MapArea.chunkZ(key));
        assertEquals(-3, MapArea.chunkZ(MapArea.chunkKey(5, -3)));
    }
}
