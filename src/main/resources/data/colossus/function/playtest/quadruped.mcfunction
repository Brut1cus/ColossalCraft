# plank-deck quadruped (the 50-block GameTest build). Built 2 blocks east and south of you; it walks south (+Z).
# Layers from the ground up: foot pads, universal ankles, shin, thrust-bearing knees, thigh x2, ball-bearing hips, deck, core + seat.
fill ~2 ~0 ~2 ~3 ~0 ~3 colossus:foot_pad
fill ~6 ~0 ~2 ~7 ~0 ~3 colossus:foot_pad
fill ~2 ~0 ~6 ~3 ~0 ~7 colossus:foot_pad
fill ~6 ~0 ~6 ~7 ~0 ~7 colossus:foot_pad
fill ~2 ~1 ~2 ~3 ~1 ~3 colossus:universal_joint[axis=y]
fill ~6 ~1 ~2 ~7 ~1 ~3 colossus:universal_joint[axis=y]
fill ~2 ~1 ~6 ~3 ~1 ~7 colossus:universal_joint[axis=y]
fill ~6 ~1 ~6 ~7 ~1 ~7 colossus:universal_joint[axis=y]
fill ~2 ~2 ~2 ~3 ~2 ~3 minecraft:white_wool
fill ~6 ~2 ~2 ~7 ~2 ~3 minecraft:white_wool
fill ~2 ~2 ~6 ~3 ~2 ~7 minecraft:white_wool
fill ~6 ~2 ~6 ~7 ~2 ~7 minecraft:white_wool
fill ~2 ~3 ~2 ~3 ~3 ~3 colossus:thrust_bearing[axis=x]
fill ~6 ~3 ~2 ~7 ~3 ~3 colossus:thrust_bearing[axis=x]
fill ~2 ~3 ~6 ~3 ~3 ~7 colossus:thrust_bearing[axis=x]
fill ~6 ~3 ~6 ~7 ~3 ~7 colossus:thrust_bearing[axis=x]
fill ~2 ~4 ~2 ~3 ~4 ~3 minecraft:white_wool
fill ~6 ~4 ~2 ~7 ~4 ~3 minecraft:white_wool
fill ~2 ~4 ~6 ~3 ~4 ~7 minecraft:white_wool
fill ~6 ~4 ~6 ~7 ~4 ~7 minecraft:white_wool
fill ~2 ~5 ~2 ~3 ~5 ~3 minecraft:white_wool
fill ~6 ~5 ~2 ~7 ~5 ~3 minecraft:white_wool
fill ~2 ~5 ~6 ~3 ~5 ~7 minecraft:white_wool
fill ~6 ~5 ~6 ~7 ~5 ~7 minecraft:white_wool
fill ~2 ~6 ~2 ~3 ~6 ~3 colossus:ball_bearing[axis=y]
fill ~6 ~6 ~2 ~7 ~6 ~3 colossus:ball_bearing[axis=y]
fill ~2 ~6 ~6 ~3 ~6 ~7 colossus:ball_bearing[axis=y]
fill ~6 ~6 ~6 ~7 ~6 ~7 colossus:ball_bearing[axis=y]
fill ~2 ~7 ~2 ~7 ~7 ~7 minecraft:oak_planks
setblock ~4 ~8 ~4 colossus:iron_hull_core
setblock ~4 ~8 ~6 colossus:pilot_seat
tellraw @s {"text":"Built. Sneak + right-click the Hull Core (on top of the deck) to assemble, then sit on the Pilot Seat: W/S walk, A/D turn.","color":"aqua"}
