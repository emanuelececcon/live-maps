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
 * /livemaps fill: puts new maps into the empty frames of a wall so that they join up seamlessly.
 * Next to existing maps the new ones continue their grid, which also extends walls made by other
 * plugins; on a wall without maps the grid is centred on the player.
 */
final class WallFiller {

    /** How far from the frame looked at the wall is searched, in blocks. */
    private static final double SEARCH = 32;

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
            int width = MapArea.SIZE << scale;
            Cell cell = anchor.getKey();
            grid = new Grid(view.getWorld(), scale, view.isTrackingPosition(),
                    view.getCenterX() - cell.column() * width, view.getCenterZ() + cell.row() * width, 0, 0);
        } else {
            int scale = requestedScale == null ? 0 : requestedScale;
            World world = player.getWorld();
            String problem = world.hasCeiling() ? "Maps never draw a dimension with a ceiling, like the Nether."
                    : plugin.settings().worldDisabled(world.getName()) ? "LiveMaps is turned off in this world." : null;
            if (problem != null) {
                return error(problem);
            }
            // The middle of the wall shows the player's position (between two maps on even sizes).
            int minRow = Integer.MAX_VALUE;
            int maxRow = Integer.MIN_VALUE;
            int minColumn = Integer.MAX_VALUE;
            int maxColumn = Integer.MIN_VALUE;
            for (Cell cell : wall.keySet()) {
                minRow = Math.min(minRow, cell.row());
                maxRow = Math.max(maxRow, cell.row());
                minColumn = Math.min(minColumn, cell.column());
                maxColumn = Math.max(maxColumn, cell.column());
            }
            Location at = player.getLocation();
            grid = new Grid(world, scale, true, at.getBlockX(), at.getBlockZ(), minColumn + maxColumn, minRow + maxRow);
        }
        if (grid.world() == null) {
            return error("The world of the maps on this wall is not loaded.");
        }

        for (Map.Entry<Cell, ItemFrame> entry : empty) {
            ItemFrame frame = entry.getValue();
            MapView view = Bukkit.createMap(grid.world());
            view.setScale(MapView.Scale.valueOf((byte) grid.scale()));
            view.setCenterX(grid.centerX(entry.getKey()));
            view.setCenterZ(grid.centerZ(entry.getKey()));
            view.setTrackingPosition(grid.tracking());
            view.setUnlimitedTracking(false);
            ItemStack item = ItemStack.of(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            meta.setMapView(view);
            item.setItemMeta(meta);
            frame.setItem(item, false);
            frame.setRotation(Rotation.NONE);
            plugin.registry().observe(frame); // starts following it and draws it in full
        }
        String how = anchor != null ? "continuing map #" + anchor.getValue().getId()
                : "centred on you (" + player.getLocation().getBlockX() + ", " + player.getLocation().getBlockZ() + ")";
        return Component.text("Put " + empty.size() + " new map" + (empty.size() == 1 ? "" : "s") + " (scale 1:"
                + (1 << grid.scale()) + ") in the empty frames, " + how + ". They are drawn in a few seconds.",
                NamedTextColor.GREEN);
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

        int centerZ(Cell cell) {
            return originZ - (2 * cell.row() - rowSum) * (MapArea.SIZE << scale) / 2;
        }
    }
}
