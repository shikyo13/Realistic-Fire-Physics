# Realistic Fire Physics

A fire overhaul for Minecraft Java 1.21.1 with spreading flames, glowing embers, charred terrain, and animated torches and candles.

## Features

- Fire spreads across ground, wood, and leaves at finer than block resolution. Stone, gaps, water, and removed fuel interrupt its path.
- Burning surfaces glow and char. Ground continues to smolder after the flames subside, then cools. Burn marks persist when the world is saved and reopened.
- Jump onto burning ground to stamp out small patches. Partially burned grass and ferns retain their scorched appearance.
- Flames bend with local gusts, and held or dropped torch flames trail their movement. Torch sound responds to airflow, with gentle crackling at rest and stronger flutter in motion.
- Gusts also carry heat: fire advances faster downwind, and rising heat helps ground fires climb trunks and reach exposed canopies.
- Rare hot embers drift away from established fires and can ignite dry ground, wood, or leaves when they land. They cool during flight, and water or exposed rain extinguishes them.
- Fire sounds follow their sources, including thrown torches and falling timber, and fade with distance. Throwing a torch produces a brief rush of flame as it flies away.
- Burning trunks and branches lose support, fall with their attached canopy, and settle into the terrain. Falling wood keeps burning and can ignite nearby fuel; water extinguishes it.
- Larger fires move in rolling groups of flames and produce a fuller roar as nearby vegetation and wood burn.
- A dropped torch gradually dries and ignites the grass beneath it. Picking it up or wetting the ground interrupts ignition.
- Water and rain extinguish fire and torches. Placed torches can be relit with flint and steel.
- Candles and candle cakes use animated flames at each wick.
- Fire casts warm light onto nearby terrain. Iris and Complementary Reimagined provide optional shader effects.

## Getting started

Install the mod in a Minecraft 1.21.1 instance running Java 21 and NeoForge 21.1.206 or a later 21.1 release.

Use flint and steel or a fire charge on grass, moss, podzol, mycelium, or a flammable structure. Ground fire follows connected fuel, including one-block slopes and grass covered by vines or low leaves. Fire on structures follows individual faces and spreads between adjacent wood and leaf blocks. Active fire and smoldering ground damage living entities; cooled char is safe.

Ignition requires the `doFireTick` game rule to be enabled.

Fire follows Minecraft's chunk ticking rules. Combustion pauses outside the ticking area and resumes when that area becomes active again, including after saving and reopening the world. Visible flames keep animating while paused. Chunks kept active by another player or a chunk-loading mechanism continue burning normally.

## Configuration

Settings are stored in `config/surfacefire-server.toml`. A world-specific file in `serverconfig/` can override them.

- `maxBurnCells` limits ground coverage for each connected outbreak. The default is 65,536 cells, equivalent to 1,024 fully covered blocks.
- `maxStructureBurnCells` limits structure face cells ignited by each connected outbreak. The default is 262,144 cells.
- `spreadWorkPerTick` and `structureWorkPerTick` control simulation work budgets.
- `spreadSpeedMultiplier` controls ground and structure propagation speed. The default is `1.3`.
- `windDrivenSpread` enables directional spread, and `risingHeatIgnites` enables upward heat transfer. Both default to `true`. `heatWorkPerTick` bounds heat-transfer terrain queries (default `1024`).
- `embersIgnite` enables hot ember ignition. `emberReleaseChance` sets the chance per burning block's heat pulse, default `0.002`. Hot embers share their source fire's spread allowance, with at most 24 in flight per dimension.
- `fallingLogs` enables support loss and falling wood. `maxFallingTimberBodies` caps simultaneously moving groups per dimension (default `24`), and `maxLogsPerFallingBody` limits each group (default `64` logs). Larger connected structures stay in place.
- `rainExtinguishesTorches` controls whether rain extinguishes exposed torches.
- `lavaIgnitesGround` and `lightningIgnitesGround` control ignition from lava and lightning.
- `burnHistoryRetentionSeconds` optionally removes scars after they have fully cooled for this many seconds. The default, `0`, preserves them indefinitely.
- `maxRetainedGroundCells` and `maxRetainedStructureBlocks` optionally set history storage targets. Both default to `0`, which disables capacity cleanup. Active fire and smoldering material are protected even when a target is exceeded.
- `historyCleanupWorkPerTick` controls the amount of optional cleanup work per tick.

Independent ignitions receive separate spread allowances. Connected fronts share one budget, with their previously burned coverage added together; joining fires does not reset that coverage. Finished burns retain their scars without preventing new fires elsewhere. Ground and structures share outbreak identity but have separate coverage limits. A separate safety ceiling bounds total live outbreak coverage in each dimension.

History cleanup is disabled by default. Enabling it removes eligible burn marks, allowing remaining fuel there to burn again; it does not restore consumed plants or blocks. Replacing burned terrain removes its old burn history. Preserving more scars increases saved history, so larger worlds can use retention settings to control that storage separately from fire spread.

Operator commands:

- `/surfacefire status` reports the dimension's fire state.
- `/surfacefire clear` stops fire in the dimension and removes burn marks. Consumed vegetation is not restored.

## Build

Run `./gradlew build` with Java 21. The distributable JAR is written to `build/libs/`.
