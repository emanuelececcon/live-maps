package io.github.emanuelececcon.livemaps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

/**
 * /livemaps fill and /livemaps scale. Fill puts new maps into the empty frames of a wall so that
 * they join up seamlessly: next to existing maps the new ones continue their grid, which also
 * extends walls made by other plugins; on a wall without maps the grid is centred on the player.
 * Scale replaces the maps of a wall with maps of another scale around the same centre.
 */
final class WallFiller {

    /** How far from the frame looked at the wall is searched, in blocks. */
    private static final double SEARCH = 32;
    /** A rescale that has to read more chunks than this asks for "confirm" first. */
    private static final long CONFIRM_CHUNKS = 100_000;

    private final LiveMapsPlugin plugin;

    WallFiller(LiveMapsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Fills the empty frames connected to {@code target}; returns the message for the player. */
    Component fill(Player player, ItemFrame target, Integer requestedScale) {
        if (!isWall(target.getFacing())) {
            return error("Map walls are made of frames on a wall, not on a floor or ceiling.");
        }
        Map<Cell, ItemFrame> wall = connectedFrames(target);
        List<Map.Entry<Cell, ItemFrame>> empty = new ArrayList<>();
        Map.Entry<Cell, MapView> anchor = null;
        for (Map.Entry<Cell, ItemFrame> entry : wall.entrySet()) {
            ItemStack item = entry.getValue().getItem();
            if (item.isEmpty()) {
                empty.add(entry);
            } else if (anchor == null && isPlainMap(item)) {
                // Frames are in search order, so this is the map closest to the frame looked at.
                anchor = Map.entry(entry.getKey(), FrameRegistry.mapView(item));
            }
        }
        if (empty.isEmpty()) {
            return error("Every frame of this wall already holds something. Hang empty frames first.");
        }
        int maxFrames = plugin.settings().fillMaxFrames();
        if (empty.size() > maxFrames) {
            return error("This wall has " + empty.size() + " empty frames, more than the limit of " + maxFrames
                    + " (fill.max-frames in config.yml).");
        }

        Grid grid;
        if (anchor != null) {
            MapView view = anchor.getValue();
            int scale = view.getScale().getValue();
            if (requestedScale != null && requestedScale != scale) {
                return error("The maps on this wall are scale " + scale + " (1:" + (1 << scale)
                        + "). Leave the scale out to continue them.");
            }
            grid = new Grid(view.getWorld(), scale, view.isTrackingPosition(), 0, 0, 0, 0)
                    .through(anchor.getKey(), view.getCenterX(), view.getCenterZ());
        } else {
            int scale = requestedScale == null ? 0 : requestedScale;
            World world = player.getWorld();
            String problem = world.hasCeiling() ? "Maps never draw a dimension with a ceiling, like the Nether."
                    : plugin.settings().worldDisabled(world.getName()) ? "LiveMaps is turned off in this world." : null;
            if (problem != null) {
                return error(problem);
            }
            // The middle of the wall shows the player's position (between two maps on even sizes).
            Bounds bounds = Bounds.of(wall.keySet());
            Location at = player.getLocation();
            grid = new Grid(world, scale, true, at.getBlockX(), at.getBlockZ(), bounds.columnSum(), bounds.rowSum());
        }
        if (grid.world() == null) {
            return error("The world of the maps on this wall is not loaded.");
        }

        for (Map.Entry<Cell, ItemFrame> entry : empty) {
            placeMap(entry.getValue(), grid, entry.getKey());
        }
        String how = anchor != null ? "continuing map #" + anchor.getValue().getId()
                : "centred on you (" + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockZ() + ")";
        return Component.text("Put " + empty.size() + " new map" + (empty.size() == 1 ? "" : "s") + " (scale 1:"
                + (1 << grid.scale()) + ") in the empty frames, " + how + ". They are drawn in a few seconds.",
                NamedTextColor.GREEN);
    }

    /**
     * Replaces the maps of the wall with maps of {@code scale} showing the same centre, and fills
     * its empty frames too. The old maps are dropped from the frames (their saved data stays, so
     * copies elsewhere keep their picture but no longer update).
     */
    Component rescale(ItemFrame target, int scale, boolean confirmed) {
        if (!isWall(target.getFacing())) {
            return error("Map walls are made of frames on a wall, not on a floor or ceiling.");
        }
        Map<Cell, ItemFrame> wall = connectedFrames(target);
        List<Map.Entry<Cell, ItemFrame>> replace = new ArrayList<>();
        Map.Entry<Cell, MapView> anchor = null;
        int locked = 0;
        for (Map.Entry<Cell, ItemFrame> entry : wall.entrySet()) {
            ItemStack item = entry.getValue().getItem();
            if (item.isEmpty()) {
                replace.add(entry);
            } else if (isPlainMap(item)) {
                MapView view = FrameRegistry.mapView(item);
                if (view.isLocked()) {
                    locked++;
                    continue;
                }
                replace.add(entry);
                if (anchor == null) {
                    anchor = Map.entry(entry.getKey(), view);
                }
            }
        }
        if (anchor == null) {
            return error("There are no maps on this wall. Use /livemaps fill " + scale + " to make some.");
        }
        if (locked > 0) {
            return error("This wall holds " + locked + " locked map" + (locked == 1 ? "" : "s")
                    + ", which LiveMaps never replaces. Take " + (locked == 1 ? "it" : "them") + " out first.");
        }
        MapView view = anchor.getValue();
        int oldScale = view.getScale().getValue();
        if (oldScale == scale) {
            return error("The maps on this wall are already scale " + scale + " (1:" + (1 << scale) + ").");
        }

        Bounds bounds = Bounds.of(wall.keySet());
        Grid grid = Grid.rescaled(new Grid(view.getWorld(), oldScale, view.isTrackingPosition(), 0, 0, 0, 0)
                .through(anchor.getKey(), view.getCenterX(), view.getCenterZ()), bounds, scale);

        long width = (long) bounds.columns() * (MapArea.SIZE << scale);
        long height = (long) bounds.rows() * (MapArea.SIZE << scale);
        long chunks = width * height / 256;
        if (chunks > CONFIRM_CHUNKS && !confirmed) {
            return error("At scale " + scale + " this wall would show " + width + " x " + height + " blocks: about "
                    + chunks + " chunks to read, which can take a long while (only generated ones are drawn). Run"
                    + " /livemaps scale " + scale + " confirm to go ahead.");
        }
        for (Map.Entry<Cell, ItemFrame> entry : replace) {
            placeMap(entry.getValue(), grid, entry.getKey());
        }
        return Component.text("Replaced " + replace.size() + " maps: scale 1:" + (1 << oldScale) + " -> 1:"
                + (1 << scale) + ", " + width + " x " + height + " blocks centred on " + grid.originX() + ", "
                + grid.originZ()
                + ". They are drawn in the background.", NamedTextColor.GREEN);
    }

    /** Puts a new map of the grid cell into the frame and starts following it, which draws it in full. */
    private void placeMap(ItemFrame frame, Grid grid, Cell cell) {
        MapView view = Bukkit.createMap(grid.world());
        view.setScale(MapView.Scale.valueOf((byte) grid.scale()));
        view.setCenterX(grid.centerX(cell));
        view.setCenterZ(grid.centerZ(cell));
        view.setTrackingPosition(grid.tracking());
        view.setUnlimitedTracking(false);
        ItemStack item = ItemStack.of(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        frame.setItem(item, false);
        frame.setRotation(Rotation.NONE);
        plugin.registry().observe(frame);
    }

    /** Frames on the same wall as {@code origin}, reachable through side-by-side frames, nearest first. */
    private static Map<Cell, ItemFrame> connectedFrames(ItemFrame origin) {
        Map<Cell, ItemFrame> candidates = new HashMap<>();
        for (ItemFrame frame : origin.getWorld().getNearbyEntitiesByType(ItemFrame.class, origin.getLocation(), SEARCH)) {
            if (frame.getFacing() == origin.getFacing() && samePlane(origin, frame)) {
                candidates.put(cellOf(origin, frame), frame);
            }
        }
        Map<Cell, ItemFrame> wall = new LinkedHashMap<>();
        Queue<Cell> queue = new ArrayDeque<>();
        Cell start = new Cell(0, 0);
        wall.put(start, origin);
        queue.add(start);
        while (!queue.isEmpty()) {
            Cell cell = queue.remove();
            for (Cell next : List.of(new Cell(cell.row() + 1, cell.column()), new Cell(cell.row() - 1, cell.column()),
                    new Cell(cell.row(), cell.column() + 1), new Cell(cell.row(), cell.column() - 1))) {
                ItemFrame frame = candidates.get(next);
                if (frame != null && !wall.containsKey(next)) {
                    wall.put(next, frame);
                    queue.add(next);
                }
            }
        }
        return wall;
    }

    /** A map item LiveMaps draws: its centre and scale define the grid of the wall. */
    private static boolean isPlainMap(ItemStack item) {
        if (item.getType() != Material.FILLED_MAP) {
            return false;
        }
        MapView view = FrameRegistry.mapView(item);
        return view != null && view.getWorld() != null;
    }

    private static boolean isWall(BlockFace facing) {
        return facing == BlockFace.NORTH || facing == BlockFace.SOUTH || facing == BlockFace.EAST
                || facing == BlockFace.WEST;
    }

    private static boolean samePlane(ItemFrame first, ItemFrame second) {
        return switch (first.getFacing()) {
            case NORTH, SOUTH -> first.getLocation().getBlockZ() == second.getLocation().getBlockZ();
            default -> first.getLocation().getBlockX() == second.getLocation().getBlockX();
        };
    }

    /**
     * Row (up is positive) and column (right is positive, as seen from the front) of a frame
     * relative to {@code origin}. A map in a frame always shows north up and east to the right.
     */
    private static Cell cellOf(ItemFrame origin, ItemFrame frame) {
        int dx = frame.getLocation().getBlockX() - origin.getLocation().getBlockX();
        int dz = frame.getLocation().getBlockZ() - origin.getLocation().getBlockZ();
        int column = switch (origin.getFacing()) {
            case SOUTH -> dx;
            case NORTH -> -dx;
            case WEST -> dz;
            default -> -dz;
        };
        return new Cell(frame.getLocation().getBlockY() - origin.getLocation().getBlockY(), column);
    }

    private static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    record Cell(int row, int column) { }

    /** The rows and columns a wall spans; the sums locate its middle in half cells. */
    record Bounds(int minRow, int maxRow, int minColumn, int maxColumn) {

        static Bounds of(Iterable<Cell> cells) {
            int minRow = Integer.MAX_VALUE;
            int maxRow = Integer.MIN_VALUE;
            int minColumn = Integer.MAX_VALUE;
            int maxColumn = Integer.MIN_VALUE;
            for (Cell cell : cells) {
                minRow = Math.min(minRow, cell.row());
                maxRow = Math.max(maxRow, cell.row());
                minColumn = Math.min(minColumn, cell.column());
                maxColumn = Math.max(maxColumn, cell.column());
            }
            return new Bounds(minRow, maxRow, minColumn, maxColumn);
        }

        int rowSum() {
            return minRow + maxRow;
        }

        int columnSum() {
            return minColumn + maxColumn;
        }

        int rows() {
            return maxRow - minRow + 1;
        }

        int columns() {
            return maxColumn - minColumn + 1;
        }
    }

    /**
     * Map centres of a wall: the map in column c, row r is centred at
     * {@code (originX + (2c - columnSum) * width / 2, originZ - (2r - rowSum) * width / 2)}.
     * The sums are 0 when the origin is a cell, and min + max when it is the middle of the wall.
     */
    record Grid(World world, int scale, boolean tracking, int originX, int originZ,
                        int columnSum, int rowSum) {

        int centerX(Cell cell) {
            return originX + (2 * cell.column() - columnSum) * (MapArea.SIZE << scale) / 2;
        }

        /** The same kind of grid, placed so that {@code cell} holds the map centred at x, z. */
        Grid through(Cell cell, int x, int z) {
            int width = MapArea.SIZE << scale;
            return new Grid(world, scale, tracking, x - cell.column() * width, z + cell.row() * width, 0, 0);
        }

        /** A grid of {@code scale} centred on the middle of the wall drawn with {@code old}. */
        static Grid rescaled(Grid old, Bounds bounds, int scale) {
            int middleX = old.centerX(new Cell(0, 0)) + bounds.columnSum() * (MapArea.SIZE << old.scale()) / 2;
            int middleZ = old.centerZ(new Cell(0, 0)) - bounds.rowSum() * (MapArea.SIZE << old.scale()) / 2;
            return new Grid(old.world(), scale, old.tracking(), middleX, middleZ, bounds.columnSum(), bounds.rowSum());
        }

        int centerZ(Cell cell) {
            return originZ - (2 * cell.row() - rowSum) * (MapArea.SIZE << scale) / 2;
        }
    }
}
