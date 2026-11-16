# A tall top-heavy tower on a 1x1 stem with a 3x3 Gyro Stabilizer on top, powered by a redstone block.
# Leave it 30 s or so for the rotor to spin up in the world, then assemble: it should stand; without power it topples.
fill ~3 ~0 ~3 ~3 ~8 ~3 minecraft:oak_planks
fill ~2 ~9 ~2 ~4 ~9 ~4 minecraft:oak_planks
fill ~2 ~10 ~2 ~4 ~10 ~4 colossus:gyro_stabilizer
setblock ~3 ~11 ~3 minecraft:redstone_block
setblock ~2 ~11 ~2 colossus:wooden_hull_core
tellraw @s {"text":"Gyro tower built. Wait ~30 s for the gyro to spin up, then sneak + right-click the Hull Core. /colossus gyros shows rpm.","color":"aqua"}
