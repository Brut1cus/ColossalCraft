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
| Orientation servos for ball and universal joints (`PhysicsBackend.setMotorOrientation`: 3-axis PD toward a rotation relative to the assembled pose, clamped to the joint's rated torque), so `joints.holdPose` now holds ball hips too and a quadruped stands instead of flopping; the brain's IK drives the same servo. Fixes found on the way: universal joints snapped 90° at creation (planner gave both sides the same axis); joint blocks weighed 7.8 t, more than a 1x1 joint's own load rating (now 1 t); strain was integrated per game tick, so back-to-back ticks recounted a stale snapshot and snapped joints early (now per simulated second); a new Colossus could free-fall for a lag spike before its terrain was cached (now cached at spawn) | done |
| Gyro Stabilizer (blueprint "stabilizer assist" / hovercraft gyro): powered control-moment gyro. Torque capacity = rotor angular momentum I·ω × gimbal rate; an N×N square merges into one rotor with N⁴ the inertia; bigger rotors spin up slower; a momentum budget means it rejects disturbances but cannot hold a permanent lean. Redstone sets the throttle until kinetic power exists. Runs every physics step via `PhysicsWorld.StepController`; `/colossus gyros` shows rpm, capacity and saturation. Rotors spin up in the world and keep their speed through assembly. A gyro steadies its own Segment and whatever is joined to it by torque-carrying joints; unmotorised ball bearings pass no torque, so a gyro on a torso cannot stop free-hipped legs tipping (seen in a client test: the torso stayed level 4 s longer, then the legs went). Walkers still need the brain's hip and knee servos | done |

| Walker brain, part 1 (`brain.walker`, pure Java, not wired into the game yet): limb discovery (root-to-foot joint chains → IK chains), exact ball-hip + hinge-knee leg IK, and `WalkerBrain` as a physics-step controller: desired body pose from the command, lateral-sequence walk with body sway toward the next support triangle, a static-stability check on CoM and capture point, footholds clamped to the leg's reach, touchdown planted where the foot really lands, IK against the desired pose, joint servos. Servos are now stiff enough to hold a loaded leg within a few degrees (full torque at 0.05 rad). Tested: IK exact, servos land feet where IK says, the machine stands under the brain, the first step lands near its foothold upright | done |
| Walker brain, part 2: sustained walking. Ankles (a ball or universal joint at the end of a leg keeps the foot level; legs without one rock on their foot edges), a crawl gait (duty 0.9, four-legged phases between swings), body sway proportional to the time between lift-offs, turning on the spot, and a servo budget sized to the machine's weight. A plank-built 2x2 quadruped walks ~0.45 m/s at under 2° of tilt; a 63 t stone-decked one too | done |
| Walker in the game: legged Colossi get a walker brain on spawn (Foot Pad Segments count as feet; rebuilt when a joint snaps); `/colossus walk <forward> [turn]` and `/colossus stop`; server config `[walker]` (enabled, maxSpeed, crouch, stepHeight, turnRate) | done |
| Pilot Seat: sit on it (before or after assembly) to drive the Colossus with W/S and A/D; a rideable seat entity follows the seat block on the moving body; `PilotInput` payload client → server, checked against the rider, stops the machine when input stops | done |
| **Gate:** a quadruped walks 50 blocks upright (GameTest `quadrupedWalksFiftyBlocks`) | see CI |

Still to do in Phase 2:

- Interaction redirect mixins: client pick, server reach checks, container `stillValid`, sound/particle positions.
- Entity carrying and ship-local mode; entity collision against Segments.
- Assembly GUI (3D preview, colour-coded Segments, CoM and support polygon), Rigging GUI (limits, gains, solver, pole vector), Gait Editor.
- Walker brain wired in: limb discovery, gait planner → foot targets → IK → joint motor targets, balance controller torques.
- Jolt backend via jolt-jni (jar-in-jar natives) behind `PhysicsBackend`; body-vs-body contacts and CCD in XPBD.
- Control discovery and binding profiles (more controls than walk and turn: gaits, crouch, joints, weapons).
- **Gate:** a quadruped walks 50 blocks upright (GameTest).

## Phase 3+: per the blueprint

Wheels and drivetrains → propellers, wings, balloons, hovercraft → kinetic network blocks (shafts, gearboxes, motors, scaling multiblocks) → energy eras and heat/airflow → materials and tech tree → demolition and ground pressure → weapons → nested Colossi, blueprints, wild Colossi.

## Decisions taken where the blueprint disagreed with itself

- **Joint ratings and block masses:** the first component ratings (1x1 bearing 20 kN·m, 50 kN) could not hold one iron block (7.8 t) at arm's length, and a plank-built walker on 2x2 joints could barely move. Base ratings are 5x higher (bearing and joint 100 kN·m, 250 kN; still × N³ / N^2.5 for merged joints), joint housings weigh 250 kg (hollow) and Hull Cores 1 t (one controller block, not a lump of metal). All of it is data (`component_scaling`, `block_physics`).
- **Quadruped gaits:** the blueprint lists walk, trot, pace and gallop. A slower statically stable crawl (the walk's lateral sequence at duty 0.9) is the default under 0.6 m/s: at duty 0.75 exactly three feet are always down and walkers could not shift their weight safely.
- **Quadruped walk order:** the gait list says "walk (lateral sequence)", but the example offsets in the gait table (0, 0.5, 0.75, 0.25 for LF, RF, LH, RH) give a diagonal sequence under the planner's phase convention, which needs large diagonal body sways. The walk uses 0.5, 0, 0.75, 0.25: legs lift LH, LF, RH, RF.

- **Gyro power:** the blueprint says the Gyro Stabilizer is "powered" but kinetic and electric power arrive in Phase 3+. Until then redstone signal strength is its throttle; it will also accept shaft power or FE when those exist.

- **Name:** display name ColossalCraft, mod id `colossus` (the blueprint also mentions "ColossusCraft" and "Colossus Arise").
- **Material tiers vs size scaling:** the later "Size-Based Mechanical Scaling" section wins. Components share one baseline material; capability = base × N^exponent per component type, all in `component_scaling` data. The earlier 2x2 = 6×, 3x3 = 20×, 4x4 = 48× table is not a single power law; the default exponent is 3 (≈ N³, "cross-section × radius").
- **Ship space:** far plots in the same level (the stated decision), not a `colossus:ship_space` dimension.
- **Physics backend:** Jolt is the primary backend in the design, but it needs bundled natives; until those are wired, the pure-Java XPBD backend (listed as the fallback in the package table and risk table) runs everything.
- **Joints at rest:** with no brain yet, motorised joints hold their assembled pose (config `joints.holdPose`), so a walker stands like a statue instead of collapsing.
