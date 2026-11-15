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
4. Right-click the Hull Core to open its screen:
   - **Overview**: a setup checklist (hover each line for help) and gauges for lift vs weight, energy and stability.
   - **Joints**: the joint tree. Click a joint to see its load, its motor torque compared with what it needs, its movement (Locked, Walk left, Walk right, Spin, Sway) and its swing angle.
   - **Power**: every engine and battery, with its stored energy and output.
   - **Help**: a short how-to.
   - **BUILD COLOSSUS** makes it move.
5. **Built:** right-click the Hull Core on the Colossus, or press **G** while piloting, to open the same screen. **PARK** turns it back into blocks.
6. Right-click the **Pilot Throne** (a real chair) to sit, or press **V** near it.

### Controls (while piloting)
| Key | Action |
|---|---|
| W / S | Forward / back (thrusters, walking legs, drive motors) |
| A / D | Turn |
| Space / C | Up / down (needs thrusters facing down/up) |
| H | Hover assist on/off |
| G | Open the Hull Core screen |
| K | Park: turns it back into blocks |
| V or Shift | Leave the seat |
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

## Walkers and footing
A Colossus that stands on parts hanging from joints set to **Walk** is a walker. It falls over unless:
1. at least one **foot** (a part holding the lowest blocks) contains a **gyro** (a Pocket Gyro or a gyro structure), and
2. every joint from that foot up to the Hull Core has a **Servo Motor** strong enough to hold it, and
3. there is **power** (gyros use energy all the time).

Balance starts at the feet and is passed up the joints to the core. If power runs out, it tips over. It stands back up once power and footing return. The Overview checklist shows what's missing.

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

Right-click an engine to open its screen. It works like a furnace: put fuel in the slot, and it burns into stored energy. Hoppers and pipes fill the slot too.

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
- Colossi turn only left/right (yaw). Joints swing limbs as animation, but the main body does not tip over.
- Big Colossi (thousands of blocks) can lower FPS, because every block is drawn each frame.
- Do not use this mod together with Valkyrien Skies.
