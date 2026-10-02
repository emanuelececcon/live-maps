# Changelog

## 1.1.0 - 2026-10-02
- `/livemaps fill [scale]` (permission `livemaps.fill`, everyone): puts new maps into the empty frames of the wall you look at. Next to existing maps they continue the grid of the nearest one; on a wall without maps they are centred on you. Limit `fill.max-frames` (100).
- `/livemaps scale <0-4> [confirm]` (permission `livemaps.scale`, everyone): replaces every map of the wall you look at with a map of another scale, keeping the middle of the wall in place. Walls with locked maps are refused; walls that would cover more than about 100,000 chunks ask for `confirm`.
- Coming from MapRevealer: the maps it locked on its walls are unlocked once on the first start, so those walls keep updating. The ids are listed in `maprevealer-unlocked.yml`.

## 1.0.1 - 2026-10-02
- Fix: no framed map was followed. Every map item carries a `map_decorations` component, empty by default, and 1.0.0 took any map with that component for a treasure or explorer map. Explorer maps are now recognised by their item type or by an actual marker.

## 1.0.0 - 2026-10-02
- First release.
- Every plain Filled Map in an Item Frame or Glow Item Frame is kept up to date, with no setup. Locked maps, treasure and explorer maps, Nether maps and disabled worlds are left alone.
- A map put in a frame is drawn in full from the terrain the server has already generated; terrain is never generated.
- Changes are found from block events (redrawn after `delay-seconds`), newly generated chunks, and a sweep over loaded chunks (`sweep-seconds`) that also catches commands and WorldEdit.
- Only the pixels a change touches are redrawn; chunk snapshots on the main thread, drawing off it, in small steps (`chunks-per-batch`).
- Vanilla 26.3 map algorithm, with optional biome colours for grass, foliage and leaf litter (`biome-colors`).
- Frames are remembered in `frames.yml`, so walls keep updating while unloaded.
- `/livemaps info`, `reveal`, `refresh`, `status`, `reload` (alias `/lm`).
