package io.github.emanuelececcon.livemaps;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.MapDecorations;
import io.papermc.paper.datacomponent.item.MapId;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/**
 * The maps that hang in item frames, so LiveMaps keeps them up to date. Frames are remembered in
 * frames.yml, so a wall keeps updating while its own chunk is unloaded (the map data lives apart
 * from the frame). Main thread only.
 */
final class FrameRegistry {

    private final LiveMapsPlugin plugin;
    private final RenderEngine engine;
    private final File file;
    private final Map<Integer, LiveMap> maps = new HashMap<>();
    private final Map<UUID, Integer> mapByFrame = new HashMap<>();
    private Map<UUID, List<LiveMap>> mapsByWorld = Map.of();
    private boolean changed;

    FrameRegistry(LiveMapsPlugin plugin, RenderEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.file = new File(plugin.getDataFolder(), "frames.yml");
    }

    boolean isLive(int mapId) {
        return maps.containsKey(mapId);
    }

    Collection<LiveMap> maps() {
        return maps.values();
    }

    int frameCount() {
        return mapByFrame.size();
    }

    /** Live maps of a world, for the hot path of block events. */
    List<LiveMap> mapsIn(UUID worldId) {
        return mapsByWorld.getOrDefault(worldId, List.of());
    }

    /** Looks at what a loaded frame holds now and starts or stops following its map. */
    void observe(ItemFrame frame) {
        UUID frameId = frame.getUniqueId();
        Integer before = mapByFrame.get(frameId);
        MapView view = whyNotLive(frame.getItem(), plugin.settings()) == null ? mapView(frame.getItem()) : null;
        if (view != null && before != null && before == view.getId()) {
            return;
        }
        if (before != null) {
            detach(frameId, before);
        }
        if (view != null) {
            attach(frameId, new FrameSpot(frame.getWorld().getUID(), frame.getChunk().getX(), frame.getChunk().getZ()), view);
        }
    }

    void forgetFrame(UUID frameId) {
        Integer mapId = mapByFrame.get(frameId);
        if (mapId != null) {
            detach(frameId, mapId);
        }
    }

    /**
     * A chunk's entities were loaded: frames remembered there that are gone were removed while
     * nobody could see them (commands, other plugins); the frames that are there get looked at.
     */
    void entitiesLoaded(World world, int chunkX, int chunkZ, List<ItemFrame> frames) {
        Set<UUID> present = new HashSet<>();
        frames.forEach(frame -> present.add(frame.getUniqueId()));
        FrameSpot spot = new FrameSpot(world.getUID(), chunkX, chunkZ);
        List<UUID> gone = new ArrayList<>();
        for (LiveMap map : maps.values()) {
            map.frames.forEach((frameId, frameSpot) -> {
                if (frameSpot.equals(spot) && !present.contains(frameId)) {
                    gone.add(frameId);
                }
            });
        }
        gone.forEach(this::forgetFrame);
        frames.forEach(this::observe);
    }

    /** Checks every remembered frame that is loaded, and drops the ones that no longer exist. */
    void validate() {
        List<UUID> frameIds = new ArrayList<>(mapByFrame.keySet());
        for (UUID frameId : frameIds) {
            Entity entity = Bukkit.getEntity(frameId);
            if (entity instanceof ItemFrame frame) {
                observe(frame);
                continue;
            }
            Integer mapId = mapByFrame.get(frameId);
            LiveMap map = mapId == null ? null : maps.get(mapId);
            FrameSpot spot = map == null ? null : map.frames.get(frameId);
            World world = spot == null ? null : Bukkit.getWorld(spot.worldId());
            if (entity != null || spot == null || world != null && world.isChunkLoaded(spot.chunkX(), spot.chunkZ())
                    && world.getChunkAt(spot.chunkX(), spot.chunkZ()).isEntitiesLoaded()) {
                forgetFrame(frameId);
            }
        }
        // A map can stop qualifying while its frames are unloaded (locked by a plugin, world disabled).
        for (LiveMap map : List.copyOf(maps.values())) {
            MapView view = Bukkit.getMap(map.id);
            if (view == null || view.isLocked() || view.getWorld() == null
                    || plugin.settings().worldDisabled(view.getWorld().getName())) {
                List.copyOf(map.frames.keySet()).forEach(this::forgetFrame);
            }
        }
    }

    /** Finds frames in chunks that are already loaded (startup, /reload). */
    void scanLoadedFrames() {
        for (World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(ItemFrame.class).forEach(this::observe);
        }
    }

    /**
     * Null when the item is a map LiveMaps keeps up to date in a frame, otherwise the reason it
     * does not, for /livemaps info.
     */
    static String whyNotLive(ItemStack item, Settings settings) {
        if (item == null || item.isEmpty() || !item.hasData(DataComponentTypes.MAP_ID)) {
            return "it is not a map";
        }
        // Every map item has a map_decorations component (empty by default); explorer maps are
        // their own item types since 26.x, and the ones that are not carry an actual marker.
        MapDecorations decorations = item.getData(DataComponentTypes.MAP_DECORATIONS);
        if (item.getType() != Material.FILLED_MAP || decorations != null && !decorations.decorations().isEmpty()) {
            return "treasure and explorer maps keep their own look";
        }
        MapView view = mapView(item);
        if (view == null) {
            return "its map data is missing";
        }
        if (view.isLocked()) {
            return "it is locked (Cartography Table + Glass Pane)";
        }
        World world = view.getWorld();
        if (world == null) {
            return "the world it shows is not loaded";
        }
        if (world.hasCeiling()) {
            return "it shows a dimension with a ceiling, like the Nether, which maps never draw";
        }
        if (settings.worldDisabled(world.getName())) {
            return "LiveMaps is turned off in world " + world.getName();
        }
        return null;
    }

    static MapView mapView(ItemStack item) {
        MapId id = item == null ? null : item.getData(DataComponentTypes.MAP_ID);
        return id == null ? null : Bukkit.getMap(id.id());
    }

    private void attach(UUID frameId, FrameSpot spot, MapView view) {
        LiveMap map = maps.get(view.getId());
        boolean first = map == null;
        if (first) {
            map = new LiveMap(view.getId(), MapArea.of(view));
            maps.put(map.id, map);
            reindex();
        }
        map.frames.put(frameId, spot);
        mapByFrame.put(frameId, map.id);
        changed = true;
        if (first) {
            // A map that just went up on a wall: draw everything the server has already generated.
            engine.markAll(map.id, map.area, false, null);
        }
    }

    private void detach(UUID frameId, int mapId) {
        mapByFrame.remove(frameId);
        LiveMap map = maps.get(mapId);
        changed = true;
        if (map == null) {
            return;
        }
        map.frames.remove(frameId);
        if (map.frames.isEmpty()) {
            maps.remove(mapId);
            reindex();
            engine.forget(mapId);
        }
    }

    private void reindex() {
        Map<UUID, List<LiveMap>> byWorld = new HashMap<>();
        for (LiveMap map : maps.values()) {
            byWorld.computeIfAbsent(map.area.worldId(), id -> new ArrayList<>()).add(map);
        }
        byWorld.replaceAll((id, list) -> List.copyOf(list));
        mapsByWorld = byWorld;
    }

    void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("maps");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                MapView view = Bukkit.getMap(Integer.parseInt(key));
                if (view == null || view.isLocked() || view.getWorld() == null || view.getWorld().hasCeiling()) {
                    continue;
                }
                LiveMap map = new LiveMap(view.getId(), MapArea.of(view));
                ConfigurationSection frames = section.getConfigurationSection(key);
                for (String frameKey : frames == null ? Set.<String>of() : frames.getKeys(false)) {
                    String[] parts = frames.getString(frameKey, "").split(" ");
                    UUID frameId = UUID.fromString(frameKey);
                    map.frames.put(frameId, new FrameSpot(UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                    mapByFrame.put(frameId, map.id);
                }
                if (!map.frames.isEmpty()) {
                    maps.put(map.id, map);
                }
            } catch (RuntimeException invalid) {
                plugin.getLogger().warning("Skipping map " + key + " in frames.yml: " + invalid.getMessage());
            }
        }
        reindex();
    }

    void saveIfChanged() {
        if (changed) {
            save();
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Maps in item frames that LiveMaps keeps up to date: map id -> frame uuid: world chunkX chunkZ.",
                "Written by the plugin. If deleted, frames are found again when their chunks load."));
        for (LiveMap map : maps.values()) {
            map.frames.forEach((frameId, spot) -> yaml.set("maps." + map.id + "." + frameId,
                    spot.worldId() + " " + spot.chunkX() + " " + spot.chunkZ()));
        }
        try {
            yaml.save(file);
            changed = false;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not save frames.yml", exception);
        }
    }

    /** A map hanging in at least one item frame. */
    static final class LiveMap {
        final int id;
        final MapArea area;
        final Map<UUID, FrameSpot> frames = new HashMap<>();

        LiveMap(int id, MapArea area) {
            this.id = id;
            this.area = area;
        }
    }

    /** The chunk a frame hangs in. */
    record FrameSpot(UUID worldId, int chunkX, int chunkZ) { }
}
