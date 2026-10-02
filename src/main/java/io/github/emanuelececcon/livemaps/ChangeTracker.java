package io.github.emanuelececcon.livemaps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Turns terrain changes into dirty map pixels. Three sources: block events (redrawn after the
 * short delay), newly generated chunks, and a slow sweep over every loaded chunk that a framed map
 * shows, which catches changes that fire no event. Main thread only.
 */
final class ChangeTracker {

    private final LiveMapsPlugin plugin;
    private final FrameRegistry registry;
    private final RenderEngine engine;
    private final ArrayDeque<Pending> sweep = new ArrayDeque<>();
    private long nextSweep;
    private int sweepPerSecond;

    ChangeTracker(LiveMapsPlugin plugin, FrameRegistry registry, RenderEngine engine) {
        this.plugin = plugin;
        this.registry = registry;
        this.engine = engine;
    }

    /** A block changed (or is about to): redraw the pixels it can affect. */
    void blockChanged(Block block) {
        List<FrameRegistry.LiveMap> maps = registry.mapsIn(block.getWorld().getUID());
        if (maps.isEmpty()) {
            return;
        }
        int x = block.getX();
        int z = block.getZ();
        boolean checked = false;
        for (FrameRegistry.LiveMap map : maps) {
            if (!map.area.shows(x, z)) {
                continue;
            }
            if (!checked) {
                if (!visibleFromAbove(block)) {
                    return;
                }
                checked = true;
            }
            engine.markBlock(map.id, map.area, x, z);
        }
    }

    /** A chunk was generated for the first time: fill in that part of the maps. */
    void chunkGenerated(World world, int chunkX, int chunkZ) {
        markChunk(world.getUID(), chunkX, chunkZ, false);
    }

    /** Called every second: queues the next part of the sweep. */
    void sweepTick() {
        int seconds = plugin.settings().sweepSeconds();
        if (seconds <= 0) {
            sweep.clear();
            return;
        }
        if (sweep.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now < nextSweep) {
                return;
            }
            nextSweep = now + seconds * 1000L;
            fillSweep();
            sweepPerSecond = Math.max(1, (sweep.size() + seconds - 1) / seconds);
        }
        for (int i = 0; i < sweepPerSecond && !sweep.isEmpty(); i++) {
            Pending pending = sweep.poll();
            markChunk(pending.worldId(), pending.chunkX(), pending.chunkZ(), false);
        }
    }

    private void fillSweep() {
        List<Pending> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            List<FrameRegistry.LiveMap> maps = registry.mapsIn(world.getUID());
            if (maps.isEmpty()) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                for (FrameRegistry.LiveMap map : maps) {
                    if (map.area.showsChunk(chunk.getX(), chunk.getZ())) {
                        chunks.add(new Pending(world.getUID(), chunk.getX(), chunk.getZ()));
                        break;
                    }
                }
            }
        }
        // Row by row, so consecutive redraw steps share the chunks they read.
        chunks.sort(Comparator.comparing(Pending::worldId).thenComparingInt(Pending::chunkZ)
                .thenComparingInt(Pending::chunkX));
        sweep.addAll(chunks);
    }

    private void markChunk(UUID worldId, int chunkX, int chunkZ, boolean urgent) {
        for (FrameRegistry.LiveMap map : registry.mapsIn(worldId)) {
            if (map.area.showsChunk(chunkX, chunkZ)) {
                engine.markChunk(map.id, map.area, chunkX, chunkZ, urgent);
            }
        }
    }

    /**
     * False when the block is buried under something the map draws, like stone over a cave:
     * maps only see the top block, through air, glass and fluids. Blocks at the surface count
     * before and after they change, since events fire at either moment.
     */
    private static boolean visibleFromAbove(Block block) {
        World world = block.getWorld();
        int y = block.getY();
        int surface = world.getHighestBlockYAt(block.getX(), block.getZ(), HeightMap.WORLD_SURFACE);
        if (y >= surface - 1) {
            return true;
        }
        for (int above = surface; above > y; above--) {
            BlockData data = world.getBlockData(block.getX(), above, block.getZ());
            if (MapColors.color(data) != 0 && !MapColors.fluid(data)) {
                return false;
            }
        }
        return true;
    }

    private record Pending(UUID worldId, int chunkX, int chunkZ) { }
}
