# ColossalCraft (NeoForge 1.21.1)

Build a machine block by block, place a **Hull Core**, and the Core turns those blocks into one living **Colossus** that walks, drives, sails or flies on real physics. Nothing is a canned animation: legs plant feet, thrust pushes mass, lift fights gravity, and every block on board stays a normal, usable block.

- Mod id: `colossus` · Minecraft 1.21.1 · NeoForge 21.1.x · Java 21
- One self-contained jar. NeoForge is the only requirement; every other mod is an optional extra.
- Design: [`docs/BLUEPRINT.md`](docs/BLUEPRINT.md) · Progress and next steps: [`docs/ROADMAP.md`](docs/ROADMAP.md)

> **Status: Phase 1 (foundation), pre-alpha.** This branch is a ground-up rewrite of the earlier entity-based prototype. Scanning, Segment splitting, ship-space assembly/disassembly, the physics engine and the walker maths are in place. **Assembled Colossi are not drawn yet** (the Segment renderer and the click/collision redirect are Phase 2), so treat assembly as a developer preview. The old `colossalcraft-1.21.1-0.1.0.jar` in the repo root is the previous prototype and is unrelated to this code.

## Build

```bash
./gradlew build              # jar in build/libs/
./gradlew test               # pure-Java tests: physics, IK, gaits, balance, scanner, kinetics (no Minecraft needed)
./gradlew runGameTestServer  # in-world tests: scan, assemble, disassemble with inventories intact
./gradlew runClient
```

## Pillars

1. **Built, not spawned.** Every Colossus is player-placed blocks. The Hull Core assembles; it never generates geometry.
2. **Simulated, not animated.** Motion comes from forces and constraints.
3. **Still a world.** Blocks on a Colossus keep their block entities, redstone, inventories and interactions.
4. **Scale is a material.** Bigger parts are stronger and heavier: an N×N joint is N³ times stronger (data-driven).
5. **Maximum independence, maximum compatibility.** Physics, piloting, gaits, joints and power are all built in.

## How it works (Phase 1)

1. **Build** your machine from normal blocks plus joint blocks. Natural terrain (dirt, grass, natural stone, sand, ores…) is in the `colossus:non_assemblable` tag and is never picked up, so a build can stand on the ground. Use crafted blocks (cobblestone, bricks, planks, metal blocks) for the machine itself.
2. **Joint blocks are Segment boundaries.** Each joint splits the build into rigid parts. The axis comes from the face you place it against. Touching joint blocks of the same kind and axis merge into one bigger joint.

   | Block | Constraint | Use |
   |---|---|---|
   | Thrust Bearing | hinge (±120° by default) | knees, elbows |
   | Ball Bearing | ball (60° cone, ±45° twist) | hips, shoulders |
   | Universal Joint | 2-axis | ankles, wrists |
   | Swivel Bearing | unlimited hinge | turrets, waist, wheels |
   | Slider Joint | linear | telescoping legs |
   | Spring Damper | sprung linear | suspension |
   | Fixed Weld | rigid | lock a sub-assembly |

3. **Place a Hull Core** touching the root part (torso or chassis). Tiers set the budget: Wooden 512 blocks / 8 parts / 8 joints, Iron 4,096 / 32 / 32, Steel 32,768 / 128 / 128, Netherite 131,072 / 512 / 512 (+16,384 blocks per touching **Core Lattice**), Singularity (server cap only).
4. **Right-click** the Hull Core to scan: block count, parts, joints, mass, errors and warnings.
5. **Sneak + right-click** to assemble. Blocks move to a far-away ship-space plot (X/Z ≥ 20,000,000) with their block-entity data intact, and the Colossus becomes a set of physics bodies. Until a walker brain drives them (Phase 2), motorised joints servo-hold the pose they were built in, up to their size-scaled torque rating; overloaded joints creak, then snap after a few seconds.
6. **Disassemble** with `/colossus disassemble nearest` (Hull Core GUI in Phase 2). Each part snaps to the nearest quarter-turn and its blocks go back into the world; it refuses if anything is in the way.

### Commands (operators)

| Command | Does |
|---|---|
| `/colossus scan <pos>` | Scan the build at a Hull Core |
| `/colossus assemble <pos>` | Assemble it |
| `/colossus disassemble nearest` / `<uuid>` | Put a Colossus back into blocks |
| `/colossus list` | Colossi in this dimension, with positions and broken joints |
| `/colossus physics` | Physics backend and last step time |

### Config (`serverconfig/colossus-server.toml`)

`maxBlocksPerColossus` (1,000,000), `maxColossiPerLevel`, `latticeGrowth`, `enableAssembly`, physics `backend` (`auto` / `jolt` / `xpbd`), `rateHz` (60), `xpbdSubsteps`, `gravity`, `airScaleHeight`, joint `holdPose`, `breaking`, `strainBreakSeconds`.

### Data packs

| Path | Contents |
|---|---|
| `data/<ns>/colossus/block_physics/*.json` | Mass (kg per full block), friction, restitution by block id → tag → sound type → default 1,000 kg. Partial blocks scale by collision volume. |
| `data/<ns>/colossus/component_scaling/*.json` | Base torque/load and size exponents per component (bearing, joint, gearbox, shaft, motor, propeller hub, wheel, piston) |
| `data/<ns>/colossus/gaits/*.json` | Phase offsets, duty factor, speed band |
| `data/<ns>/tags/block/non_assemblable.json` | Blocks a scan never picks up |

## Code layout

| Package | What |
|---|---|
| `colossus.core` | Mod entry, registries, config, commands, data loaders, per-dimension manager, terrain cache |
| `colossus.assembly` | Hull Core, joint blocks, scanner (`graph/`), assemble/disassemble, body planning |
| `colossus.shipyard` | Plot allocator and saved data |
| `colossus.physics.api` | `PhysicsBackend` interface: the only door to a physics engine |
| `colossus.physics.xpbd` | Built-in pure-Java XPBD articulated rigid-body solver |
| `colossus.physics.jolt` | Jolt (jolt-jni) backend slot, Phase 2 |
| `colossus.physics.forces` | Drag, lift, buoyancy, propeller thrust, air density |
| `colossus.brain` | IK (analytic 2-bone, digitigrade, FABRIK, CCD, damped least squares), gait planner, foot targeting, swing paths, balance controller |
| `colossus.kinetics` | Size-based component scaling, rotational network with speed sag and jams |
| `colossus.net` / `colossus.client` | Pose sync (smallest-three quaternions) and the client pose buffer |
| `colossus.compat` | Optional-mod detection; compat modules live here only |

`tools/gen_assets.py` regenerates textures, models, loot tables, recipes, tags and default data.
