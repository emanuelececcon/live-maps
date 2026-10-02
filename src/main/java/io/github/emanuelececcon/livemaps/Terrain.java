package io.github.emanuelececcon.livemaps;

/**
 * The block data the map algorithm reads. {@link ChunkTerrain} reads chunk snapshots; tests use
 * small fakes.
 */
interface Terrain {

    /** Returned by {@link #surfaceY} for a column whose chunk is not available. */
    int UNAVAILABLE = Integer.MIN_VALUE;

    int minY();

    /**
     * The highest block that is not air (vanilla's WORLD_SURFACE heightmap), {@code minY() - 1}
     * when the column is all air, or {@link #UNAVAILABLE} when the chunk was not generated.
     */
    int surfaceY(int x, int z);

    /** Map colour id of the block, 0 when maps do not draw it (air, glass...). */
    int color(int x, int y, int z);

    /** True when the block holds a fluid: water, lava, waterlogged blocks, kelp, seagrass... */
    boolean fluid(int x, int y, int z);

    /**
     * Colour of a fluid-holding block seen from above: the fluid's colour, unless the block has a
     * sturdy top face (then its own colour). Vanilla {@code MapItem.getCorrectStateForFluidBlock}.
     */
    int fluidSurfaceColor(int x, int y, int z);
}
