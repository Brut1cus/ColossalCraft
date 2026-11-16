#!/usr/bin/env python3
"""Generates ColossalCraft (mod id: colossus) textures, models, blockstates, loot tables, recipes, tags and lang.
Run from anywhere: python3 tools/gen_assets.py   (needs Pillow for textures)."""
import json, os, random, zlib
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources')
NS = 'colossus'
A = f'{ROOT}/assets/{NS}'
D = f'{ROOT}/data/{NS}'

def w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')

TIERS = [('wooden', 'Wooden Hull Core'), ('iron', 'Iron Hull Core'), ('steel', 'Steel Hull Core'),
         ('netherite', 'Netherite Hull Core'), ('singularity', 'Singularity Hull Core')]
JOINTS = [('thrust_bearing', 'Thrust Bearing'), ('ball_bearing', 'Ball Bearing'), ('universal_joint', 'Universal Joint'),
          ('swivel_bearing', 'Swivel Bearing'), ('slider_joint', 'Slider Joint'), ('spring_damper', 'Spring Damper'),
          ('fixed_weld', 'Fixed Weld')]
SIMPLE = [('core_lattice', 'Core Lattice'), ('foot_pad', 'Foot Pad')]
GYRO = ('gyro_stabilizer', 'Gyro Stabilizer')
SEAT = ('pilot_seat', 'Pilot Seat')

# ----------------------------------------------------------------------------- textures
def textures():
    try:
        from PIL import Image
    except ImportError:
        print('Pillow missing: skipping textures'); return
    out = f'{A}/textures/block'
    os.makedirs(out, exist_ok=True)
    def img(base, seed, noise=0.08):
        r = random.Random(seed); im = Image.new('RGBA', (16, 16))
        for y in range(16):
            for x in range(16):
                f = 1 + r.uniform(-noise, noise)
                im.putpixel((x, y), tuple(max(0, min(255, int(c * f))) for c in base) + (255,))
        return im
    def shade(c, f): return tuple(max(0, min(255, int(v * f))) for v in c)
    def px(im, x, y, c): im.putpixel((x, y), tuple(c[:3]) + (255,))
    def frame(im, c):
        for i in range(16):
            px(im, i, 0, shade(c, 1.3)); px(im, 0, i, shade(c, 1.2)); px(im, i, 15, shade(c, 0.55)); px(im, 15, i, shade(c, 0.65))
    def ring(im, cx, cy, r0, r1, c):
        for y in range(16):
            for x in range(16):
                d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if r0 <= d <= r1: px(im, x, y, c)
    tier_col = {'wooden': (150, 110, 60), 'iron': (190, 190, 195), 'steel': (120, 130, 145),
                'netherite': (70, 60, 65), 'singularity': (40, 20, 70)}
    glow = {'wooden': (240, 180, 60), 'iron': (90, 200, 255), 'steel': (120, 255, 200),
            'netherite': (255, 120, 60), 'singularity': (200, 120, 255)}
    for t, _ in TIERS:
        c = tier_col[t]; im = img(c, zlib.crc32(t.encode())); frame(im, c)
        for y in range(4, 12):
            for x in range(4, 12): px(im, x, y, shade(c, 0.6))
        ring(im, 7.5, 7.5, 1.5, 3.2, glow[t])
        for (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13)]: px(im, x, y, shade(c, 1.5))
        im.save(f'{out}/{t}_hull_core.png')
    c = (60, 55, 60); im = img(c, 77); frame(im, c)
    for i in range(1, 15):
        px(im, i, i, (255, 120, 60)); px(im, 15 - i, i, (90, 220, 255))
    im.save(f'{out}/core_lattice.png')
    c = (35, 35, 38); im = img(c, 91, 0.15)
    for y in range(2, 16, 4):
        for x in range(16): px(im, x, y, shade(c, 1.6))
    im.save(f'{out}/foot_pad.png')
    # Gyro Stabilizer: brass rotor ring on a steel housing (end), gimbal band on the sides
    housing = (95, 98, 105); brass = (205, 165, 75)
    end = img(housing, 4242); frame(end, housing)
    ring(end, 7.5, 7.5, 3.5, 6.6, brass); ring(end, 7.5, 7.5, 4.6, 5.4, shade(brass, 0.7)); ring(end, 7.5, 7.5, 0, 1.6, (90, 200, 255))
    for i in (2, 13):
        for j in (7, 8): px(end, i, j, shade(brass, 1.2)); px(end, j, i, shade(brass, 1.2))
    end.save(f'{out}/gyro_stabilizer_end.png')
    side = img(housing, 4343); frame(side, housing)
    for x in range(1, 15):
        for y in (5, 10): px(side, x, y, shade(brass, 0.9))
        for y in range(6, 10): px(side, x, y, shade(brass, 0.6 + 0.04 * ((x + y) % 4)))
    for (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13)]: px(side, x, y, shade(housing, 1.5))
    side.save(f'{out}/gyro_stabilizer_side.png')
    # Pilot Seat: wool cushion on a dark wood frame
    wood = (110, 80, 50); cushion = (170, 40, 40)
    top = img(cushion, 5151, 0.06); frame(top, shade(cushion, 0.8))
    for i in range(3, 13): px(top, i, 7, shade(cushion, 0.75)); px(top, 7, i, shade(cushion, 0.75))
    top.save(f'{out}/pilot_seat_top.png')
    side = img(wood, 5252); frame(side, wood)
    for x in range(16):
        for y in range(0, 4): px(side, x, y, shade(cushion, 0.9))
    side.save(f'{out}/pilot_seat_side.png')
    joint_col = {'thrust_bearing': (200, 160, 70), 'ball_bearing': (180, 180, 190), 'universal_joint': (150, 170, 200),
                 'swivel_bearing': (190, 120, 80), 'slider_joint': (120, 160, 120), 'spring_damper': (200, 200, 90),
                 'fixed_weld': (150, 150, 150)}
    housing = (95, 98, 105)
    for j, _ in JOINTS:
        accent = joint_col[j]
        side = img(housing, zlib.crc32(j.encode())); frame(side, housing)
        for x in range(16):
            px(side, x, 7, shade(accent, 0.9)); px(side, x, 8, shade(accent, 0.7))
        if j in ('slider_joint', 'spring_damper'):
            for y in range(2, 14, 2):
                for x in range(6, 10): px(side, x, y, accent)
        side.save(f'{out}/{j}_side.png')
        end = img(housing, zlib.crc32(j.encode()) >> 4); frame(end, housing)
        ring(end, 7.5, 7.5, 4.5, 6.5, shade(accent, 0.8))
        ring(end, 7.5, 7.5, 0, 2.2, accent)
        if j == 'ball_bearing': ring(end, 7.5, 7.5, 2.2, 4.0, shade(accent, 1.25))
        if j == 'fixed_weld':
            for i in range(3, 13): px(end, i, i, accent); px(end, 15 - i, i, accent)
        end.save(f'{out}/{j}_end.png')

# ----------------------------------------------------------------------------- models and states
def models():
    lang = {'itemGroup.colossus.main': 'ColossalCraft'}
    for t, name in TIERS:
        n = f'{t}_hull_core'
        w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/{n}'}})
        w(f'{A}/blockstates/{n}.json', {'variants': {'': {'model': f'{NS}:block/{n}'}}})
        w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}'})
        lang[f'block.{NS}.{n}'] = name
    for n, name in SIMPLE:
        w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/{n}'}})
        w(f'{A}/blockstates/{n}.json', {'variants': {'': {'model': f'{NS}:block/{n}'}}})
        w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}'})
        lang[f'block.{NS}.{n}'] = name
    n, name = SEAT
    w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/slab',
                                      'textures': {'bottom': f'{NS}:block/{n}_side', 'top': f'{NS}:block/{n}_top', 'side': f'{NS}:block/{n}_side'}})
    w(f'{A}/blockstates/{n}.json', {'variants': {'': {'model': f'{NS}:block/{n}'}}})
    w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}'})
    lang[f'block.{NS}.{n}'] = name
    lang['entity.colossus.seat'] = 'Pilot Seat'
    n, name = GYRO
    w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/cube_column',
                                      'textures': {'end': f'{NS}:block/{n}_end', 'side': f'{NS}:block/{n}_side'}})
    w(f'{A}/blockstates/{n}.json', {'variants': {'': {'model': f'{NS}:block/{n}'}}})
    w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}'})
    lang[f'block.{NS}.{n}'] = name
    for n, name in JOINTS:
        w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/cube_column',
                                          'textures': {'end': f'{NS}:block/{n}_end', 'side': f'{NS}:block/{n}_side'}})
        m = f'{NS}:block/{n}'
        w(f'{A}/blockstates/{n}.json', {'variants': {'axis=y': {'model': m}, 'axis=z': {'model': m, 'x': 90},
                                                     'axis=x': {'model': m, 'x': 90, 'y': 90}}})
        w(f'{A}/models/item/{n}.json', {'parent': m})
        lang[f'block.{NS}.{n}'] = name
    lang.update({
        'colossus.scan.summary': 'Scan: %s blocks, %s parts, %s joints, %s t',
        'colossus.scan.ready': 'Ready. Sneak + right-click the Hull Core to assemble.',
        'colossus.assembly.disabled': 'Assembly is disabled on this server.',
        'colossus.assembly.too_many': 'This dimension already has the most Colossi the server allows.',
        'colossus.assembly.failed': 'Cannot assemble:',
        'colossus.assembly.done': '%s blocks in %s parts are now one Colossus. Use /colossus disassemble nearest to put it back.',
        'colossus.disassembly.blocked': 'Cannot put it back: %s blocks are in the way (first at %s).',
        'colossus.disassembly.done': '%s blocks are back in the world.',
    })
    w(f'{A}/lang/en_us.json', lang)

# ----------------------------------------------------------------------------- data
def loot(n):
    w(f'{D}/loot_table/blocks/{n}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'bonus_rolls': 0,
        'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{n}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

def shaped(name, pattern, key, count=1, needs_tag=None):
    k = {c: ({'tag': v[1:]} if v.startswith('#') else {'item': v}) for c, v in key.items()}
    r = {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pattern, 'key': k, 'result': {'id': f'{NS}:{name}', 'count': count}}
    if needs_tag:  # only load when some mod (or a later Colossus phase) provides the material
        r = {'neoforge:conditions': [{'type': 'neoforge:not', 'value': {'type': 'neoforge:tag_empty', 'tag': needs_tag}}], **r}
    w(f'{D}/recipe/{name}.json', r)

def shapeless(name, items, count=1):
    ing = [({'tag': v[1:]} if v.startswith('#') else {'item': v}) for v in items]
    w(f'{D}/recipe/{name}.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': ing,
                                  'result': {'id': f'{NS}:{name}', 'count': count}})

def data():
    all_blocks = [f'{t}_hull_core' for t, _ in TIERS] + [n for n, _ in SIMPLE] + [n for n, _ in JOINTS] + [GYRO[0], SEAT[0]]
    for n in all_blocks: loot(n)
    axe = ['wooden_hull_core', 'pilot_seat']
    hoe = ['foot_pad']
    w(f'{ROOT}/data/minecraft/tags/block/mineable/pickaxe.json', {'values': [f'{NS}:{n}' for n in all_blocks if n not in axe + hoe]})
    w(f'{ROOT}/data/minecraft/tags/block/mineable/axe.json', {'values': [f'{NS}:{n}' for n in axe]})
    # Natural terrain is never picked up by a scan, so a build standing on the ground does not swallow it.
    terrain = ['bedrock', 'grass_block', 'dirt', 'coarse_dirt', 'rooted_dirt', 'podzol', 'mycelium', 'mud', 'sand', 'red_sand',
               'gravel', 'clay', 'stone', 'deepslate', 'tuff', 'granite', 'diorite', 'andesite', 'calcite', 'netherrack',
               'end_stone', 'soul_sand', 'soul_soil', 'scaffolding', 'snow_block', 'powder_snow']
    w(f'{D}/tags/block/non_assemblable.json', {'values': [f'minecraft:{b}' for b in terrain] + [{'id': '#c:ores', 'required': False}]})
    w(f'{D}/tags/block/unsteppable.json', {'values': ['minecraft:magma_block', 'minecraft:fire', 'minecraft:soul_fire', 'minecraft:campfire',
                                                      'minecraft:soul_campfire', 'minecraft:sweet_berry_bush', 'minecraft:powder_snow']})
    w(f'{D}/tags/block/uncrushable.json', {'values': ['minecraft:obsidian', 'minecraft:crying_obsidian', 'minecraft:bedrock',
                                                      'minecraft:reinforced_deepslate', 'minecraft:end_portal_frame', 'minecraft:barrier']})
    # Recipes ask for common c: tags only, so lead/steel/etc. from any mod work.
    shaped('wooden_hull_core', ['PPP', 'PRP', 'PPP'], {'P': '#minecraft:planks', 'R': '#c:dusts/redstone'})
    shaped('iron_hull_core', ['IRI', 'ICI', 'IRI'], {'I': '#c:ingots/iron', 'R': '#c:dusts/redstone', 'C': f'{NS}:wooden_hull_core'})
    shaped('steel_hull_core', ['SRS', 'SCS', 'SRS'], {'S': '#c:ingots/steel', 'R': '#c:dusts/redstone', 'C': f'{NS}:iron_hull_core'},
           needs_tag='c:ingots/steel')
    shaped('netherite_hull_core', ['DND', 'NCN', 'DND'], {'D': '#c:gems/diamond', 'N': '#c:ingots/netherite', 'C': f'{NS}:steel_hull_core'})
    shaped('core_lattice', ['ENE', 'NRN', 'ENE'], {'E': 'minecraft:echo_shard', 'N': '#c:ingots/netherite', 'R': '#c:storage_blocks/redstone'})
    shaped('thrust_bearing', ['III', 'N N', 'III'], {'I': '#c:ingots/iron', 'N': '#c:nuggets/iron'}, 2)
    shaped('ball_bearing', ['NIN', 'IBI', 'NIN'], {'I': '#c:ingots/iron', 'N': '#c:nuggets/iron', 'B': '#c:slime_balls'}, 2)
    shapeless('universal_joint', [f'{NS}:thrust_bearing', f'{NS}:thrust_bearing'])
    shaped('swivel_bearing', ['INI', 'I I', 'INI'], {'I': '#c:ingots/iron', 'N': '#c:nuggets/iron'}, 2)
    shaped('slider_joint', ['III', 'P P', 'III'], {'I': '#c:ingots/iron', 'P': 'minecraft:piston'})
    shapeless('spring_damper', [f'{NS}:slider_joint', '#c:slime_balls', '#c:ingots/iron'])
    shaped('fixed_weld', ['NIN', 'I I', 'NIN'], {'I': '#c:ingots/iron', 'N': '#c:nuggets/iron'}, 4)
    shaped('foot_pad', ['WWW', 'III'], {'W': '#minecraft:wool', 'I': '#c:ingots/iron'}, 2)
    shaped('pilot_seat', ['  W', 'WWW', 'P P'], {'W': '#minecraft:wool', 'P': '#minecraft:planks'})
    shaped('gyro_stabilizer', ['IRI', 'GBG', 'IRI'], {'I': '#c:ingots/iron', 'R': '#c:dusts/redstone', 'G': '#c:ingots/gold',
                                                      'B': '#c:storage_blocks/iron'})

    # Blueprint mass table (kg per full block), friction, restitution
    def p(m, f=0.6, r=0.1): return {'mass': m, 'friction': f, 'restitution': r}
    w(f'{D}/colossus/block_physics/defaults.json', {
        'default': p(1000),
        'blocks': {
            'minecraft:glass': p(800, 0.3), 'minecraft:stone': p(1600, 0.7), 'minecraft:cobblestone': p(1600, 0.7),
            'minecraft:iron_block': p(7800, 0.4, 0.2), 'minecraft:gold_block': p(19300, 0.3), 'minecraft:obsidian': p(2600, 0.6, 0.05),
            'minecraft:crying_obsidian': p(2600, 0.6, 0.05), 'minecraft:netherite_block': p(15000, 0.5), 'minecraft:diamond_block': p(3500, 0.5),
            'minecraft:copper_block': p(8900, 0.4, 0.2), 'minecraft:ice': p(900, 0.02), 'minecraft:packed_ice': p(900, 0.02),
            'minecraft:blue_ice': p(900, 0.01), 'minecraft:slime_block': p(1100, 0.8, 0.8), 'minecraft:honey_block': p(1400, 0.9, 0.0),
            'colossus:foot_pad': p(300, 0.9, 0.05), 'colossus:gyro_stabilizer': p(3000, 0.4, 0.1),
            # bearing housings are mostly hollow; heavy housings overload small joints and make legs as heavy as the
            # body they carry (a walker needs light legs)
            **{f'colossus:{j}': p(250, 0.4, 0.1) for j, _ in JOINTS}},
        'tags': {
            'c:storage_blocks/steel': p(7850, 0.4, 0.2), 'c:storage_blocks/lead': p(11300, 0.5, 0.05),
            'minecraft:wool': p(50, 0.6), 'minecraft:leaves': p(50, 0.6), 'minecraft:planks': p(400, 0.5, 0.2),
            'minecraft:logs': p(600, 0.5, 0.2), 'minecraft:stone_bricks': p(1600, 0.7), 'c:glass_blocks': p(800, 0.3)},
        'sound_types': {
            'wool': p(50), 'plant': p(50), 'wood': p(500, 0.5, 0.2), 'stone': p(1600, 0.7), 'metal': p(7800, 0.4, 0.2),
            'netherite': p(15000, 0.5), 'glass': p(800, 0.3), 'gravel': p(1500, 0.6), 'sand': p(1500, 0.5), 'snow': p(300, 0.1),
            'slime': p(1100, 0.8, 0.8), 'mud': p(1700, 0.8, 0.0)}})
    w(f'{D}/colossus/component_scaling/defaults.json', {
        'bearing': {'base_torque': 20000, 'base_load': 50000, 'torque_exponent': 3.0, 'load_exponent': 2.5},
        'joint': {'base_torque': 20000, 'base_load': 50000, 'torque_exponent': 3.0, 'load_exponent': 2.5},
        'gearbox': {'base_torque': 15000, 'base_load': 40000, 'torque_exponent': 3.0, 'load_exponent': 2.5},
        'shaft': {'base_torque': 10000, 'base_load': 30000, 'torque_exponent': 3.0, 'load_exponent': 2.0},
        'motor': {'base_torque': 8000, 'base_load': 30000, 'torque_exponent': 3.0, 'load_exponent': 2.0},
        'propeller_hub': {'base_torque': 12000, 'base_load': 40000, 'torque_exponent': 3.0, 'load_exponent': 2.5},
        'wheel': {'base_torque': 10000, 'base_load': 60000, 'torque_exponent': 3.0, 'load_exponent': 2.5},
        'piston': {'base_torque': 0, 'base_load': 120000, 'torque_exponent': 0.0, 'load_exponent': 2.5}})
    gaits = {
        'quad_walk': ([0.5, 0, 0.75, 0.25], 0.75, False, 0, 1.5), 'quad_trot': ([0, 0.5, 0.5, 0], 0.5, True, 1.2, 4),
        'quad_pace': ([0, 0.5, 0, 0.5], 0.5, True, 1.2, 4), 'quad_gallop': ([0, 0.1, 0.6, 0.5], 0.35, True, 3.5, 99),
        'biped_walk': ([0, 0.5], 0.6, True, 0, 2), 'biped_run': ([0, 0.5], 0.4, True, 1.8, 99),
        'tripod_step': ([0, 1 / 3, 2 / 3], 2 / 3, False, 0, 99),
        'hexapod_alternating_tripod': ([0, 0.5, 0.5, 0, 0, 0.5], 0.5, False, 0.8, 99),
        'hexapod_wave': ([0, 1 / 6, 2 / 6, 3 / 6, 4 / 6, 5 / 6], 5 / 6, False, 0, 1)}
    for n, (off, duty, dyn, lo, hi) in gaits.items():
        w(f'{D}/colossus/gaits/{n}.json', {'phase_offsets': [round(x, 4) for x in off], 'duty_factor': round(duty, 4),
                                           'dynamic': dyn, 'min_speed': lo, 'max_speed': hi})

if __name__ == '__main__':
    textures(); models(); data()
    print('done')
