package io.github.emanuelececcon.livemaps;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.map.MapView;

/**
 * One-time step for servers coming from MapRevealer. Its walls lock their maps by default, only
 * to keep the game from drawing over them while it redraws them itself. LiveMaps leaves locked
 * maps alone, so those walls would freeze: this unlocks the maps of the walls MapRevealer locked,
 * once, and lists them in maprevealer-unlocked.yml.
 */
final class MapRevealerMigration {

    private MapRevealerMigration() {
    }

    static void run(LiveMapsPlugin plugin) {
        File walls = new File(plugin.getDataFolder().getParentFile(), "MapRevealer/walls.yml");
        File done = new File(plugin.getDataFolder(), "maprevealer-unlocked.yml");
        if (!walls.isFile() || done.exists()) {
            return;
        }
        ConfigurationSection root = YamlConfiguration.loadConfiguration(walls).getConfigurationSection("walls");
        YamlConfiguration report = new YamlConfiguration();
        report.options().setHeader(List.of(
                "Maps that MapRevealer had locked on its walls and LiveMaps unlocked once, so they update again.",
                "While this file exists the step does not run again."));
        MapWriter writer = new MapWriter();
        int unlocked = 0;
        List<String> wallNames = new ArrayList<>();
        for (String name : root == null ? List.<String>of() : root.getKeys(false)) {
            ConfigurationSection wall = root.getConfigurationSection(name);
            if (wall == null || !wall.getBoolean("locked", true)) {
                continue;
            }
            List<Integer> ids = new ArrayList<>();
            for (Map<?, ?> frame : wall.getMapList("frames")) {
                MapView view = mapOf(frame.get("map"));
                if (view == null || !view.isLocked()) {
                    continue;
                }
                view.setLocked(false);
                writer.markDirty(view); // the lock flag alone does not get the map saved
                ids.add(view.getId());
            }
            if (!ids.isEmpty()) {
                report.set("unlocked." + name, ids);
                wallNames.add(name);
                unlocked += ids.size();
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            report.save(done);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not write maprevealer-unlocked.yml", exception);
        }
        if (unlocked > 0) {
            plugin.getLogger().info("Unlocked " + unlocked + " maps that MapRevealer had locked on its walls "
                    + wallNames + ", so they update again.");
        }
    }

    private static MapView mapOf(Object id) {
        try {
            return id == null ? null : Bukkit.getMap(Integer.parseInt(String.valueOf(id)));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
