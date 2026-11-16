# ColossalCraft: notes for Claude

NeoForge 1.21.1 mod (Java 21), mod id `colossus`, display name ColossalCraft. Players build machines from blocks, a Hull Core assembles them into physics-driven Colossi.

## Source of truth
- Design: `docs/BLUEPRINT.md`. Status, next phase and decisions where the blueprint contradicts itself: `docs/ROADMAP.md`. Update ROADMAP when a phase item lands.
- Current phase: Phase 2 (renderer, interaction redirect, walker brain wiring, GUIs). See ROADMAP for the gate. The vanilla Segment renderer is in (`client.render`); the client gets Segment blocks from `net.SegmentBlocks`, never from ship-space chunks.

## Commands
- `./gradlew test` — pure-Java unit tests (fast, no Minecraft). Run after any change under `physics/`, `brain/`, `kinetics/`, `assembly/graph/`.
- `./gradlew build` — compile + unit tests.
- `./gradlew runGameTestServer` — in-world GameTests (`colossus.test.ColossusGameTests`, template `empty16`).
- `python3 tools/gen_assets.py` — regenerates textures, models, blockstates, loot, recipes, tags, lang and default data. Edit the script, not the generated JSON.
- CI (`.github/workflows/build.yml`) runs build + GameTests on every PR and push to `main`. If local network is blocked, push a branch and read the run logs with `gh api repos/Brut1cus/ColossalCraft/actions/...`.

## Architecture rules
- The physics thread never touches Minecraft objects. Packages `physics.*`, `brain.*`, `kinetics`, `assembly.graph`, `assembly.BodyPlanner`, `assembly.JointKind`, `shipyard.PlotAllocator`, `net.PoseCodec`, `net.PoseInterpolation`, `client.render.SegmentTransform`, `core.data.BlockPhysicsTable` stay pure Java (no `net.minecraft`/`net.neoforged` imports) so they are unit-testable.
- All engine access goes through `physics.api.PhysicsBackend`. XPBD is the working backend; Jolt is a stub slot.
- Only `colossus.compat.*` may import other mods' code, guarded by `ModList.isLoaded`. NeoForge is the only required dependency.
- Frames: Segment cells and joint anchors are stored relative to the Hull Core block ("core frame"); a body's local frame is the core frame shifted to that Segment's centre of mass.
- Recipes use `c:` tags, never our own material items. Tunables go in data packs (`data/<ns>/colossus/...`) or `ColossusConfig`, not constants.

## Conventions
- Work on a branch and open a PR; keep CI green, no compiler warnings (`-Xlint:deprecation,unchecked` is on).
- Add a unit test for new maths and a GameTest for new in-world behaviour.
