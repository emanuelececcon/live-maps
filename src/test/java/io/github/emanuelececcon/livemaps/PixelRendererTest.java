package io.github.emanuelececcon.livemaps;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class PixelRendererTest {

    private static final int GRASS = 1;
    private static final int PLANT = 7;
    private static final int STONE = PixelRenderer.STONE;
    private static final int WATER = PixelRenderer.WATER;
    private static final int LOW = 0;
    private static final int NORMAL = 1;
    private static final int HIGH = 2;

    @Test
    void flatGroundIsNormalBrightness() {
        FakeTerrain terrain = new FakeTerrain((x, z) -> 64, (x, z) -> GRASS);
        for (int pixel : PixelRenderer.render(terrain, area(1), new PixelRect(0, 0, 3, 3))) {
            assertEquals(GRASS * 4 + NORMAL, pixel);
        }
    }

    @Test
    void risingTerrainIsBrightAndFallingTerrainDark() {
        // One block up per row: 1 * 4 / 5 = 0.8, minus 0.2 for even pixels. In doubles that is
        // 0.6000000000000001, just above vanilla's 0.6 limit, so both dither cells are bright.
        FakeTerrain rising = new FakeTerrain((x, z) -> 64 + z, (x, z) -> STONE);
        for (int pixel : PixelRenderer.render(rising, area(1), new PixelRect(0, 10, 1, 10))) {
            assertEquals(STONE * 4 + HIGH, pixel);
        }
        FakeTerrain falling = new FakeTerrain((x, z) -> 64 - 2 * z, (x, z) -> STONE);
        for (int pixel : PixelRenderer.render(falling, area(1), new PixelRect(0, 10, 1, 10))) {
            assertEquals(STONE * 4 + LOW, pixel);
        }
    }

    @Test
    void halfABlockStepKeepsTheDither() {
        // Scale 2, one column of the pair one block higher: mean height +0.5 -> 0.5 * 4 / 6 = 0.33 +- 0.2.
        FakeTerrain terrain = new FakeTerrain((x, z) -> Math.floorMod(z, 4) < 2 ? 64 : 64 + Math.floorMod(x, 2),
                (x, z) -> STONE);
        int[] pixels = PixelRenderer.render(terrain, area(2), new PixelRect(0, 1, 0, 1));
        assertEquals(STONE * 4 + NORMAL, pixels[0]);
    }

    @Test
    void waterIsShadedByDepthNotHeight() {
        FakeTerrain shallow = new FakeTerrain((x, z) -> 62, (x, z) -> STONE);
        shallow.waterLevel = 63;
        FakeTerrain deep = new FakeTerrain((x, z) -> 50, (x, z) -> STONE);
        deep.waterLevel = 63;
        // One water block: vanilla counts the block under it too, depth 1 -> 0.1 / 0.3, bright.
        for (int pixel : PixelRenderer.render(shallow, area(1), new PixelRect(0, 0, 1, 0))) {
            assertEquals(WATER * 4 + HIGH, pixel);
        }
        // Thirteen water blocks: depth 13 -> 1.3 / 1.5, dark.
        for (int pixel : PixelRenderer.render(deep, area(1), new PixelRect(0, 0, 1, 0))) {
            assertEquals(WATER * 4 + LOW, pixel);
        }
        PixelRenderer.Column column = new PixelRenderer.Column();
        PixelRenderer.column(deep, 0, 0, column);
        assertEquals(13, column.depth);
        assertEquals(63, column.y);
    }

    @Test
    void blocksThatMapsSkipShowWhatIsUnderThem() {
        FakeTerrain terrain = new FakeTerrain((x, z) -> 64, (x, z) -> GRASS);
        terrain.extra.put(new Pos(0, 70, 0), 0); // glass high above the ground
        PixelRenderer.Column column = new PixelRenderer.Column();
        PixelRenderer.column(terrain, 0, 0, column);
        assertEquals(64, column.y);
        assertEquals(GRASS, column.color);
    }

    @Test
    void plantsOnTopAreWhatTheMapShows() {
        FakeTerrain terrain = new FakeTerrain((x, z) -> 64, (x, z) -> GRASS);
        terrain.extra.put(new Pos(0, 65, 0), PLANT); // short grass: no collision, still on WORLD_SURFACE
        PixelRenderer.Column column = new PixelRenderer.Column();
        PixelRenderer.column(terrain, 0, 0, column);
        assertEquals(65, column.y);
        assertEquals(PLANT, column.color);
    }

    @Test
    void anEmptyColumnIsDrawnAsBedrockAtTheBottomLikeVanilla() {
        FakeTerrain terrain = new FakeTerrain((x, z) -> -65, (x, z) -> 0);
        PixelRenderer.Column column = new PixelRenderer.Column();
        PixelRenderer.column(terrain, 0, 0, column);
        assertEquals(STONE, column.color);
        assertEquals(-64, column.y);
    }

    @Test
    void equallyCommonColoursGoToTheFirstColumnMetLikeVanilla() {
        // Scale 2: columns are read x outer, z inner: (0,0) plant, (0,1) grass, (1,0) grass, (1,1) plant.
        FakeTerrain terrain = new FakeTerrain((x, z) -> 64,
                (x, z) -> Math.floorMod(x, 2) == Math.floorMod(z, 2) ? PLANT : GRASS);
        MapArea area = area(2);
        PixelRenderer.Pixel pixel = new PixelRenderer.Pixel();
        PixelRenderer.sample(terrain, area.blockX(0), area.blockZ(0), 2, pixel);
        assertEquals(PLANT, pixel.color);
    }

    @Test
    void ungeneratedTerrainLeavesPixelsUntouched() {
        FakeTerrain terrain = new FakeTerrain((x, z) -> 64, (x, z) -> GRASS);
        terrain.missingFromX = 0;
        int[] pixels = PixelRenderer.render(terrain, area(1), new PixelRect(62, 0, 65, 0));
        assertArrayEquals(new int[] {GRASS * 4 + NORMAL, GRASS * 4 + NORMAL, PixelRenderer.SKIP, PixelRenderer.SKIP},
                pixels);
    }

    @Test
    void anyRectangleGivesTheSamePixelsAsTheWholeMap() {
        FakeTerrain terrain = new FakeTerrain((x, z) -> 60 + Math.floorMod(x * 7 + z * 13, 9),
                (x, z) -> Math.floorMod(x + z, 5) == 0 ? PLANT : GRASS);
        for (int scale : new int[] {1, 2, 4}) {
            MapArea area = area(scale);
            int[] whole = PixelRenderer.render(terrain, area, PixelRect.WHOLE_MAP);
            PixelRect part = new PixelRect(37, 50, 52, 61);
            int[] piece = PixelRenderer.render(terrain, area, part);
            for (int z = part.z0(); z <= part.z1(); z++) {
                for (int x = part.x0(); x <= part.x1(); x++) {
                    assertEquals(whole[z * 128 + x], piece[(z - part.z0()) * part.width() + x - part.x0()]);
                }
            }
        }
    }

    private static MapArea area(int scale) {
        return new MapArea(UUID.randomUUID(), 0, 0, scale);
    }

    private record Pos(int x, int y, int z) { }

    /** Ground of one colour over stone, optional water up to a level, and extra blocks above. */
    private static final class FakeTerrain implements Terrain {
        final IntBinaryOperator ground;
        final IntBinaryOperator topColor;
        final Map<Pos, Integer> extra = new HashMap<>();
        int waterLevel = Integer.MIN_VALUE;
        int missingFromX = Integer.MAX_VALUE;

        FakeTerrain(IntBinaryOperator ground, IntBinaryOperator topColor) {
            this.ground = ground;
            this.topColor = topColor;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int surfaceY(int x, int z) {
            if (x >= missingFromX) {
                return UNAVAILABLE;
            }
            int top = Math.max(ground.applyAsInt(x, z), waterLevel);
            for (Pos pos : extra.keySet()) {
                if (pos.x() == x && pos.z() == z) {
                    top = Math.max(top, pos.y());
                }
            }
            return top;
        }

        @Override
        public int color(int x, int y, int z) {
            Integer block = extra.get(new Pos(x, y, z));
            if (block != null) {
                return block;
            }
            int groundY = ground.applyAsInt(x, z);
            if (y > groundY) {
                return y <= waterLevel ? WATER : 0;
            }
            return y == groundY ? topColor.applyAsInt(x, z) : STONE;
        }

        @Override
        public boolean fluid(int x, int y, int z) {
            return !extra.containsKey(new Pos(x, y, z)) && y > ground.applyAsInt(x, z) && y <= waterLevel;
        }

        @Override
        public int fluidSurfaceColor(int x, int y, int z) {
            return WATER;
        }
    }
}
