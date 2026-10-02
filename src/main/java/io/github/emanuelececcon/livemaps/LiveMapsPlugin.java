package io.github.emanuelececcon.livemaps;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * LiveMaps: every plain map hanging in an item frame shows the terrain as it is now, without
 * anybody walking around with it.
 */
public final class LiveMapsPlugin extends JavaPlugin {

    private Settings settings;
    private final BiomeTints tints = new BiomeTints();
    private RenderEngine engine;
    private FrameRegistry registry;
    private ChangeTracker tracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        engine = new RenderEngine(this, mapId -> registry.isLive(mapId));
        registry = new FrameRegistry(this, engine);
        registry.load();
        tracker = new ChangeTracker(this, registry, engine);

        getServer().getPluginManager().registerEvents(new FrameListener(this, registry), this);
        getServer().getPluginManager().registerEvents(new TerrainListener(tracker), this);
        PluginCommand command = getCommand("livemaps");
        if (command != null) {
            LiveMapsCommand executor = new LiveMapsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        registry.scanLoadedFrames();
        engine.start();
        getServer().getScheduler().runTaskTimer(this, tracker::sweepTick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, registry::validate, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, registry::saveIfChanged, 600L, 600L);
        getLogger().info("Keeping " + registry.maps().size() + " framed maps up to date ("
                + registry.frameCount() + " item frames).");
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.stop();
        }
        if (registry != null) {
            registry.save();
        }
    }

    void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig());
        registry.validate();
    }

    Settings settings() {
        return settings;
    }

    BiomeTints tints() {
        return tints;
    }

    RenderEngine engine() {
        return engine;
    }

    FrameRegistry registry() {
        return registry;
    }
}
