# LiveMaps

A Paper 26.3 plugin that keeps every map in an item frame up to date. Build, dig, blow things up, let trees grow: a map wall shows the change within about ten seconds, even when nobody stands next to it and even while its own chunk is unloaded.

## What it does

- **No setup.** Any plain Filled Map in an Item Frame or Glow Item Frame is followed. There are no walls to register and no commands to run. Copies of the same map (in a chest, in your hand) share the picture, so they update too.
- **Whole map at once.** When a map goes into a frame, everything the server has already generated in its area is drawn right away. Chunks that were never generated stay blank and fill in as soon as someone explores them. LiveMaps never generates terrain.
- **Vanilla look.** Pixels are computed with the game's own map algorithm (26.3 `MapItem.update`): colours from the server's block definitions, the top block as vanilla sees it (grass, flowers and snow layers included), height shading with the checkerboard dither, water shaded by depth, and the most common colour per pixel on zoomed-out maps. Biome colours are an optional extra, see below.
- **Only what changed.** A change redraws the pixels it touches (one block changes one pixel and the pixel south of it, which is shaded against it), not the whole map.
- **Walls in one command.** `/livemaps fill` puts new maps into the empty frames of a wall so that they join up seamlessly, next to the maps already there or centred on you for a new wall. `/livemaps scale` zooms a whole wall in or out around its centre.
- **Left alone:** locked maps (Cartography Table + Glass Pane), treasure and explorer maps (they keep their own look), maps of a dimension with a ceiling such as the Nether (vanilla never draws those), and worlds listed in `disabled-worlds`.

## Building a wall

1. Hang Item Frames or Glow Item Frames side by side on a wall, in any shape. Frames on floors and ceilings don't count.
2. Look at one of them and run `/livemaps fill`. Every empty frame connected to it gets a new map, drawn within seconds:
   - **next to frames that already hold maps**, the new maps continue the grid of the nearest one, with its scale. This extends any wall, also walls made by hand or by other plugins whose maps are not on the vanilla grid;
   - **on a wall without maps**, the middle of the wall shows where you stand. Add a scale for zoomed-out maps: `/livemaps fill 1` (1:2, 256 blocks per map) up to `/livemaps fill 4` (1:16, 2048 blocks). Default 0 (1:1, 128 blocks).
3. To grow the wall later, hang more empty frames next to it and run `/livemaps fill` again.
4. To zoom the whole wall in or out, look at it and run `/livemaps scale <0-4>`. Every map on the wall is replaced by a map of the new scale, empty frames are filled too, and the middle of the wall keeps showing the same place. Walls with locked maps are refused (take the locked maps out first). A wall that would cover more than about 100,000 chunks asks for `/livemaps scale <n> confirm`. The old maps are only taken out of the frames: copies of them elsewhere keep their picture but stop updating.

Frames that hold something are never touched. A map always shows north up and east to the right, whichever way the wall faces. Maps made by hand work too: put them in frames and they update by themselves.

To stop a map updating, take it out of its frame. To keep a "photo", lock a copy in a Cartography Table with a Glass Pane and hang that.

## How changes are found

1. **Block events**: placing, breaking, explosions, pistons, fluids flowing, fire, leaves decaying, crops and trees growing, snow and ice forming or melting, endermen, farmland, bone meal, buckets, sponges... Changes buried under what the map shows (a cave under stone) are ignored. The affected pixels are redrawn after `delay-seconds`.
2. **New chunks**: a chunk generated for the first time inside a framed map's area is drawn in.
3. **Sweep**: every loaded chunk that a framed map shows is checked again within `sweep-seconds`. This catches changes that fire no event: `/fill`, `/setblock`, WorldEdit, other plugins.

Frames are remembered in `plugins/LiveMaps/frames.yml`, so a wall keeps updating while its chunk is unloaded. A frame removed while nobody could see it (by a command) is noticed the next time its chunk loads.

## Performance

Work is done in small steps, one at a time. Each step snapshots at most `chunks-per-batch` chunks on the main thread (roughly 0.05 ms each), draws the pixels on an async thread, and writes back only the pixels that changed, so players only receive the changed part of a map. Unloaded chunks are loaded asynchronously and never generated. Block changes go before full redraws and sweeps, and full redraws of several maps take turns. `/livemaps status` shows the queue and the average cost of a step.

## Biome colours (not vanilla)

With `biome-colors: true`, grass, foliage and leaf litter take the colours their biome defines instead of the fixed map greens: the orange Dappled Forest, the grey-green Pale Garden, the yellow-green Cherry Grove, the brown Badlands. Biomes without their own colours look vanilla. The colours come from the server's worldgen biome files, so datapack biomes stay vanilla.

A player carrying a copy of a framed map redraws the area around them in vanilla colours, as the game always does. LiveMaps puts the biome colours back with the next change or sweep there.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/livemaps info` | `livemaps.info` (everyone) | For the framed map you look at, or the map in your hand: does it update by itself, and if not, why |
| `/livemaps fill [scale]` | `livemaps.fill` (everyone) | Puts seamless new maps into the empty frames of the wall you look at, see [Building a wall](#building-a-wall) |
| `/livemaps scale <0-4> [confirm]` | `livemaps.scale` (everyone) | Replaces the maps of the wall you look at with maps of another scale, around the same centre |
| `/livemaps reveal` | `livemaps.reveal` (op) | Draws the whole map in your hand from the generated terrain, once |
| `/livemaps refresh [map id]` | `livemaps.admin` (op) | Redraws all framed maps, or one |
| `/livemaps status` | `livemaps.admin` (op) | Framed maps, frames, queue and timings |
| `/livemaps reload` | `livemaps.admin` (op) | Reloads `config.yml` |

`/lm` is an alias.

## Settings

`plugins/LiveMaps/config.yml`. Apply changes with `/livemaps reload`.

| Key | What it does | Default |
|---|---|---|
| `updates.delay-seconds` | Block changes are collected this long, then their pixels are redrawn | `10` |
| `updates.sweep-seconds` | Each loaded chunk shown on a framed map is checked again within this time; `0` turns the sweep off | `300` |
| `rendering.biome-colors` | Biome colours for grass, foliage and leaf litter; `false` = exact vanilla colours. Run `/livemaps refresh` after changing it | `true` |
| `performance.chunks-per-batch` | Chunks snapshotted per step; lower it if a big redraw causes lag | `32` |
| `fill.max-frames` | Most empty frames one `/livemaps fill` fills | `100` |
| `disabled-worlds` | World names whose maps LiveMaps never touches | `[]` |

## How it works

| File | What it does |
|---|---|
| `LiveMapsPlugin.java` | Entry point, timers |
| `Settings.java` | Reads `config.yml` |
| `FrameRegistry.java` | Which maps hang in which frames, `frames.yml`, what qualifies |
| `FrameListener.java` | Maps going into and out of frames, frames loading and breaking |
| `TerrainListener.java` | Block events that can change a map |
| `ChangeTracker.java` | Turns block events, new chunks and the sweep into dirty pixels |
| `DirtyPixels.java` | Pixels waiting to be redrawn, grouped in tiles |
| `RenderEngine.java` | The steps: snapshot chunks, draw async, write back |
| `PixelRenderer.java` | The vanilla map algorithm |
| `ChunkTerrain.java` | Reads blocks and the top block of each column from chunk snapshots |
| `MapColors.java` | Block state to map colour id, fluids |
| `BiomeTints.java` | Optional biome colours |
| `MapWriter.java` | Writes pixels into the saved map data |
| `MapArea.java`, `PixelRect.java` | Map pixel and world coordinates |
| `WallFiller.java` | `/livemaps fill` and `scale`: finds the wall, works out the grid, creates the maps |
| `MapRevealerMigration.java` | One-time unlock of the maps MapRevealer locked on its walls |
| `LiveMapsCommand.java` | `/livemaps` |

Bukkit has no API to change a map's saved picture (map renderers only paint a per-player overlay), so `MapWriter` calls the server's `MapItemSavedData.updateColor` through reflection. That method stores the pixel, marks the map for saving and sends the change to everyone who sees the map. If a future server version changes it, LiveMaps logs an error and stops writing instead of breaking maps.

## Build

Requirements: JDK 21 or newer and Maven 3.9+.

```bash
mvn clean package
```

The jar is `target/live-maps-<version>.jar`. Released jars are in `release/` and on the GitHub releases page.

## Install

1. Put the jar in `plugins/` and restart the server.
2. Framed maps in loaded chunks are found at startup, the others when their chunks load. Each one is drawn in full the first time.

Replacing MapRevealer: remove its jar first. Its map walls need no conversion, their maps are ordinary framed maps. MapRevealer locks the maps of its walls by default (only to keep the game from drawing over them), which would make LiveMaps leave them alone, so on the first start LiveMaps reads `plugins/MapRevealer/walls.yml` and unlocks the maps of those walls once. The unlocked map ids are listed in `plugins/LiveMaps/maprevealer-unlocked.yml`.

## License

MIT, see [LICENSE](LICENSE).
