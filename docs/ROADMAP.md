# Roadmap

Build the hardest shared foundation first (ship space, interaction and the physics backend), then prove each locomotion type with one playable test before adding content. Walkers come before flight because they stress the joint and IK systems that everything else reuses. Full design: [`BLUEPRINT.md`](BLUEPRINT.md).

## Phase 1: foundation (this branch)

| Area | Status |
|---|---|
| Mod id `colossus`, one jar, NeoForge only, every other mod optional | done |
| Hull Cores (Wooden → Singularity) with budgets, Core Lattice growth, server cap | done |
| Joint blocks: thrust, ball, universal, swivel, slider, spring damper, fixed weld; same-kind neighbours merge (N×N footprint) | done |
| Scanner: flood fill from the core, `colossus:non_assemblable` tag, world-eating guard, Segment split at joints, Segment Glue hook, graph oriented away from the core, budget and support checks | done |
| Mass, centre of mass and inertia tensor per Segment from block cells (data-driven block physics table) | done |
| Ship space: far plots at X/Z 20,000,000 on a 1024 grid, plot reuse, chunk force-loading | done |
| Assemble / disassemble with block entities and inventories intact; quarter-turn snap; refuse when blocked | done |
| `PhysicsBackend` interface; physics thread at a fixed rate with command queue and immutable snapshots | done |
| XPBD solver: rigid bodies, gravity, forces, all joint types with limits, PD motors, breaking, voxel terrain contacts with friction, restitution, sleeping | done |
| Joint strain meter (load vs size rating) → snap | done |
| Forces library: drag, air density by altitude, lift with stall, buoyancy, propeller thrust and wash, balloon lift | done (not yet applied to Colossi) |
| Brain maths: IK solvers, gait patterns and planner (hysteresis, stability rule), Raibert foot targeting with spiral search, Bézier swing, support polygon, capture point, wrench distribution with friction cones, τ = JᵀF | done (not yet driving Colossi) |
| Kinetics: size scaling, rotational network with sag, gear ratios, jams, overload | done (no blocks yet) |
| Pose sync payload (smallest-three quaternions) and client pose buffer | done |
| Unit tests (55) and GameTests (scan, terrain exclusion, assemble/disassemble keeping a chest's contents), all passing | done |
| CI: GitHub Actions builds, runs unit tests and GameTests on every PR and on `main`, and uploads the jar | done |

## Phase 2: see it and touch it

| Area | Status |
|---|---|
| Segment renderer, vanilla path: Segment blocks synced to clients (`SegmentBlocks`, split into parts, sent on assembly/login/dimension change; `ColossusRemoved` on disassembly), compiled per chunk layer into vertex buffers, drawn with the vanilla chunk shaders at the pose interpolated between the last two `PoseSync` packets, frustum-culled | done |
| Segment renderer follow-ups: block entity renderers on board (chests, signs) and block-entity model data, live per-block light (now: world light sampled at the centre of mass, mesh rebuilt at most once a second when it changes), translucent sorting, resync when blocks change on board, 16³ render sub-regions for huge Segments | to do |
| Segment renderer: Sodium and Iris paths in `colossus.compat` (the vanilla path runs alongside them until then) | to do |
| Gyro Stabilizer (blueprint "stabilizer assist" / hovercraft gyro): powered control-moment gyro. Torque capacity = rotor angular momentum I·ω × gimbal rate; an N×N square merges into one rotor with N⁴ the inertia; bigger rotors spin up slower; a momentum budget means it rejects disturbances but cannot hold a permanent lean. Redstone sets the throttle until kinetic power exists. Runs every physics step via `PhysicsWorld.StepController`; `/colossus gyros` shows rpm, capacity and saturation | done |

Still to do in Phase 2:

- Interaction redirect mixins: client pick, server reach checks, container `stillValid`, sound/particle positions.
- Entity carrying and ship-local mode; entity collision against Segments.
- Assembly GUI (3D preview, colour-coded Segments, CoM and support polygon), Rigging GUI (limits, gains, solver, pole vector), Gait Editor.
- Walker brain wired in: limb discovery, gait planner → foot targets → IK → joint motor targets, balance controller torques.
- Jolt backend via jolt-jni (jar-in-jar natives) behind `PhysicsBackend`; body-vs-body contacts and CCD in XPBD.
- Pilot Seat, control discovery and binding profiles, control-state payload.
- **Gate:** a quadruped walks 50 blocks upright (GameTest).

## Phase 3+: per the blueprint

Wheels and drivetrains → propellers, wings, balloons, hovercraft → kinetic network blocks (shafts, gearboxes, motors, scaling multiblocks) → energy eras and heat/airflow → materials and tech tree → demolition and ground pressure → weapons → nested Colossi, blueprints, wild Colossi.

## Decisions taken where the blueprint disagreed with itself

- **Gyro power:** the blueprint says the Gyro Stabilizer is "powered" but kinetic and electric power arrive in Phase 3+. Until then redstone signal strength is its throttle; it will also accept shaft power or FE when those exist.

- **Name:** display name ColossalCraft, mod id `colossus` (the blueprint also mentions "ColossusCraft" and "Colossus Arise").
- **Material tiers vs size scaling:** the later "Size-Based Mechanical Scaling" section wins. Components share one baseline material; capability = base × N^exponent per component type, all in `component_scaling` data. The earlier 2x2 = 6×, 3x3 = 20×, 4x4 = 48× table is not a single power law; the default exponent is 3 (≈ N³, "cross-section × radius").
- **Ship space:** far plots in the same level (the stated decision), not a `colossus:ship_space` dimension.
- **Physics backend:** Jolt is the primary backend in the design, but it needs bundled natives; until those are wired, the pure-Java XPBD backend (listed as the fallback in the package table and risk table) runs everything.
- **Joints at rest:** with no brain yet, motorised joints hold their assembled pose (config `joints.holdPose`), so a walker stands like a statue instead of collapsing.
