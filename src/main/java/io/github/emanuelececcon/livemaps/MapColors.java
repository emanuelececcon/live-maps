package io.github.emanuelececcon.livemaps;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/**
 * Map colours of block states, taken from the server's own block definitions
 * ({@link BlockData#getMapColor()}), so blocks added by later versions need no table.
 * Safe to use from any thread.
 */
final class MapColors {

    private static final int FLUID = 1 << 8;
    private static final int SHOWS_FLUID = 1 << 9;

    /** Blocks that always hold water (or lava), besides waterlogged ones. */
    private static final Set<Material> FLUID_BLOCKS = Set.of(Material.WATER, Material.LAVA,
            Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS);

    /**
     * Unshaded rgb of each vanilla MapColor id (0-61), as in MapColor of Minecraft 26.3. A colour
     * added by a later version is drawn with its closest one here.
     */
    private static final int[] BASE_RGB = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
            0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19,
            0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919,
            0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A,
            0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464,
            0xD8AF93, 0x7FA796
    };
    private static final Map<Integer, Integer> ID_BY_RGB = new HashMap<>();
    private static final Map<BlockData, Integer> INFO = new ConcurrentHashMap<>();

    static {
        for (int id = 1; id < BASE_RGB.length; id++) {
            ID_BY_RGB.put(BASE_RGB[id], id);
        }
    }

    private MapColors() {
    }

    /** Base colour id of the block, 0 when maps do not draw it. */
    static int color(BlockData data) {
        return info(data) & 0xFF;
    }

    static boolean fluid(BlockData data) {
        return (info(data) & FLUID) != 0;
    }

    /** True when the block is seen as its fluid from above (its top face is not sturdy). */
    static boolean showsFluid(BlockData data) {
        return (info(data) & SHOWS_FLUID) != 0;
    }

    /** Colour of the fluid in a block that {@link #showsFluid shows its fluid}. */
    static int fluidColor(BlockData data) {
        return data.getMaterial() == Material.LAVA ? color(data) : PixelRenderer.WATER;
    }

    private static int info(BlockData data) {
        Integer cached = INFO.get(data);
        if (cached != null) {
            return cached;
        }
        int info = compute(data);
        INFO.put(data, info);
        return info;
    }

    private static int compute(BlockData data) {
        int color = idOf(data.getMapColor().asRGB());
        boolean fluid = FLUID_BLOCKS.contains(data.getMaterial())
                || data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
        boolean showsFluid = fluid && !data.isFaceSturdy(BlockFace.UP, BlockSupport.FULL);
        return color | (fluid ? FLUID : 0) | (showsFluid ? SHOWS_FLUID : 0);
    }

    private static int idOf(int rgb) {
        if (rgb == 0) {
            return 0; // MapColor.NONE
        }
        Integer id = ID_BY_RGB.get(rgb);
        return id != null ? id : nearest(rgb, false);
    }

    /** The base colour closest to {@code rgb} ("redmean" distance), optionally never water. */
    static int nearest(int rgb, boolean avoidWater) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int id = 1; id < BASE_RGB.length; id++) {
            if (avoidWater && id == PixelRenderer.WATER) {
                continue;
            }
            int other = BASE_RGB[id];
            int pr = (other >> 16) & 0xFF;
            int pg = (other >> 8) & 0xFF;
            int pb = other & 0xFF;
            double mean = (r + pr) / 2.0;
            double distance = (2 + mean / 256) * (r - pr) * (r - pr) + 4.0 * (g - pg) * (g - pg)
                    + (2 + (255 - mean) / 256) * (b - pb) * (b - pb);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = id;
            }
        }
        return best;
    }
}
