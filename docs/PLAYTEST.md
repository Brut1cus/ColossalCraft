# Playtest guide

What to build, what should happen, and what to send back. Each test takes a few minutes. The builds come as functions inside the mod, so every tester builds exactly the same machine.

## Setup

1. **Get the jar.** Either download the `colossalcraft-jar` artifact from the latest green run on the pull request's Actions page, or build it yourself with `./gradlew build` (the jar lands in `build/libs/`).
2. Install NeoForge **21.1.x for Minecraft 1.21.1**. Put the jar in `mods/`. No other mods are needed.
3. Create a **Creative, Superflat** world with cheats on.
4. Press F3 if you want to read coordinates. All builds are placed relative to where you stand: 2 blocks east (+X) and south (+Z) of you.

Useful commands (operator): `/colossus list`, `/colossus physics`, `/colossus walk <speed> [turn]`, `/colossus stop`, `/colossus gyros`, `/colossus disassemble nearest`.

## Test 1: the quadruped walks (the main one)

1. Stand on open ground. Run `/function colossus:playtest/track` to lay a flat stone track 100 blocks long to the south.
2. Run `/function colossus:playtest/quadruped`. You get a 6×6 plank deck on four 2×2 legs. Each leg has a ball-bearing hip, a thrust-bearing knee, a universal-joint ankle and foot pads, with a Hull Core and a Pilot Seat on the deck.
3. **Look before assembling.** Right-click the Hull Core (on the deck): chat shows a scan with 150 blocks and 13 parts, and should say it is ready.
4. **Assemble:** sneak + right-click the Hull Core. The machine should crouch slightly and stand still. The renderer should keep drawing it in place, with no flicker or gaps.
5. **Drive:** right-click the Pilot Seat (it sits on the deck) to get in. **W** walks forward (south at first), **S** backs up, **A/D** turn. Sneak gets you out, and the machine stops when you leave or let go.
   - Without the seat: `/colossus walk 0.5` walks it, `/colossus walk 0 0.25` turns it, `/colossus stop` stops it.
6. Walk it about 50 blocks down the track, turn it around and come back.

**Expected:** about 0.4–0.5 blocks per second. One leg moves at a time (left hind, left front, right hind, right front) and the body leans before each step. It stays level (under about 5° of tilt) and no "joint snapped" messages appear in the log. In GameTests it has covered 50 blocks in about 130 s, but it sometimes pauses mid-walk while a leg waits for the body to shift its weight (another run managed 36 blocks in 4 minutes). **Where and how often it pauses is exactly what I most want to know.**

**Please report:**
- How far it got, and roughly how long that took.
- Whether it fell over, got stuck (standing still with W held), drifted sideways or wobbled. Note where and how.
- Whether turning with A/D works, and in which direction it turns.
- How it looked: smooth or jittery motion, legs drawn in the right places.
- Anything in chat or in `logs/latest.log` that mentions `colossus`, especially `snapped` lines and any stack traces.

## Test 2: the Pilot Seat before assembly

1. Build another quadruped somewhere else (or disassemble the first with `/colossus disassemble nearest`).
2. Sit on the Pilot Seat **first**, then have someone else assemble it. Or, on your own: sit, get up, assemble, and sit again by right-clicking the seat (the invisible seat entity is clickable where the seat block is drawn).

**Expected:** you stay seated through assembly and ride along. After disassembly you're put back on the ground and the blocks return.

**Please report:** whether you kept your seat, whether you could get back in, and whether the camera ride was smooth.

## Test 3: the heavy quadruped (joint strength)

1. Run `/function colossus:playtest/quadruped_heavy`: the same frame with a stone deck (about 63 t) on plank legs.
2. Assemble it and walk it as in Test 1.

**Expected:** it should still walk, a little more sluggishly. This build sits near the limits of 2×2 joints, so a `snapped` message here is useful data, not a disaster.

**Please report:** distance, any joints that snapped (the log names the joint type), and any sagging.

## Test 4: the Gyro Stabilizer

1. Run `/function colossus:playtest/gyro_tower`: a tall plank stem with a heavy platform and a 3×3 Gyro Stabilizer on top, powered by a redstone block.
2. Wait about 30 seconds so the rotor spins up in the world.
3. Assemble it (sneak + right-click the Wooden Hull Core on top). Run `/colossus gyros` a few times.
4. For comparison, build it again, break the redstone block before assembling, and assemble it unpowered.

**Expected:** the powered tower stays standing (or tips very slowly as the gyro saturates). `/colossus gyros` shows a 3×3 rotor near 3000 rpm and a budget slowly filling up. The unpowered tower tips over.

**Please report:** whether the powered one stood and for how long, the rpm and capacity numbers, and what the unpowered one did.

## Test 5: general sanity

- Put a chest with items on any build, assemble, then disassemble. The items should still be there.
- Log out and back in with a Colossus assembled. It should still be drawn and still respond.
- `/colossus physics` shows the physics step time. Note it with one walker running, and with two or three if you have time.

## Known limits (please don't report these)

- **You can't click blocks on an assembled machine**, except the Pilot Seat entity. Interaction redirect is still a Phase 2 item.
- **Chests, signs and other block entities on board are invisible while assembled.** They come back on disassembly.
- **Players and mobs don't collide with an assembled machine.** You can walk through it; entity collision isn't done yet.
- **Machines don't collide with each other.**
- **Only the slow crawl is tuned.** The top speed is 0.5 blocks/s (`maxSpeed` in the `[walker]` server config).

## Server config

All in `serverconfig/colossus-server.toml` inside your world:
- `[walker]`: `enabled`, `maxSpeed`, `crouch`, `stepHeight`, `turnRate`.
- `[gyro]`: rotor and controller numbers.
- `[joints]`: `breaking` and `strainBreakSeconds`. Turn breaking off if you want to see how far a machine gets without snapping.
