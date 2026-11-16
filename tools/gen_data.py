# Generates blockstates, models, lang, loot tables, recipes and tags.
import json, os
R = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources')
NS = 'colossalcraft'
A = f'{R}/assets/{NS}'; D = f'{R}/data/{NS}'
def w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f: json.dump(obj, f, indent=2)
def tex(n): return f'{NS}:block/{n}'
MATS = ['wood', 'stone', 'copper', 'iron', 'gold', 'diamond', 'netherite']
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
JN = {'wood': 'Timber', 'stone': 'Cobble', 'copper': 'Copper', 'iron': 'Iron', 'gold': 'Gilded', 'diamond': 'Diamond', 'netherite': 'Netherite'}
AX = {'x': (0, 1, 2), 'y': (1, 0, 2), 'z': (2, 0, 1)}  # axis index, u index, v index
def box(lo, hi, axis_i, spin=False):
    faces = {}
    for f, ax in (('north', 2), ('south', 2), ('east', 0), ('west', 0), ('up', 1), ('down', 1)):
        faces[f] = {'texture': '#end' if ax == axis_i else '#side', 'uv': fit_uv(f, lo, hi)}
        if spin: faces[f]['tintindex'] = 9   # marks parts that turn on a moving Colossus
    return {'from': lo, 'to': hi, 'faces': faces}
def make(a, u, v, ra, ru, rv):
    lo = [0, 0, 0]; hi = [0, 0, 0]
    lo[a], hi[a] = ra; lo[u], hi[u] = ru; lo[v], hi[v] = rv
    return lo, hi
def joint_elements(shape, axis, xyz=None):
    """xyz = [(x0,x1),(y0,y1),(z0,z1)] outer box; defaults by shape."""
    a, u, v = AX[axis]
    if xyz is None:
        xyz = {'joint': [(0, 16), (0, 16), (0, 16)], 'joint_slab': [(0, 16), (0, 8), (0, 16)]}.get(shape)
        if shape == 'joint_pin':
            xyz = [(5, 11), (5, 11), (5, 11)]; xyz[a] = (0, 16)
    (a0, a1), (u0, u1), (v0, v1) = xyz[a], xyz[u], xyz[v]
    els = []
    if shape == 'joint_pin':
        els.append(box(*make(a, u, v, (a0, a1), (u0, u1), (v0, v1)), a))
        for c0, c1 in ((1, 3), (13, 15)): els.append(box(*make(a, u, v, (c0, c1), (u0 - 1, u1 + 1), (v0 - 1, v1 + 1)), a, spin=True))
        return els
    t = 2 if min(u1 - u0, v1 - v0, a1 - a0) <= 8 else 3
    for ru, rv in (((u0, u1), (v0, v0 + t)), ((u0, u1), (v1 - t, v1)), ((u0, u0 + t), (v0 + t, v1 - t)), ((u1 - t, u1), (v0 + t, v1 - t))):
        els.append(box(*make(a, u, v, (a0, a1), ru, rv), a, spin=True))   # outer housing: turns with the joint
    cu, cv = (u0 + u1) / 2, (v0 + v1) / 2
    hs = max(0.5, min(2, (u1 - u0 - 2 * t) / 2 - 1, (v1 - v0 - 2 * t) / 2 - 1))
    ext = 0 if shape == 'joint' else 0.5
    els.append(box(*make(a, u, v, (max(0, a0 - ext), min(16, a1 + ext)), (cu - hs, cu + hs), (cv - hs, cv + hs)), a))  # inner shaft: fixed to the parent
    return els
PANEL_BOX = {'north': [(0, 16), (0, 16), (0, 8)], 'south': [(0, 16), (0, 16), (8, 16)], 'west': [(0, 8), (0, 16), (0, 16)], 'east': [(8, 16), (0, 16), (0, 16)]}
SHAPES = [('joint', 'Bearing Joint'), ('joint_slab', 'Slab Joint'), ('joint_panel', 'Panel Joint'), ('joint_pin', 'Pin Joint'), ('ball_joint', 'Ball Joint')]
def ball_els():
    def b(lo, hi, ax=None, ang=0, t='#side', tint=11):
        el = {'from': lo, 'to': hi, 'faces': {f: {'texture': t, 'uv': fit_uv(f, lo, hi), 'tintindex': tint} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}
        if ang: el['rotation'] = {'origin': [8, 8, 8], 'axis': ax, 'angle': ang}
        return el
    return [b([4, 4, 4], [12, 12, 12], t='#end'), b([3.5, 4.5, 4.5], [12.5, 11.5, 11.5], 'y', 45, '#end'), b([4.5, 3.5, 4.5], [11.5, 12.5, 11.5], 'x', 45, '#end'),
            b([2, 0, 2], [14, 3, 14], tint=9), b([2, 13, 2], [14, 16, 14], tint=10), b([6, 3, 6], [10, 4, 10], tint=9), b([6, 12, 6], [10, 13, 10], tint=10)]
    # tint 9 = bottom base, 10 = top base (each stays flat on the part it touches), 11 = the ball (turns halfway)
for m in MATS:
    for sh, title in SHAPES:
        n = f'{m}_{sh}'
        tx = {'end': tex(f'{m}_joint_end'), 'side': tex(f'{m}_joint_side'), 'particle': tex(f'{m}_joint_side')}
        def model(name, els): w(f'{A}/models/block/{name}.json', {'parent': 'minecraft:block/block', 'textures': tx, 'elements': els})
        variants = {}
        if sh == 'joint_slab':
            for top in (False, True):
                for axis in 'xyz':
                    mn = f'{n}_{"top_" if top else ""}{axis}'
                    model(mn, joint_elements(sh, axis, [(0, 16), (8, 16) if top else (0, 8), (0, 16)]))
                    variants[f'axis={axis},top={"true" if top else "false"}'] = {'model': f'{NS}:block/{mn}'}
            item = f'{n}_x'
        elif sh == 'joint_panel':
            for side, xyz in PANEL_BOX.items():
                for axis in 'xyz':
                    mn = f'{n}_{side}_{axis}'
                    model(mn, joint_elements(sh, axis, xyz))
                    variants[f'axis={axis},facing={side}'] = {'model': f'{NS}:block/{mn}'}
            item = f'{n}_north_z'
        elif sh == 'ball_joint':
            model(f'{n}_x', ball_els())
            for axis in 'xyz': variants[f'axis={axis}'] = {'model': f'{NS}:block/{n}_x'}
            item = f'{n}_x'
        else:
            for axis in 'xyz':
                model(f'{n}_{axis}', joint_elements(sh, axis))
                variants[f'axis={axis}'] = {'model': f'{NS}:block/{n}_{axis}'}
            item = f'{n}_x'
        w(f'{A}/blockstates/{n}.json', {'variants': variants})
        blocks.append(n); NAMES[n] = f'{JN[m]} {title}'
        w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{item}'})
def axis_box(axis, ra, ru, rv, tx, spin=False):
    a, u, v = AX[axis]
    lo, hi = make(a, u, v, ra, ru, rv)
    faces = {}
    for f in ('north', 'south', 'east', 'west', 'up', 'down'):
        faces[f] = {'texture': tx, 'uv': fit_uv(f, lo, hi)}
        if spin: faces[f]['tintindex'] = 9
    return {'from': lo, 'to': hi, 'faces': faces}
def pillar_block(n, name, elements_for_axis, textures):
    for axis in 'xyz':
        w(f'{A}/models/block/{n}_{axis}.json', {'parent': 'minecraft:block/block', 'textures': textures, 'elements': elements_for_axis(axis)})
    w(f'{A}/blockstates/{n}.json', {'variants': {f'axis={ax}': {'model': f'{NS}:block/{n}_{ax}'} for ax in 'xyz'}})
    w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}_y'})
    blocks.append(n); NAMES[n] = name
def motor_els(axis):
    return [axis_box(axis, (2, 14), (1, 15), (1, 15), '#body'),                      # housing
            axis_box(axis, (0, 16), (6, 10), (6, 10), '#shaft', spin=True),           # shaft, sticks out both ends
            axis_box(axis, (0, 1), (4, 12), (4, 12), '#shaft', spin=True), axis_box(axis, (15, 16), (4, 12), (4, 12), '#shaft', spin=True)]
for m in MATS:
    pillar_block(f'{m}_motor', f'{JN[m]} Servo Motor', motor_els, {'body': tex(f'{m}_motor'), 'shaft': tex('shaft'), 'particle': tex(f'{m}_motor')})
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
    if '_joint' not in b and not b.endswith('_motor'): item_from_block(b)
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
MM = {'wood': '#minecraft:planks', 'stone': '#minecraft:stone_crafting_materials', 'copper': 'minecraft:copper_ingot', 'iron': 'minecraft:iron_ingot', 'gold': 'minecraft:gold_ingot', 'diamond': 'minecraft:diamond', 'netherite': 'minecraft:netherite_ingot'}
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

# --- gearboxes + natural ground tag ---
AXL = {'x': 'x', 'y': 'y', 'z': 'z'}
def to_xyz(axis, a, u, v):
    ai, ui, vi = AX[axis]; p = [0, 0, 0]; p[ai] = a; p[ui] = u; p[vi] = v; return p
def rot_el(axis, ra, ru, rv, tx, tint, origin_uv, angle, rot_axis=None):
    el = axis_box(axis, ra, ru, rv, tx)
    for f in el['faces'].values():
        if tint is not None: f['tintindex'] = tint
    if angle:
        ra_letter = rot_axis or axis
        o = to_xyz(axis, (ra[0] + ra[1]) / 2, origin_uv[0], origin_uv[1])
        el['rotation'] = {'origin': o, 'axis': ra_letter, 'angle': angle}
    return el
def gear(axis, cu, cv, a0, a1, R, tx, tint):
    """Round toothed gear in the plane across the axis: an octagon hub and tooth bars at several angles."""
    els = []
    h = R * 0.78
    for ang in (0, 45):
        els.append(rot_el(axis, (a0, a1), (cu - h, cu + h), (cv - h, cv + h), tx, tint, (cu, cv), ang))
    angles = (0, 45) if R <= 2.6 else (0, 22.5, 45, -22.5)
    tw = 0.8 if R <= 2.6 else 1.1
    for ang in angles:
        els.append(rot_el(axis, (a0 + 0.5, a1 - 0.5), (cu - R, cu + R), (cv - tw, cv + tw), tx, tint, (cu, cv), ang))
        els.append(rot_el(axis, (a0 + 0.5, a1 - 0.5), (cu - tw, cu + tw), (cv - R, cv + R), tx, tint, (cu, cv), ang))
    return els
def shaft(axis, cu, cv, tint, r=0.8):
    return [rot_el(axis, (0, 16), (cu - r, cu + r), (cv - r, cv + r), '#shaft', tint, (cu, cv), 0)]
def frame_els(axis):
    els = []
    for (ra, ru, rv) in (((0, 16), (0, 2), (0, 2)), ((0, 16), (14, 16), (0, 2)), ((0, 16), (0, 2), (14, 16)), ((0, 16), (14, 16), (14, 16)),
                         ((0, 2), (2, 14), (0, 2)), ((0, 2), (2, 14), (14, 16)), ((14, 16), (2, 14), (0, 2)), ((14, 16), (2, 14), (14, 16)),
                         ((0, 2), (0, 2), (2, 14)), ((0, 2), (14, 16), (2, 14)), ((14, 16), (0, 2), (2, 14)), ((14, 16), (14, 16), (2, 14))):
        els.append(axis_box(axis, ra, ru, rv, '#frame'))
    return els
# input gear = tintindex 9 (turns with the motor side), output gear = tintindex 10 (turns with the joint side). Centres must match GearboxBlock in Java.
def spur_els(axis):        # 2:1 small pinion drives a gear twice its size
    return frame_els(axis) + gear(axis, 4.5, 8, 6, 10, 2, '#gear', 9) + shaft(axis, 4.5, 8, 9) + gear(axis, 10.5, 8, 6, 10, 4, '#gear', 10) + shaft(axis, 10.5, 8, 10)
def overdrive_els(axis):   # 1:2 big gear drives a small one
    return frame_els(axis) + gear(axis, 5.5, 8, 6, 10, 4, '#gear', 9) + shaft(axis, 5.5, 8, 9) + gear(axis, 11.5, 8, 6, 10, 2, '#gear', 10) + shaft(axis, 11.5, 8, 10)
def planetary_els(axis):   # 5:1 sun (input), three planets orbiting on the carrier (output), fixed ring
    els = frame_els(axis) + gear(axis, 8, 8, 6, 10, 2, '#gear', 9) + shaft(axis, 8, 8, 9)
    import math
    for k in range(3):
        an = 2 * math.pi * k / 3
        els += gear(axis, 8 + 4.2 * math.cos(an), 8 + 4.2 * math.sin(an), 6.5, 9.5, 1.9, '#gear', 10)
    for ang in (0, 45):  # ring
        for (ru, rv) in (((2.5, 13.5), (2.5, 3.3)), ((2.5, 13.5), (12.7, 13.5)), ((2.5, 3.3), (3.3, 12.7)), ((12.7, 13.5), (3.3, 12.7))):
            els.append(rot_el(axis, (6, 10), ru, rv, '#frame', None, (8, 8), ang))
    return els
def worm_els(axis):        # 20:1 a spiral worm (turns across the block) drives a toothed wheel
    ai, ui, vi = AX[axis]; uletter = 'xyz'[ui]; vletter = 'xyz'[vi]
    els = frame_els(axis)
    els.append(rot_el(axis, (6.5, 9.5), (2, 14), (2, 5), '#gear', 9, (8, 3.5), 0))            # worm core
    for u0 in (3, 5, 7, 9, 11, 13):                                                              # helix threads
        els.append(rot_el(axis, (5.5, 10.5), (u0 - 0.5, u0 + 0.5), (1.5, 5.5), '#gear', 9, (u0, 3.5), 22.5, vletter))
    els.append(rot_el(axis, (7.3, 8.7), (0, 16), (2.8, 4.2), '#shaft', 9, (8, 3.5), 0))           # worm shaft (along u)
    els += gear(axis, 8, 10.5, 6, 10, 4.5, '#gear', 10) + shaft(axis, 8, 10.5, 10)
    return els
GEAR_ELS = {'overdrive_gearbox': overdrive_els, 'spur_gearbox': spur_els, 'planetary_gearbox': planetary_els, 'worm_drive': worm_els}
def gearbox_model(n):
    # north = output side (arrow points there). Input shaft (tintindex 9) at the back, output shaft (tintindex 10) at the front, both centred.
    def face(texv, rot=0, tint=None):
        f = {'texture': texv}
        if rot: f['rotation'] = rot
        if tint is not None: f['tintindex'] = tint
        return f
    body = {'from': [1, 1, 1], 'to': [15, 15, 15], 'faces': {'north': face('#out'), 'south': face('#in'), 'up': face('#arrow'), 'down': face('#arrow', 180),
            'east': face('#arrow', 90), 'west': face('#arrow', 270)}}
    def shaft_el(z0, z1, lo, hi, tint):
        el = {'from': [lo, lo, z0], 'to': [hi, hi, z1], 'faces': {}}
        for f in ('north', 'south', 'east', 'west', 'up', 'down'): el['faces'][f] = face('#shaft', 0, tint) | {'uv': fit_uv(f, [lo, lo, z0], [hi, hi, z1])}
        return el
    els = [body, shaft_el(0, 8, 6, 10, 10), shaft_el(0, 1, 4, 12, 10), shaft_el(8, 16, 6, 10, 9), shaft_el(15, 16, 4, 12, 9)]
    w(f'{A}/models/block/{n}.json', {'parent': 'minecraft:block/block', 'textures': {'out': tex(n), 'in': tex('gearbox_in'), 'arrow': tex(n + '_arrow'), 'shaft': tex('shaft'), 'particle': tex(n)}, 'elements': els})
    w(f'{A}/blockstates/{n}.json', {'variants': {f'facing={f}': dict({'model': f'{NS}:block/{n}'}, **r) for f, r in D6.items()}})
    w(f'{A}/models/item/{n}.json', {'parent': f'{NS}:block/{n}'})
for n, nm in [('overdrive_gearbox', 'Overdrive Gearbox (1:2, faster)'), ('spur_gearbox', 'Spur Gearbox (2:1)'), ('planetary_gearbox', 'Planetary Gearbox (5:1)'), ('worm_drive', 'Worm Drive (20:1)')]:
    gearbox_model(n); blocks.append(n); NAMES[n] = nm
    w(f'{D}/loot_table/blocks/{n}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{n}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
lang = json.load(open(f'{A}/lang/en_us.json'))
for b in blocks: lang[f'block.{NS}.{b}'] = NAMES[b]
lang[f'block.{NS}.flux_accumulator'] = 'Flux Accumulator'; lang[f'block.{NS}.kinetic_dynamo'] = 'Kinetic Dynamo'
w(f'{A}/lang/en_us.json', lang)
w(f'{R}/data/minecraft/tags/block/mineable/pickaxe.json', {'replace': False, 'values': [f'{NS}:{b}' for b in blocks if b not in ('sky_sail', 'kinetic_dynamo', 'gantry_scaffold')] + [{'id': f'{NS}:kinetic_dynamo', 'required': False}]})
shaped('spur_gearbox', ['SIS', 'ICI', 'SIS'], {'S': S, 'I': 'minecraft:iron_ingot', 'C': 'minecraft:copper_ingot'}, 2)
shaped('overdrive_gearbox', ['SGS', 'GBG', 'SGS'], {'S': S, 'G': 'minecraft:gold_ingot', 'B': 'spur_gearbox'})
shaped('planetary_gearbox', ['SBS', 'BDB', 'SBS'], {'S': S, 'B': 'spur_gearbox', 'D': 'minecraft:diamond'})
shaped('worm_drive', ['SBS', 'RPR', 'SBS'], {'S': S, 'B': 'minecraft:iron_block', 'R': 'minecraft:redstone', 'P': 'planetary_gearbox'})
# rotor blade (per axis) and gantry hoist
def blade_els(axis): return [axis_box(axis, (0, 16), (6, 10), (3, 13), '#all')]
pillar_block('rotor_blade', 'Rotor Blade', blade_els, {'all': tex('rotor_blade'), 'particle': tex('rotor_blade')})
w(f'{A}/models/block/gantry_hoist.json', {'parent': 'minecraft:block/block', 'textures': {'beam': tex('gantry_scaffold'), 'hook': tex('gantry_hoist'), 'particle': tex('gantry_hoist')},
    'elements': [cube_el([0, 12, 0], [16, 16, 16], {'*': '#beam'}), cube_el([5, 4, 5], [11, 12, 11], {'*': '#hook'}), cube_el([6, 1, 7], [10, 4, 9], {'*': '#hook'})]})
w(f'{A}/models/block/gantry_hoist_on.json', {'parent': f'{NS}:block/gantry_hoist', 'textures': {'hook': tex('gantry_hoist_on')}})
w(f'{A}/blockstates/gantry_hoist.json', {'variants': {'engaged=false': {'model': f'{NS}:block/gantry_hoist'}, 'engaged=true': {'model': f'{NS}:block/gantry_hoist_on'}}})
item_from_block('gantry_hoist'); blocks.append('gantry_hoist'); NAMES['gantry_hoist'] = 'Gantry Hoist'
for b in ('rotor_blade', 'gantry_hoist'):
    w(f'{D}/loot_table/blocks/{b}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{b}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
lang = json.load(open(f'{A}/lang/en_us.json'))
for b in blocks: lang[f'block.{NS}.{b}'] = NAMES[b]
w(f'{A}/lang/en_us.json', lang)
w(f'{R}/data/minecraft/tags/block/mineable/pickaxe.json', {'replace': False, 'values': [f'{NS}:{b}' for b in blocks if b not in ('sky_sail', 'kinetic_dynamo', 'gantry_scaffold')] + [{'id': f'{NS}:kinetic_dynamo', 'required': False}]})
w(f'{D}/tags/block/assembly_blacklist.json', {'values': ['minecraft:scaffolding', f'{NS}:gantry_scaffold', f'{NS}:gantry_hoist']})
shaped('rotor_blade', ['  S', ' P ', 'S  '], {'S': S, 'P': '#minecraft:planks'}, 4)
shaped('gantry_hoist', ['GGG', ' C ', ' I '], {'G': 'gantry_scaffold', 'C': 'minecraft:chain', 'I': 'minecraft:iron_block'})
# Locomotion Controller + Jump Drive
orientable('locomotion_controller', 'locomotion_controller_front', 'locomotion_controller_side', 'locomotion_controller_side', 'Locomotion Controller')
item_from_block('locomotion_controller')
w(f'{A}/models/block/jump_drive.json', {'parent': 'minecraft:block/cube_column', 'textures': {'end': tex('jump_drive_top'), 'side': tex('jump_drive_side')}})
w(f'{A}/blockstates/jump_drive.json', {'variants': {'': {'model': f'{NS}:block/jump_drive'}}})
item_from_block('jump_drive'); blocks.append('jump_drive'); NAMES['jump_drive'] = 'Jump Drive'
orientable('step_controller', 'step_controller_front', 'locomotion_controller_side', 'locomotion_controller_side', 'Step Controller')
item_from_block('step_controller')
for b in ('locomotion_controller', 'jump_drive', 'step_controller') + tuple(f'{m}_ball_joint' for m in MATS):
    w(f'{D}/loot_table/blocks/{b}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{b}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
lang = json.load(open(f'{A}/lang/en_us.json'))
for b in blocks: lang[f'block.{NS}.{b}'] = NAMES[b]
lang['key.colossalcraft.loco'] = 'Colossus: Open Locomotion Controller'; lang['key.colossalcraft.jump'] = 'Colossus: Jump Drive (default)'
w(f'{A}/lang/en_us.json', lang)
w(f'{R}/data/minecraft/tags/block/mineable/pickaxe.json', {'replace': False, 'values': [f'{NS}:{b}' for b in blocks if b not in ('sky_sail', 'kinetic_dynamo', 'gantry_scaffold')] + [{'id': f'{NS}:kinetic_dynamo', 'required': False}]})
shaped('locomotion_controller', ['SCS', 'RMR', 'SRS'], {'S': S, 'C': 'minecraft:comparator', 'R': 'minecraft:redstone', 'M': 'iron_motor'})
shaped('step_controller', ['SOS', 'CEC', 'SRS'], {'S': S, 'O': 'minecraft:observer', 'C': 'minecraft:compass', 'E': 'minecraft:ender_eye', 'R': 'minecraft:redstone'})
shaped('jump_drive', ['SPS', 'RBR', 'SPS'], {'S': S, 'P': 'minecraft:piston', 'R': 'minecraft:redstone_block', 'B': 'minecraft:slime_block'})
for m in MATS: shapeless(f'{m}_ball_joint', [f'{m}_joint', 'minecraft:slime_ball'])
w(f'{D}/tags/block/natural_ground.json', {'values': ['#minecraft:dirt', '#minecraft:sand', '#minecraft:base_stone_overworld', '#minecraft:base_stone_nether',
    'minecraft:gravel', 'minecraft:snow', 'minecraft:snow_block', 'minecraft:powder_snow', 'minecraft:ice', 'minecraft:packed_ice', 'minecraft:blue_ice',
    'minecraft:clay', 'minecraft:end_stone', 'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:fern', 'minecraft:large_fern', 'minecraft:dead_bush',
    'minecraft:sculk', 'minecraft:soul_sand', 'minecraft:soul_soil', 'minecraft:mycelium', 'minecraft:podzol', 'minecraft:mud', 'minecraft:bedrock',
    '#minecraft:flowers']})
print('gear data done')
