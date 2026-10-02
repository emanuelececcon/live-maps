package io.github.emanuelececcon.livemaps;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

/** The values of config.yml, clamped to safe ranges. */
record Settings(int delaySeconds, int sweepSeconds, boolean biomeColors, int chunksPerBatch,
                Set<String> disabledWorlds) {

    static Settings load(FileConfiguration config) {
        Set<String> disabled = new HashSet<>();
        for (String world : config.getStringList("disabled-worlds")) {
            disabled.add(world.toLowerCase(Locale.ROOT));
        }
        int sweep = config.getInt("updates.sweep-seconds", 300);
        return new Settings(
                Math.clamp(config.getInt("updates.delay-seconds", 10), 1, 300),
                sweep <= 0 ? 0 : Math.max(10, sweep),
                config.getBoolean("rendering.biome-colors", true),
                Math.clamp(config.getInt("performance.chunks-per-batch", 32), 4, 512),
                Set.copyOf(disabled));
    }

    boolean worldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
