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
| H | Hover assist on/off |
| G | Open the Hull Core screen |
| K | Park: turns it back into blocks |
| V | Leave the seat (Shift no longer dismounts, it lowers) |
| F5 | First person, third person back, third person front. The camera zooms out to fit the Colossus. |

You can walk around on a Colossus while it moves. A lighter Colossus parked inside a heavier one rides along with it (nesting: a jet inside a carrier). Assemble the inner one first, because each Colossus allows only one Hull Core.

## Joints (bearings)
Joint blocks split a Colossus into **segments** ("parts" in the Hull Core screen). Joint blocks of the same material and the same axis, placed side by side, act as **one bigger joint**, even if their shapes differ.

Each material comes in four hollow shapes. The axis comes from the face you place it against.

| Shape | Look | Strength |
|---|---|---|
| Bearing Joint | full hollow bearing | 1 |
| Slab Joint | bottom or top half (click the upper half of a side, or a ceiling, for top) | 0.5 |
| Panel Joint | vertical half slab against the N/E/S/W side you place it on. Axis goes through the panel; sneak while placing for a vertical (door-hinge) axis | 0.5 |
| Pin Joint | thin axle | 0.25 |

Bearing blocks turn along with the part their joint moves. In the Joints tab each joint shows its world **X, Y, Z**, so you can match it to the block you see in-game (F3).

Recipes: 3 Bearing Joints in a row give 6 Slab Joints, 3 stacked give 6 Panel Joints, and 1 gives 4 Pin Joints.

| Material | 1x1 holds (force) | 1x1 bending limit |
|---|---|---|
| Timber (wood) | 20 kN (about 2 t) | 30 kN·m |
| Cobble (stone) | 50 kN | 60 kN·m |
| Iron | 120 kN | 200 kN·m |
| Gilded (gold) | 200 kN | 350 kN·m |
| Diamond | 500 kN | 1000 kN·m |

Scaling for a joint of total strength k (an **n×n** square of full joints has k = n²):
- The force it can hold grows with its area: k times (9× for 3x3).
- The bending (lever) it can hold grows with k^1.5, which is **n³** (27× for 3x3).

Load counts gravity plus acceleration. If a joint stays over 100% load, it creaks, sparks and then **snaps**, and everything hanging from it falls off as blocks.

## Walkers: physics-driven legs
A walker is a Hull (a rigid body) plus legs (chains of joints). The Hull never moves because a leg "animates". It moves because planted feet push against the ground.

1. **Contact:** a foot that touches the ground is *planted*, and its world position is locked.
2. **IK:** each tick the leg joints are solved so the leg reaches from the Hull's hip to its foot (within each joint's limit). What you see is that solution.
3. **Balance controller:** works out the force the Hull needs (follow the pilot's speed, keep the weight over the feet), then splits it over the planted feet.
4. **Ground reaction limits:** each foot can push sideways at most *grip × load*, and at most *hip motor torque ÷ hip height*. Grip is the lower of the foot block's and the ground's: about 0.8 normally, 0.02-0.04 on ice. A stiff leg also pushes along itself, so it only holds while tan(leg angle) ≤ grip. Past that the foot **slides**.
5. **Hull dynamics:** the Hull moves only from those forces plus gravity and thrust. Legs are rigid struts: the Hull can't get further from a planted foot than the leg reaches, and it brakes before it would.
6. **Gait:** before a foot lifts, the Hull stops going forward and leans just far enough over the foot that will carry it. Planted feet never slide with the body. Then the foot lifts, swings in an arc to a spot ahead (further at higher speed), and plants. Repeat.

7. **Terrain:** a step lands on the real ground there (up a step, down a slope) and lifts high enough to clear what's in between. If any leg block would end up inside the ground, that foot is raised. A wall or a drop of more than 1.5 blocks makes the foot step in place.

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
- **IK modes:** Analytic 2-link (exact for hip + knee), FABRIK (any number of joints), CCD (simple, stable).
- **Gait modes:** Biped, Digitigrade (knees bend backward), Quadruped (diagonal pairs step together), Spider (alternating legs around the body), Custom (pick each joint's step group in the Joints tab).
- **Keys:** click a key button, press the new key. Esc sets it back to default. Defaults follow your movement keys (WASD, Space, Shift, Ctrl) and R for the Jump Drive.
- Raise / lower bends the knees (legs with a knee only).

## Ball Joint
A small joint that keeps the part below it **level with the ground** (feet stay flat on slopes and while the leg swings). It turns freely within its Limit in any direction; set Limit ±5° for a tight foot. Needs no motor. Recipe: any joint + slime ball. Put one between the shin and the foot.

## Jump Drive
A capacitor. It charges slowly from your engines (400 CJ/tick, holds 600k) and dumps it all at once when you press the jump key. It overpowers the servos: the legs go limp and the whole Colossus is launched up. More drives = more charge. The HUD shows JUMP DRIVE %.

## Walkers and balance
A Colossus that stands on parts hanging from joints set to **Leg** is a walker. It stays standing only if:
1. the **Hull Core's part** has a **gyro** (a Pocket Gyro or a gyro structure) with **power**,
2. every **Leg** joint (hips, knees) from each foot up to the core has a strong enough **Servo Motor** (Locked and Ball joints don't need one),
3. the engines give enough power for the knee motors to hold the weight (the scan warns if not), and
4. the **centre of mass is over the feet**. The gyro widens that area a little.

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
- **Jets (thrusters)** push along the way they actually point, even on a turning part (thrust vectoring). Jets placed off-center also turn the Colossus.
- **Propellers / rotors:** put **Rotor Blades** (or Sky Sails) on the turning side of a **Spin** joint.
  - Thrust follows blade area × (spin speed × distance from the axis)² and pushes along the axis, towards the blades.
  - The blades drag back on the motor, so top speed is limited by gears *and* torque.
  - Space spins lifting rotors up, and hover assist holds height. W spins propellers.

## Servo motors and gearboxes
- Each motor has a **shaft axis**: place it against the joint or gearbox it should drive.
- Motors of the same material and axis placed together form a **grid** (2x2, 3x3 …). The grid adds up all its motors' torque, plus 10% for each step of grid size, and turns one shaft as thick as the grid.
- Shafts, gearbox gears and bearing axles visibly spin on a moving Colossus.

## Gearboxes
Gearboxes are solid blocks with a **direction**: the arrows on the sides point from input to output. The input shaft (back) and output shaft (front) are centered and spin at the right ratio. The output faces the block you placed it against, so place it against the joint, then put the motor on the back. Placed backward, the ratio flips (a 2:1 becomes 1:2). Gearboxes in a chain multiply.

| Gearbox | Ratio | Torque | Speed |
|---|---|---|---|
| Overdrive Gearbox | 1:2 | ×0.5 | ×2 |
| Spur Gearbox | 2:1 | ×1.9 | ×½ |
| Planetary Gearbox | 5:1 | ×4.5 | ×⅕ |
| Worm Drive | 20:1 | ×12 | ×1/20 |

Motor top speeds (before gears): Timber 30 RPM, Cobble 40, Iron 60, Gilded 120, Diamond 120 (1 RPM = 6°/s).

## Motors
**Servo Motors** come in Timber, Cobble, Iron, Gilded and Diamond.
- A motor **touching a joint** drives that joint. If its torque is below what the limb needs, the joint stalls.
- A motor touching **no joint** is a **drive motor** that pushes the Colossus along the ground, or through water.
- Torque: 20k / 50k / 150k / 300k / 1M N·m. Power: 5 / 10 / 20 / 40 / 100 CJ/t.

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
Pocket Gyro (20 t), Gyro Array (600 t) and Grand Gyroscope (6,000 t). Gyros make turning stronger. Walkers need gyro capacity at least equal to their mass to walk at full speed.

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
- Walkers need real power for their knees. A very wide two-leg stance still sways a bit when stepping.
- Big Colossi (thousands of blocks) can lower FPS, because every block is drawn each frame.
- Do not use this mod together with Valkyrien Skies.
