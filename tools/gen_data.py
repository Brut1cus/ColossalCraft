# Generates blockstates, models, lang, loot tables, recipes and tags.
import json, os
R = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources')
NS = 'colossalcraft'
A = f'{R}/assets/{NS}'; D = f'{R}/data/{NS}'
def w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f: json.dump(obj, f, indent=2)
def tex(n): return f'{NS}:block/{n}'
MATS = ['wood', 'stone', 'iron', 'gold', 'diamond']
NAMES = {}
blocks = []

def cube_all(n, t=None, name=None):
    w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': tex(t or n)}})
    w(f'{A}/blockstates/{n}.json', {'variants': {'': {'model': f'{NS}:block/{n}'}}})
    blocks.append(n); NAMES[n] = name
def item_from_block(n, model=None): w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{model or n}'})
HZ = {'north': 0, 'east': 90, 'south': 180, 'west': 270}

def orientable(n, front, side, top, name, extra_state=None):
    w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/orientable', 'textures': {'front': tex(front), 'side': tex(side), 'top': tex(top)}})
    v = {}
    for f, y in HZ.items(): v[f'facing={f}'] = {'model': f'{NS}:block/{n}', 'y': y} if y else {'model': f'{NS}:block/{n}'}
    w(f'{A}/blockstates/{n}.json', {'variants': v}); blocks.append(n); NAMES[n] = name

# --- control ---
cube_all('hull_core', name='Hull Core')
def fit_uv(face, lo, hi):
    x0, y0, z0 = lo; x1, y1, z1 = hi
    uv = {'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0], 'south': [x0, 16 - y1, x1, 16 - y0], 'west': [z0, 16 - y1, z1, 16 - y0],
          'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0], 'up': [x0, z0, x1, z1], 'down': [x0, 16 - z1, x1, 16 - z0]}[face]
    for i in (0, 1):
        lo_v, hi_v = uv[i], uv[i + 2]
        if lo_v < 0: uv[i] -= lo_v; uv[i + 2] -= lo_v
        elif hi_v > 16: d = hi_v - 16; uv[i] -= d; uv[i + 2] -= d
    return [max(0, min(16, v)) for v in uv]
def cube_el(lo, hi, tx):
    return {'from': lo, 'to': hi, 'faces': {f: {'texture': tx.get(f, tx['*']), 'uv': fit_uv(f, lo, hi)} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
chair = [cube_el([2, 0, 2], [4, 5, 4], {'*': '#side'}), cube_el([12, 0, 2], [14, 5, 4], {'*': '#side'}),
         cube_el([2, 0, 12], [4, 5, 14], {'*': '#side'}), cube_el([12, 0, 12], [14, 5, 14], {'*': '#side'}),
         cube_el([1, 5, 1], [15, 8, 15], {'*': '#side', 'up': '#top'}),
         cube_el([1, 8, 13], [15, 22, 15], {'*': '#side', 'north': '#front'}),
         cube_el([0, 8, 2], [2, 12, 13], {'*': '#side', 'up': '#top'}), cube_el([14, 8, 2], [16, 12, 13], {'*': '#side', 'up': '#top'})]
w(f'{A}/models/block/pilot_seat.json', {'parent': 'minecraft:block/block', 'textures': {'front': tex('pilot_seat_front'), 'side': tex('pilot_seat_side'), 'top': tex('pilot_seat_top'), 'particle': tex('pilot_seat_side')}, 'elements': chair})
w(f'{A}/blockstates/pilot_seat.json', {'variants': {f'facing={f}': ({'model': f'{NS}:block/pilot_seat', 'y': y} if y else {'model': f'{NS}:block/pilot_seat'}) for f, y in HZ.items()}})
blocks.append('pilot_seat'); NAMES['pilot_seat'] = 'Pilot Throne'
# --- gantry scaffold (never part of a Colossus)
bars = []
for x0 in (0, 14):
    for z0 in (0, 14): bars.append(cube_el([x0, 0, z0], [x0 + 2, 16, z0 + 2], {'*': '#all'}))
for y0 in (0, 14):
    bars += [cube_el([2, y0, 0], [14, y0 + 2, 2], {'*': '#all'}), cube_el([2, y0, 14], [14, y0 + 2, 16], {'*': '#all'}),
             cube_el([0, y0, 2], [2, y0 + 2, 14], {'*': '#all'}), cube_el([14, y0, 2], [16, y0 + 2, 14], {'*': '#all'})]
bars.append(cube_el([2, 14.5, 2], [14, 15.5, 14], {'*': '#deck'}))
w(f'{A}/models/block/gantry_scaffold.json', {'parent': 'minecraft:block/block', 'textures': {'all': tex('gantry_scaffold'), 'deck': tex('gantry_scaffold_deck'), 'particle': tex('gantry_scaffold')}, 'render_type': 'minecraft:cutout', 'elements': bars})
w(f'{A}/blockstates/gantry_scaffold.json', {'variants': {'': {'model': f'{NS}:block/gantry_scaffold'}}})
blocks.append('gantry_scaffold'); NAMES['gantry_scaffold'] = 'Gantry Scaffold'
# --- joints & motors ---
JN = {'wood': 'Timber', 'stone': 'Cobble', 'iron': 'Iron', 'gold': 'Gilded', 'diamond': 'Diamond'}
AX = {'x': (0, 1, 2), 'y': (1, 0, 2), 'z': (2, 0, 1)}  # axis index, u index, v index
def box(lo, hi, axis_i):
    faces = {}
    for f, ax in (('north', 2), ('south', 2), ('east', 0), ('west', 0), ('up', 1), ('down', 1)):
        faces[f] = {'texture': '#end' if ax == axis_i else '#side', 'uv': fit_uv(f, lo, hi)}
    return {'from': lo, 'to': hi, 'faces': faces}
def make(a, u, v, ra, ru, rv):
    lo = [0, 0, 0]; hi = [0, 0, 0]
    lo[a], hi[a] = ra; lo[u], hi[u] = ru; lo[v], hi[v] = rv
    return lo, hi
def joint_elements(shape, axis):
    a, u, v = AX[axis]
    outer = {'joint': ((0, 16), (0, 16), (0, 16)), 'joint_slab': None, 'joint_panel': ((4, 12), (0, 16), (0, 16)), 'joint_pin': ((0, 16), (5, 11), (5, 11))}[shape]
    if shape == 'joint_slab':  # always the bottom half of the block
        rng = [(0, 16), (0, 8), (0, 16)]
        outer = (rng[a], rng[u], rng[v])
    (a0, a1), (u0, u1), (v0, v1) = outer
    els = []
    if shape == 'joint_pin':
        els.append(box(*make(a, u, v, (a0, a1), (u0, u1), (v0, v1)), a))
        for c0, c1 in ((1, 3), (13, 15)): els.append(box(*make(a, u, v, (c0, c1), (u0 - 1, u1 + 1), (v0 - 1, v1 + 1)), a))
        return els
    t = 2 if shape == 'joint_slab' else 3
    for ru, rv in (((u0, u1), (v0, v0 + t)), ((u0, u1), (v1 - t, v1)), ((u0, u0 + t), (v0 + t, v1 - t)), ((u1 - t, u1), (v0 + t, v1 - t))):
        els.append(box(*make(a, u, v, (a0, a1), ru, rv), a))
    # axle through the hole
    cu, cv = (u0 + u1) / 2, (v0 + v1) / 2
    hs = max(0.5, min(2, (u1 - u0 - 2 * t) / 2 - 1, (v1 - v0 - 2 * t) / 2 - 1))
    els.append(box(*make(a, u, v, (a0 - (1 if shape != 'joint' else 0), a1 + (1 if shape != 'joint' else 0)), (cu - hs, cu + hs), (cv - hs, cv + hs)), a))
    return els
SHAPES = [('joint', 'Bearing Joint'), ('joint_slab', 'Slab Joint'), ('joint_panel', 'Panel Joint'), ('joint_pin', 'Pin Joint')]
for m in MATS:
    for sh, title in SHAPES:
        n = f'{m}_{sh}'
        for axis in 'xyz':
            w(f'{A}/models/block/{n}_{axis}.json', {'parent': 'minecraft:block/block', 'textures': {'end': tex(f'{m}_joint_end'), 'side': tex(f'{m}_joint_side'), 'particle': tex(f'{m}_joint_side')},
                                                   'elements': joint_elements(sh, axis)})
        w(f'{A}/blockstates/{n}.json', {'variants': {f'axis={ax}': {'model': f'{NS}:block/{n}_{ax}'} for ax in 'xyz'}})
        blocks.append(n); NAMES[n] = f'{JN[m]} {title}'
        w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}_x'})
for m in MATS: cube_all(f'{m}_motor', name=f'{JN[m]} Servo Motor')
# --- engines ---
for n, nm in [('ember_boiler', 'Ember Boiler'), ('blaze_combustor', 'Blaze Combustor'), ('redstone_dynamo', 'Redstone Dynamo')]:
    orientable(n, n + '_front', n + '_side', n + '_top', nm)
def controller(n, casing, name):
    for on in (False, True):
        mn = n + ('_on' if on else '')
        w(f'{A}/models/block/{mn}.json', {'parent': 'minecraft:block/orientable', 'textures': {'front': tex(n + ('_front_on' if on else '_front')), 'side': tex(casing), 'top': tex(casing)}})
    v = {}
    for f, y in HZ.items():
        for on in ('false', 'true'):
            d = {'model': f'{NS}:block/{n}' + ('_on' if on == 'true' else '')}
            if y: d['y'] = y
            v[f'facing={f},formed={on}'] = d
    w(f'{A}/blockstates/{n}.json', {'variants': v}); blocks.append(n); NAMES[n] = name
for n, cas, core, nm, cn, kn in [
    ('ion_drive', 'void_casing', 'ender_coil', 'Ender Ion Drive', 'Void Casing', 'Ender Coil'),
    ('fission_reactor', 'leadlined_casing', 'fuel_rod_assembly', 'Fission Reactor', 'Lead-Lined Casing', 'Fuel Rod Assembly'),
    ('fusion_torus', 'containment_casing', 'plasma_coil', 'Fusion Torus', 'Magnetic Containment Casing', 'Plasma Coil'),
    ('zero_point_core', 'singularity_casing', 'vacuum_lattice', 'Zero-Point Core', 'Singularity Casing', 'Vacuum Lattice'),
    ('gyro_array', 'gyro_casing', 'gyro_rotor', 'Gyro Array', 'Gyro Casing', 'Gyro Rotor')]:
    cube_all(cas, name=cn); cube_all(core, name=kn); controller(n, cas, nm)
cube_all('null_singularity', name='Null Singularity')
controller('grand_gyroscope', 'gyro_casing', 'Grand Gyroscope')
cube_all('small_gyro', name='Pocket Gyro')
# --- thrusters (6 directions, nozzle = north face of model) ---
D6 = {'north': {}, 'south': {'y': 180}, 'east': {'y': 90}, 'west': {'y': 270}, 'up': {'x': 270}, 'down': {'x': 90}}
for n, nm in [('steam_vent', 'Steam Vent'), ('blaze_jet', 'Blaze Jet'), ('arc_thruster', 'Arc Thruster'), ('ion_lance', 'Ion Lance'), ('graviton_lens', 'Graviton Lens')]:
    s = tex(n + '_side')
    w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/block', 'textures': {'particle': s, 'side': s, 'nozzle': tex(n + '_nozzle')}, 'elements': [
        cube_el([1, 1, 5], [15, 15, 16], {'*': '#side'}),
        cube_el([2, 2, 2], [14, 14, 5], {'*': '#side', 'north': '#nozzle'}),
        cube_el([3, 3, 0], [13, 13, 2], {'*': '#side', 'north': '#nozzle'})]})
    w(f'{A}/blockstates/{n}.json', {'variants': {f'facing={f}': dict({'model': f'{NS}:block/{n}'}, **r) for f, r in D6.items()}})
    blocks.append(n); NAMES[n] = nm
w(f'{A}/models/block/sky_sail.json', {'parent': 'minecraft:block/block', 'textures': {'all': tex('sky_sail'), 'particle': tex('sky_sail')}, 'elements': [cube_el([0, 0, 0], [16, 3, 16], {'*': '#all'})]})
w(f'{A}/blockstates/sky_sail.json', {'variants': {'': {'model': f'{NS}:block/sky_sail'}}})
blocks.append('sky_sail'); NAMES['sky_sail'] = 'Sky Sail'

for b in blocks:
    if '_joint' not in b: item_from_block(b)
ITEMS = {'colossteel_blend': 'Colossteel Blend', 'colossteel_ingot': 'Colossteel Ingot', 'reactor_alloy': 'Reactor Alloy',
         'neutronium_ingot': 'Neutronium Ingot', 'enriched_fuel_cell': 'Enriched Fuel Cell', 'deuterium_cell': 'Deuterium Cell'}
for i in ITEMS: w(f'{A}/models/item/{i}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{i}'}})

# --- lang ---
lang = {f'block.{NS}.{b}': NAMES[b] for b in blocks}
lang.update({f'item.{NS}.{i}': n for i, n in ITEMS.items()})
lang.update({
    'itemGroup.colossalcraft.command': 'ColossalCraft: Command', 'itemGroup.colossalcraft.joints': 'ColossalCraft: Joints & Motors',
    'itemGroup.colossalcraft.power': 'ColossalCraft: Power', 'itemGroup.colossalcraft.propulsion': 'ColossalCraft: Propulsion & Gyros',
    'itemGroup.colossalcraft.materials': 'ColossalCraft: Materials', 'entity.colossalcraft.colossus': 'Colossus', 'entity.colossalcraft.seat': 'Seat',
    'key.colossalcraft.screen': 'Colossus: Open Hull Core screen',
    'key.categories.colossalcraft': 'ColossalCraft', 'key.colossalcraft.pilot': 'Enter / Leave Pilot Throne',
    'key.colossalcraft.descend': 'Colossus: Go Down', 'key.colossalcraft.hover': 'Colossus: Hover Assist', 'key.colossalcraft.disassemble': 'Colossus: Park (back to blocks)',
    'death.attack.colossalcraft.crushed': '%1$s was crushed by a Colossus', 'death.attack.colossalcraft.crushed.player': '%1$s was crushed by a Colossus',
})
w(f'{A}/lang/en_us.json', lang)

# --- loot tables ---
for b in blocks:
    w(f'{D}/loot_table/blocks/{b}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{b}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

# --- tags ---
w(f'{R}/data/minecraft/tags/block/mineable/pickaxe.json', {'values': [f'{NS}:{b}' for b in blocks if b not in ('sky_sail', 'gantry_scaffold')]})
w(f'{D}/tags/block/assembly_blacklist.json', {'values': ['minecraft:scaffolding', f'{NS}:gantry_scaffold']})
w(f'{D}/damage_type/crushed.json', {'message_id': 'colossalcraft.crushed', 'scaling': 'never', 'exhaustion': 0.1})
w(f'{D}/colossal_density/defaults.json', {'values': {'minecraft:iron_block': 7870, 'minecraft:gold_block': 19300, 'minecraft:diamond_block': 3500, '#minecraft:logs': 650}})

# --- recipes ---
def I(x): return {'tag': x[1:]} if x.startswith('#') else {'item': x if ':' in x else f'{NS}:{x}'}
def shaped(out, pat, key, n=1):
    w(f'{D}/recipe/{out}.json', {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pat, 'key': {k: I(v) for k, v in key.items()}, 'result': {'id': f'{NS}:{out}', 'count': n}})
def shapeless(out, ings, n=1):
    w(f'{D}/recipe/{out}.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': [I(i) for i in ings], 'result': {'id': f'{NS}:{out}', 'count': n}})
S = 'colossteel_ingot'; AL = 'reactor_alloy'; NE = 'neutronium_ingot'
shapeless('colossteel_blend', ['minecraft:iron_ingot', '#minecraft:coals', '#minecraft:coals'])
for kind, t in (('blasting', 100), ('smelting', 200)):
    w(f'{D}/recipe/colossteel_ingot_from_{kind}.json', {'type': f'minecraft:{kind}', 'category': 'misc', 'ingredient': I('colossteel_blend'), 'result': {'id': f'{NS}:{S}'}, 'experience': 0.5, 'cookingtime': t})
shapeless(AL, [S, 'minecraft:netherite_scrap', 'minecraft:quartz', 'minecraft:quartz'], 2)
shaped(NE, [' E ', 'ACA', ' E '], {'E': 'minecraft:echo_shard', 'A': AL, 'C': 'minecraft:crying_obsidian'}, 2)
shaped('enriched_fuel_cell', ['GRG', 'AFA', 'GRG'], {'G': 'minecraft:glowstone_dust', 'R': 'minecraft:redstone', 'A': AL, 'F': 'minecraft:glowstone'}, 2)
shaped('deuterium_cell', ['PAP', 'AWA', 'PAP'], {'P': 'minecraft:prismarine_crystals', 'A': AL, 'W': 'minecraft:water_bucket'}, 2)
shaped('hull_core', ['CRC', 'RBR', 'CRC'], {'C': S, 'R': 'minecraft:redstone', 'B': 'minecraft:redstone_block'})
shaped('pilot_seat', ['W  ', 'WWW', 'CLC'], {'W': '#minecraft:wool', 'C': S, 'L': 'minecraft:lever'})
MM = {'wood': '#minecraft:planks', 'stone': '#minecraft:stone_crafting_materials', 'iron': 'minecraft:iron_ingot', 'gold': 'minecraft:gold_ingot', 'diamond': 'minecraft:diamond'}
for m, mat in MM.items():
    shaped(f'{m}_joint', ['MCM', 'C C', 'MCM'], {'M': mat, 'C': S}, 4)
    shaped(f'{m}_motor', ['CMC', 'MRM', 'CPC'], {'M': mat, 'C': S, 'R': 'minecraft:redstone', 'P': 'minecraft:copper_ingot'})
for m in MATS:
    J = f'{m}_joint'
    shaped(f'{m}_joint_slab', ['JJJ'], {'J': J}, 6)
    shaped(f'{m}_joint_panel', ['J', 'J', 'J'], {'J': J}, 6)
    shapeless(f'{m}_joint_pin', [J], 4)
shaped('gantry_scaffold', ['SNS', ' S ', 'SNS'], {'S': 'minecraft:stick', 'N': 'minecraft:iron_nugget'}, 12)
shaped('ember_boiler', ['III', 'IFI', 'IBI'], {'I': 'minecraft:iron_ingot', 'F': 'minecraft:furnace', 'B': 'minecraft:bucket'})
shaped('blaze_combustor', ['SBS', 'BEB', 'SBS'], {'S': S, 'B': 'minecraft:blaze_rod', 'E': 'ember_boiler'})
shaped('redstone_dynamo', ['SRS', 'RBR', 'SCS'], {'S': S, 'R': 'minecraft:redstone_block', 'B': 'blaze_combustor', 'C': 'minecraft:copper_block'})
shaped('void_casing', ['SOS', 'OPO', 'SOS'], {'S': S, 'O': 'minecraft:obsidian', 'P': 'minecraft:ender_pearl'}, 4)
shaped('ender_coil', ['SES', 'ECE', 'SES'], {'S': S, 'E': 'minecraft:ender_eye', 'C': 'minecraft:copper_block'})
shaped('ion_drive', ['SES', 'VDV', 'SES'], {'S': S, 'E': 'minecraft:ender_eye', 'V': 'void_casing', 'D': 'redstone_dynamo'})
shaped('leadlined_casing', ['AOA', 'SIS', 'AOA'], {'A': AL, 'O': 'minecraft:obsidian', 'S': S, 'I': 'minecraft:iron_block'}, 8)
shaped('fuel_rod_assembly', ['AGA', 'AFA', 'AGA'], {'A': AL, 'G': 'minecraft:glowstone', 'F': 'enriched_fuel_cell'}, 2)
shaped('fission_reactor', ['ADA', 'LIL', 'ADA'], {'A': AL, 'D': 'minecraft:diamond_block', 'L': 'leadlined_casing', 'I': 'ion_drive'})
shaped('containment_casing', ['ACA', 'CSC', 'ACA'], {'A': AL, 'C': 'minecraft:copper_block', 'S': S}, 8)
shaped('plasma_coil', ['ABA', 'EDE', 'ABA'], {'A': AL, 'B': 'minecraft:blaze_rod', 'E': 'ender_coil', 'D': 'deuterium_cell'}, 2)
shaped('fusion_torus', ['ANA', 'PFP', 'ANA'], {'A': AL, 'N': 'minecraft:nether_star', 'P': 'plasma_coil', 'F': 'fission_reactor'})
shaped('singularity_casing', ['NCN', 'C C', 'NCN'], {'N': NE, 'C': 'minecraft:crying_obsidian'}, 8)
shaped('vacuum_lattice', ['NGN', 'GEG', 'NGN'], {'N': NE, 'G': 'minecraft:glass', 'E': 'minecraft:end_rod'}, 4)
shaped('null_singularity', ['DND', 'NSN', 'DND'], {'D': 'minecraft:dragon_breath', 'N': NE, 'S': 'minecraft:nether_star'})
shaped('zero_point_core', ['DHD', 'NFN', 'DND'], {'D': 'minecraft:dragon_breath', 'H': 'minecraft:dragon_head', 'N': NE, 'F': 'fusion_torus'})
shaped('small_gyro', ['SIS', 'ICI', 'SIS'], {'S': S, 'I': 'minecraft:iron_ingot', 'C': 'minecraft:compass'})
shaped('gyro_casing', ['SIS', 'I I', 'SIS'], {'S': S, 'I': 'minecraft:iron_ingot'}, 8)
shaped('gyro_rotor', ['SGS', 'GMG', 'SGS'], {'S': S, 'G': 'small_gyro', 'M': 'iron_motor'}, 2)
shaped('gyro_array', ['SRS', 'RGR', 'SRS'], {'S': S, 'R': 'gyro_rotor', 'G': 'small_gyro'})
shaped('grand_gyroscope', ['DRD', 'RAR', 'DRD'], {'D': 'minecraft:diamond_block', 'R': 'gyro_rotor', 'A': 'gyro_array'})
shaped('steam_vent', ['SBS', 'S S', 'SFS'], {'S': S, 'B': 'minecraft:iron_bars', 'F': 'minecraft:furnace'}, 2)
shaped('blaze_jet', ['SBS', 'BVB', 'SBS'], {'S': S, 'B': 'minecraft:blaze_rod', 'V': 'steam_vent'})
shaped('arc_thruster', ['SLS', 'RJR', 'SLS'], {'S': S, 'L': 'minecraft:lightning_rod', 'R': 'minecraft:redstone_block', 'J': 'blaze_jet'})
shaped('ion_lance', ['SES', 'EAE', 'SVS'], {'S': S, 'E': 'ender_coil', 'A': 'arc_thruster', 'V': 'void_casing'})
shaped('graviton_lens', ['NLN', 'LIL', 'NLN'], {'N': NE, 'L': 'vacuum_lattice', 'I': 'ion_lance'})
shaped('sky_sail', ['WSW', 'WWW', 'WSW'], {'W': '#minecraft:wool', 'S': 'minecraft:stick'}, 4)
print('data done:', len(blocks), 'blocks')

# --- compat blocks ---
cube_all('flux_accumulator', name='Flux Accumulator'); item_from_block('flux_accumulator')
n = 'kinetic_dynamo'
w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/cube_column', 'textures': {'end': tex(n + '_end'), 'side': tex(n + '_side')}})
w(f'{A}/blockstates/{n}.json', {'variants': {'axis=y': {'model': f'{NS}:block/{n}'}, 'axis=z': {'model': f'{NS}:block/{n}', 'x': 90}, 'axis=x': {'model': f'{NS}:block/{n}', 'x': 90, 'y': 90}}})
item_from_block(n); blocks.append(n); NAMES[n] = 'Kinetic Dynamo (Create)'
lang = json.load(open(f'{A}/lang/en_us.json'))
lang[f'block.{NS}.flux_accumulator'] = 'Flux Accumulator'; lang[f'block.{NS}.kinetic_dynamo'] = 'Kinetic Dynamo'
w(f'{A}/lang/en_us.json', lang)
for b in ('flux_accumulator', n):
    w(f'{D}/loot_table/blocks/{b}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{b}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
w(f'{R}/data/minecraft/tags/block/mineable/pickaxe.json', {'replace': False, 'values': [f'{NS}:{b}' for b in blocks if b not in ('sky_sail', 'kinetic_dynamo', 'gantry_scaffold')] + [{'id': f'{NS}:kinetic_dynamo', 'required': False}]})
shaped('flux_accumulator', ['SRS', 'CBC', 'SRS'], {'S': S, 'R': 'minecraft:redstone_block', 'C': 'minecraft:copper_block', 'B': 'redstone_dynamo'})
w(f'{D}/recipe/kinetic_dynamo.json', {'neoforge:conditions': [{'type': 'neoforge:mod_loaded', 'modid': 'create'}],
    'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': ['ACA', 'HRH', 'ACA'],
    'key': {'A': {'item': 'create:andesite_alloy'}, 'C': {'item': 'create:andesite_casing'}, 'H': {'item': 'create:shaft'}, 'R': {'item': f'{NS}:redstone_dynamo'}},
    'result': {'id': f'{NS}:kinetic_dynamo', 'count': 1}})
print('compat data done')
