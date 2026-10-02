package io.github.emanuelececcon.livemaps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.map.MapView;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

/**
 * Redraws dirty map pixels one step at a time: on the main thread it snapshots the chunks a step
 * needs (loading unloaded ones asynchronously, never generating), draws the pixels on an async
 * thread, then writes them back on the main thread. Only one step runs at a time, and block
 * changes ("urgent") go before full redraws and sweeps ("background").
 */
final class RenderEngine {

    private static final long STUCK_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final LiveMapsPlugin plugin;
    private final IntPredicate live;
    private final MapWriter writer = new MapWriter();
    private final Map<Integer, Target> targets = new LinkedHashMap<>();
    private BukkitTask task;
    private Batch current;
    private long currentSince;
    private long urgentDueAt = Long.MAX_VALUE;
    private boolean writesBroken;

    private long steps;
    private long chunksRead;
    private long pixelsChanged;
    private long snapshotNanos;
    private long drawNanos;

    /** {@code live} tells whether a map is in an item frame and may be updated. */
    RenderEngine(LiveMapsPlugin plugin, IntPredicate live) {
        this.plugin = plugin;
        this.live = live;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void stop() {
        if (task != null) {
            task.cancel();
        }
        targets.clear();
        current = null;
    }

    /** A block column changed: redraw its pixel (and the one south of it) after the delay. */
    void markBlock(int mapId, MapArea area, int x, int z) {
        PixelRect rect = area.affected(x, z, x, z);
        if (rect != null) {
            target(mapId, area).urgent.mark(rect);
            scheduleUrgent();
        }
    }

    void markChunk(int mapId, MapArea area, int chunkX, int chunkZ, boolean urgent) {
        PixelRect rect = area.affectedByChunk(chunkX, chunkZ);
        if (rect == null) {
            return;
        }
        Target target = target(mapId, area);
        if (urgent) {
            target.urgent.mark(rect);
            scheduleUrgent();
        } else {
            target.background.mark(rect);
        }
    }

    /**
     * Redraws the whole map. {@code forced} draws it even when it is not in a frame (a held map
     * being revealed); {@code onDone} gets the number of changed pixels at the end.
     */
    void markAll(int mapId, MapArea area, boolean forced, IntConsumer onDone) {
        Target target = target(mapId, area);
        target.background.mark(PixelRect.WHOLE_MAP);
        target.forced |= forced;
        if (onDone != null) {
            IntConsumer previous = target.onDone;
            target.onDone = previous == null ? onDone : previous.andThen(onDone);
        }
    }

    /** The map left its last frame: drop its pending work, unless someone is revealing it. */
    void forget(int mapId) {
        Target target = targets.get(mapId);
        if (target != null && !target.forced) {
            targets.remove(mapId);
        }
    }

    int pendingMaps() {
        return (int) targets.values().stream().filter(target -> !target.idle()).count();
    }

    int pendingPixels() {
        return targets.values().stream().mapToInt(target -> target.urgent.count() + target.background.count()).sum();
    }

    List<String> statistics() {
        return List.of(
                "Waiting: " + pendingPixels() + " pixels on " + pendingMaps() + " maps",
                "Since start: " + steps + " redraw steps, " + chunksRead + " chunks read, "
                        + pixelsChanged + " pixels changed",
                String.format(Locale.ROOT, "Average step: %.2f ms reading chunks (main thread), %.2f ms drawing (async)",
                        steps == 0 ? 0.0 : snapshotNanos / 1e6 / steps, steps == 0 ? 0.0 : drawNanos / 1e6 / steps));
    }

    private Target target(int mapId, MapArea area) {
        Target target = targets.get(mapId);
        if (target == null || !target.area.equals(area)) {
            target = new Target(mapId, area);
            targets.put(mapId, target);
        }
        return target;
    }

    private void scheduleUrgent() {
        if (urgentDueAt == Long.MAX_VALUE) {
            urgentDueAt = System.currentTimeMillis() + plugin.settings().delaySeconds() * 1000L;
        }
    }

    private void tick() {
        if (current != null) {
            if (System.nanoTime() - currentSince < STUCK_NANOS) {
                return;
            }
            plugin.getLogger().warning("A map redraw step took more than 2 minutes; going on with the next one.");
            current = null;
        }
        Batch batch = null;
        if (System.currentTimeMillis() >= urgentDueAt) {
            batch = build(true);
            if (batch == null) {
                urgentDueAt = Long.MAX_VALUE;
            }
        }
        if (batch == null) {
            batch = build(false);
        }
        if (batch != null) {
            run(batch);
        }
    }

    /** Takes dirty tiles of the first map that has some, up to the chunk budget. */
    private Batch build(boolean urgent) {
        Target chosen = null;
        World world = null;
        List<Target> unloadedWorld = new ArrayList<>();
        for (Target target : targets.values()) {
            if ((urgent ? target.urgent : target.background).isEmpty()) {
                continue;
            }
            world = Bukkit.getWorld(target.area.worldId());
            if (world == null) {
                unloadedWorld.add(target);
                continue;
            }
            chosen = target;
            break;
        }
        for (Target target : unloadedWorld) {
            target.urgent.clearAll();
            target.background.clearAll();
            finishIfIdle(target);
        }
        if (chosen == null) {
            return null;
        }

        DirtyPixels dirty = urgent ? chosen.urgent : chosen.background;
        int budget = plugin.settings().chunksPerBatch();
        List<PixelRect> rects = new ArrayList<>();
        Set<Long> chunks = new HashSet<>();
        Set<Long> needed = new HashSet<>();
        for (int tile = dirty.nextTile(0); tile >= 0; tile = dirty.nextTile(tile + 1)) {
            PixelRect rect = dirty.bounds(tile);
            if (rect == null) {
                dirty.clearTile(tile);
                continue;
            }
            needed.clear();
            chosen.area.chunksFor(rect, needed);
            int added = 0;
            for (long key : needed) {
                if (!chunks.contains(key)) {
                    added++;
                }
            }
            if (!rects.isEmpty() && chunks.size() + added > budget) {
                break;
            }
            chunks.addAll(needed);
            rects.add(rect);
        }
        if (rects.isEmpty()) {
            return build(urgent); // only stale tile marks, which are cleared now
        }
        for (PixelRect rect : rects) {
            chosen.urgent.clear(rect);
            chosen.background.clear(rect);
        }
        // Round robin: a big redraw of one map does not hold back the others.
        targets.remove(chosen.mapId);
        targets.put(chosen.mapId, chosen);
        return new Batch(chosen, world, rects, chunks);
    }

    private void run(Batch batch) {
        current = batch;
        currentSince = System.nanoTime();
        batch.target.inFlight++;
        World world = batch.world;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        BiomeTints tints = plugin.settings().biomeColors() ? plugin.tints() : null;
        capture(world, batch.chunks, tints != null).whenComplete((snapshots, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (failure != null) {
                finish(batch, null, 0, failure);
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                long started = System.nanoTime();
                List<int[]> pixels = new ArrayList<>(batch.rects.size());
                Throwable error = null;
                try {
                    ChunkTerrain terrain = new ChunkTerrain(snapshots, minY, maxY, tints);
                    for (PixelRect rect : batch.rects) {
                        pixels.add(PixelRenderer.render(terrain, batch.target.area, rect));
                    }
                } catch (RuntimeException exception) {
                    error = exception;
                }
                long took = System.nanoTime() - started;
                Throwable failed = error;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> finish(batch, failed == null ? pixels : null, took, failed));
                } catch (IllegalPluginAccessException disabledMeanwhile) {
                    // The server is stopping; the pixels are dropped and redrawn after the restart if needed.
                }
            });
        });
    }

    /** Snapshots the chunks; unloaded ones are loaded asynchronously, ungenerated ones are left out. */
    private CompletableFuture<Map<Long, ChunkSnapshot>> capture(World world, Set<Long> keys, boolean biomes) {
        Map<Long, ChunkSnapshot> snapshots = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> loads = new ArrayList<>();
        long started = System.nanoTime();
        for (long key : keys) {
            int chunkX = MapArea.chunkX(key);
            int chunkZ = MapArea.chunkZ(key);
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                snapshots.put(key, world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, biomes, false, false));
                continue;
            }
            // Paper completes this future on the main thread, where taking the snapshot is safe.
            loads.add(world.getChunkAtAsync(chunkX, chunkZ, false).thenAccept(chunk -> {
                if (chunk != null) {
                    long loadedAt = System.nanoTime();
                    snapshots.put(key, chunk.getChunkSnapshot(false, biomes, false, false));
                    snapshotNanos += System.nanoTime() - loadedAt;
                }
            }));
        }
        snapshotNanos += System.nanoTime() - started;
        chunksRead += keys.size();
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApply(ignored -> snapshots);
    }

    private void finish(Batch batch, List<int[]> pixels, long drawTook, Throwable failure) {
        if (current == batch) {
            current = null;
        }
        Target target = batch.target;
        target.inFlight--;
        steps++;
        drawNanos += drawTook;
        if (failure != null) {
            plugin.getLogger().log(Level.WARNING, "Could not redraw part of map #" + target.mapId, failure);
        } else {
            write(target, batch.rects, pixels);
        }
        finishIfIdle(target);
    }

    private void write(Target target, List<PixelRect> rects, List<int[]> pixels) {
        MapView view = Bukkit.getMap(target.mapId);
        if (writesBroken || view == null || view.isLocked() || !(target.forced || live.test(target.mapId))) {
            return;
        }
        int changed = 0;
        try {
            for (int i = 0; i < rects.size(); i++) {
                changed += writer.write(view, rects.get(i), pixels.get(i));
            }
        } catch (IllegalStateException unsupported) {
            writesBroken = true;
            plugin.getLogger().log(Level.SEVERE, unsupported.getMessage() + " LiveMaps cannot update maps.", unsupported);
        }
        target.changed += changed;
        pixelsChanged += changed;
    }

    private void finishIfIdle(Target target) {
        if (!target.idle()) {
            return;
        }
        if (target.onDone != null) {
            IntConsumer onDone = target.onDone;
            target.onDone = null;
            onDone.accept(target.changed);
        }
        target.forced = false;
        target.changed = 0;
        if (targets.get(target.mapId) == target) {
            targets.remove(target.mapId);
        }
    }

    /** Pending work for one map. */
    private static final class Target {
        final int mapId;
        final MapArea area;
        final DirtyPixels urgent;
        final DirtyPixels background;
        boolean forced;
        IntConsumer onDone;
        int changed;
        int inFlight;

        Target(int mapId, MapArea area) {
            this.mapId = mapId;
            this.area = area;
            this.urgent = new DirtyPixels(area.scale());
            this.background = new DirtyPixels(area.scale());
        }

        boolean idle() {
            return urgent.isEmpty() && background.isEmpty() && inFlight == 0;
        }
    }

    private record Batch(Target target, World world, List<PixelRect> rects, Set<Long> chunks) { }
}
