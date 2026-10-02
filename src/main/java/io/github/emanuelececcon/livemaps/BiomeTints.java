package io.github.emanuelececcon.livemaps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;

/**
 * Optional, not vanilla: grass, foliage and leaf litter drawn in the colours their biome defines
 * (the orange Dappled Forest, the grey Pale Garden...) instead of the fixed map greens. The colours
 * come from the server's own worldgen biome files; biomes without explicit colours stay vanilla.
 * Safe to use from any thread.
 */
final class BiomeTints {

    private enum Kind { GRASS, FOLIAGE, DRY_FOLIAGE }

    /** Map colours are darker than the in-game tint they stand for (vanilla GRASS vs plains grass). */
    private static final double MAP_SHADE = 0.88;
    /** The swamp grass modifier draws this colour on most of the swamp. */
    private static final int SWAMP_GRASS = 0x6A7039;
    private static final int[] NO_COLORS = {-1, -1, -1};

    private static final Set<Material> GRASS = EnumSet.of(Material.GRASS_BLOCK, Material.SHORT_GRASS,
            Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.BUSH, Material.SUGAR_CANE);
    private static final Set<Material> FOLIAGE = EnumSet.of(Material.OAK_LEAVES, Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.MANGROVE_LEAVES, Material.VINE);
    private static final Set<Material> DRY_FOLIAGE = EnumSet.of(Material.LEAF_LITTER);

    private final Map<NamespacedKey, int[]> biomeColors = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> nearest = new ConcurrentHashMap<>();

    static boolean tintable(Material type) {
        return GRASS.contains(type) || FOLIAGE.contains(type) || DRY_FOLIAGE.contains(type);
    }

    /** The colour id to draw a tintable block with in this biome, or {@code vanillaId}. */
    int tint(Material type, Biome biome, int vanillaId) {
        Kind kind = GRASS.contains(type) ? Kind.GRASS : FOLIAGE.contains(type) ? Kind.FOLIAGE
                : DRY_FOLIAGE.contains(type) ? Kind.DRY_FOLIAGE : null;
        if (kind == null || biome == null || vanillaId == 0) {
            return vanillaId;
        }
        NamespacedKey key;
        try {
            key = biome.getKey();
        } catch (RuntimeException unregistered) {
            return vanillaId;
        }
        int rgb = biomeColors.computeIfAbsent(key, BiomeTints::load)[kind.ordinal()];
        return rgb < 0 ? vanillaId : nearest.computeIfAbsent(rgb, BiomeTints::toMapColor);
    }

    private static int toMapColor(int tint) {
        int r = (int) (((tint >> 16) & 0xFF) * MAP_SHADE);
        int g = (int) (((tint >> 8) & 0xFF) * MAP_SHADE);
        int b = (int) ((tint & 0xFF) * MAP_SHADE);
        // Never water: the map would shade it by depth.
        return MapColors.nearest(r << 16 | g << 8 | b, true);
    }

    private static int[] load(NamespacedKey key) {
        String path = "data/" + key.getNamespace() + "/worldgen/biome/" + key.getKey() + ".json";
        try (InputStream stream = resource(path)) {
            if (stream == null) {
                return NO_COLORS;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject effects = root.has("effects") ? root.getAsJsonObject("effects") : new JsonObject();
            int grass = color(effects.get("grass_color"));
            if (grass < 0 && effects.has("grass_color_modifier")
                    && "swamp".equals(effects.get("grass_color_modifier").getAsString())) {
                grass = SWAMP_GRASS;
            }
            return new int[] {grass, color(effects.get("foliage_color")), color(effects.get("dry_foliage_color"))};
        } catch (Exception unreadable) {
            return NO_COLORS;
        }
    }

    private static InputStream resource(String path) {
        for (ClassLoader loader : new ClassLoader[] {Bukkit.getServer().getClass().getClassLoader(),
                ClassLoader.getSystemClassLoader()}) {
            InputStream stream = loader == null ? null : loader.getResourceAsStream(path);
            if (stream != null) {
                return stream;
            }
        }
        return null;
    }

    /** Biome colours are "#rrggbb" strings since 26.x and plain integers before. */
    private static int color(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return -1;
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt() & 0xFFFFFF;
        }
        String text = element.getAsString().trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
