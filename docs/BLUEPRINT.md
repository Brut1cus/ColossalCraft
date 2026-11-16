# Colossus Mod — Design Blueprint (NeoForge 1.21.1)

Nov 16, 2026 · @Bru2cus

> Source of truth for ColossalCraft. Copied from the ColossalCraft project doc. Where sections disagree, see "Decisions taken where the blueprint disagreed with itself" in [`ROADMAP.md`](ROADMAP.md).

## Vision and pillars

Players build a machine block by block in the world, place a Hull Core, and the Core turns those blocks into one living Colossus that walks, drives, sails or flies on real physics. Nothing is a canned animation: legs plant feet, thrust pushes mass, lift fights gravity, and every block on board stays a normal, usable block.

Working title: **ColossalCraft** (mod id `colossus`). Display name and repository: ColossusCraft (mod id stays colossus). Target: NeoForge 1.21.1, Java 21, shipped as one self-contained jar whose only requirement is NeoForge.

Five pillars every feature is judged against:

1. **Built, not spawned.** Every Colossus is player-placed blocks. The Hull Core assembles; it never generates geometry.
2. **Simulated, not animated.** Motion comes from forces and constraints. Walkers move because feet push the ground, not because a keyframe says so.
3. **Still a world.** Blocks on a Colossus keep their block entities, redstone, Create kinetics, inventories and interactions. You can walk around inside it while it moves.
4. **Scale is a material.** Bigger parts are stronger, heavier and visually grow (2x2 bearings become bigger bearings). Mass and material tier decide what a joint can carry.
5. **Maximum independence, maximum compatibility.** Every system (physics, piloting, gaits, joints, turret aim, scripting, power) is built in and needs no other mod. Other mods are welcome extras: tags for materials, soft compat modules that load only when present, and no rendering hacks that break Sodium or Iris.

**Glossary**

| Term | Meaning |
| --- | --- |
| Colossus | One assembled machine: a tree of Segments driven by one Hull Core |
| Hull Core | Controller block that scans, assembles, disassembles and runs the brain of a Colossus |
| Segment | A rigid body: a connected group of blocks that moves as one (torso, thigh, shin, foot, rotor) |
| Joint | A constraint between two Segments (hinge, ball, slider, fixed, wheel axle) |
| Actuator | Anything that applies force or torque: motor-driven joints, thrusters, propellers, wheels |
| Limb | An ordered chain of Segments and Joints ending in an end effector (foot, wheel, claw) |
| Gait | A rule set that decides which feet step when and where |

## Core architecture: Hull Core, Segments and the ship world

A Colossus is a tree of rigid Segments joined by Joints, with its blocks moved into a hidden "ship space" region and rendered and collided at their live world transform. This is the proven Valkyrien Skies / Create Aeronautics (Sable) approach, and it is the only approach that keeps every block fully functional.

### Build-to-life flow

1. **Build** the machine in the world from normal blocks plus Colossus parts (bearings, joints, thrusters, wheels).
2. **Mark Segments.** Joint blocks are natural boundaries: a hinge joint splits the thigh from the shin. Optional **Segment Marker** tool or **Segment Glue** lets players override (merge or split) where the flood fill would guess wrong.
3. **Place the Hull Core** on the root Segment (usually the torso or chassis). It must touch the build.
4. **Scan.** The Core flood-fills connected blocks (ignoring air, fluids-as-set, and blocks tagged `colossus:non_assemblable`), splits at joint blocks, and builds the Segment graph. The Assembly GUI previews it.
5. **Validate.** Checks: block budget, every joint within its tier load at rest, at least one support (feet, wheels, lift or buoyancy) or a warning, power source present for actuators, no loose islands.
6. **Assemble.** Blocks move to ship space (one plot per Segment), block entities are serialized and restored with NBT intact, and the Colossus spawns as one physics object group.
7. **Disassemble** (Core GUI or redstone signal) snaps every Segment to the nearest grid alignment and places blocks back, refusing (with a ghost preview) if the target space is blocked.

### Data model

| Object | Holds | Lives in |
| --- | --- | --- |
| `Colossus` | UUID, owner, Segment tree, joint list, brain state, power network, controller bindings | `ColossusSavedData` per dimension |
| `Segment` | Plot in ship space, AABB, block mass grid, center of mass, inertia tensor, pose (position + quaternion), velocities | Physics world + saved data |
| `Joint` | Type, parent and child Segment, anchor in each local frame, axis, limits, tier, motor target, current stress | Physics world |
| `Limb` | Ordered joint chain, end effector, foot contact shape, rest pose, IK settings | Brain |
| `ShipPlot` | Reserved chunk region in a dedicated `colossus:ship_space` dimension or far coordinates | Level storage |

### Ship space choice

Decision: **far-away plots in the same level as the Colossus** (the Valkyrien Skies approach), starting at X and Z = 20,000,000 on a 1024-block grid. Blocks tick there as real blocks in the Colossus's own level, so redstone, block entities, the level's time, weather and rules, and other mods' "same level" checks all work unchanged. No extra dimension is registered, so worlds open without Minecraft's experimental-settings warning. Plot chunks are force-loaded while their Colossus exists, and terrain physics ignores the plot region. A single plot is limited to the level's build height, so taller Segments use stacked plots.

### Interacting with blocks on board

The player's ray is transformed into each Segment's local frame (inverse pose), tested against the plot, and the hit is redirected to the real block in ship space. Placing, breaking, opening GUIs, right-click, Create wrench, and item use all go through that redirect. Mixins needed:

- `GameRenderer.pick` / `ProjectileUtil` for the client raycast
- `ServerGamePacketListenerImpl` reach-distance checks (distance measured in world space)
- `Level.getBlockState` wrappers for entity collision queries near Colossi
- Container `stillValid` checks (player distance uses world transform)
- Sound and particle positions (emit at transformed coordinates)

### Entities on board

Entities standing on a Segment are **carried**: each tick they inherit the Segment's delta transform (position + yaw), like VS2. Entities inside an enclosed hull switch to **ship-local mode** while inside: their position is stored relative to the Segment so a fast airship does not leave the crew behind. Mobs path-find on board by running vanilla pathfinding inside ship space.

### Nested Colossi

A Hull Core inside another Colossus creates a **child Colossus**. It has its own Segment tree and physics, but its root is constrained to the parent Segment by a **Dock joint** (fixed when docked, released on command). Use cases: a walker carrying a gunship, a carrier deploying cars, a rotating turret that is itself a Colossus. Depth limit configurable (default 3). Child physics run in the parent's island so the solver handles them together.

### Hull Core tiers

| Core | Block budget | Segments | Joints | Brain features |
| --- | --- | --- | --- | --- |
| Wooden Core | 512 | 8 | 8 | Wheels, rudders, simple boats |
| Iron Core | 4,096 | 32 | 32 | Walkers up to 4 legs, basic autopilot |
| Steel Core | 32,768 | 128 | 128 | Any leg count, flight controller |
| Netherite Core + Core Lattice | 131,072 | 512 | 512 | Nested Colossi, full scripting |
| Singularity Core | config cap | config cap | config cap | Everything, zero-point powered |

All budgets are config values; server owners set the ceiling.

### Extending the Core: Core Lattice (survival path to massive builds)

From the Netherite tier up, a Hull Core grows by building more Core onto it, so the limit on size is the player's resources, not a fixed number.

- **Core Lattice blocks** (netherite plate + echo shard + redstone circuitry) placed touching the Netherite Core, or touching another Lattice block, form one multiblock brain. Each Lattice block adds +16,384 block budget, +32 Segments and +32 joints. The Core GUI shows a 3D view of the lattice and its totals.
- **Lattice Cooling:** past 16 Lattice blocks, the brain makes heat and needs radiators (see Heat, radiators and airflow), tying size to engineering.
- **Relay Cores** on distant Segments (a leg 200 blocks from the torso) take local work: limb IK, foot targeting and stress checks run on the nearest relay, and they count toward the lattice budget. A relay that is destroyed leaves its limb limp until repaired.
- **Singularity Core** (zero-point tier) removes the lattice cap entirely; only the server config cap applies.
- Server config sets a hard ceiling (`maxBlocksPerColossus`, default 1,000,000) and can disable lattice growth.

### Larger than build height

A Colossus can stand taller than the world's build height (384 blocks in the Overworld) because its blocks never live in the world column.

- A plot spans its level's full build height. One Segment taller than that is stored as several stacked plots side by side, stitched into one rigid body.
- Building a Colossus taller than build height: assemble it in parts (each under the height limit), then join them with Dock joints or weld them in the Core GUI. A **Gantry Builder** lets players keep adding blocks on top of an assembled Colossus, since placing blocks on board writes to ship space, not the world.
- Rendering, entity collision and interaction already use world transforms, so blocks above Y 320 render, are walkable and can be used like any other. Players and mobs can stand on them (vanilla allows entities above build height).
- Terrain contacts only exist inside build height, which is the only place feet touch ground anyway.
- Very large Segments are split into render and collision sub-regions (16³ sections) so frustum culling and broadphase stay fast, while physics treats the Segment as one body.

## Physics engine

Run a real rigid-body solver with articulated joints on a dedicated server thread at a fixed 60 Hz (3 substeps per 20 Hz game tick), and sync poses to the game thread with interpolation. Decision: no other mod is involved. Physics ships inside the Colossus jar, either as a bundled, well-established library or as the mod's own solver.

### Engine choice

| Option | Packaging | Pros | Cons | Verdict |
| --- | --- | --- | --- | --- |
| **Jolt Physics** via jolt-jni (a plain library, not a mod) | Bundled inside the mod jar with NeoForge jar-in-jar; natives for Windows, macOS and Linux (x64 + ARM) extracted at launch | Fast, stable, multithreaded; motorized hinge, swing-twist and slider constraints built in; used in shipped AAA games | Native code per platform; bigger jar | **Primary backend** |

Valkyrien Skies and other ship mods are **not** dependencies. Colossus implements its own ship space, interaction and rendering, and only detects those mods to avoid double-assembling the same blocks.

Keep the engine behind a `PhysicsBackend` interface (create body, add constraint, step, query contacts) so the backend can be swapped. All mod logic talks to that interface only.

### Mass, inertia and center of mass

Every block gets a mass in kilograms from a data-driven table, resolved in this order: exact block id, then block tag, then material sound-type fallback, then default 1,000 kg.

| Example block | Mass (kg) | Friction | Restitution |
| --- | --- | --- | --- |
| Wool, leaves | 50 | 0.6 | 0.1 |
| Planks, logs | 400 / 600 | 0.5 | 0.2 |
| Glass | 800 | 0.3 | 0.1 |
| Stone, cobble | 1,600 | 0.7 | 0.1 |
| Iron block | 7,800 | 0.4 | 0.2 |
| Steel block | 7,850 | 0.4 | 0.2 |
| Lead block | 11,300 | 0.5 | 0.05 |
| Gold block | 19,300 | 0.3 | 0.1 |
| Obsidian | 2,600 | 0.6 | 0.05 |
| Netherite block | 15,000 | 0.5 | 0.1 |

Values live in `data/<ns>/colossus/block_physics/*.json` so packs and other mods override them. Partial blocks (slabs, stairs, panes) scale mass by their collision-shape volume. Block entities can add contents mass (a full chest of iron weighs more) via an optional `IMassContributor` capability, recomputed when inventories change (throttled).

Per Segment: total mass, center of mass, and a 3x3 inertia tensor are summed from block cells (each cell a cube with the parallel-axis theorem). Recomputed incrementally when a block is placed or broken on board.

### Collision

- **Shape:** each Segment is a compound of boxes. Merge block collision shapes with greedy meshing into the fewest boxes; slabs, stairs and fences keep their real voxel shapes.
- **Colossus vs world terrain:** terrain chunks near a body are fed to the backend as static voxel/heightfield shapes, streamed in and out as bodies move (cache per chunk section, invalidate on block change).
- **Colossus vs Colossus:** normal body-body contacts.
- **Self-collision:** on by default between non-adjacent Segments; adjacent pairs (directly jointed) are filtered so a knee does not fight its own thigh. Per-joint toggle in the Rigging GUI.
- **Entities:** players and mobs collide against transformed block shapes using vanilla's movement code (mixins into `Entity.collide`), so they stand on decks and bump into legs.
- **CCD:** continuous collision for fast Segments (jets, rotor tips) to prevent tunnelling.
- **Impact damage (optional, config):** contact impulses above a block's blast-resistance-derived threshold break that block, so crashes deform hulls.

### Forces applied every substep

- **Gravity:** 9.81 m/s² per block-meter by default, scaled per dimension (configurable: lower in the End, custom for space mods).
- **Friction:** Coulomb friction from the two contact materials (geometric mean), with static vs kinetic split. Ice, packed ice and blue ice are near zero; soul sand and honey are high. Feet can carry grip pads (rubber, spikes) that raise it.
- **Drag:** quadratic, per exposed face: F = 0.5 · ρ · v² · Cd · A, where A is the projected area of faces on the windward side (computed from the voxel grid, cached per orientation bucket). Air density ρ falls with altitude (configurable curve) so aircraft have a ceiling.
- **Buoyancy:** submerged volume from block cells touching water/lava source and flowing blocks; F = ρ\_fluid · g · V. Hollow sealed hulls count interior air cells via a flood fill ("airtight hull" check) so boats float.
- **Lift:** from wing and sail blocks (see Propulsion).
- **Thrust and torque:** from actuators.
- **Joint motor torque:** from the kinetic network and walker controller.

### Stress and failure

Every joint reads its constraint impulse each step. Load over the tier rating raises a **strain meter**. Sustained overload makes the joint creak (sound + particles), then **slip** (motor loses its target), then **break** (constraint removed; the child Segment becomes a free body or the block drops). Shown live in the Goggles overlay and the Core GUI.

### Performance budget

- Physics thread separate from the server tick; the game thread only reads the last completed snapshot.
- Sleeping: bodies at rest go to sleep and cost nothing.
- Islands: unconnected Colossi solve in parallel.
- LOD: Colossi more than N chunks from any player freeze (or run a simplified point-mass simulation if they are mid-flight).
- Network: send pose deltas per Segment at 20 Hz, quantized quaternions (smallest-three, 10 bits each), client interpolates; no full-block resyncs while moving.
- Target: 10 walkers of 2,000 blocks and 8 joints each at under 4 ms per tick of physics thread time.

## Walkers: legs, joints, IK and balance

Walkers move because their feet push the ground. The brain picks footholds, IK turns footholds into joint angles, joint motors apply torque to reach those angles, and the physics engine moves the whole body from the resulting ground contacts. No layer ever teleports the torso.

*(Diagram in the project doc: walker control loop, brain to physics and back.)*

The brain only ever outputs joint targets and torques; the measured body state feeds the next gait and balance decision.

### Joint blocks

| Joint block | DOF | Use | Built from |
| --- | --- | --- | --- |
| **Thrust Bearing** (hinge) | 1 rotational, with limits | Knees, elbows, ankle pitch, digitigrade hocks | Bearing + shaft; axis = shaft direction |
| **Ball Bearing** (spherical) | 3 rotational, cone + twist limits | Hips, shoulders, neck | Socket housing + ball |
| **Universal Joint** | 2 rotational | Ankles (pitch + roll), wrists | Two crossed bearings |
| **Swivel Bearing** | 1 rotational, unlimited | Turrets, waist yaw | Turntable |
| **Piston / Slider Joint** | 1 linear | Telescoping legs, shock absorbers, mech pistons | Hydraulic cylinder blocks |
| **Spring Damper** | 1 linear, passive | Suspension, foot pads | Coil block |
| **Fixed Weld** | 0 | Rigidly lock a sub-assembly | Weld block |

Every joint has a **material tier** (see Kinetics) that sets max torque, max holding load and max speed, and its size scales with the multiblock footprint (1x1 to 5x5). Players set limits by right-clicking with a Wrench or in the Rigging GUI, with an on-screen arc gizmo showing the allowed range.

### Leg topologies

Any number of legs, any number of joints per leg. The brain discovers limbs automatically: every chain from the root Segment that ends in a Segment touching the ground at rest (or tagged with a **Foot Pad** block) is a limb.

| Layout | Legs | Default gait | Notes |
| --- | --- | --- | --- |
| Biped | 2 | Walk, run | Needs active balance; hardest |
| Tripod | 3 | Tripod step (one leg at a time) | Statically stable when 2 feet down with CoM inside |
| Quadruped | 4 | Walk (lateral sequence), trot, pace, gallop | Most forgiving |
| Hexapod | 6 | Alternating tripod, wave, ripple | Insect walkers |
| Octopod+ | 8+ | Wave, ripple | Spiders, centipedes; gait phase offsets generated |
| Hybrid | any | Mixed | Legs plus wheels (wheel-legs), legs plus thrusters (jump jets) |

**Plantigrade vs digitigrade vs unguligrade** is not a special mode. It falls out of the chain: a digitigrade leg is hip, knee, hock (reversed hinge), ankle, toe. The IK solver handles any chain; presets in the Rigging GUI just pre-fill joint limits and the preferred bend direction (pole vector) so a reverse knee bends backward.

### IK solvers

Solvers run on the server brain each control tick (20 Hz, with targets interpolated across physics substeps). Choose per limb, with automatic defaults:

| Solver | When used | Why |
| --- | --- | --- |
| **Analytic 2-bone** | Exactly 2 hinges in a plane (thigh + shin) | Exact, cheap, no iteration |
| **Analytic 3-bone digitigrade** | Hip, knee, hock with a hock-angle coupling ratio | Solves as 2-bone with a locked ratio, keeps the animal look |
| **FABRIK** | Chains of 3 to 12 joints | Fast, robust, natural poses; projects onto each joint's constraint after each pass |
| **Damped Least Squares (Jacobian)** | Any chain incl. ball joints and sliders; when orientation of the foot also matters | Handles mixed DOF, limits and a secondary goal (keep the foot flat, avoid self-collision) via null-space projection |
| **CCD** | Tails, tentacles, cranes | Simple, good for long whip chains |

Common features for all solvers:

- **Joint limits** respected every iteration (clamp hinge angle, clamp ball cone and twist).
- **Pole vector** per limb so knees bend the intended way.
- **Foot orientation goal:** align foot to the ground normal from the raycast (ankles absorb slopes).
- **Reach check:** a target out of reach returns the closest pose plus a flag the gait planner reads to shorten stride.
- **Warm start** from the previous solution so poses do not flip between frames.

### Foot targeting (where to step)

Each limb has a **home point**: where its foot sits at rest relative to the body. Each control tick, for each limb:

1. Predict body pose at touchdown: current position + velocity × (time until this foot lands), plus yaw rate.
2. Desired foothold = predicted home point + a **Raibert term** k · (v − v\_desired) that places the foot further ahead when moving faster than desired. This is what keeps a biped from falling over at speed.
3. **Raycast down** (and against terrain and other Colossi) from above the desired foothold to find the ground. Reject if: no ground within leg reach, slope steeper than the foot's max (config, default 45°), fluid or lava, block tagged `colossus:unsteppable`, or occupied by another foot.
4. If rejected, **search** a spiral of nearby candidate points ranked by distance from the ideal, ground flatness and height change.
5. Commit the foothold when the foot lifts. Re-target mid-swing only if the ideal moves more than a threshold (prevents jitter).

### Step trajectory (swing phase)

The foot follows a curve from lift-off point to foothold: a cubic Bézier whose apex height is max(step height setting, obstacle height along the path + clearance). Obstacles between are found by a sweep along the path. Swing duration comes from the gait. The IK target follows this curve; the joint motors chase the IK angles.

### Stance phase

A planted foot is a contact, not a target. Its friction against the ground is what lets the motors push the body forward. Stance legs share the job of holding the body up through the balance controller.

### Balance controller

The torso is never placed. It is held up and steered by torques from stance legs:

1. **Support polygon** = convex hull of planted feet.
2. **Desired CoM** = above a point inside that polygon, biased toward the next foothold during transfer. For dynamic gaits (biped walk, trot, gallop) use the **capture point** (CoM + velocity · sqrt(height / g)) instead of plain CoM.
3. A **PD controller** on torso height, pitch, roll and yaw produces a desired wrench (force + torque) on the body.
4. That wrench is distributed across stance feet (least-squares with friction-cone limits so no foot is asked to pull on the ground or slide).
5. Each foot's force is mapped to joint torques by the transposed Jacobian (τ = JᵀF) and added to the IK tracking torques.
6. **Fall detection:** if the capture point leaves every reachable foothold, trigger a recovery step (fastest leg swings to the capture point) or, failing that, a controlled crouch.

The result: a walker that sways, leans into turns, catches itself when shoved, and falls over for real if you knock out a knee joint.

### Gait planner

A gait = for each leg a phase offset and a duty factor (fraction of the cycle on the ground). The planner advances a phase clock at a frequency from commanded speed.

| Gait | Phase offsets (example quadruped LF, RF, LH, RH) | Duty factor |
| --- | --- | --- |
| Walk | 0, 0.5, 0.75, 0.25 | 0.75 |
| Trot | 0, 0.5, 0.5, 0 | 0.5 |
| Pace | 0, 0.5, 0, 0.5 | 0.5 |
| Gallop | 0, 0.1, 0.6, 0.5 | 0.35 |
| Wave (n legs) | i / n | (n − 1) / n |

Adaptive behaviour:

- Speed picks the gait automatically (walk to trot to gallop) with hysteresis.
- A leg that cannot reach its foothold delays its lift-off; neighbours shorten stride.
- On a stairs or cliff edge, stride and body height adjust to the terrain height map sampled ahead.
- **Stability margin rule:** never lift a leg if the remaining support polygon would leave the CoM unsupported (static gaits), unless the gait is marked dynamic.
- Players can author custom gaits in the **Gait Editor** (phase grid per leg) and save them as data.

### Turning, strafing and special moves

Commanded motion is a body twist (forward, strafe, yaw rate, height, pitch). Home points rotate with yaw rate so feet trace arcs and the walker turns in place. Extra moves: crouch, rear up, jump (all legs extend at max motor torque), kneel/park (lowers to the ground and locks joints so it costs no power), climb (needs grab feet), and swim (paddling gait if buoyant).

### Joint motors

Each jointed hinge is a PD servo: τ = Kp · (θ\_target − θ) − Kd · ω + τ\_feedforward, clamped by the joint tier's max torque and by the power available on the kinetic or electric network. Low power = weak, slow limbs; underpowered legs buckle under heavy torsos, which is the core engineering puzzle.

## Propulsion, aerodynamics and wheels

Every force-producing block applies its force at its own position on its Segment, so an off-center thruster spins the craft and a lopsided balloon tilts it. Players balance their builds the same way real engineers do.

### Propellers and rotors

Built from a **Propeller Hub** on a shaft plus **Blade** blocks attached radially (blades are blocks, so blade count, length and material are physical choices). The hub assembles into a spinning sub-Segment.

Thrust uses simplified momentum theory:

```latex
T = C_T \cdot \rho \cdot n^2 \cdot D^4
```

- n = RPM / 60 from the shaft speed, D = rotor diameter from blade length, ρ = air (or water) density, C\_T from blade pitch and blade count (data table).
- Total blade area raises C\_T until solidity saturates (diminishing returns past \~4 blades).
- **Negative thrust:** reversing shaft direction or setting negative pitch (Pitch Control block, redstone analog) reverses T. Reverse thrust brakes airships and backs up boats.
- **Torque reaction:** the rotor applies counter-torque to its Segment, so single-rotor helicopters need a tail rotor or counter-rotating pair.
- **Medium:** in water the same block is a ship screw (ρ \~800× air, so tiny screws work). Cavitation caps RPM effectiveness underwater.
- Load feeds back into the kinetic network as stress: a big rotor at high RPM demands a strong motor.

### Jet and rocket thrusters

| Thruster | Fuel | Thrust curve | Notes |
| --- | --- | --- | --- |
| Steam Jet | Steam | Low, steady | Early hovercraft and steam airships |
| Combustion Jet | Diesel / kerosene / biofuel | Medium, throttleable | Needs air intake blocks; thrust falls with altitude |
| Turbofan | Fuel + shaft power | High, efficient at cruise | Multiblock (intake, compressor, combustor, nozzle), scales 2x2 to 5x5 |
| Rocket Nozzle | Fuel + oxidizer | Very high, no air needed | Works in space dimensions |
| Ion Drive | Electricity | Tiny, very efficient | Long-range late game |
| Gravitic Lifter | Zero-point power | Cancels gravity locally | End game |

Thrust magnitude = nozzle exit area × throttle × fuel energy density curve. Direction = nozzle facing; **Gimbal Mount** blocks vector thrust for flight control. Exhaust is a damaging particle cone and pushes entities behind it.

### Wings, control surfaces and sails

- **Wing blocks** (wood, canvas, steel, alloy panels) form surfaces. The brain groups adjacent wing blocks into airfoils, computes area, span, chord and aspect ratio.
- Lift: L = 0.5 · ρ · v² · S · C\_L(α), with C\_L rising roughly linearly with angle of attack up to a stall angle (\~15°), then dropping. Induced drag from aspect ratio.
- **Control surfaces** (aileron, elevator, rudder, flap) are hinge joints on wing edges, moved by redstone or the cockpit. They change local α.
- **Sails** catch wind: a world wind vector (direction and strength vary per biome, weather and altitude; thunderstorms gust). Sail force from apparent wind × sail area, trimmed by rotating the mast joint. Keels (underwater fin blocks) provide lateral resistance so ships tack upwind.

### Balloons and lighter-than-air

- **Envelope** blocks form a gas bag; the Core flood-fills the enclosed volume.
- **Gas types:** hot air (from a Burner, cools over time, cheap), hydrogen (from electrolysis, flammable: lightning or fire ignites it), helium (rare, safe).
- Lift = gas volume × (ρ\_air − ρ\_gas) × g; falls with altitude as air density drops, giving a natural ceiling.
- **Ballast tanks** (water or sand) and **valve** blocks control altitude.
- Punctured envelopes (arrows, explosions) leak gas.

### Hovercraft and hover pads

- **Air Cushion Skirt + Lift Fan:** fan pushes air into a skirted area; lift = cushion pressure × skirt area. Works over land, water and ice with very low friction.
- **Repulsor Pads** (electric, later tier): spring-damper force toward a hover height from a raycast to the ground, like a car suspension made of thrust.
- Gyro Stabilizer block applies corrective torque to keep level (powered).

### Wheels and vehicles

Cars use real wheel physics, not sliding boxes.

- **Wheel** = a wheel block (or 2x2/3x3/4x4 assembled tire) on an **Axle Joint** (hinge). Tire material: wood, iron, rubber, treads.
- **Suspension:** Spring Damper joint between chassis and axle, with stiffness and damping settable.
- **Tire model:** simplified Pacejka ("magic formula") for longitudinal and lateral grip vs slip ratio and slip angle, multiplied by the ground block's friction. Drifting, wheelspin, and ice skidding happen naturally.
- **Drivetrain:** engine shaft → **Gearbox** (selectable gear ratios, see Kinetics) → **Differential** block (open, locked, limited-slip) → axles. Torque splits like a real car.
- **Steering:** a steering joint (limited hinge) per front wheel; Ackermann geometry auto-computed from wheelbase.
- **Brakes:** brake blocks on axles; handbrake locks rear wheels.
- **Tracks:** a **Track Assembly** links road wheels with a tread loop for tanks; skid steering.
- Hybrid: wheel-legs and legged rovers mix wheel contacts with IK feet.

## Kinetics: shafts, bearings, gearboxes, motors and material tiers

The mod has its own rotational network (speed + torque, like Create's speed + stress) that also bridges to Create and to electricity. Every part is animated, and same-type parts placed in a square merge into one bigger, stronger part that re-renders at the new size.

### Rotational network model

- Each network carries **RPM** (signed, direction matters) and **torque**. Power = torque × angular velocity, displayed in watts.
- Sources (engines, motors) declare max torque and target RPM; consumers (joints, propellers, wheels, machines) declare torque demand at the current speed.
- If demand exceeds supply, speed sags along the source's torque curve (not an instant hard stop like Create). Overloaded beyond the weakest part's rating → that part strains and can shear.
- Gear ratios multiply torque and divide speed (and vice versa). Conflicting speeds on one network lock it (jam), with a clear particle and Goggles warning.
- Networks on different Segments connect through joints: a shaft passing through a Thrust Bearing joint can carry power across the knee.

### Dynamic multiblock scaling

Place identical parts in a filled N×N square (same axis) and they **auto-merge** into one large part. Breaking any block splits it back.

| Footprint | Bearing look | Torque rating multiplier | Mass |
| --- | --- | --- | --- |
| 1x1 | Single bearing block, thin shaft | 1× | Sum of blocks |
| 2x2 | One large housing, thick shaft, visible rollers | 6× | Sum of blocks |
| 3x3 | Heavy housing with bolt ring, ribbed shaft | 20× | Sum of blocks |
| 4x4 | Industrial bearing, inner race visibly spinning | 48× | Sum of blocks |
| 5x5+ | Colossal bearing with gear teeth and pistons | \~N³ scaling | Sum of blocks |

Rating grows roughly with cross-section × radius (≈ N³), which rewards building big joints for big machines.

Applies to: shafts, bearings, ball joints, gearboxes, motors, engines, propeller hubs, wheels, pistons. Each scaled size has its own model (baked per size from a template + generated detail, or authored 2x2 to 5x5 models with procedural above). Implementation: a `ScalableMultiblock` controller block entity at one corner owns the merged state; the other blocks become proxies that forward interactions and render nothing.

### Bearings and shafts

- **Shaft** (1x1 up to 5x5 thick axles), with **encased** variants and **chain/belt drives** to carry power sideways.
- **Rotational Bearing:** turns a rotating sub-Segment (windmills, turrets, rotors). The outer housing stays fixed; the inner race and shaft visibly rotate.
- **Thrust Bearing** and **Ball Bearing** are the walker joints (see Walkers); they render the same way, with a driven inner part.
- **Clutch, Brake, Ratchet and Torque Limiter** blocks for control and protection.

### Gearboxes

- **Inline gearbox:** same axis in and out; ratio set by gear blocks inside or a dial (1:4 to 4:1).
- **Right-angle gearbox:** perpendicular output (bevel gears visible).
- **Multi-output gearbox:** outputs on any face.
- **Shifting gearbox:** multiple selectable ratios via redstone or cockpit (for cars and gaits).
- **Differential** and **Planetary** gearboxes for drivetrains and big reductions.
- **Chaining:** place gearboxes face to face and they mesh; adjacent parallel gearboxes share a gear train (horizontal parallel chains), perpendicular contacts mesh as bevels (orthogonal chains). The renderer animates every visible gear at its true speed and phase so teeth stay meshed.
- Gearboxes scale (2x2 to 5x5) like bearings, with visible large gears through a casing window.

### Motors

| Motor | Input | Behaviour |
| --- | --- | --- |
| Hand Crank | Player | Tiny torque, for testing |
| Water / Wind Wheel | Environment | Free, low, intermittent |
| Steam Motor | Steam | High torque, slow response |
| Combustion Engine | Fuel | Medium-high, noisy, exhaust |
| Electric Motor | Electricity | Precise speed control, ideal for servos |
| Servo Motor | Electricity | Position control: target angle input, used by joints |
| Hydraulic Actuator | Pressure from a pump | Huge linear force, for pistons and slider joints |
| Zero-Point Motor | ZPE | Near-limitless torque within tier |

### Size-Based Mechanical Scaling

Every core movement component (bearings, joints, gearboxes, shafts, and motors) uses a size-based scaling system rather than material tiers. All components are constructed from the same baseline material specification, with their mechanical capabilities determined by their physical footprint.

A basic 1x1 component establishes the baseline mechanical limits:

- **Maximum torque:** Base torque capacity (N·m).
- **Maximum RPM:** Unlimited by component size; rotational speed is governed by the connected system, gearing, and motor capabilities.
- **Maximum holding load:** Base structural load capacity (tonnes).

Components scale according to the dimensions of their assembled block footprint. Placing identical components in a larger contiguous grid causes them to visually combine into a single, larger component, increasing their mechanical capabilities.

| Component Footprint | Relative Area | Mechanical Scaling |
| --- | --- | --- |
| 1x1 | 1 | Base capacity |
| 2x2 | 1 | Exponential increase |
| 3x3 | 1 | Exponential increase |
| 4x4 | 1 | Exponential increase |
| 5x5 | 1 | Exponential increase |
| NxN | 1 | Continually scaling |

#### Scaling Rules

- Torque capacity and maximum holding load increase exponentially with component size, rather than scaling linearly with the number of blocks.
- RPM is not restricted by component size. Larger components do not inherently rotate more slowly or have a lower maximum RPM.
- Components of the same type and connected footprint automatically merge into a single mechanical assembly, adopting the appropriate larger visual model.
- Scaling is theoretically unlimited. There is no predefined maximum component size or highest tier.
- Each component type has its own scaling coefficients, allowing bearings, joints, gearboxes, shafts, and motors to have different relationships between physical size and mechanical capability.
- Mechanical properties are calculated dynamically from the component's assembled dimensions, rather than requiring separate block variants or predefined size tiers.

All base mechanical properties and scaling coefficients are configurable through datapack JSON files. Modpacks can adjust the baseline specifications and scaling curves independently for each component type.

The intention is to make physical construction directly determine mechanical capability: a massive bearing should be capable of supporting and transmitting vastly more force and torque than a small bearing, without requiring the player to upgrade its material.

## Energy tree: coal to zero-point

Power comes in ten eras, and each era's generator is a physical multiblock the player builds on the Colossus (or on the ground and cables in). Every generator outputs rotational force, electricity, or both, and every era adds mass, heat and a hazard to manage.

### Energy forms

- **Rotation** (RPM + torque) on shafts, the native power for joints and rotors.
- **Electricity** in Forge Energy (FE) units, so it works with any mod's cables and batteries. Conversion: Dynamo (rotation → FE) and Electric Motor (FE → rotation), each \~85% efficient by tier.
- **Heat** as a byproduct and a resource (boilers use it, reactors must shed it via radiators).
- **Fluids:** steam, fuels, coolant, using NeoForge fluid capabilities so other mods' pipes connect.

### Generators and their physical builds

| Era | Generator | Output | Physical build (multiblock) | Fuel / input | Hazard |
| --- | --- | --- | --- | --- | --- |
| 1 Muscle and wind | Crank, Water Wheel, Windmill | Rotation, very low | Single blocks; windmill = sail blocks on a bearing | None | None |
| 2 Coal | Firebox + Stirling Engine | Rotation, low | Firebox (brick, 1x1x2) + engine block | Coal, charcoal, wood | Fire spread |
| 3 Steam | Boiler + Steam Engine / Turbine | Rotation, medium-high | Boiler: iron/steel tank shell 3x3x3 to 5x5x8, firebox under it, water inlet, pressure valve, chimney; engine with flywheel | Coal / any furnace fuel + water | Over-pressure explosion if valve missing |
| 4 Blaze | **Blaze Generator** (Blaze Chamber + Blaze Turbine) | Rotation and heat, high; works in the Nether with no water | Blaze Chamber: nether brick and blackstone shell 3x3x3 to 5x5x5, a **Blaze Cage** core holding a captured blaze (Blaze Jar item) or a blaze-rod furnace, soul-fire vents, magma heat exchanger, Blaze Turbine on the output shaft | Blaze rods / blaze powder; a caged blaze is fed coal or fire charges to stay lit and keeps burning far longer | Breached cage releases an angry blaze; fire spread; overheats without radiators |
| 5 Combustion | Diesel Engine, Gas Turbine | Rotation, high; good power-to-mass | Engine block (steel) 2x2 to 4x4 with cylinders, intake, exhaust, radiator | Diesel, gasoline, ethanol, plant oil (refined in a Refinery multiblock) | Exhaust smoke, fuel fires |
| 6 Electric | Dynamo, Battery Banks, Capacitors, Solar Arrays | FE | Battery rack multiblock (lead-acid early, lithium later) | Any upstream power | Battery fire when overcharged |
| 7 Nuclear | Fission Reactor + steam turbine loop | FE / rotation, very high | Reactor vessel (steel walls), fuel rod channels, control rods (redstone analog), coolant loop, heat exchanger, **lead casing shell** for shielding, turbine | Uranium fuel rods (enriched in a Centrifuge); produces spent rods | Radiation, meltdown, fallout |
| 8 Fusion | Tokamak / Plasma Core | FE, extreme | Ring of superconducting magnet blocks, vacuum chamber, plasma injector, cryo cooling, ignition laser | Deuterium + tritium (from water and lithium) | Plasma breach |
| 9 Ender Ion | **Ender Ion Generator** | FE, extreme, plus ionized ender plasma | Ion chamber: End stone and purpur shell 5x5x5, obsidian core, copper or superconductor ionization rings, an Ender Pearl Injector, an End Crystal focus on top | Ender pearls, chorus fruit; dragon's breath as a boost | Unstable field randomly teleports nearby entities and loose items; attracts endermen |
| 10 Zero-Point | Zero-Point Reactor | FE + rotation, near-limitless | Dark matter core in a **containment lattice**: 7x7x7 frame of Void Steel, field emitter pylons at the 8 corners, inner Casimir plate shells, stabilizer rings, a quantum regulator, all fully enclosed | Dark matter (End / Void harvest) + startup charge from fusion or Ender Ion | Containment failure creates a gravity well that pulls blocks in |

**Generator synergies:** the Blaze Generator's waste heat can drive a boiler for a combined blaze-steam cycle. The Ender Ion Generator's plasma piped straight into **Ion Drives** gives them +50% thrust, making it the natural heart of long-range airships and space craft.

Each multiblock validates its pattern (like Create's or Mekanism's), shows a ghost-block guide from the **Blueprint Projector** item, and refuses to start if a required layer is missing. Lead casing is mandatory around fission cores; each missing lead block leaks radiation in a cone from that face.

### Radiation

- Radiation sources: reactor cores (scaled by power and casing gaps), spent fuel, fallout blocks after a meltdown.
- Shielding by block density along the ray (lead best, then steel, concrete, water).
- Effects: Radiation Sickness status (hunger, weakness, damage over time), cured by a Rad-Away item, prevented by a Hazmat suit.
- Compat: if Mekanism is present, read and write its radiation system instead of running a separate one.

### Power on a moving Colossus

Generators on board power the Colossus directly. A **Power Bus** (cables and shafts) links all Segments; across joints, power flows through **Slip Ring** blocks (electric) or through shafts in the joint axis (rotation). Weight matters: a nuclear walker must carry its lead shielding, which is why big power demands big legs.

### Heat, radiators and airflow

Engines, reactors, generators, Core Lattices and heavy servos make heat, and heat leaves through radiators that need moving air. Fans and propellers share one airflow system, so the propeller that pushes an airship also cools its engines.

- **Heat model:** each heat-producing block has a temperature. Over its rated temperature, output drops; far over, it takes damage or (reactors) melts down. Heat moves between touching blocks and through **coolant pipes** (water, then coolant, then cryo fluid).
- **Radiators** (copper, steel, aluminium-tagged, netherite) dump heat at a rate of k × radiator area × airflow speed × (radiator temperature − air temperature). Still air cools almost nothing.
- **Airflow field:** every **Fan** and every **Propeller** projects a column of moving air along its axis. Air speed comes from RPM and blade or fan area, and fades with distance (about 1 block per block of rotor diameter, then falls off). The same calculation also gives propeller thrust, so the two systems never disagree.
- **Pairing propellers with radiators:** a radiator placed in a propeller's wash gets that propeller's air speed for free. Pusher props behind a radiator bank, or radiators in a ducted fan, are the best-cooling layouts. Forward motion adds ram air too, so a fast plane cools itself.
- **Fans** cost power and make no useful thrust (small fans) or a little (large fans); use them where there is no propeller, such as inside a walker's torso or around a reactor. Reversed fans become intakes.
- **Ducts** guide airflow around corners and through hulls; blocked ducts mean no cooling.
- Biome and altitude change air temperature: radiators work poorly in the Nether and in deserts, very well in snow biomes and at high altitude.
- Airflow also pushes entities, items and particles (and can carry smoke), so players can see it working.

## Materials, tech tree and crafting tree

Add lead and steel (plus a few late-game materials), but have every recipe ask for the common `c:` tag, never the mod's own item, so lead from Mekanism or Thermal and steel from Immersive Engineering or Create addons all work.

### New materials

| Material | How obtained | Used for | Tags (NeoForge `c:` conventions) |
| --- | --- | --- | --- |
| Lead | Lead ore (Y −64 to 32, often near galena-like deposits in deep dark), raw lead, smelting | Radiation shielding, batteries, ballast, counterweights | `c:ores/lead`, `c:raw_materials/lead`, `c:ingots/lead`, `c:nuggets/lead`, `c:storage_blocks/lead`, `c:plates/lead` |
| Steel | Iron + coal coke in a **Blast Forge** multiblock (or Bessemer Converter later) | Mid-game joints, boilers, chassis, reactor walls | `c:ingots/steel`, `c:plates/steel`, `c:rods/steel`, `c:storage_blocks/steel` |
| Coal Coke | Coke Oven multiblock | Steel making, high-heat fuel | `c:coal_coke` |
| Rubber | Rubber tree sap or synthetic from oil | Tires, seals, grip pads | `c:rubber` |
| Uranium | Uranium ore (deep, rare) | Fission fuel | `c:ingots/uranium` |
| Superconductor Alloy | Copper + rare earth + cryo process | Fusion magnets, high-tier servos | `colossus:superconductor` |
| Void Steel | Steel infused in End void fields | Zero-point containment | `colossus:void_steel` |
| Dark Matter | Harvested from Void Rifts in the outer End with a Containment Jar | Zero-point fuel | `colossus:dark_matter` |

**Compatibility rules**

- Worldgen ores are **toggleable** and auto-disable if another loaded mod already generates `c:ores/lead` (config: `auto`, `always`, `never`).
- Unification: a `preferred_material_source` config chooses which mod's item a recipe outputs when several mods provide the same tag.
- Recipes are datapack JSON using tag inputs; KubeJS / CraftTweaker friendly.
- Optional recipe types for Create (pressing plates, mixing alloys), Mekanism (enrichment), Immersive Engineering (arc furnace steel) when those mods are present, via NeoForge conditional recipes (`neoforge:mod_loaded`).

### Tech tree

Progression is unlocked by building, not by research points: crafting a part for the first time, or assembling a Colossus that meets a milestone, unlocks the next tier in the **Engineer's Codex** (an in-game guide book with an advancement-backed tree). Milestones double as advancements.

| Tier | Theme | Key unlocks | Milestone to advance |
| --- | --- | --- | --- |
| 0 Tinkerer | Wood and stone | Wooden Core, wood joints, crank, wheels, sails, boats, carts | Assemble any moving Colossus |
| 1 Smith | Iron and coal | Iron Core, iron joints, Stirling engine, propellers, first biped and quadruped kits | Walk a walker 100 blocks |
| 2 Steam Age | Steam, steel, lead | Blast Forge, steel tier, boilers, steam engines, balloons, gearbox shifting, fans and radiators, Blaze Generator (after first Nether visit), first cannons | Fly an airship above Y 200 |
| 3 Combustion | Oil and engines | Refinery, diesel engines, turbofans, rubber tires, cars with differentials, hydraulics | Drive a car at 30 m/s |
| 4 Electric | Electricity and control | Dynamos, batteries, servos, Control Computer, repulsors, autopilot | Build a walker with 6+ legs |
| 5 Atomic | Fission | Centrifuge, reactor, radiation, diamond/netherite tiers, Netherite Core | Run a reactor on a moving Colossus |
| 6 Plasma | Fusion | Superconductors, tokamak, Ender Ion Generator, ion drives, rocket nozzles, guided missiles | Reach orbit / space dimension (if present) or Y 1,000 |
| 7 Singularity | Zero-point | Dark matter, Void Steel, ZPE reactor, gravitic lifters, Singularity Core | Assemble a Colossus over 100,000 blocks |

### Crafting tree (example chains)

1. **Iron Thrust Bearing (1x1):** iron plate ×4 + shaft + bearing race (iron nugget ring) → at an Engineering Table.
2. **Steel Ball Bearing:** steel ingots → Blast Forge → steel plate (press) → socket housing + steel ball (lathe) → assembled with grease (slime / oil).
3. **Servo Motor:** copper coil (copper wire ×8) + iron rotor + redstone controller + steel casing.
4. **Fission Fuel Rod:** uranium ore → crushed → Centrifuge (enrich) → uranium pellet → zirconium or steel cladding → rod.
5. **Zero-Point Core:** dark matter in a Containment Jar + Void Steel frame + Casimir plates (superconductor + diamond) + fusion-powered startup.

New crafting stations: **Engineering Table** (big grid, blueprints), **Blast Forge** (steel), **Coke Oven**, **Lathe** and **Press** (or Create's press when present), **Refinery**, **Centrifuge**, **Fabricator** (late-game, programmable).

## GUIs: assembly, rigging and operation

Five screens cover the whole life of a Colossus: assemble it, rig its limbs, tune its gaits, drive it, and inspect it. All are client screens backed by server-validated packets; nothing in a GUI can move a Colossus without the physics agreeing.

| Screen | Opened from | What it does |
| --- | --- | --- |
| **Assembly** | Hull Core (right-click) | 3D preview of the scanned build with Segments color-coded, joint list, mass and CoM marker, support polygon, power budget, validation errors with "show me" highlights; Assemble / Disassemble buttons; block budget meter |
| **Rigging** | Core tab, or Wrench on a joint | Per joint: type, limits (arc gizmo in-world), tier stats, motor gains (Kp/Kd with a "auto-tune" button), self-collision toggles. Per limb: solver choice, pole vector, home point, foot pad, step height, preset (plantigrade, digitigrade, unguligrade, insect) |
| **Gait Editor** | Core tab | Phase timeline per leg (drag bars), duty factor, speed range, gait transitions; live simulated preview with the real IK; save as data-pack JSON to share |
| **Cockpit / Helm** | Sitting in a Pilot Seat bound to the Core | HUD: speed, altitude, heading, attitude indicator, power, fuel, heat, radiation, joint strain bars, leg contact lights; control scheme selector |
| **Inspector (Goggles)** | Wearing Engineer's Goggles | In-world overlays: block mass, Segment CoM, force arrows (thrust, lift, drag, gravity, contact), joint stress colours, kinetic RPM/torque, airflow |

### Controls

- **Walkers:** WASD = body twist (forward, strafe), mouse = yaw / look, Space = jump, Ctrl = crouch, Shift = dismount. Optional **direct limb mode** where each key drives one joint (for cranes and arms).
- **Vehicles:** throttle, brake, steer, gear up/down, handbrake.
- **Aircraft:** throttle, pitch, roll, yaw, flaps, trim, hover-hold.
- **Control Binding:** any keyboard input can be mapped to a **Redstone Link channel** on board, so players wire custom functions (weapons, doors, lights).
- **Controller support** via an optional Controlify compat module.
- **Multicrew:** extra seats (Gunner, Engineer) with their own control sets.

### Dynamic control mapping (Hull Core → Controls tab)

Every Colossus carries its own control scheme, built in the Hull Core and used from any cockpit seat. Nothing is hard-coded: the Core lists every controllable thing on board and the player decides which key drives it.

1. **Auto-discovered controls.** On assembly the Core lists every input it found: gait twist axes, throttle, each thruster group, each turret, each weapon group, each control surface, doors and lights wired to **Control Channels**, and any joint set to manual.
2. **Bindings.** Each control can bind to a key, a key pair as an axis (W/S), the mouse (X/Y, wheel, buttons), or a gamepad axis. Modifiers (Shift+F), toggle vs hold, and press vs release are all options.
3. **Analog shaping.** Per axis: dead zone, sensitivity curve, invert, rate limit, and return-to-center vs latching (for throttles).
4. **Seat profiles.** Each seat (Pilot, Gunner, Engineer, Passenger) has its own profile; a gunner's mouse aims turrets while the pilot's mouse steers.
5. **Profiles are data.** Save, copy and share a profile as a file or item; ship a default per leg layout and vehicle type.
6. **In-cockpit editing.** Sitting in a seat, press the Controls key (default `K`) to open a live overlay and rebind without leaving the seat; capture mode "press the key you want".
7. **Conflict handling.** While seated, Colossus bindings take priority over vanilla keybinds they collide with (configurable), and the overlay warns about conflicts. Inventory, chat and Escape can never be captured.

Inputs travel to the server as a compact per-tick control state (axes quantized, buttons as a bitset), validated against the seat's permissions.

### Programmable brain (tier 4+)

**Autopilot** covers waypoints, hover-hold, altitude-hold, patrol and follow-player without any code.

All of this works with no other mod installed. When CC:Tweaked is present, an optional compat module also exposes the Control Computer, sensors and turrets as ComputerCraft peripherals, calling the same internal API as Colossus Script, so neither path has features the other lacks.

## Compatibility and rendering

Because blocks keep living in ship space as ordinary blocks, vanilla mechanics and most mods work without per-mod code; the remaining work is rendering through Sodium/Iris and a short list of interaction mixins. Compat modules load only when the other mod is present.

### Independence rule

Colossus Arise is one jar whose only requirement is NeoForge 1.21.1. Its `neoforge.mods.toml` lists every other mod as `optional`, and no class outside `colossus.compat.*` may import another mod's code. Each compat module is guarded by a `ModList.isLoaded` check and can be removed without breaking anything. Bundled libraries (Jolt bindings and natives) ship inside the jar through jar-in-jar, so players install exactly one file.

### Vanilla

- **Redstone:** works unchanged in ship space (ticks with the plot's chunks). Signals cross joints via **Redstone Slip Rings**; analog levels drive throttles and servo angles.
- Doors, trapdoors, chests, furnaces, hoppers, beds (sleeping on a moving Colossus sets spawn to a **Respawn Anchor on board**, if configured), crafting tables, note blocks, lecterns: all interact through the raycast redirect.
- Fluids on board flow in ship space; pistons work within a Segment; TNT on board explodes with real impulse to the hull.
- Mobs spawn rules off on Colossi by default (config).

### Create

- Create kinetics run inside ship space unchanged (Create is optional; Colossus has its own full kinetic system). A **Create Kinetic Adapter** converts between Colossus torque/RPM and Create speed/stress units both ways.
- Create contraptions (mechanical bearings, gantries) on a Colossus: they already run in their own entity space; add a mixin so contraption entities ride the Segment's transform.
- Create Goggles show Colossus data too.
- Coordinate with **Create Aeronautics / Sable** and **Valkyrien Skies**: detect them and either refuse to assemble the same blocks twice, or (stretch) offer an adapter so a VS ship can be a nested Colossus.

### Rendering

| Path | When | How |
| --- | --- | --- |
| **Vanilla renderer** | No Sodium | Each Segment's ship-space chunk sections are compiled to vertex buffers and drawn with the Segment's model matrix (pose interpolated at partial tick) |
| **Sodium** | Sodium present | Hook Sodium's chunk render lists: register ship plot sections as extra render regions with a per-region transform uniform (the approach VS2 + Sodium compat uses); never fork Sodium |
| **Iris shaders** | Iris present | Draw Segments in the terrain pass so shader packs light and shadow them; shadow pass renders them too |
| **Flywheel / Create instancing** | Flywheel present | Animated parts (spinning shafts, gears, rotors, bearing races) render as Flywheel instances with the Segment transform as parent |
| **Distant Horizons** | DH present | Large Colossi far away draw as simplified LOD meshes |

Animated parts (bearings, gears, rotors) use instanced rendering with a per-part rotation computed from RPM × time, phase-synced from the server so meshed gears stay meshed.

Block entity renderers (chests, signs, Create belts) on board are called with the Segment's pose pushed onto the pose stack.

### Other mods

| Mod | Integration |
| --- | --- |
| Mekanism, Thermal, IE, Create addons | Use `c:` tags for metals and fluids; FE for power; Mekanism radiation system |
| CC:Tweaked | Peripheral for the Control Computer and sensors |
| JEI / EMI / REI | Recipes, multiblock structure previews, tier stat pages |
| Jade / WTHIT | Tooltips: mass, tier, stress, RPM |
| Patchouli or built-in book | Engineer's Codex |
| Curios | Goggles slot |
| Simple Voice Chat | Voice works between crews on board (positions transformed) |
| FTB Chunks / Open Parties and Claims | Claims and permissions extend into a Colossus's plots |
| Immersive Portals | Mark incompatible at first; evaluate later |

### Multiplayer and permissions

- Owner and crew list per Colossus; who can pilot, build on, assemble or disassemble.
- Claims mods: a Colossus cannot walk into a claim where its owner lacks build permission (config).
- Server config: global caps on total Colossi, blocks per Colossus, and physics thread time.

## Demolition and ground interaction

Heavy Colossi change the land they cross: ground pressure from real contact forces crushes and flattens blocks, and the heavier the machine, the more it destroys. All of it comes from the physics contacts, so a light scout walks over a forest and a 50,000-tonne walker flattens it.

### Ground pressure

For every contact patch (a foot, a wheel, a track, a hull scraping the ground), each physics step:

```latex
P = \frac{F_{normal}}{A_{contact}}
```

P is compared with each touched block's **crush strength** (kPa), a data-driven value defaulting from block hardness and blast resistance. Wide feet spread load (snowshoe effect); spiked feet concentrate it. Impacts count too: a landing or a stomp adds the impact impulse, so jumping walkers leave craters.

### What happens to crushed blocks

| Block group | Light pressure | Heavy pressure | Extreme pressure |
| --- | --- | --- | --- |
| Grass, flowers, crops, saplings | Trampled (grass to dirt path, crops reset) | Broken | Broken |
| Leaves, vines, snow layers | Broken / flattened | Broken | Broken |
| Logs and whole trees | Untouched | Snapped: the whole tree is felled and topples in the push direction as falling blocks | Splintered to drops |
| Dirt, sand, gravel | Compacted (dirt to coarse dirt / path) and the foot sinks slightly | Pressed down one block (terrain flattens) | Pressed down several blocks |
| Glass, fences, walls, doors, torches | Broken | Broken | Broken |
| Stone, deepslate, ores | Untouched | Cracked (stone to cobble) | Shattered to gravel |
| Obsidian, bedrock-tier, blocks tagged `colossus:uncrushable` | Untouched | Untouched | Untouched |

The table lives in `data/<ns>/colossus/crush_rules/*.json`, so packs can tune it and other mods can add their blocks.

### Demolition tools and modes

- **Sweep damage:** a moving Segment that hits a block with kinetic energy (0.5 · m · v²) above the block's threshold breaks it, so a charging walker or a ramming ship plows through walls.
- **Bulldozer Blade** and **Tamper Foot** blocks: purpose-built parts that flatten terrain to a set level as the Colossus moves, turning heavy machines into terraformers.
- **Harvester mode:** drops from crushed blocks go into on-board storage instead of the ground (logging walkers, mining crawlers).
- **Soft ground:** feet sink into mud, sand, snow and soul sand, which raises drag and makes heavy walkers slow in swamps.
- **Tread Lightly toggle** (a pilot control): limits crushing to plants and leaves, for walking through your own base.

### Safety and server control

- Gamerule `colossusTerrainDamage`: `none`, `plants` (default), `soft`, `full`.
- Respects spawn protection and claims (built-in claim checks plus FTB Chunks / Open Parties and Claims compat): no crushing where the pilot cannot build.
- Per-tick crush budget with a queue, so a giant crossing a forest cannot freeze the server.

## Weapons and defense

Weapons are built-in, block-based and physical: every gun is a block or a multiblock, every shot is a projectile with mass, every firing pushes the Colossus with recoil, and aiming is done by real joints driven by a built-in fire-control computer. No other mod is needed for any of it.

### Turrets and fire control

- A **turret** is a Swivel Bearing (yaw) carrying a Thrust Bearing (pitch) with weapons on top. Both joints are real, with material tiers and motor limits, so a heavy cannon on a weak turret traverses slowly.
- The built-in **Fire Control Computer** (one per turret group) does targeting and aims the joints: it solves the launch angle for gravity and drag, leads moving targets from their velocity, and compensates for the Colossus's own motion.
- **Targeting modes:** pilot crosshair (mouse aims all slaved turrets), lock-on (track a selected entity or Colossus), automatic point defense (hostile mobs, incoming projectiles and missiles, enemy Colossi), and map coordinates (artillery).
- **Friend or foe:** owner, crew and team lists; turrets never target allies or the Colossus's own Segments (self-hit prevention ray check).
- **Sensors** for fire control: Rangefinder, Radar (detects Colossi and large mobs by size), Heat Sensor (engines, blazes, players), Laser Designator.

### Weapon systems

| System | Built from | Ammunition | Behaviour |
| --- | --- | --- | --- |
| **Machine Gun / Autocannon** | Receiver + barrel + ammo feed block | Bullet belts, autocannon shells | Fast fire, low damage per hit; good anti-mob and anti-missile point defense |
| **Modular Cannon** | Breech + barrel segments + recoil absorber + loader; scales 1x1 to 5x5 like bearings | Solid shot, armor-piercing, high-explosive, incendiary, shrapnel | Caliber = barrel footprint; longer barrels raise muzzle velocity; barrel material caps pressure (a weak barrel bursts) |
| **Artillery (howitzer / mortar)** | Large modular cannon on a high-elevation mount + Fire Control + Rangefinder or Spotter | Large HE shells, smoke, illumination | Indirect fire over hills to map coordinates picked in a **Targeting Table** GUI |
| **Bombs** | Bomb bay (doors are a hinge joint) + bomb racks + Bombsight | Gravity bombs, incendiary, cluster, depth charges | Bombsight computes the release point from speed and altitude |
| **Guided Missiles** | Launcher rack or vertical launch cells; each missile is assembled from warhead, guidance, motor and fins items | Missiles with HE, incendiary or EMP warheads | Each missile is a small physics projectile with thrust and fins; guidance: laser-designated, heat-seeking, radar, or coordinate |
| **Torpedoes** | Underwater tube | Torpedoes | For naval Colossi |
| **Flak / Point Defense** | Fast turret + proximity shells | Flak shells | Shoots down missiles, bombs and fliers |

Propellants and explosives follow the tech tree: gunpowder (tier 2), cordite and TNT-grade fillers (tier 3), rocket fuel (tier 6), and late-game plasma or ender charges.

### Physics of firing and damage

- **Recoil:** muzzle momentum is applied as an equal and opposite impulse on the cannon's Segment. Firing a big gun rocks a walker, and an unbraced cannon can tip a light machine over.
- **Projectiles** are simulated with gravity and drag; armor-piercing shells penetrate blocks according to their kinetic energy and the block's blast resistance before exploding.
- **Explosions** apply impulse to nearby Segments (hits knock Colossi around) and break blocks on board and in the world, governed by a separate gamerule `colossusWeaponBlockDamage`.
- **Armor:** plating blocks (steel, lead, netherite composite, later void-steel) trade mass for protection; sloped armor deflects shells at shallow angles.
- **Late-game defense:** a zero-point **Deflector Field** that absorbs incoming projectiles while it has power.

### Logistics and safety

- Ammunition sits in on-board **magazines**; loaders and feeds move it to weapons, and ammo mass counts toward the Colossus's weight. A hit on a magazine can cook it off.
- Server config can disable weapons entirely, disable block damage, or limit weapons to PvE, so builder-only servers stay peaceful.

## Suggested additions

These are the additions most likely to make the mod feel complete, grouped by priority. The first group is close to essential for a playable release.

### Essential

- **Blueprints / schematics:** save an assembled Colossus (with rigging and gaits) to a blueprint item; print it with a **Fabrication Bay** that consumes materials. Export/import as files to share designs. Critical for big builds.
- **Test Rig / Simulation mode:** a creative-only "ghost run" that simulates the Colossus without risk (no block damage), plus a **Gantry Crane** block that holds a walker up while you tune legs.
- **Auto-tune for joint gains:** one-click PD tuning from Segment mass and inertia, so players do not need control theory.
- **Damage model:** blocks have HP; explosions and impacts break them; broken joints drop limbs; a **Repair Drone** or welding tool restores blueprint state.
- **Ponder-style tutorials** (in the Codex): animated scenes showing how to build a biped leg, a boiler, a propeller.
- **Sensors:** Gyroscope (attitude), Altimeter, Speedometer, Contact Sensor (foot down), Strain Gauge, Radar; all output redstone and computer values.

### Gameplay depth

- **Utility tools:** cranes, claws, drills, harvesters, grabber arms using the same IK. (Weapons are part of the core mod: see Weapons and defense.)
- **Fuel logistics:** fuel tanks with mass that drops as fuel burns (changes balance in flight), refuelling ports, docking stations.
- **Heat management:** engines and reactors heat up; radiators, fans and coolant loops; overheated parts lose power.
- **Weather and environment:** wind for sails and aircraft, rain reducing tire grip, lightning striking tall metal Colossi (lightning rods help), deep snow slowing walkers.
- **Terrain interaction:** heavy walkers leave footprints (grass to path, snow pressed), dust clouds and debris particles on heavy steps (crushing itself is core: see Demolition).
- **Sound design:** joint servo whines pitched by speed, footstep thuds scaled by mass, creaks at high strain, engine sounds by RPM. Big immersion payoff for little code.
- **Camera:** third-person orbit camera scaled to Colossus size, cockpit camera, and camera shake from footsteps.

### Builder quality-of-life

- **Symmetry tool:** mirror edits on the left leg to the right leg.
- **Leg Kit items:** pre-built templates (biped, digitigrade, spider) that place a starter leg the player can then modify.
- **Counterweight blocks** (lead) for tuning CoM.
- **Balance preview:** the Assembly GUI shows the support polygon and CoM before you commit.
- **Paint and decals:** hull paint, team colours, insignia.
- **Lighting:** headlights, searchlights, running lights that move with the Colossus.

### Late-game spectacle

- **Colossus-scale doors and hangars** (nested Colossi as rotating hatches).
- **Mobile bases:** walking cities with beds, farms and respawn points.
- **Space:** compat with space mods (Ad Astra or similar) so rocket and ion drives reach other planets.
- **Wild Colossi:** optional rare world-generated derelict machines (in your own original style) that players can repair and claim. A strong discovery hook and a showcase of what is possible.

## Code architecture

Split the mod into a game-thread layer (blocks, ship space, networking), a physics-thread layer behind one backend interface, and a client layer for rendering and input. The physics thread never touches Minecraft objects; the two sides exchange immutable snapshots and command queues.

### Packages

| Package | Responsibility |
| --- | --- |
| `colossus.core` | Mod entry, registries (DeferredRegister), config, gamerules, data-pack loaders (block physics, tiers, gaits, crush rules) |
| `colossus.assembly` | Hull Core, Core Lattice and Relay Cores, flood-fill scanner, Segment splitter, validation, assemble/disassemble, blueprint save |
| `colossus.shipyard` | Ship-space dimension, plot allocation (stacked plots for tall Segments), chunk tickets, block move/restore with block entity NBT |
| `colossus.physics.api` | `PhysicsBackend`, `BodyHandle`, `ConstraintHandle`, `ContactListener` interfaces |
| `colossus.physics.jolt` | Jolt backend (bundled jolt-jni), terrain streaming to static shapes, CCD, sleeping |
| `colossus.physics.xpbd` | Built-in pure-Java solver, the fallback backend |
| `colossus.physics.forces` | Gravity, drag, buoyancy, lift, thrust, wind, airflow field; per-substep force accumulators |
| `colossus.brain` | Limb discovery, IK solvers, foot targeting, gait planner, balance controller, autopilot |
| `colossus.kinetics` | Rotational network graph, gear ratios, torque solving, FE bridge |
| `colossus.multiblock` | ScalableMultiblock merging, structure pattern validation for reactors, boilers and generators |
| `colossus.energy` | Generators (coal to zero-point, Blaze, Ender Ion), fuels, heat, radiators, radiation |
| `colossus.demolition` | Ground pressure, crush rules, tree felling, sweep damage, crush queue and claim checks |
| `colossus.weapons` | Weapon blocks, projectiles, ballistics solver, fire control, missiles and guidance, magazines, armor |
| `colossus.control` | Seats, control discovery, binding profiles, input state, Colossus Script interpreter |
| `colossus.interaction` | Raycast redirect, entity carrying, ship-local entity mode, reach checks |
| `colossus.client.render` | Segment renderers (vanilla / Sodium / Iris paths), instanced animated parts, overlays |
| `colossus.client.gui` | Assembly, Rigging, Gait Editor, Controls, Cockpit HUD, Targeting Table, Codex |
| `colossus.net` | Payload records (NeoForge `CustomPacketPayload` + `StreamCodec`), pose sync, control input |
| `colossus.compat.*` | Optional only: Create, Sodium, Iris, Flywheel, Mekanism, CC:Tweaked, JEI/EMI, Jade, claims mods; each loaded only when its mod is present |

### Tick pipeline

1. **Game thread (server tick, 20 Hz):** apply block changes on board (mass/shape deltas), read player control input, read kinetic network outputs, run the brain (gait, foot targets, IK, balance) to produce joint targets and actuator commands, push them onto the command queue.
2. **Physics thread (60 Hz, 3 substeps):** drain commands, accumulate forces, step the solver, collect contacts and joint impulses, publish a pose + stress snapshot.
3. **Game thread (next tick):** read the snapshot, update Segment transforms, carry entities, apply impact damage, sync poses to clients.
4. **Client (every frame):** interpolate Segment poses between the last two snapshots at partial tick, render, draw HUD and overlays.

The brain runs one tick behind physics, which is fine at 20 Hz for walking; the per-substep PD joint servos inside the physics step keep limbs stiff between brain updates.

### Data-driven files

- `data/<ns>/colossus/block_physics/*.json` — mass, friction, restitution, buoyancy override by block or tag
- `data/<ns>/colossus/material_tiers/*.json` — torque, RPM, load, durability per tier
- `data/<ns>/colossus/gaits/*.json` — phase offsets, duty factor, speed range
- `data/<ns>/colossus/fuels/*.json` — energy density, burn rate, exhaust
- `data/<ns>/colossus/multiblocks/*.json` — reactor, boiler, ZPE structure patterns
- `config/colossus-server.toml` — budgets, caps, physics rate, terrain damage, ore generation

### Testing

- Pure-Java unit tests for IK, gait and balance math (no Minecraft needed).
- NeoForge GameTests: assemble a reference biped, walk 50 blocks, assert it is upright and moved; a boat floats; a car accelerates; a reactor validates.
- A headless physics benchmark scene for the performance budget.

## Roadmap and risks

Build the hardest shared foundation first (ship space, interaction and the physics backend), then prove each locomotion type with one playable test before adding content. Walkers come before flight because they stress the joint and IK systems that everything else reuses.

*(Diagram in the project doc: development roadmap, 6 phases, each with a gate.)*

Each phase ships as a playable alpha; the gate on the right must pass before the next phase starts.

### Top risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Biped balance is unstable | Walkers fall constantly; core fantasy fails | Ship quadrupeds and hexapods first; capture-point controller; auto-tune; optional "stabilizer assist" gyro |
| Native physics library packaging (JNI) | Crashes on some OS/CPU | Bundle natives for Windows, macOS (x64 + ARM), Linux; keep the pure-Java XPBD fallback backend |
| Sodium / Iris API changes | Invisible Colossi | Isolate in compat modules; track VS2's approach; test against each Sodium release |
| Server performance with many Colossi | TPS drops | Separate physics thread, sleeping, LOD freeze, hard caps in config |
| Mod interaction edge cases (block entities that cache world position) | Duped or broken items | Blacklist tag `colossus:non_assemblable`; API for mods to opt in; crash-safe disassembly |
| Overlap with Valkyrien Skies / Create Aeronautics | Players confused, double assembly | Detect and block conflicts; position this mod around articulated walkers and joints, which those mods do not cover |
| Scope | Never ships | Phase gates; cut weapons and space to optional modules |
