package io.github.emanuelececcon.livemaps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.emanuelececcon.livemaps.WallFiller.Cell;
import io.github.emanuelececcon.livemaps.WallFiller.Grid;
import org.junit.jupiter.api.Test;

class WallGridTest {

    @Test
    void newMapsContinueTheGridOfTheNearestMap() {
        // Wall "main" made by MapRevealer: map 17 sits in row 3, column -2, centred at -216, -353.
        int width = 128;
        Grid grid = new Grid(null, 0, false, -216 - (-2) * width, -353 + 3 * width, 0, 0);
        assertEquals(-216, grid.centerX(new Cell(3, -2)));
        assertEquals(-353, grid.centerZ(new Cell(3, -2)));
        // Its neighbours from walls.yml: column -1 -> x -88, row -1 (bottom) -> z 159.
        assertEquals(-88, grid.centerX(new Cell(3, -1)));
        assertEquals(159, grid.centerZ(new Cell(-1, 0)));
        // A new column on the right and a new row below.
        assertEquals(424, grid.centerX(new Cell(0, 3)));
        assertEquals(287, grid.centerZ(new Cell(-2, 0)));
    }

    @Test
    void aNewWallIsCentredOnThePlayer() {
        // Three columns (-1..1) and two rows (0..1) around a player at 100, 200.
        Grid grid = new Grid(null, 0, true, 100, 200, -1 + 1, 0 + 1);
        assertEquals(100, grid.centerX(new Cell(0, 0)));
        assertEquals(-28, grid.centerX(new Cell(0, -1)));
        // The player stands on the line between the two rows: the upper row is the northern one.
        assertEquals(136, grid.centerZ(new Cell(1, 0)));
        assertEquals(264, grid.centerZ(new Cell(0, 0)));
    }

    @Test
    void rescalingKeepsTheMiddleOfTheWall() {
        // Wall "main": columns -2..2, rows -1..3, map 17 at row 3, column -2 centred at -216, -353.
        Grid main = new Grid(null, 0, false, 0, 0, 0, 0).through(new Cell(3, -2), -216, -353);
        WallFiller.Bounds bounds = new WallFiller.Bounds(-1, 3, -2, 2);
        Grid zoomedOut = Grid.rescaled(main, bounds, 1);
        // The middle map of "main" (row 1, column 0) is centred at 40, -97 and stays the middle.
        assertEquals(40, main.centerX(new Cell(1, 0)));
        assertEquals(-97, main.centerZ(new Cell(1, 0)));
        assertEquals(40, zoomedOut.centerX(new Cell(1, 0)));
        assertEquals(-97, zoomedOut.centerZ(new Cell(1, 0)));
        // Neighbours are now 256 blocks apart, so the wall spans 1280 blocks instead of 640.
        assertEquals(40 + 2 * 256, zoomedOut.centerX(new Cell(1, 2)));
        assertEquals(-97 - 2 * 256, zoomedOut.centerZ(new Cell(3, 0)));
        // Back to scale 0 gives the original maps.
        Grid back = Grid.rescaled(zoomedOut.through(new Cell(1, 0), 40, -97), bounds, 0);
        assertEquals(-216, back.centerX(new Cell(3, -2)));
        assertEquals(-353, back.centerZ(new Cell(3, -2)));
    }

    @Test
    void anEvenWallKeepsItsMiddleBetweenTwoMaps() {
        // 16 x 16 wall (columns -8..7, rows -7..8) of "big": middle between columns -1 and 0.
        Grid big = new Grid(null, 0, false, 0, 0, 0, 0).through(new Cell(8, -8), -931, -984);
        WallFiller.Bounds bounds = new WallFiller.Bounds(-7, 8, -8, 7);
        Grid zoomedOut = Grid.rescaled(big, bounds, 1);
        int middleOld = (big.centerX(new Cell(0, -1)) + big.centerX(new Cell(0, 0))) / 2;
        int middleNew = (zoomedOut.centerX(new Cell(0, -1)) + zoomedOut.centerX(new Cell(0, 0))) / 2;
        assertEquals(middleOld, middleNew);
        assertEquals(256, zoomedOut.centerX(new Cell(0, 0)) - zoomedOut.centerX(new Cell(0, -1)));
    }

    @Test
    void zoomedOutMapsAreWider() {
        Grid grid = new Grid(null, 2, true, 0, 0, 0, 0);
        assertEquals(512, grid.centerX(new Cell(0, 1)));
        assertEquals(-512, grid.centerZ(new Cell(1, 0)));
    }
}
