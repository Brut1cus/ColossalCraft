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
3. Add a **Pilot Throne** (it faces "forward"), an engine with fuel, and thrusters, motors or legs.
4. Right-click the **Hull Core** (physics) to open its screen:
   - **Overview**: a setup checklist (hover each line for help) and gauges for lift vs weight, energy and stability.
   - **Power**: every engine and battery, with its stored energy and output.
   - **Help**: a short how-to.
   - **BUILD COLOSSUS** makes it move.
5. Walkers also need a **Locomotion Controller** (anywhere on the build). Right-click it, or press **L** while piloting:
   - **Joints**: 3D view of the joints. Set each joint's Mode and its range of motion (up/down arrows).
   - **Gait**: IK mode, gait mode, pace, hull height, stride, lift, stance.
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
| J | Open the Flight Controller |
| Arrow keys | Tilt the nose down/up, roll left/right (with a Flight Controller) |
| H | Hover assist on/off (only with thrusters; it rests while a walker stands on its feet) |
| G | Open the Hull Core screen |
| K | Park: turns it back into blocks |
| V | Leave the seat (Shift no longer dismounts, it lowers) |
| F5 | First person, third person back, third person front. The camera zooms out to fit the Colossus. |

You can walk around on a Colossus while it moves. A lighter Colossus parked inside a heavier one rides along with it (nesting: a jet inside a carrier). Assemble the inner one first, because each Colossus allows only one Hull Core.

## Joints and assemblies (no materials: size is strength)
Joint blocks split a Colossus into **segments** ("parts" in the Hull Core screen). There are no material tiers: one kind of each part, and **bigger assemblies are stronger**.

**Grid assemblies.** Bearings and Servo Motors placed side by side (across their axis) join like chests into **squares**: 2x2, 3x3, up to 6x6. They tile into the biggest squares that fit. **Sneak while placing** to keep a block on its own (parallel 1x1s). A square shows one big shaft across all its blocks. **Ball Joints** join the same way and also **stack up**: an n x n square up to n layers high becomes one big ball joint (see Ball joints below).

An **n x n assembly is n^n times as strong as a 1x1**: 2x2 = 4x, 3x3 = 27x, 4x4 = 256x, 5x5 = 3,125x, 6x6 = 46,656x. Squares stacked along the axis count a little extra. Past 6x6, use **gearboxes** (more torque, slower) and **parallel joints**: separate assemblies on the same hinge line between the same two parts work as one joint, and their strength and motors add up.

**What a 1x1 carries:** a 1x1 bearing holds 400 kN straight on, a 1x1 Servo Motor gives 600 kN·m. That is set by a reference walker (a test in the mod): two legs of 1x1 stone columns about 10 blocks long, hip and knee, a slab platform, about 25 t. It walks on 1x1 bearings and motors, with one leg carrying ~290 kN while the other steps. The same walker built of iron (~100 t) can't stand on them. Metal, gold and gem-block mechs need bigger joints, gearboxes or more legs, and a colossal metal mech needs huge ones.

| Part | 1x1 strength | Notes |
|---|---|---|
| Bearing | holds 120 kN, bends 200 kN·m | the main hinge; grid assembly |
| Slab Joint / Panel Joint | half a bearing | single blocks; small joints side by side join and add up |
| Pin Joint | a quarter | thin axle |
| Ball Joint | like a bearing per n^n | keeps the part below level; joins into n x h x n boxes (one big ball) |
| Powered Ball Joint | like a bearing per n^n | a ball joint with its own motor: ankle, hip or shoulder; joins into boxes too |


The outer housing of a bearing turns with the part its joint moves; the inner shaft stays with the parent. A ball joint's two base plates each stay with the part they touch, and the ball swivels between them. In the Joints tab each joint shows its world **X, Y, Z** and its size.

Recipes: Bearing (iron + Colossteel), 3 Bearings → 6 Slab Joints or 6 Panel Joints, 1 → 4 Pin Joints, Bearing + slime ball → Ball Joint, Ball Joint + Servo Motor → Powered Ball Joint.

Load counts gravity plus acceleration. If a joint stays over 100% load, it creaks, sparks and then **snaps**, and everything hanging from it falls off as one **wreck**: it falls, tumbles and turns back into blocks where it lands.

## Walker physics (Stage 1: forces from the feet)
The body moves only by real forces: gravity, thrust, and what its **planted feet push against the ground**. Each tick (in 4 small sub-steps):
- The legs try to reach the wanted speed and hold the hull at its height.
- Each planted foot can push **up** at most what its leg's joints can hold in their current bend (every motor-driven joint must hold the foot's push times how far, sideways, the foot is from it; a straight leg takes it in its bones, a bent one much less), and at most its joints' strength.
- Each foot can push **sideways** at most its grip (grip × the weight on it, the friction) and what its hip motor can push. Feet nearer the weight carry more of it.
- Stopping is the feet pushing back; on ice it slides. Weak or overbent legs can't hold the hull up and it sinks onto its folded legs.
- **Joints carry what their foot really pushes**: when one foot carries everything while the other steps, every joint in that leg feels it, and breaks if it's past its strength for a second and a half.
- Turning uses the whole build's real inertia (how hard it is to spin), from every block's mass and place.
- **Big legs step slowly.** The natural step rate comes from leg length, like a pendulum: about 1.8 × √(g / leg length) ÷ 2π steps a second. A person-sized leg gives about 1 step a second, a 10-block leg about 0.28, a 40-block leg one step every ~7 s. The Gait tab's **Pace** is a share of that natural rate.
- Walkers don't need gyros: feet, ankles and the gait balance them. A gyro is an optional helper.

## Walker balance (Stage 2: from the feet)
- **Support area:** the feet can only push where they touch. It's the outline around every planted sole block. Stiff feet (no ankle joint) and **powered ball ankles** can press anywhere on the sole; a foot on a **plain (passive) ball ankle** can't push off its edges, only on the middle half around the point under the ankle. Bigger feet = steadier.
- **Centre of pressure:** how hard the body can speed up, slow down or lean is limited by where the feet can press: acceleration = √(g / height)² × (centre of mass − centre of pressure), and the centre of pressure must stay inside the support area. Tall walkers on small feet can't start or stop as hard as squat ones on big feet.
- **Capture point:** where the body would come to rest if the feet stopped it right now (its position plus its speed ÷ √(g / height)). When that point leaves the support area (a shove, an overhang, a hard stop), the walker takes a **catch step** toward it. In the test, a hard shove backwards (1.8 m/s) on the stone reference walker is caught in a step or so and it settles still.
- **Shifting weight:** a foot only lifts once the weight is sideways over the feet that stay down. It may be ahead of them (it then falls forward and the swinging foot catches it), never behind. Leaning over is only as fast as the body can stop over the foot, so it doesn't overshoot and sway. If one foot can't lift for a while, it steps the other one.
- **Steps:** each foot keeps to its own track, and never lands further ahead of the standing foot than about a stride (else the body can't get over it).
- **Tipping:** it falls only when its weight is well outside every place its feet are on or about to land.

## Walker legs (Stage 3: driven by motor torque)
- Each leg is a chain of rigid links (the parts between its joints) with their real masses and inertias, solved with **Featherstone's articulated-body algorithm**: given each joint's torque, it gives each joint's acceleration exactly, with gravity and the body's own motion.
- The IK no longer sets a lifted leg's pose; it only gives each joint a **target**. Each motor is a servo: it pushes toward the target (holding the leg's weight up, plus a spring and damper sized to how heavy the leg feels to that joint), never harder than the motor and its gears can, never faster than its top speed. Bigger legs respond more slowly.
- So **heavy legs swing slowly**, a weak motor can't lift its leg in time, a leg trails its target and settles, and **a foot lands where the leg really put it**, not where it was planned (when that's close to the planned spot).
- Planted legs are held where their feet are; how hard each foot may push is limited by its joints' torque (Stage 1).
- **Arms, tails and heads** (Sway or Program joints outside the legs) work the same way: their animation is only a target, and the motor drives the limb toward it against its weight and inertia. A heavy arm swings slowly and overshoots a little; a limb on a joint with no working motor sags to its stop.
- Not yet: the body's tilt still follows the feet.

## Smooth motion
On your screen a moving Colossus (fast walkers, thrusters) glides between server updates instead of jumping, and you move with it, so it no longer looks like teleporting.

## Walkers: feet first
A walker stands on its feet and is placed from the lowest point up. Nothing "holds the Hull in the air": the body is wherever its legs put it.

1. **Contact:** a foot that touches the ground is *planted*, and its world position is locked. It plants on the highest open ground under any of its sole blocks (never inside a wall).
2. **Gait clock:** each leg group lifts when its turn in the step cycle comes (set by the gait in the Locomotion Controller). A group only lifts once the weight is over the other feet; until then the clock waits and the body leans over.
3. **Body:** eases in and out of motion (the stick ramps over about 0.6 s, acceleration is capped at 2.5 m/s² and changes gradually), moves forward at the gait's own speed (stride ÷ stance time), leans early over the feet that carry the next step, leans sideways just far enough over the feet carrying the weight, and speeds up or slows down only as fast as the feet's forces allow (see Walker physics). Its **height comes from the planted feet**: up a step, down a slope. Knee legs crouch with raise/lower; weak knees sag.
4. **Grip:** about 1.0 on normal ground, 0.03-0.05 on ice. On ice the walker can't speed up quickly and the HUD shows SLIP. A stiff (knee-less) leg slides if it leans past its grip.
5. **IK** bends each leg to its foot. Knees always bend to the same side (forward, or back for digitigrade), so they can't flip over, and joints turn smoothly: a leg in the air at most about 460°/s. If any leg block would end up inside the ground, that foot is raised.
6. **Steps** land on the real ground (up a step, down a slope) and lift high enough to clear what's in between. A wall or a drop of more than 1.5 blocks makes the foot step in place.
7. **Tilt from the feet up:** on uneven ground (a hill, a step under one foot) every planted leg tries to hold its hip at standing height above its own foot. If the legs can take up the difference (knees bend, legs stretch) the Hull stays level; only what they can't take up tilts the body (pitch forward/back, roll left/right, up to about 23°). No foot is left hanging off the ground.
8. **Turning by walking:** the legs turn the walker. The body can only turn as far as its planted feet can twist under it, so to keep turning it steps round, one foot at a time, shifting its weight over the other foot first; each foot lands already pointing a little into the turn. A plain hinge leg can only pivot its foot about 11° under the body, so it turns in short steps. A hip twist joint (below) lets each step turn much further. The feet's grip limits how fast it can start or stop turning. **Hip twist:** a vertical-axis Bearing set to **Leg** above a leg's hip, or a **Powered Ball Joint hip**, turns the leg so a planted foot keeps pointing where it landed while the body turns.
9. **Walls:** legs don't walk through buildings. If a leg would end up inside a wall, the body doesn't move that way (the foot steps in place); climb with steps of a block or so, not straight up a wall.
10. **Balance (equilibrium):** standing still, the body always settles its weight over the middle of its feet. If something pushes the weight outside the feet (an overhang, a hit), it takes a **catch step** under it, like a person. Only if that fails does it tip. Powered Ball Joints at the ankles let it balance further out, like a gyro.
11. **Step speed:** the legs' natural rate × the Pace setting, limited by power: the walker steps no faster than the engines can feed its leg motors, and the Gait tab shows the cap.

Set each **hip** (and any **knee**) joint to **Leg**, with a foot under it. Left/right step groups are automatic. The range of motion (ROM) is how far the joint may turn. The HUD shows each foot: DOWN, STEP, SLIP.

What it needs: grippy feet, hip motors strong enough to push the Hull, and joints strong enough to carry the whole weight on one leg.

## Which mode for which joint?
The Joints tab tells you for each joint (and the scan warns if it's wrong):
| Joint | Mode |
|---|---|
| Hip (top joint of a leg) | **Leg**, with a Servo Motor |
| Knee (any joint lower in the leg) | **Leg**, with a Servo Motor |
| Ankle **Ball Joint** | no mode (shows "auto"). It levels the foot by itself. Set its range, e.g. ±10° |
| Joint that twists a whole leg (vertical axis) | **Locked** |
| Arms, tails, turrets | **Locked** (rigid), **Spin** (turns with the Spin keys) or **Sway** (idle motion) |
| Wheels | **Spin**: turn with the Spin keys and drive along the ground |
| Rotor with blades | **Spin** |

**Locked** means the joint never moves: the parts on both sides act like one solid piece.

## Locomotion Controller
Joint setup moved here from the Hull Core. One per walker.
- **IK modes:** **Auto** (default: picks per leg), Analytic 2-link (exact for hip + knee), **Analytic 3-link** (hip, knee and ankle hinges, foot kept flat), FABRIK (any number of joints in one plane), CCD (any joint axes). Legs with a ball-joint foot solve to the ankle.
- **Gait modes:** Biped, Digitigrade (knees bend backward), Quadruped (diagonal pairs step together), Spider (alternating legs around the body), **Triped** (three legs, or groups of three, step in turn round the body), **Wave** (one leg at a time: slow and very steady), Custom (pick each joint's step group in the Joints tab).
- **Keys:** click a key button, press the new key. Esc sets it back to default. Defaults follow your movement keys (WASD, Space, Shift, Ctrl) and R for the Jump Drive.
- Raise / lower bends the knees (legs with a knee only).
- **Gait tab:** **pace** (share of the legs' natural step rate), **hull height** (how high the hips stand, as a share of the legs' standing height; weak knees keep it higher), **stride** (how far each foot travels, as a share of the leg's reach), **lift** (step height) and **stance** (share of each step a foot stays down; "auto" uses the gait type's default). A plan preview (greens and yellows, like the Footing map) shows the **hull from above** (with hips and feet), a **step chart** (bars = foot down), and **each leg from the side** (up to 6), walking with its real blocks: every part turns with its joint, and a foot under a Ball Joint stays level. The speed shown is the speed the walker really walks at. Each leg in the preview bends within its real limits (range of motion and folding), and its knees always bend the same way. With no walking legs the Gait tab is off (wheels and spinners use the Spin keys).
- The Locomotion Controller only sets up legs. Build/Park are on the Hull Core, and Hover too (shown only when the Colossus has thrusters).

## Using a built Colossus
You don't have to park to use or change it:
- **Every block works.** Each built Colossus keeps a real copy of its blocks in a hidden, empty dimension (the *dock*). Right-clicking a block on the Colossus uses that real block, so doors, trapdoors, levers, buttons, chests, furnaces, crafting tables, redstone, crops and other mods' machines (Create too) work just like on the ground, while it moves. Changes go both ways: what you do on the Colossus is written to the dock, and what happens in the dock (a lamp lights, a machine places a block, a chest fills) comes back to the Colossus. Sounds, effects, dropped items and dispenser shots come out at the Colossus.
- **Engines** open their fuel screen, and fuel you put in burns into the Colossus' power while it runs.
- **Real blocks on the client too:** players near a Colossus get its dock blocks as part of their world, and the crosshair points at them. So **F3 shows the targeted block**, block-info mods (Jade, WTHIT) show it, **mining works like normal mining** (same speed, holding the button), and right-click, middle-click pick and placing all go through the normal game. (F3's coordinates are the block's dock place, far away.) A small label with the block's name and mod is shown when no block-info mod is installed.
- **Place blocks** against any face with a block in hand, facing the way you look (sneak to place against a door or chest). Not into the ground.
- **Left-click** to mine a block (like normal mining; instant in creative). The Hull Core can't be removed while built.
- Anything no longer joined to the main body breaks off as a **wreck**.
- **Explosions** blow off weak blocks (like terrain) and damage joints. A joint that takes too much breaks, and what it held falls off as a wreck. Stronger and bigger joints take more.

## Smart footing (Locomotion Controller, Footing tab)
Smart footing is **on by default** and uses **25 CJ/t** while a walker stands or walks. Before every step the legs look at the ground and put the foot on safe, level footing: every sole block has ground under it (not over an edge), the soles are nearly level, the rise or drop is one the leg can take, and lava, fire, magma, cactus, campfires, berry bushes and powder snow are refused. Water and slippery ground count against a spot. If there's no good spot nearby the foot takes a short step, or steps in place. Turn it off on the **Footing** tab to save power (feet then land wherever the gait says).

The **Footing** tab shows a small top-down map of what the feet see (17x17 blocks, a few flat colours): lower, level and higher ground, water, danger and walls, the body's outline, the feet (solid = planted, hollow = stepping), where each foot steps next (yellow, red = no safe spot) and the heading. It refreshes once a second, only while the tab is open. (This replaces the old Step Controller block.)

## Wheels and spinners
Set a joint to **Spin** and it works like a wheel: with no blades on it, it turns only when you press the **Spin keys** (Keys tab: Spin clockwise, Spin counter-clockwise, Brake; by default W, S and X). A Spin joint whose part reaches the ground **drives the Colossus along**: "clockwise" always drives forward, whichever side the wheel is on. Brake stops the wheels and the Colossus; let go and it rolls on. A/D still turn it. With Rotor Blades on it, a Spin joint is a rotor (throttle as before).

## Ball joints: ankles, hips and shoulders
- **Ball Joint** (plain): keeps the part below it **level** (a flat foot). Needs no motor and has no mode. Put one between the shin and the foot.
- **Sideways ball joints:** place a Ball Joint like a log: its axis (through the ball) points at the face you click. A powered ball **upright** (axis up) is a **hip** (swings forward/back and to the side, and twists). **On its side** (axis level) it is a **knee**: a hinge about its own axis that can turn **all the way round** (±180°), while its tilts stay within its shape's range. Ball assemblies stack along their axis.
- **Big ball joints:** Ball Joints next to each other join into **one** joint: an n x n square, up to n layers high (2x1x2, 2x2x2, 3x2x3 ...). It is drawn as one big ball and carries n^n the load. Its **range of motion** grows with height: **±90° × layers / side**, so a flat 2x1x2 turns ±45° and a 2x2x2 cube ±90° any way. Sneak while placing to keep one on its own.
- **Two-axis joints** (ball joints, Powered Ball Joint hips) have two ranges: **Swing** (forward/back) and **Side** (out to the side). A hip's side range sets how wide a leg can step or how far it can cross under the body; an ankle's sets how far the foot rolls to a side slope. Hips default to ±30° to the side.
- **Range of motion:** in the Joints tab, the up/down arrows set how far a joint may turn either way, up to ±180° for bearings (shift-click for big steps; balls stop at what their shape allows). Legs fold as far as they can **without hitting the rest of the Colossus**: each side of each joint is checked when it's built, so a knee can fold right back even if a motor blocks it from bending forward. The value turns yellow when it's set past that point.
- **Powered Ball Joint** (or any ball joint with a Servo Motor touching it) has three modes, set in the Joints tab:
  - **Ankle (Locked):** keeps the foot flat and tilts it to match a slope; helps the walker balance.
  - **Hip (Leg):** a three-way leg joint. The IK swings the leg forward/back *and* sideways, and twists it when the body turns, so the body can lean over a foot and turn without the feet sliding. Use it as the top joint of a leg (a knee bearing below it is fine). No extra motor needed.
  - **Shoulder (Sway):** swings an arm in time with the steps (left and right opposite), and rests when standing.

## Jump Drive
A capacitor. It charges slowly from your engines (400 CJ/tick, holds 600k) and dumps it all at once when you press the jump key. It overpowers the servos: the legs go limp and the whole Colossus is launched up. More drives = more charge. The HUD shows JUMP DRIVE %.

## Walkers and balance
A Colossus that stands on parts hanging from joints set to **Leg** is a walker. It stays standing only if:
1. every **Leg** joint (hips, knees) from each foot up to the core has a strong enough **Servo Motor** (Locked and Ball joints don't need one),
2. the engines give enough power for the knee motors to hold the weight (the scan warns if not), and
3. the **centre of mass is over the feet**, or it can step under it (walkers with a Locomotion Controller balance themselves). A gyro widens that area a little.

Otherwise it tips over towards its heavy side. The Overview checklist and the **Top** view in the Joints tab (the cyan box is the feet, + is the weight) show what's wrong.

## Joint loads: lifted and standing
Each joint shows two estimates:
- **LIFTED:** everything below the joint hangs from it (a leg in the air, an arm). The top bar is the load against the joint's strength, and the bottom bar is the motor's turning force against what's needed to swing it.
- **STAND:** feet on the ground carry the whole rig. Each foot gets a share of the weight: two equal legs placed evenly each carry half, and a foot nearer the centre of mass carries more. A joint carries its feet's share minus what is below it, so the **ankle carries the most** and the hip the least. The bottom bar is the turning force needed to hold the rig up from that joint: straight legs need little, bent knees need more.
- Either bar over 100% means the joint will snap (live, while standing too). Use bigger or stronger joints low on the legs.

## Joints view (3D schematic)
The Joints tab (in the Locomotion Controller) names what each joint is (hip, knee, ankle, wheel...) in a line; what to do about a joint in the wrong mode is in the Hull Core checklist (**Joint modes set**). It is a 3D view of the whole Colossus: left-drag turns it, right-drag moves it, and scroll zooms. The side panel shows the selected joint: position, load, torque, gears and, for legs, foot grip, push and carry limits. A button sets the joint's **Mode** (Locked, Leg, Spin, Sway); up/down arrows set its **range of motion** (how far it may turn).
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
  - **Placing blades:** they lie flat. On a side face a blade points out from it; on a top or bottom face it points the way you look (sneak to stand it upright); on another blade's end it carries on that line.
  - Blades hold onto other blades, and onto anything else only at their ends. So blades put straight onto a bearing's sides, lying across the hull, still turn with the bearing.
  - A sideways rotor (a tail rotor) is also a **rudder**: A/D turn the craft with it.
  - A Spin joint has no range: it turns round and round. Its **Turns** button (Joints tab) sets which way.

## Flight Controller
A block for anything that flies. Right-click it, or press **J** while piloting. Its tabs: **Flight**, **Joints** (same as the Locomotion Controller's, so a helicopter needs only this) and **Keys**.
- **Lean:** W/S tip the whole craft toward the move (up to the Lean angle), so every rotor and jet pushes that way a little, like a helicopter. 0 = always level.
- **Rotor heads:** a **Powered Ball Joint set to Spin** with blades on it (on a hub above it, or straight on it) spins them and **tilts its blades** toward where you steer (up to the Rotor tilt angle). Motors and gearboxes on it set how fast it spins.
- **Tilt keys:** arrow keys tip the nose down/up and roll left/right (roll slides the craft sideways). Change them in the Keys tab.
- **Air brake:** hold X to stop fast in the air.
- **Dampeners** (on by default): let go of the keys in the air and it slows to a stop and holds its height.
- W/S no longer spin lifting rotors to full when a Flight Controller is on: they steer instead. Space/Shift still climb and sink.

## Servo motors and gearboxes
- Each motor has a **shaft axis**: place it against the joint or gearbox it should drive.
- Motors side by side across their axis join into a square motor (2x2 up to 6x6; sneak to keep them apart). An n x n motor gives **n^n times the torque** (and uses n^n times the power) and turns one shaft as thick as the square.
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

## Gyros
A **Gyro** is a reaction wheel: it adds turning force and steadies a Colossus (useful for ships, jets and hovering craft). Gyros next to each other join into **one big gyro** like ball joints: an n x n square up to n layers high, n^n × the capacity × layers ÷ side (a 1x1 holds 20 t, a 2x2x2 80 t, a 3x3x3 540 t), drawn as one big gyro with spinning rings. Walkers don't need one. (The old Gyro Array and Grand Gyroscope structures are gone.)

## Obsidian armour and the Diamond Stonecutter
**Obsidian Slab**, **Obsidian Vertical Slab** (stands on its edge against a side) and **Obsidian Stairs** are cut from obsidian in a **Diamond Stonecutter** (a diamond above three stone). A normal stonecutter can't cut obsidian. Obsidian is about as heavy as stone but very tough: good armour plating.

## Toughness, crushing and impacts
Mass and toughness are separate. **Mass** comes from density (below). **Toughness** comes from vanilla's own numbers for each block: hardness (how long it takes to mine) and blast resistance.
- **Feet crush** what they land on or swing into if the foot block is tougher than it and pushes hard enough on it (a share of the weight; landing hits harder). A wooden foot can't break a log; an iron foot under a heavy mech can, and then **the whole tree comes down**. Plants and flowers are flattened by anything heavy. Natural ground (dirt, sand, stone, terracotta, gravel) is never dug out by walking. Crushed blocks drop as items.
- **Impacts:** a hard landing or crash breaks the Colossus' own blocks that take the hit if the energy on each is more than they can take: obsidian shrugs off what shatters wood.
- Pilots only pick up items near their own body, not everything under the Colossus.
- Both can be turned off: `footCrushing` and `impactDamage` in the server config.

## Thrusters
Steam Vent, Blaze Jet, Arc Thruster, Ion Lance and Graviton Lens (40 kN up to 8 MN). The nozzle points where the exhaust goes, and the push goes the other way. **Sky Sails** give lift when moving fast.

## Creative tabs
There are five tabs: Command (Hull Core, throne, scaffold), Joints & Motors, Power, Propulsion & Gyros, and Materials.

## Config (`colossalcraft-server.toml` in each world's `serverconfig` folder)
- `maxBlocksPerSegment` (default 1,000,000): the most blocks in one part.
- `maxBlocks` (default 250,000): a safety stop, so a build that touches the ground can't swallow the world.
- Also: `massScale`, `maxSpeed`, `liftScale`, `groundCrushing`, `entityCrushing`, `jointBreaking`, `footCrushing`, `impactDamage`.

## Mass
Mass = block density × block shape volume × `massScale` (default 0.25). A slab weighs half a block. Wood is about 650 kg/m³, stone 2,500, obsidian 2,600, iron 7,870 and gold 19,300. Storage blocks of precious things are heavy: diamond and emerald blocks 9,000, redstone 8,000, lapis 7,000. Datapacks can change densities in `data/<ns>/colossal_density/*.json`.

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
- The mining crack isn't drawn on a moving Colossus (the block still breaks at the normal speed).
- Colossi turn only left/right (yaw) while walking; they tip only when they fall.
- **Old worlds:** the material-tier joints, motors and the four old gearboxes are gone; those blocks disappear from existing builds. Rebuild with Bearings, Servo Motors and the two new gearboxes.
- Legs that only hinge front-to-back can't really shift weight sideways: the body still leans over the feet, and the feet can look a little off to the side while it does. Powered ball ankles need less lean.
- Walkers need real power for their knees. A very wide two-leg stance leans a lot when stepping.
- Editing blocks or toggling a door makes the walker re-plant its feet.
- Blocks on a moving Colossus don't tick (furnaces don't smelt, Create machines don't run) until it's parked. Engines are the exception.
- A wreck keeps the pose of the piece that broke off, except that its own inner joints straighten.
- Big Colossi (thousands of blocks) can lower FPS, because every block is drawn each frame.
- Do not use this mod together with Valkyrien Skies.
