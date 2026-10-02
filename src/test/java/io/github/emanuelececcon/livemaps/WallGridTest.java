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
    void zoomedOutMapsAreWider() {
        Grid grid = new Grid(null, 2, true, 0, 0, 0, 0);
        assertEquals(512, grid.centerX(new Cell(0, 1)));
        assertEquals(-512, grid.centerZ(new Cell(1, 0)));
    }
}
