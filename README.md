# ColossalCraft (NeoForge 1.21.1)

Build Colossi (mechs, ships, robots, creatures) out of any blocks, then turn them into one moving thing.

## Install
1. Install **NeoForge 21.1.x** for Minecraft **1.21.1**.
2. Put `colossalcraft-1.21.1-0.1.0.jar` in your `mods` folder.

## Build it yourself
```bash
./gradlew build
```
The jar ends up in `build/libs/`. Run the in-world tests with `./gradlew runGameTestServer`.

## How to play
1. Build your thing on **Gantry Scaffold** so it touches nothing else. Scaffold (and vanilla scaffolding) is never picked up. **Every other block is**, dirt and stone included.
2. Place **one Hull Core** on the main body. Joints branch out from the part the Hull Core sits on.
3. Add a **Pilot Throne** (it faces "forward"), an engine with fuel, and thrusters, motors or gyros.
4. Right-click the **Hull Core** (physics) to open its screen:
   - **Overview**: a setup checklist (hover each line for help) and gauges for lift vs weight, energy and stability.
   - **Power**: every engine and battery, with its stored energy and output.
   - **Help**: a short how-to.
   - **BUILD COLOSSUS** makes it move.
5. Walkers also need a **Locomotion Controller** (anywhere on the build). Right-click it, or press **L** while piloting:
   - **Joints**: 3D view of the joints. Set each joint's Mode and Limit.
   - **Gait**: IK mode, gait mode, step speed.
   - **Keys**: the keys used to walk, turn, raise, lower, sprint and jump.
6. **Built:** right-click the Hull Core on the Colossus, or press **G** while piloting. **PARK** turns it back into blocks.
7. Right-click the **Pilot Throne** (a real chair) to sit, or press **V** near it.

### Controls (while piloting)
| Key | Action |
|---|---|
| W / S | Forward / back (thrusters, walking legs, drive motors) |
| A / D | Turn |
| Space / Shift | Up / down (thrusters), or raise / lower a walker |
| Ctrl | Sprint (walkers step 1.6× faster) |
| R | Fire the Jump Drive |
| L | Open the Locomotion Controller |
| H | Hover assist on/off (only with thrusters; it rests while a walker stands on its feet) |
| G | Open the Hull Core screen |
| K | Park: turns it back into blocks |
| V | Leave the seat (Shift no longer dismounts, it lowers) |
| F5 | First person, third person back, third person front. The camera zooms out to fit the Colossus. |

You can walk around on a Colossus while it moves. A lighter Colossus parked inside a heavier one rides along with it (nesting: a jet inside a carrier). Assemble the inner one first, because each Colossus allows only one Hull Core.

## Joints and assemblies (no materials: size is strength)
Joint blocks split a Colossus into **segments** ("parts" in the Hull Core screen). There are no material tiers: one kind of each part, and **bigger assemblies are stronger**.

**Grid assemblies.** Bearings, Servo Motors, Ball Halves and Sockets placed side by side (across their axis) join like chests into **squares**: 2x2, 3x3, up to 4x4. They tile into the biggest squares that fit. **Sneak while placing** to keep a block on its own (parallel 1x1s). A square shows one big shaft (or ball) across all its blocks.

An **n x n assembly is n^n times as strong as a 1x1**: 2x2 = 4x, 3x3 = 27x, 4x4 = 256x. Squares stacked along the axis count a little extra.

| Part | 1x1 strength | Notes |
|---|---|---|
| Bearing | holds 120 kN, bends 200 kN·m | the main hinge; grid assembly |
| Slab Joint / Panel Joint | half a bearing | single blocks; small joints side by side join and add up |
| Pin Joint | a quarter | thin axle |
| Ball Joint | like a bearing | 1x1; keeps the part below level |
| Powered Ball Joint | like a bearing | a ball joint with its own motor: tilts the foot to the slope and helps balance |
| Ball Half + Socket | like a bearing per n^n | an n x n layer of Ball Halves against an n x n layer of Sockets makes an **n x 2 x n ball joint**; a motor touching it powers it |

Ball Halves: placed on top of something the dome points up, under something it points down. Sockets the same (cup up / cup down). Put the dome into the cup.

The outer housing of a bearing turns with the part its joint moves; the inner shaft stays with the parent. A ball joint's halves each stay with the part they touch, and the ball swivels between them. In the Joints tab each joint shows its world **X, Y, Z** and its size.

Recipes: Bearing (iron + Colossteel), 3 Bearings → 6 Slab Joints or 6 Panel Joints, 1 → 4 Pin Joints, Bearing + slime ball → Ball Joint, Ball Joint + Servo Motor → Powered Ball Joint, Ball Joint + iron → Ball Halves / Sockets.

Load counts gravity plus acceleration. If a joint stays over 100% load, it creaks, sparks and then **snaps**, and everything hanging from it falls off as one **wreck**: it falls, tumbles and turns back into blocks where it lands.

## Walkers: feet first
A walker stands on its feet and is placed from the lowest point up. Nothing "holds the Hull in the air": the body is wherever its legs put it.

1. **Contact:** a foot that touches the ground is *planted*, and its world position is locked. It plants on the highest open ground under any of its sole blocks (never inside a wall).
2. **Gait clock:** each leg group lifts when its turn in the step cycle comes (set by the gait in the Locomotion Controller). A group only lifts once the weight is over the other feet; until then the clock waits and the body leans over.
3. **Body:** eases in and out of motion (the stick ramps over about 0.6 s, acceleration is capped at 2.5 m/s² and changes gradually), moves forward at the gait's own speed (stride ÷ stance time), leans early over the feet that carry the next step, leans sideways just far enough over the feet carrying the weight (a gyro lets it lean less), and speeds up or slows down only as fast as grip and hip motors allow. Its **height comes from the planted feet**: up a step, down a slope. Knee legs crouch with raise/lower; weak knees sag.
4. **Grip:** about 1.0 on normal ground, 0.03-0.05 on ice. On ice the walker can't speed up quickly and the HUD shows SLIP. A stiff (knee-less) leg slides if it leans past its grip.
5. **IK** bends each leg to its foot. Knees always bend to the same side (forward, or back for digitigrade), so they can't flip over, and joints turn smoothly: a leg in the air at most about 460°/s. If any leg block would end up inside the ground, that foot is raised.
6. **Steps** land on the real ground (up a step, down a slope) and lift high enough to clear what's in between. A wall or a drop of more than 1.5 blocks makes the foot step in place.
7. **Balance (equilibrium):** standing still, the body always settles its weight over the middle of its feet. If something pushes the weight outside the feet (an overhang, a hit), it takes a **catch step** under it, like a person. Only if that fails does it tip. Powered Ball Joints at the ankles let it balance further out, like a gyro.
8. **Step speed is limited by power:** the leg motors use their full power at 0.5 steps a second, more when faster. The walker steps no faster than the engines can feed (after gyros, smart footing and powered ball joints), and the Gait tab shows the cap.

Set each **hip** (and any **knee**) joint to **Leg**, with a foot under it. Left/right step groups are automatic. The Swing setting is the joint's angle limit. The HUD shows each foot: DOWN, STEP, SLIP.

What it needs: grippy feet, hip motors strong enough to push the Hull, joints strong enough to carry the whole weight on one leg, and a powered gyro in the core part (below).

## Which mode for which joint?
The Joints tab tells you for each joint (and the scan warns if it's wrong):
| Joint | Mode |
|---|---|
| Hip (top joint of a leg) | **Leg**, with a Servo Motor |
| Knee (any joint lower in the leg) | **Leg**, with a Servo Motor |
| Ankle **Ball Joint** | no mode (shows "auto"). It levels the foot by itself. Set its Limit, e.g. ±5° |
| Joint that twists a whole leg (vertical axis) | **Locked** |
| Arms, tails, turrets | **Locked** (rigid), **Spin** (keeps turning) or **Sway** (idle motion) |
| Rotor with blades | **Spin** |

**Locked** means the joint never moves: the parts on both sides act like one solid piece.

## Locomotion Controller
Joint setup moved here from the Hull Core. One per walker.
- **IK modes:** **Auto** (default: picks per leg), Analytic 2-link (exact for hip + knee), **Analytic 3-link** (hip, knee and ankle hinges, foot kept flat), FABRIK (any number of joints in one plane), CCD (any joint axes). Legs with a ball-joint foot solve to the ankle.
- **Gait modes:** Biped, Digitigrade (knees bend backward), Quadruped (diagonal pairs step together), Spider (alternating legs around the body), **Triped** (three legs, or groups of three, step in turn round the body), **Wave** (one leg at a time: slow and very steady), Custom (pick each joint's step group in the Joints tab).
- **Keys:** click a key button, press the new key. Esc sets it back to default. Defaults follow your movement keys (WASD, Space, Shift, Ctrl) and R for the Jump Drive.
- Raise / lower bends the knees (legs with a knee only).
- **Gait tab:** step speed, **stride** (how far each foot travels, as a share of the leg's reach), **lift** (step height) and **stance** (share of each step a foot stays down; "auto" uses the gait type's default). A blueprint preview shows the **hull from above** (with hips and feet), a **step chart** (bars = foot down), and **each leg from the side** (up to 6), walking with its real blocks: every part turns with its joint, and a foot under a Ball Joint stays level. The speed shown is the speed the walker really walks at.
- The Locomotion Controller only sets up legs. Build/Park are on the Hull Core, and Hover too (shown only when the Colossus has thrusters).

## Using a built Colossus
You don't have to park to use or change it:
- **Right-click** doors, trapdoors, fence gates and levers to toggle them. **Chests, barrels, hoppers, dispensers** and **crafting tables** open. **Engines** open their fuel screen, and fuel you put in burns into the Colossus' power while it runs.
- **Place blocks** against any face with a block in hand (sneak to place against a door or chest). Not into the ground.
- **Left-click** to mine a block (like normal mining; instant in creative). The Hull Core can't be removed while built.
- Anything no longer joined to the main body breaks off as a **wreck**.
- **Explosions** blow off weak blocks (like terrain) and damage joints. A joint that takes too much breaks, and what it held falls off as a wreck. Stronger and bigger joints take more.
- Other mods' machines (for example Create's) are carried along but only run once parked.

## Smart footing (Locomotion Controller, Footing tab)
Smart footing is **on by default** and uses **25 CJ/t** while a walker stands or walks. Before every step the legs look at the ground and put the foot on safe, level footing: every sole block has ground under it (not over an edge), the soles are nearly level, the rise or drop is one the leg can take, and lava, fire, magma, cactus, campfires, berry bushes and powder snow are refused. Water and slippery ground count against a spot. If there's no good spot nearby the foot takes a short step, or steps in place. Turn it off on the **Footing** tab to save power (feet then land wherever the gait says).

The **Footing** tab shows a top-down map of what the feet see: ground height in shades of green, water, slippery ground, danger and walls, the body's outline, the feet (solid = planted, hollow = stepping), where each foot steps next (yellow, red = no safe spot) and the heading. It refreshes twice a second, only while the tab is open. (This replaces the old Step Controller block.)

## Ball joints: ankles, hips and shoulders
- **Ball Joint** (plain): keeps the part below it **level** (a flat foot). Needs no motor and has no mode. Put one between the shin and the foot.
- **Powered Ball Joint** (or a Ball Half + Socket assembly with a Servo Motor touching it) has three modes, set in the Joints tab:
  - **Ankle (Locked):** keeps the foot flat and tilts it to match a slope; helps the walker balance.
  - **Hip (Leg):** a two-way leg joint. The IK swings the leg forward/back *and* sideways, so the body can lean over a foot without the feet sliding. Use it as the top joint of a leg (a knee bearing below it is fine). No extra motor needed.
  - **Shoulder (Sway):** swings an arm in time with the steps (left and right opposite), and rests when standing.
- **Ball Half + Socket:** stack a Ball Half and a Socket (or matching n x n squares of each). They turn to face each other by themselves and become one ball joint.

## Jump Drive
A capacitor. It charges slowly from your engines (400 CJ/tick, holds 600k) and dumps it all at once when you press the jump key. It overpowers the servos: the legs go limp and the whole Colossus is launched up. More drives = more charge. The HUD shows JUMP DRIVE %.

## Walkers and balance
A Colossus that stands on parts hanging from joints set to **Leg** is a walker. It stays standing only if:
1. the **Hull Core's part** has a **gyro** (a Pocket Gyro or a gyro structure) with **power** (powered ball ankles help too),
2. every **Leg** joint (hips, knees) from each foot up to the core has a strong enough **Servo Motor** (Locked and Ball joints don't need one),
3. the engines give enough power for the knee motors to hold the weight (the scan warns if not), and
4. the **centre of mass is over the feet**, or it can step under it (walkers with a Locomotion Controller balance themselves). The gyro widens that area a little.

Otherwise it tips over towards its heavy side. The Overview checklist and the **Top** view in the Joints tab (the cyan box is the feet, + is the weight) show what's wrong.

## Joint loads: lifted and standing
Each joint shows two estimates:
- **LIFTED:** everything below the joint hangs from it (a leg in the air, an arm). The top bar is the load against the joint's strength, and the bottom bar is the motor's turning force against what's needed to swing it.
- **STAND:** feet on the ground carry the whole rig. Each foot gets a share of the weight: two equal legs placed evenly each carry half, and a foot nearer the centre of mass carries more. A joint carries its feet's share minus what is below it, so the **ankle carries the most** and the hip the least. The bottom bar is the turning force needed to hold the rig up from that joint: straight legs need little, bent knees need more.
- Either bar over 100% means the joint will snap (live, while standing too). Use bigger or stronger joints low on the legs.

## Joints view (3D schematic)
The Joints tab (in the Locomotion Controller) is a 3D view of the whole Colossus: left-drag turns it, right-drag moves it, and scroll zooms. The side panel shows the selected joint: position, load, torque, gears and, for legs, foot grip, push and carry limits. Two buttons set the joint's **Mode** (Locked, Leg, Spin, Sway) and its **Limit** (how far it may turn).
- **green** is the core part, **blue** marks the other parts, **yellow** marks bearings, and **red** marks problems.
- Click a joint marker to select it.
- Red X marks show where the build touches natural ground. Build on Gantry Scaffold instead.

## Gantry Hoist
Hang a **Gantry Hoist** above where the Colossus stands (up to 32 blocks up, usually on top of a scaffold frame). Right-click it to grab the Colossus below: it winches it up as high as it can go (its top right under the hoist, or until something above blocks it), holds it upright, and shows a chain. **Park** while it's held and the blocks stay off the ground, so you can **Build** it again later without picking up terrain. Right-click again to let go.

## Jets, propellers and rotors
- **Jets (thrusters)** push along the way they actually point, even on a turning part (thrust vectoring). Jets placed off-center also turn the Colossus. Firing jets blow exhaust out of their nozzles (steam, flame, sparks, soul fire or portal light by type) and make an engine roar that follows the throttle.
- **Propellers / rotors:** put **Rotor Blades** (or Sky Sails) on the turning side of a **Spin** joint.
  - Thrust follows blade area × (spin speed × distance from the axis)² and pushes along the axis, towards the blades.
  - The blades drag back on the motor, so top speed is limited by gears *and* torque.
  - Space spins lifting rotors up, and hover assist holds height. W spins propellers.

## Servo motors and gearboxes
- Each motor has a **shaft axis**: place it against the joint or gearbox it should drive.
- Motors side by side across their axis join into a square motor (2x2, 3x3, 4x4; sneak to keep them apart). An n x n motor gives **n^n times the torque** (and uses n^n times the power) and turns one shaft as thick as the square.
- Shafts, gearbox shafts and bearing housings visibly turn on a moving Colossus.

## Gearboxes
Two gearboxes, in a stone-and-timber casing with a shaft sticking out of each end. The arrows on the sides point from input to output. The input shaft (back) turns at the motor's speed, the output shaft (front) at the geared speed. The output faces the block you placed it against, so place it against the joint, then put the motor on the back. **Placed backwards, the ratio flips** (2:1 becomes 1:2). Gearboxes in a chain multiply.

| Gearbox | Ratio | Torque | Speed | Backwards |
|---|---|---|---|---|
| Gearbox | 2:1 | ×1.9 | ×½ | 1:2 (twice as fast) |
| Reduction Gearbox | 5:1 | ×4.5 | ×⅕ | 1:5 (five times as fast) |

Motor top speed (before gears): 60 RPM (1 RPM = 6°/s).

## Motors
- A motor **touching a joint** drives that joint. If its torque is below what the limb needs, the joint stalls.
- A motor touching **no joint** is a **drive motor** that pushes the Colossus along the ground, or through water.
- A 1x1 motor: 150k N·m, 20 CJ/t. Bigger squares multiply both by n^n.

## Engines (power, in CJ per tick)
| Tier | Engine | Size | Output | Fuel |
|---|---|---|---|---|
| I | Ember Boiler | 1 block | 40 | any furnace fuel |
| II | Blaze Combustor | 1 block | 160 | blaze rods/powder, magma cream |
| III | Redstone Dynamo | 1 block | 500 | redstone |
| IV | Ender Ion Drive | 3x3x3 | 1,400 | ender pearls/eyes, chorus |
| V | Fission Reactor | 5x5x5 | 6,000 | Enriched Fuel Cells |
| VI | Fusion Torus | 5x5x5 | 25,000 | Deuterium Cells |
| VII | Zero-Point Core | 5x5x5 | 100,000 | one Nether Star primes it forever |

Right-click an engine to open its screen. It works like a furnace: put fuel in the slot, and it burns into stored energy. A lava bucket gives the empty bucket back. Hoppers and pipes fill the slot too.

**Multiblocks:** build a cube. The outside is casing, the inside is the core block, and the controller goes in the middle of one face, front facing out. Its front lights up when the structure is complete.

| Controller | Casing (shell) | Inside |
|---|---|---|
| Ender Ion Drive | Void Casing | Ender Coil (1) |
| Fission Reactor | Lead-Lined Casing | Fuel Rod Assembly (27) |
| Fusion Torus | Magnetic Containment Casing | Plasma Coil (27) |
| Zero-Point Core | Singularity Casing | Vacuum Lattice (26) + Null Singularity in the centre |
| Gyro Array (3x3x3) | Gyro Casing | Gyro Rotor |
| Grand Gyroscope (5x5x5) | Gyro Casing | Gyro Rotor |

## Gyros
Pocket Gyro (20 t), Gyro Array (600 t) and Grand Gyroscope (6,000 t). Their rings and rotors spin on a powered Colossus. Gyros make turning stronger. Walkers need gyro capacity at least equal to their mass to walk at full speed.

## Thrusters
Steam Vent, Blaze Jet, Arc Thruster, Ion Lance and Graviton Lens (40 kN up to 8 MN). The nozzle points where the exhaust goes, and the push goes the other way. **Sky Sails** give lift when moving fast.

## Creative tabs
There are five tabs: Command (Hull Core, throne, scaffold), Joints & Motors, Power, Propulsion & Gyros, and Materials.

## Config (`colossalcraft-server.toml` in each world's `serverconfig` folder)
- `maxBlocksPerSegment` (default 1,000,000): the most blocks in one part.
- `maxBlocks` (default 250,000): a safety stop, so a build that touches the ground can't swallow the world.
- Also: `massScale`, `maxSpeed`, `liftScale`, `groundCrushing`, `entityCrushing`, `jointBreaking`.

## Mass
Mass = block density × block shape volume × `massScale` (default 0.25). A slab weighs half a block. Wood is about 650 kg/m³, stone 2,500, iron 7,870 and gold 19,300. Datapacks can change densities in `data/<ns>/colossal_density/*.json`.

## Crafting tree (short)
- Iron + 2 coal gives Colossteel Blend, which you smelt into a **Colossteel Ingot**.
- Colossteel + netherite scrap + 2 quartz gives **Reactor Alloy**.
- Reactor Alloy + echo shards + crying obsidian gives **Neutronium**.
- Each engine is crafted from the one before it: Ember, then Blaze, then Redstone, then Ion, then Fission, then Fusion, then Zero-Point.
- Thrusters follow the same chain: Steam, then Blaze, then Arc, then Ion, then Graviton.

JEI or EMI show the exact recipes.

## Other mods
- **Any mod's blocks** can be part of a Colossus. Their data (inventories, tanks, settings) is kept through assembly, flight and landing.
- **Machines don't run while flying.** Block entities on a moving Colossus don't tick. They start working again after landing.
- **Drawing:** block entities (chests, machines) are drawn on moving Colossi using their own renderers. Create parts are drawn as still models while flying.
- **Mass for modded blocks** is guessed from the block's name (steel, copper, aluminum, osmium, lead, uranium and so on) and its sound type. You can set exact values in a datapack.
- **Fuel:** the Ember Boiler burns any mod's furnace fuel. Hoppers and pipes can feed fuel into all engines.
- **Flux Accumulator:** a battery that charges from any Forge Energy (FE) mod, such as Mekanism, Thermal, Immersive Engineering or Create Crafts & Additions. 1 FE = 1 CJ. It stores 4M CJ and can supply 2,000 CJ/t to a Colossus.
- **Create (optional):**
  - **Kinetic Dynamo:** turns rotation into Colossus power (0.5 CJ/t per RPM, 8 SU per RPM of stress). It stores 8M CJ and supplies 800 CJ/t while flying.
  - Create machines on a Colossus rejoin their kinetic network after landing.
  - The Create recipe only loads when Create is installed.
- **Valkyrien Skies:** you get a warning if both mods are installed.

## Known limits (v0.1)
- Colossi turn only left/right (yaw) while walking; they tip only when they fall.
- **Old worlds:** the material-tier joints, motors and the four old gearboxes are gone; those blocks disappear from existing builds. Rebuild with Bearings, Servo Motors and the two new gearboxes.
- Legs that only hinge front-to-back can't really shift weight sideways: the body still leans over the feet, and the feet can look a little off to the side while it does. Add a gyro or powered ball ankles to need less lean.
- Walkers need real power for their knees. A very wide two-leg stance leans a lot when stepping (add a gyro to lean less).
- Editing blocks or toggling a door makes the walker re-plant its feet.
- Blocks on a moving Colossus don't tick (furnaces don't smelt, Create machines don't run) until it's parked. Engines are the exception.
- A wreck keeps the pose of the piece that broke off, except that its own inner joints straighten.
- Big Colossi (thousands of blocks) can lower FPS, because every block is drawn each frame.
- Do not use this mod together with Valkyrien Skies.
