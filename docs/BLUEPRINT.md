# ColossalCraft: Design Blueprint
*Minecraft 1.21.1 · Forge (NeoForge discussed in §0) · Build mechs, ships, robots and creatures as single moving bodies*

---

## 0. Decisions to make first

| Decision | Recommendation | Why |
|---|---|---|
| **Loader** | **NeoForge 21.1.x** is strongly recommended over Forge 52.x | On 1.21.1 most large mods target NeoForge, including Create 6 and most physics and tech mods. If you choose Forge, you mostly lose Create compatibility. The architecture below works on either loader; only the event and registration APIs differ. |
| **Block storage** | **"Yard" model** (Valkyrien Skies style), not Create's "contraption" model | The blocks stay real blocks in real chunks, so modded machines, block entities, redstone and inventories keep working inside a moving Colossus. That is your core promise of "vanilla and modded blocks". Create-style contraptions freeze block entities. |
| **Physics engine** | **A custom XPBD articulated-body solver** written in pure Java on JOML (JOML already ships with Minecraft) | You need joints with limits, motors, IK-driven actuation, voxel collision and mass from blocks. An off-the-shelf engine (Bullet through LibBulletJME, or Jolt through JNI) adds native binaries, and you would still write all the voxel and joint glue yourself. XPBD (Extended Position-Based Dynamics) stays stable with stiff joints and long chains, such as snakes. |
| **Units** | 1 block = 1 m, mass in kg, SI forces, fixed 60 Hz physics | Real units keep lift, thrust and crushing equations honest. A config value scales "gameplay mass" if real stone feels too heavy. |
| **Compatibility** | Declared incompatible with Valkyrien Skies 2 at first | Both mods mixin into the same rendering, raycast and collision paths. |

> **Terminology:** Minecraft already uses "chunk" for 16×16 world columns. In code and in the UI, the rigid pieces between joints are called **Segments**. The word "chunk" is kept only in player-facing flavour text, if you want it.

---

## 1. Core concepts

```
 COLOSSUS  (one controllable entity: a graph of rigid Segments)
 ├── Segment "Torso"   ← contains the Hull Core
 │     ├─ Joint (Ball)   → Segment "Head"
 │     ├─ Joint (Hinge)  → Segment "L Upper Arm" ─ Joint (Hinge) → "L Forearm"
 │     ├─ Joint (Ball)   → Segment "L Thigh" ─ Hinge → "L Shin" ─ Ball → "L Foot" [Foot Pad]
 │     └─ Joint (Ball)   → Segment "R Thigh" ─ ...
 └── Systems: Power network · Controllers · Gait/IK · Aero · Buoyancy · Stress
```

- **Segment**: a set of blocks that move together as one rigid body. Each Segment has its own mass, centre of mass (COM), inertia tensor, collision shape and pose.
- **Joint**: a special block that sits *between* two Segments and defines how they can move relative to each other. "Between two joints is a segment" is enforced structurally: assembly flood-fill stops at joint blocks.
- **Hull Core**: the block that turns a built structure into a Colossus. Its Segment becomes the **root** Segment.
- **Binder** (the glue): marks which blocks belong to which Segment when adjacency alone is ambiguous, the same way Create's Super Glue works.

---

## 2. Player workflow

1. **Build** the structure in the world from any blocks.
2. **Place joint blocks** where limbs articulate: hinges at knees and elbows, ball joints at hips and shoulders, sliders for pistons.
3. **(Optional) Bind:** use the **Colossal Binder** item to select a region. This works like Create glue: two-corner box selection plus click-to-add or click-to-remove. Each selection is a Segment group and is shown with a colour tint. Without binding, Segments are flood-filled automatically (see §4).
4. **Place the Hull Core** on the part that should be the root, usually the torso or the ship's keel.
5. **Open the Hull Core GUI** to preview the result before committing:
   - Segments, each with a colour, its mass, and a COM marker
   - The joint tree, with its limits
   - Warnings, such as "Joint at (x,y,z) overloaded at rest: 340%", "Segment 'Tail' is disconnected", or "No Foot Pads: walking disabled"
6. **Assemble.** The blocks are moved into the Yard and the Colossus entity spawns in the same place.
7. **Pilot it** from a Pilot Seat, or walk around inside it.
8. **Disassemble** from the Hull Core GUI. The Colossus aligns to the nearest 90° rotation and writes its blocks back into the world, failing gracefully if blocks are in the way.

---

## 3. Block and item roster (initial)

### Structural and assembly
| Block / item | Function |
|---|---|
| **Hull Core** | Root of a Colossus; opens the assembly and disassembly GUI and stores the Colossus UUID. |
| **Colossal Binder** (item) | Creates Segment groups by selection, like Create glue. Consumes slime and string. |
| **Segment Divider** | A thin block that forces a Segment split without a joint, for a rigid weld you can break later. |
| **Weld Joint** | A rigid joint with a breaking strength; lets you build *breakable* structures. |

### Joints (each is a block whose facing defines its axis)
| Joint | Degrees of freedom | Use |
|---|---|---|
| **Hinge Joint** | 1 rotational | Knees, elbows, doors, ship rudders |
| **Ball Joint** | 3 rotational (swing cone + twist limit) | Hips, shoulders, necks |
| **Universal Joint** | 2 rotational | Wrists, tails, snake vertebrae |
| **Slider Joint** | 1 linear | Pistons, telescoping limbs |
| **Rotor Bearing** | 1 rotational, unlimited | Propellers, turrets, wheels |
| **Spring Joint** | Any of the above, plus compliance | Suspension, tails, antennae |

Joint tier materials (iron → steel → netherite → neutronium) determine **maximum load** and **motor torque**.

### Control
| Block | Function |
|---|---|
| **Pilot Seat** | Mount point; enables the third-person orbit camera and Colossus controls. |
| **Crew Seat** | Mount point only; passengers stay in first person. |
| **Control Console** | Configures gait, key bindings and motor groups. |
| **Foot Pad** | Marks a gait contact point and has high friction. Its Segment becomes an IK end-effector. |
| **Gyro Stabilizer** | Applies a balancing torque, scaled by power and tier. Makes bipeds playable (§7.5). |
| **Sensor Eye** | Required for terrain-aware foot placement. Sets the foot raycast range. |

### Propulsion and aero
| Block | Function |
|---|---|
| **Thruster** (per engine tier) | Directional force at the block's position, along its facing. |
| **Wing Panel / Fin / Sail** | Lift and drag surfaces (§8). |
| **Propeller / Rotor Blade** | Lift or thrust when attached to a Rotor Bearing. |
| **Ballast Tank** | Variable buoyancy for ships and submarines. |
| **Tread / Wheel Hub** | Driven ground contact for vehicles. |

### Power
Engines, conduits and storage are covered in §10.

---

## 4. Assembly algorithm

```
assemble(hullCorePos):
  1. Flood-fill from hullCorePos over face-adjacent non-air blocks
     (diagonals optional via config), stopping at:
       - air / fluids (fluids are recorded for buoyancy, not moved)
       - blocks tagged #colossalcraft:assembly_blacklist (bedrock, portals, other Hull Cores)
       - maxBlocks (config, default 32,768)
  2. Partition the collected blocks into Segments:
       a. Blocks inside a Binder group → that group's Segment
       b. Joint blocks are nodes: each joint's two faces (A-side, B-side) seed
          separate flood-fills that may not cross the joint
       c. Remaining connected components → their own Segments
  3. Build the joint graph: nodes = Segments, edges = Joints.
       - Spanning tree rooted at the Hull Core Segment → primary hierarchy (for IK)
       - Extra edges (loops, e.g. a 4-bar linkage) → closed-loop constraints (XPBD handles these)
  4. Compute mass properties for each Segment (§6)
  5. Validate: static load check at rest, disconnected parts, missing pads, etc.
  6. Allocate a Yard region for each Segment; copy blocks (with block entity NBT) into it;
     remove the originals from the world without triggering drops or updates
     (use flag 2|16 and suppress neighbour updates during the move)
  7. Spawn the ColossusEntity with each Segment's initial pose = its original world placement
```

### The Yard
- This is a reserved, far-off area of the **same dimension**, like VS's shipyard; for example X ≥ 28,000,000 in a 2D grid of slots. Block entities keep their real `Level`, so hoppers, furnaces and modded machines work unchanged.
- Each Segment gets a **slot**: a box of whole chunks (rounded up to 16) plus padding, so builders can extend the Segment from inside later.
- Yard chunks are force-loaded with a custom `TicketType` while their Colossus is loaded.
- **Coordinate mapping:** `worldPos = segment.pose * (yardPos - segment.yardOrigin - segment.localCOM)`, and its inverse.

### Live editing
Players can place and break blocks on a moving Colossus. A raycast that hits a Segment is converted into yard coordinates, and the place or break happens there. The Segment's mass properties and collision shape are then recomputed incrementally (§6.3).

---

## 5. Physics architecture

### 5.1 Threading and timing
```
 Server main thread (20 TPS)               Physics thread (60 Hz fixed, substepped)
 ───────────────────────────               ────────────────────────────────────────
 gather inputs (pilot keys, power)  ──►    double-buffered InputFrame
 refresh WorldCollisionCache       ──►     (immutable voxel snapshot near each Colossus)
                                           for substep in 0..N (default 4):
                                             integrate forces (gravity, thrust, lift, buoyancy)
                                             predict positions
                                             solve constraints (joints, limits, motors, contacts)
                                             update velocities
                                             apply friction / restitution
                                           publish PoseFrame
 read latest PoseFrame             ◄──
 move riders / attached entities
 apply crush & damage events
 send snapshots to clients
```
- The physics thread **never touches `Level`**. It reads only the `WorldCollisionCache`, a per-Colossus sparse voxel copy of world blocks within the swept bounds plus a margin, rebuilt incrementally on block-change events.
- Damage and crushing results are queued back to the main thread as `PhysicsEvent`s.
- **Budget guard:** if a step exceeds its budget, reduce substeps and then mark the Colossus "frozen" (configurable), so the server never stalls.

### 5.2 Solver: XPBD (Extended Position-Based Dynamics)
- Each Segment is a rigid body with position, orientation (quaternion), linear velocity, angular velocity, inverse mass and inverse inertia tensor in world space.
- Constraint types:
  - **Joint attachment**: positional, anchor A = anchor B, with compliance 0 (rigid) or k (spring).
  - **Hinge axis alignment**: angular.
  - **Joint limits**: angular. Hinge: min/max angle. Ball: swing-twist decomposition with a swing cone and a twist range.
  - **Motors**: an angular target with compliance derived from motor strength. The correction torque is capped at `maxTorque` (set by joint tier and available power).
  - **Contacts** (world and Segment-to-Segment): non-penetration plus Coulomb friction, with static and dynamic coefficients per material.
- XPBD's compliance parameter maps directly to real stiffness, which makes the material and tier stats in §6 meaningful.

### 5.3 Collision detection
**Shapes**
- Each Segment's voxel set is **greedy-meshed into boxes**, merging contiguous full blocks into large AABBs in local space. Non-full blocks (slabs, stairs) contribute their `VoxelShape` boxes. A BVH is built over the boxes.

**Broadphase**
- Sweep-and-prune over Segment world-space OBB bounds, both inside one Colossus and across Colossi.

**Narrowphase**
- **Segment vs world:** transform the candidate world blocks into Segment-local space and test OBB against the box list with SAT. This yields a contact manifold of up to 4 points.
- **Segment vs Segment:** OBB-tree against OBB-tree.
- **Self-collision:** enabled between all Segments of one Colossus *except* directly jointed pairs, which joint limits already govern. Players can add per-pair exclusions in the Console, for overlapping armour plates for example.

**Continuous collision (CCD)**
- Used for fast Segments, such as thruster ships and swinging limbs: swept-box conservative advancement when `|v|·dt > 0.5 block`.

### 5.4 Entity collision (inside and outside)
This is the part that makes walking inside a moving mech work.

- **Outside:** a mixin on `Entity.collide` (the `move` path) adds Colossus boxes as obstacles, so entities are pushed by moving hulls.
- **Inside:** each entity can have an **anchor Segment**. While anchored:
  1. Each tick, the entity's position is transformed by the Segment's delta pose (`newPose * oldPose⁻¹`), so the structure carries the entity.
  2. The entity's own movement input is resolved in the Segment's local frame against the Segment's local voxels, so walls, floors and stairs inside the Colossus collide normally even while it pitches and rolls.
  3. Gravity is applied in world space; the "down" direction can optionally use the Segment frame (config: `interiorGravity = WORLD | HULL`).
- **Anchor acquisition:** when an entity stands on, or is boxed in by, Segment blocks, the anchor becomes that Segment. It is released after leaving contact for N ticks. Jumping keeps the anchor through a short grace period, which prevents the "jump off a moving ship and get left behind" problem.
- The client predicts its own player's anchored motion using interpolated poses, and the server reconciles.

---

## 6. Mass, inertia and structural model

### 6.1 Block mass
```
mass(block) = density(block) × volume(block's collision VoxelShape) × gameplayMassScale
```
- **Density source**, in priority order:
  1. Datapack: `data/<ns>/colossalcraft/block_density/*.json` → `{ "minecraft:iron_block": 7870, "#minecraft:logs": 650 }`
  2. Built-in table for vanilla blocks and popular mods
  3. **Heuristic fallback** for unknown modded blocks, using `SoundType` and map colour as a material class (stone ≈ 2500, wood ≈ 650, metal ≈ 7800, wool ≈ 120, glass ≈ 2500, plant ≈ 300), nudged by destroy time
- Using volume from the `VoxelShape` means a slab is automatically half the mass of a full block, and a fence is a fraction.
- **Inventory contents** optionally add mass (config), so a ship full of ore really is heavier.

Example densities (kg/m³): wool 120, oak planks 650, stone 2500, obsidian 2600, iron block 7870, gold block 19300, netherite block 25000 (game value).

### 6.2 Segment mass properties
For each block *i* with mass mᵢ and centre rᵢ (local):
```
M        = Σ mᵢ
COM      = Σ mᵢ rᵢ / M
I_seg    = Σ [ (mᵢ/6)·E₃  +  mᵢ ( |dᵢ|² E₃ − dᵢ dᵢᵀ ) ]     where dᵢ = rᵢ − COM
```
- Here (m/6)·E₃ is the inertia of a unit cube about its own centre, and the second term is the parallel-axis shift.
- Non-full blocks use their shape's box decomposition.
- `I_seg` is diagonalized once into principal axes, which simplifies the solver.

### 6.3 Incremental updates
- When a block is added or removed, update M, the first moment and the second moment (Σm·ddᵀ form) in O(1), recompute COM and I, and patch the greedy box mesh locally.

### 6.4 Structural stress and crushing
These are three separate mechanisms:

| Mechanism | Computation | Consequence |
|---|---|---|
| **Joint overload** | Each joint's constraint impulse ÷ dt gives the force and torque it carries, compared with the joint tier's `maxLoad` (smoothed over ~0.5 s) | Above 100%: warning sparks and a creaking sound. Above 150% sustained: the **joint breaks**, and the child sub-tree becomes a free, unpowered Colossus (wreckage). |
| **Ground crushing** | Contact impulse ÷ contact area gives pressure on each world block touched, compared with block strength = f(destroy time, blast resistance) | World blocks under the feet break (for example, a 2,000 t mech crushes grass, dirt and glass, but not obsidian). Gated by a `mobGriefing`-style gamerule. |
| **Entity crushing** | Contact force on an entity pinned between a Segment and another solid | Damage ∝ (force − threshold). This covers "stepped on by a mech", trapped in a joint, and drawbridges. |

Optional (phase 3): **intra-Segment stress**. Run a simple spring network on the Segment's voxels during impact events only, so a weak glass section of a hull can shatter off.

---

## 7. Locomotion: inverse kinematics plus physics

### 7.1 Philosophy
- **Physics is authoritative.** IK never teleports Segments.
- IK computes **target joint angles**; joint **motors** (§5.2) try to reach them with limited torque.
- This makes heavy mechs feel heavy. An under-powered knee motor buckles, and an over-heavy limb lags.

### 7.2 Limb discovery
- At assembly, each Segment with a **Foot Pad** (or a **Hand Grip** for climbers) becomes an **end-effector**.
- Its chain is the path in the joint tree back to the nearest "body" Segment: the root, or any Segment flagged as body in the Console.
- **Limb type**, auto-detected and editable:
  - `LEG`: an end-effector whose rest position lies below the body's COM
  - `ARM`: any other end-effector
  - `SPINE`: a chain with no end-effector, such as a tail, neck or snake body
  - `WING`: a chain containing lift surfaces

### 7.3 IK solver
- **FABRIK with joint-limit projection**: fast, converges in 3–10 iterations, and handles chains of any length.
- After each pass, every joint is projected onto its legal range: the hinge plane and angle limits, and the ball-joint swing cone and twist.
- A **pole target** (knee and elbow direction) comes from each limb's rest pose.
- **CCD** is available as a fallback for chains where FABRIK oscillates against tight limits.
- The solver runs on the **physics thread** at 20 Hz, and its targets are interpolated between runs.

### 7.4 Gait generator (legged)
```
for each LEG:
  restPoint  = hip position + rest offset, projected with body velocity × stepLeadTime
  footTarget = raycast down from restPoint onto WorldCollisionCache (needs a Sensor Eye in range)
  if |footPos − footTarget| > stepThreshold and the gait scheduler grants a swing slot:
      swing: move the IK target along an arc (lift height ∝ leg length) over swingDuration
  else:
      stance: keep the foot target pinned (high-friction contact holds the pad in place)
```
- The **gait scheduler** enforces phase patterns that keep the body supported:

| Gait | Legs | Pattern |
|---|---|---|
| Biped walk | 2 | Alternating, with a double-support phase |
| Quadruped walk / trot | 4 | Lateral sequence / diagonal pairs |
| Hexapod tripod | 6 | Alternating tripods (very stable; a good beginner mech) |
| Arachnid wave | 8 | Wave gait |
| Custom | any | Phase-offset table in the Control Console |

- **Body control:** the body's height and tilt are a PD target. Hip motors push the body toward the support polygon's centroid, offset in the direction of travel.

### 7.5 Balance (the hard part, solved in a game-friendly way)
Real biped balance (ZMP, capture point) is a research problem. ColossalCraft uses three layers:

1. **Support-polygon check:** if the COM's ground projection leaves the polygon formed by the stance feet, the scheduler triggers a recovery step toward the falling direction (capture-point heuristic: `stepTo = COM + v·√(h/g)`).
2. **Gyro Stabilizer block:** applies a corrective torque toward upright. Its strength is bounded by tier and power draw, so bipeds need gyros and hexapods don't.
3. **Fall state:** if both layers fail, motors go limp to a "brace" pose, the mech falls with full physics and crushing, and players can stand it back up with a scripted recovery routine.

### 7.6 Crawling (snakes, worms, centipedes)
- `SPINE` chains are driven by a **Central Pattern Generator (CPG)** that produces a travelling wave:
  ```
  θᵢ(t) = A · sin(ω t − i·φ) + steerBias
  ```
  where i = vertebra index, A = amplitude, ω = frequency (speed) and φ = phase lag. steerBias is set by the A/D keys.
- **Anisotropic friction** is what makes undulation produce forward motion. Segments flagged `BELLY` get lateral friction μ⊥ ≫ longitudinal μ∥.
- Modes: lateral undulation (default), sidewinding (an added vertical wave with a 90° phase shift), and concertina for tight tunnels.
- **Centipedes** combine both: a CPG spine plus a wave-gait leg on each Segment.

### 7.7 Flapping (birds, dragons, insects)
- Applies a CPG to `WING` chains, with downstroke and upstroke asymmetry.
- The lift from §8 is computed per wing Segment, per substep. Feathering (twist) reduces drag on the upstroke.

---

## 8. Aerodynamics, thrust and buoyancy

### 8.1 Lift surfaces
Each Wing Panel, Fin or Sail block contributes the following at its own position (so asymmetric damage causes roll):
```
v_rel = (segment velocity at block) − wind
α     = angle of attack between v_rel and the panel plane
L     = ½ · ρ(y, dim) · |v_rel|² · S · C_L(α)        ⟂ to v_rel, in the panel's lift plane
D     = ½ · ρ(y, dim) · |v_rel|² · S · C_D(α)        opposite v_rel
```
- **C_L(α):** linear up to the stall angle (~15°), then drops off; tunable per block type.
- **Air density ρ** falls with altitude, roughly ρ₀·e^(−(y−63)/scaleHeight). The Nether is thicker (+20%) and the End is near-vacuum, where only thrusters work.
- **Wind:** a weather-dependent global vector, stronger in thunderstorms, so sailing ships make sense.
- Adjacent panels in the same plane are merged into one surface for performance.

### 8.2 Thrusters
- Force = `thrustRating × throttle × efficiency(power available)`, applied at the block's centre along its facing.
- Off-centre thrust produces torque automatically.
- **Thrust vectoring:** mount a thruster on a powered Rotor Bearing or hinge.
- The pilot's throttle groups (forward, vertical, yaw) map to thruster groups in the Console.
- An auto-allocator can solve thruster mixing for "move up / translate / yaw" (a least-squares allocation over the thruster Jacobian).

### 8.3 Buoyancy (ships and subs)
```
F_buoy = ρ_fluid · g · V_displaced
V_displaced = submerged voxel volume + enclosed air volume below the waterline
```
- **Enclosed air:** at assembly and on hull edits, flood-fill the Segment's interior air from outside. Air cells that the flood can't reach are "sealed" and count as displacement.
- A hole in the hull reduces sealed volume over time (gradual flooding), so ships really sink.
- Each Segment's submerged fraction is sampled per vertical column against the `WorldCollisionCache` fluid data.
- Lava is denser fluid (ρ = 3100), so Nether lava boats are possible.
- **Ballast Tanks** adjust sealed volume, which gives submarines their dive and surface control.

### 8.4 Gravity
- Uniform g = 9.81 m/s² (configurable per dimension), applied at each Segment's COM.

---

## 9. Cameras and views

### 9.1 First person (walking inside)
- The player is anchored to a Segment (§5.4). The camera's position is the player's eye, transformed by the interpolated Segment pose.
- **Camera roll options:**
  - `RIDE`: the view rolls and pitches with the hull, which is immersive.
  - `HORIZON`: the camera stays world-level and only translates, which is comfortable.
  - `HYBRID` (default): rides yaw fully and damps pitch and roll by 70%.
- Mouse look stays relative to the hull frame, so turning the Colossus also turns you.

### 9.2 Pilot seat (third person)
- Sitting in a Pilot Seat mounts the player as a passenger of a seat proxy entity anchored to the seat block in yard space. The player is locked to that element of the Colossus, even if it is a swinging head.
- **F5 cycles through four modes**, extending vanilla's cycle:
  1. **First person**: the view from the seat (cockpit view)
  2. **Third-person back**: the orbit camera behind the Colossus
  3. **Third-person front**: the orbit camera facing the Colossus
  4. back to first person
- **Orbit camera:**
  - **Pivot:** the Colossus root COM by default, or the seat's Segment (toggle in the Console).
  - **Distance:** auto-scaled to `boundingRadius × 1.6`, with the scroll wheel zooming between 0.5× and 3×.
  - Mouse orbits freely. The default heading follows the root Segment's yaw with smoothing (a critically-damped spring).
  - **Camera collision** ignores the pilot's own Colossus, so a huge mech never blocks its own camera, but still collides with terrain and other Colossi.
  - **FOV** is widened slightly at high speed, and the far plane is extended for very large Colossi.
- **Implementation:**
  - Hook `ViewportEvent.ComputeCameraAngles` and add a mixin on `Camera.setup`, gated on "player is piloting".
  - Intercept the F5 key mapping to drive a custom `ColossusCameraMode` enum instead of vanilla `CameraType`. Fall back to vanilla when not piloting.

### 9.3 Pilot controls (defaults, all rebindable)
| Input | Walker | Ship / flyer | Snake |
|---|---|---|---|
| W / S | Gait forward / back | Throttle + / − | Wave speed |
| A / D | Turn (gait yaw) | Yaw | Steer bias |
| Space / Ctrl | Crouch / stand height | Vertical thrust up / down | Lift head |
| Mouse | Torso and head aim (IK look-at) | Pitch / roll (fly mode) | Head aim |
| R | Toggle gait (walk ↔ run) | Toggle hover-assist | Mode cycle |
| G | Brace / kneel | Anchor | Coil |
| 1–9 | Action groups (arm poses, weapons, doors) | | |

**Action groups** are recorded poses (keyframes of joint targets) that are played back through the motors. An arm swing is a pose sequence, and IK can blend toward a look target.

---

## 10. Power and the engine tech tree

### 10.1 Power model
- **Unit:** ColossalJoules (CJ), with a 1 CJ = 1 FE adapter for Forge Energy interoperability. The Colossal Kinetic Adapter converts Create stress units.
- **Network:** Power Conduit blocks link sources, storage and consumers within a Segment. **Joints carry power** across Segments, up to a capacity set by joint tier.
- **Consumers:** joint motors (∝ torque × angular velocity), thrusters, gyros, sensors and lights.
- **Brown-out:** when demand exceeds supply, consumers are scaled by priority (gyros > motors > thrusters > utility).

### 10.2 Engine hierarchy

| Tier | Engine | Fuel | Output (CJ/t) | Mass (t) | Special mechanic |
|---|---|---|---|---|---|
| **I** | **Coal Firebox + Steam Piston** | Coal, charcoal, wood | 40 | 6 | Needs a water supply; build-up time; smoke particles |
| **II** | **Blaze Combustion Engine** | Blaze powder, biofuel | 160 | 4 | High heat, so it needs radiators |
| **III** | **Redstone Dynamo / Electric Motor** | Redstone, Accumulator cells | 500 | 3 | Silent, instant response, battery-limited |
| **IV** | **Ender Ion Drive** | Ender pearls, chorus | 1,400 | 2.5 | Efficient thrust in the End and in vacuum |
| **V** | **Fission Reactor** (multiblock) | Uranium fuel rods (provided, or a modded uranium tag) | 6,000 | 40 | Heat management, control rods, **meltdown** risk |
| **VI** | **Fusion Torus** (multiblock) | Deuterium (from water), Nether Star ignition | 25,000 | 60 | Needs a startup power spike; containment field upkeep |
| **VII** | **Zero-Point Core** | None (needs a dragon-egg-tier catalyst to build) | 100,000 | 8 | Unlimited power but **instability**; reality tears (cosmetic or griefing, configurable) when overdrawn |

**Power-to-weight is the core trade-off.** A Tier V reactor is powerful but very heavy, which suits ships and huge walkers. Tier VII is the light endgame.

### 10.3 Supporting tech
- **Radiators / Heat Sinks:** heat is a per-Segment resource. Overheating throttles output, then damages blocks.
- **Accumulators:** storage, in tiered capacity.
- **Thruster tiers** match engine tiers: Steam Vent → Blaze Jet → Ion Thruster → Plasma Thruster → Graviton Drive (ZPE). Higher tiers have a better thrust-to-power ratio.
- **Motor tiers** come from joint materials (§3) and determine the torque cap.

### 10.4 Progression gates
- Steel, from the **Colossal Forge** multiblock, is needed for Tier II joints and engines.
- The **Reactor Grade** alloy needs nether quartz plus netherite scrap.
- **Neutronium** joints need fusion-processed materials.
- The Zero-Point Core needs an End-game catalyst, so it is post-dragon.
- Recipes ship as datapack recipes, so modpacks can rebalance them, with JEI and EMI integration.

---

## 11. Networking and rendering

### 11.1 Network sync
- **Server-authoritative.**
- **Snapshot packets** are sent at 20 Hz per Colossus, in priority order: piloted ones first, then nearby ones, with distant ones throttled. Each contains, per Segment, position (3×double, delta-encoded), orientation (smallest-three quaternion, 3×int16), and linear and angular velocity (half-floats for extrapolation).
- **Client interpolation buffer:** ~100 ms, with Hermite interpolation using velocities. Extrapolation is capped at 250 ms.
- **Pilot input:** an input packet every tick with keys and mouse deltas. Optional client-side prediction for the piloted Colossus is planned for a later phase.
- **Yard chunk data** reaches clients through vanilla chunk sync. Clients within range of a Colossus are sent its yard chunks (a mixin on `ChunkMap` tracking).
- **Assembly and disassembly** events have their own packets, so the client can animate the transition.

### 11.2 Rendering
- Each Segment's yard section meshes are rendered with an extra model matrix: `projection × view × segmentPose × yardOffset`.
  - Vanilla renderer: hook `LevelRenderer` section rendering and skip yard sections in normal passes.
  - **Embeddium/Sodium:** a compat module that injects per-region transforms (the hardest compat item, so budget time for it).
  - **Iris shaders:** shadow pass support is required, using the same transform.
- Block entity renderers and entities inside the Colossus are rendered with the same pose stack transform.
- **Debug overlay** (F3 + C toggle): COM markers, joint axes and limits, contact points, force vectors (lift, thrust, buoyancy) and joint stress colours.

### 11.3 Mixin hotspots (all of these need compat testing)
`Entity.collide`, `Level.clip` (raycasts), `ClientLevel` and `LevelRenderer` section rendering, `Camera.setup`, `ServerPlayerGameMode` (block interaction distance checks against the yard), `ChunkMap` (tracking), `Explosion` (explosions damaging yard blocks), sound and particle position transforms, and `Entity.getPassengerAttachmentPoint`.

---

## 12. Persistence and lifecycle
- **`ColossusSavedData`** (per dimension) holds: Colossus UUID → Segments (yard slot, local COM, mass properties cache, pose, velocity), joints (type, anchors, limits, motor config, wear), gait config, action groups and power network graph.
- Yard blocks are saved in their chunks by vanilla.
- **Loading:** a Colossus loads when the world chunk at its root position loads. It then force-loads its yard chunks and resumes physics. Unloaded Colossi are frozen in place.
- **World edges:** a Colossus that crosses unloaded chunks gets a temporary ticket (config: max loaded radius) or is frozen.
- **Wreckage:** an unpowered, pilotless Colossus with a broken root settles. After a configurable timeout it auto-disassembles into world blocks, or stays as a wreck.

---

## 13. Code architecture (packages)

```
com.colossalcraft
├── ColossalCraft.java              // mod entry, registries
├── registry/                      // Blocks, Items, BlockEntities, Entities, Menus, DataComponents
├── content/
│   ├── block/ joint/ engine/ aero/ control/ power/
│   └── item/ (ColossalBinderItem, ...)
├── assembly/
│   ├── AssemblyScanner            // flood-fill, partitioning
│   ├── SegmentPartitioner
│   ├── JointGraphBuilder
│   └── Disassembler
├── yard/
│   ├── YardAllocator  YardSlot  YardTicketManager
│   └── YardTransforms             // world <-> yard <-> segment-local
├── colossus/
│   ├── Colossus  Segment  Joint   // pure data model
│   ├── ColossusManager            // per-level registry, lifecycle
│   └── ColossusEntity             // vanilla entity proxy (tracking, passengers)
├── physics/
│   ├── PhysicsWorld  PhysicsThread  StepScheduler
│   ├── body/ RigidBody MassProperties
│   ├── solver/ XpbdSolver constraints/{Attachment,HingeAxis,AngularLimit,Motor,Contact}
│   ├── collision/ BoxMesher Bvh Sap ObbSat ContactManifold WorldCollisionCache
│   ├── forces/ Gravity Aero Thrust Buoyancy Gyro
│   └── stress/ JointLoadMonitor CrushResolver
├── locomotion/
│   ├── LimbDetector  Fabrik  LimitProjector
│   ├── GaitScheduler GaitPatterns  FootPlanner
│   ├── Cpg (snake/wing)  BalanceController
│   └── PoseLibrary (action groups)
├── power/ PowerNetwork  EngineTier  HeatModel
├── entity/ AnchorTracker  EntityCollisionHandler
├── client/
│   ├── render/ SegmentRenderer  DebugOverlay  compat/{embeddium, iris}
│   ├── camera/ ColossusCamera  CameraModeCycler
│   ├── input/ PilotInputHandler
│   └── gui/ HullCoreScreen  ConsoleScreen  JointConfigScreen
├── network/ packets/{Snapshot, PilotInput, AssemblyEvent, ...}
├── data/ DensityLoader  (datapack JSON reload listener)
├── compat/ create/ jei/ emi/
├── config/ ServerConfig  ClientConfig
└── mixin/ (see §11.3)
```

**Engineering rules**
- `physics/` and `locomotion/` have **zero Minecraft imports**, only JOML and plain Java. This allows **headless unit tests**: drop a hexapod onto a plane, assert that it walks 10 m without falling, and run that in CI.
- Use doubles for world positions (large coordinates) and floats inside Segment-local space.

---

## 14. Configuration (server)
```toml
[limits]
maxBlocksPerColossus = 32768
maxSegmentsPerColossus = 128
maxColossiPerPlayer = 8
physicsSubsteps = 4
physicsBudgetMsPerTick = 8.0

[gameplay]
gameplayMassScale = 0.25        # 1.0 = real densities
groundCrushing = true
entityCrushing = true
jointBreaking = true
zeroPointInstabilityGriefing = false
interiorGravity = "WORLD"       # or "HULL"

[aero]
airDensitySeaLevel = 1.225
windEnabled = true
```

---

## 15. Development roadmap

| Phase | Goal | Deliverable / "done when" |
|---|---|---|
| **0. Skeleton** (1–2 wk) | Toolchain | MDK, Parchment mappings, mixin config, CI with headless physics tests |
| **1. Rigid ship** (4–6 wk) | One Segment, no joints | Hull Core assembles a structure into the Yard; it renders, collides with the world, and players walk inside it while it moves. Thruster + seat = a flyable box. **This is the highest-risk milestone: build it first.** |
| **2. Mass and buoyancy** (2–3 wk) | Real physics feel | Density table, inertia, gravity, buoyancy, sealed-air detection; ships float, list and sink |
| **3. Joints** (4–6 wk) | Articulated bodies | Hinge, ball and slider joints with XPBD limits, motors and self-collision; joint graph assembly; Binder item |
| **4. Cameras and piloting** (2 wk) | UX | F5 four-mode cycle, orbit camera, HYBRID first-person roll, input packets |
| **5. IK and gait** (6–8 wk) | Walking | FABRIK with limits, foot planner, hexapod tripod first, then quadruped, then biped with gyros |
| **6. Crawling and flapping** (3–4 wk) | Creatures | CPG, anisotropic friction, wing lift |
| **7. Power tiers I–IV** (4 wk) | Progression | Power network, engines, thrusters, heat, recipes, JEI |
| **8. Stress and destruction** (3 wk) | Consequences | Joint breaking, ground and entity crushing, wreckage |
| **9. Tiers V–VII** (4 wk) | Endgame | Reactor and fusion multiblocks, meltdown, ZPE instability |
| **10. Compat and polish** (ongoing) | Release | Embeddium/Iris, Create kinetic adapter, performance passes, multiplayer stress tests |

**MVP release:** phases 0–4 (flyable and floatable articulated ships). **1.0:** phases 0–8.

---

## 16. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Yard rendering with Embeddium/Sodium | High | Ship vanilla-renderer support first; study VS2's open-source Sodium compat approach; keep the compat module isolated |
| Biped balance feels bad | High | Hexapod-first design; Gyro Stabilizer as a sanctioned "cheat"; optional kinematic-assist mode |
| Physics cost of large Colossi | High | Greedy box meshing, sleep states for resting Colossi, LOD (distant Colossi simulate at 20 Hz with no self-collision), budget guard |
| Entity desync when walking inside | Medium | Anchor model with client-side prediction; jump grace period; reconcile with tolerance |
| Mixin conflicts with other mods | Medium | Minimal, narrowly targeted injections; Valkyrien Skies 2 declared incompatible |
| Modded blocks with odd behaviour in the Yard (multiblocks, chunk-aware machines) | Medium | `#colossalcraft:assembly_blacklist` tag; per-mod compat notes; pack makers can extend the list |
| Scope | Very high | Strict phase gating: nothing from phase N+1 starts until phase N's tests pass |

---

## 17. Suggested next steps
1. Settle the loader question (§0): NeoForge vs Forge.
2. Generate the MDK project with the package skeleton from §13.
3. Write `physics/` as a standalone library first: rigid body, XPBD hinge and box contacts, with a tiny debug visualizer. Prove a jointed chain is stable before touching Minecraft.
4. Then build Phase 1, the rigid flying box you can walk around inside.
