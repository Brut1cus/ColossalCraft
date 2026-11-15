# Procedural 16x16 texture generator for ColossalCraft
import random, os, math
from PIL import Image
OUT = os.path.join(os.path.dirname(__file__), '..', 'src/main/resources/assets/colossalcraft/textures')
B = os.path.join(OUT, 'block'); I = os.path.join(OUT, 'item')
os.makedirs(B, exist_ok=True); os.makedirs(I, exist_ok=True)

def hx(c): return ((c >> 16) & 255, (c >> 8) & 255, c & 255)
def shade(c, f): return tuple(max(0, min(255, int(v * f))) for v in c)
def mix(a, b, t): return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))

def base(col, seed, n=0.10):
    r = random.Random(seed); im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            im.putpixel((x, y), shade(col, 1 + r.uniform(-n, n)) + (255,))
    return im
def px(im, x, y, c): im.putpixel((x, y), tuple(c[:3]) + (255,))
def bevel(im, col, w=1):
    for i in range(16):
        for k in range(w):
            px(im, i, k, shade(col, 1.25)); px(im, k, i, shade(col, 1.15))
            px(im, i, 15 - k, shade(col, 0.6)); px(im, 15 - k, i, shade(col, 0.7))
def rivets(im, col, pts=((2, 2), (13, 2), (2, 13), (13, 13))):
    for x, y in pts: px(im, x, y, shade(col, 1.4)); px(im, x + 1, y + 1, shade(col, 0.5))
def rect(im, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1): px(im, x, y, c)
def circle(im, cx, cy, r, c, fill=True):
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + .5 - cx, y + .5 - cy)
            if (fill and d <= r) or (not fill and abs(d - r) < .6): px(im, x, y, c)
def glow(im, cx, cy, r, c):
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + .5 - cx, y + .5 - cy)
            if d < r:
                old = im.getpixel((x, y))[:3]; px(im, x, y, mix(old, c, (1 - d / r) ** 0.8))
def grill(im, col, x0=3, x1=12, y0=3, y1=12, vertical=False):
    for i in range(y0, y1 + 1):
        for j in range(x0, x1 + 1):
            if (j if vertical else i) % 2 == 0: px(im, j, i, shade(col, 0.35))
def save(im, folder, name): im.save(os.path.join(folder, name + '.png'))

STEEL = hx(0x6f7a84)
MATS = {'wood': hx(0xa07a45), 'stone': hx(0x8d8d8d), 'iron': hx(0xe2e2e2), 'gold': hx(0xf5c842), 'diamond': hx(0x4fe3dc)}

# --- Joints (log-like column: side + end) ---
for i, (m, a) in enumerate(MATS.items()):
    s = base(STEEL, 10 + i); bevel(s, STEEL)
    for y in range(16):
        for x in (4, 5, 10, 11): px(s, x, y, mix(shade(a, 0.9), STEEL, 0.25))
    for y in (3, 12): rect(s, 1, y, 14, y, shade(STEEL, 0.5))
    rivets(s, STEEL, ((7, 1), (7, 13)))
    save(s, B, f'{m}_joint_side')
    e = base(STEEL, 20 + i); bevel(e, STEEL)
    circle(e, 8, 8, 6, shade(STEEL, 0.8)); circle(e, 8, 8, 6, shade(a, 0.9), False)
    circle(e, 8, 8, 3.2, shade(a, 1.0)); circle(e, 8, 8, 1.5, shade(STEEL, 0.35))
    save(e, B, f'{m}_joint_end')

# --- Motors ---
for i, (m, a) in enumerate(MATS.items()):
    s = base(STEEL, 40 + i); bevel(s, STEEL); rivets(s, STEEL)
    rect(s, 3, 3, 12, 12, shade(STEEL, 0.75))
    for y in range(4, 12):
        if y % 2 == 0: rect(s, 4, y, 11, y, shade(a, 0.85))
    circle(s, 8, 8, 2.2, shade(STEEL, 1.3)); px(s, 7, 7, shade(STEEL, 0.4))
    save(s, B, f'{m}_motor')

# --- Hull core: screen-like computer face ---
hc = base(hx(0x3a4048), 70); bevel(hc, hx(0x3a4048)); rivets(hc, hx(0x3a4048))
rect(hc, 3, 3, 12, 12, hx(0x0b1a10)); glow(hc, 8, 8, 6, hx(0x2cff7a))
for y in (5, 7, 9): rect(hc, 4, y, 4 + (y * 3) % 7 + 2, y, hx(0x9dffbf))
circle(hc, 8, 8, 1.4, hx(0xe8fff0)); save(hc, B, 'hull_core')

# --- Pilot seat (chair): quilted red cushion + steel frame ---
sc = hx(0x8a1c22)
cu = base(sc, 80, 0.06)
for y in range(16):
    for x in range(16):
        if (x + y) % 5 == 0 or (x - y) % 5 == 0: px(cu, x, y, shade(sc, 0.7))
for x, y in ((2, 2), (7, 2), (12, 2), (4, 7), (9, 7), (14, 7), (2, 12), (7, 12), (12, 12)): px(cu, x, y, hx(0xd8c070))
save(cu, B, 'pilot_seat_front'); save(cu, B, 'pilot_seat_top')
pss = base(STEEL, 82); bevel(pss, STEEL); rivets(pss, STEEL); save(pss, B, 'pilot_seat_side')

# --- Engines (single block): side + front(fire) ---
ENG = {'ember_boiler': (hx(0x5b4a3c), hx(0xff7a1a)), 'blaze_combustor': (hx(0x6b5420), hx(0xffd23a)), 'redstone_dynamo': (hx(0x5a2a2a), hx(0xff2020))}
for i, (n, (c, g)) in enumerate(ENG.items()):
    s = base(c, 100 + i); bevel(s, c); rivets(s, c); grill(s, c, 4, 11, 4, 11, vertical=True); save(s, B, n + '_side')
    f = base(c, 110 + i); bevel(f, c); rect(f, 3, 3, 12, 12, shade(c, 0.3)); glow(f, 8, 9, 6, g); grill(f, c, 3, 12, 3, 5); save(f, B, n + '_front')
    t = base(STEEL, 120 + i); bevel(t, STEEL); circle(t, 8, 8, 4, shade(STEEL, 0.5)); circle(t, 8, 8, 2, g); save(t, B, n + '_top')

# --- Multiblocks: casing / core / controller (off/on) ---
MB = {
 'ion_drive': (hx(0x1d1630), hx(0xb15cff), 'void_casing', 'ender_coil'),
 'fission_reactor': (hx(0x3d4a3a), hx(0x7dff3a), 'leadlined_casing', 'fuel_rod_assembly'),
 'fusion_torus': (hx(0x2b3550), hx(0x3ad7ff), 'containment_casing', 'plasma_coil'),
 'zero_point_core': (hx(0x101014), hx(0xfafaff), 'singularity_casing', 'vacuum_lattice'),
 'gyro_array': (hx(0x5c6670), hx(0xffb43a), 'gyro_casing', 'gyro_rotor'),
}
for i, (n, (c, g, cas, core)) in enumerate(MB.items()):
    k = base(c, 200 + i); bevel(k, c, 2); rivets(k, c, ((3, 3), (11, 3), (3, 11), (11, 11)))
    for j in range(4, 12): px(k, j, j, shade(c, 1.3)); px(k, 15 - j, j, shade(c, 1.3))
    save(k, B, cas)
    o = base(shade(c, 0.6), 210 + i)
    if n == 'gyro_array':
        circle(o, 8, 8, 7, shade(STEEL, 1.1)); circle(o, 8, 8, 5, shade(STEEL, 0.6)); circle(o, 8, 8, 6, g, False)
        rect(o, 7, 1, 8, 14, shade(STEEL, 1.3)); rect(o, 1, 7, 14, 8, shade(STEEL, 1.3))
    elif n == 'fission_reactor':
        for x in (2, 6, 10):
            rect(o, x, 1, x + 2, 14, shade(STEEL, 0.9)); rect(o, x + 1, 2, x + 1, 13, g)
    else:
        for y in range(1, 15, 3): rect(o, 1, y, 14, y + 1, shade(g, 0.55))
        glow(o, 8, 8, 6, g)
    save(o, B, core)
    for on in (False, True):
        f = base(c, 220 + i); bevel(f, c, 2); rect(f, 3, 3, 12, 12, hx(0x08080a))
        circle(f, 8, 8, 4, shade(g, 1.0 if on else 0.25), False)
        if on: glow(f, 8, 8, 5, g)
        rect(f, 3, 13, 12, 13, shade(g, 0.9 if on else 0.2))
        save(f, B, n + ('_front_on' if on else '_front'))
# Zero point centre + grand gyroscope controller (reuse palette)
ns = base(hx(0x000000), 300, 0.0); glow(ns, 8, 8, 8, hx(0x6a3dff)); glow(ns, 8, 8, 4, hx(0xffffff)); save(ns, B, 'null_singularity')
for on in (False, True):
    f = base(hx(0x4a525a), 301); bevel(f, hx(0x4a525a), 2); circle(f, 8, 8, 6, hx(0x202428))
    circle(f, 8, 8, 6, hx(0xffb43a) if on else hx(0x553a10), False); circle(f, 8, 8, 3, hx(0xffd27a) if on else hx(0x553a10), False)
    save(f, B, 'grand_gyroscope_front' + ('_on' if on else ''))

# --- Small gyro ---
sg = base(STEEL, 310); bevel(sg, STEEL); circle(sg, 8, 8, 6, shade(STEEL, 0.5)); circle(sg, 8, 8, 5, hx(0xffb43a), False); rect(sg, 7, 3, 8, 12, shade(STEEL, 1.3)); save(sg, B, 'small_gyro')

# --- Thrusters: side + nozzle ---
TH = {'steam_vent': hx(0xdddddd), 'blaze_jet': hx(0xffa020), 'arc_thruster': hx(0xff3030), 'ion_lance': hx(0xb15cff), 'graviton_lens': hx(0xffffff)}
for i, (n, g) in enumerate(TH.items()):
    body = mix(STEEL, g, 0.15)
    s = base(body, 400 + i); bevel(s, body); rivets(s, body)
    for y in (5, 10): rect(s, 1, y, 14, y, shade(body, 0.55))
    rect(s, 6, 6, 9, 9, shade(g, 0.8)); save(s, B, n + '_side')
    f = base(body, 410 + i); bevel(f, body); circle(f, 8, 8, 7, shade(body, 0.6)); circle(f, 8, 8, 5.5, hx(0x111111)); glow(f, 8, 8, 5, g); circle(f, 8, 8, 7, shade(body, 1.3), False)
    save(f, B, n + '_nozzle')

# --- Sky sail ---
ss = base(hx(0xe8e2d0), 500, 0.05); bevel(ss, hx(0xc8c2b0))
for x in range(16): px(ss, x, 7, hx(0x7a5a30)); px(ss, x, 8, hx(0x6a4a20))
for y in range(16): px(ss, 7, y, hx(0x7a5a30))
save(ss, B, 'sky_sail')

# --- Items ---
def ingot(col, seed, name):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); r = random.Random(seed)
    for y in range(5, 12):
        off = (11 - y) // 2
        for x in range(2 + off, 14 - (6 - off)):
            t = (x - 2) / 12
            px(im, x, y, shade(col, 1.25 - 0.5 * t + r.uniform(-.05, .05)))
    for x in range(4, 10): px(im, x, 5, shade(col, 1.5))
    save(im, I, name)
ingot(hx(0x8a98a6), 1, 'colossteel_ingot'); ingot(hx(0x4a8a5a), 2, 'reactor_alloy'); ingot(hx(0x2a2440), 3, 'neutronium_ingot')
bl = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); r = random.Random(4)
for y in range(16):
    for x in range(16):
        if math.hypot(x - 7.5, (y - 10) * 1.6) < 6.5: px(bl, x, y, mix(hx(0x7a7a7a), hx(0x222222), r.random() * 0.6))
save(bl, I, 'colossteel_blend')
def cell(col, name):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    rect(im, 5, 2, 10, 13, hx(0x707880)); rect(im, 6, 4, 9, 11, col); rect(im, 6, 4, 6, 11, shade(col, 1.4)); rect(im, 6, 1, 9, 2, hx(0xaab0b8)); rect(im, 6, 14, 9, 14, hx(0x40464c))
    save(im, I, name)
cell(hx(0x7dff3a), 'enriched_fuel_cell'); cell(hx(0x3ad7ff), 'deuterium_cell')
print('textures done', len(os.listdir(B)), len(os.listdir(I)))

# --- compat blocks ---
fc = hx(0x2a3550); fa = base(fc, 600); bevel(fa, fc); rivets(fa, fc)
rect(fa, 4, 3, 11, 12, hx(0x10131c))
for y in range(4, 12): rect(fa, 5, y, 10, y, mix(hx(0xff3a3a), hx(0x3aa0ff), (y - 4) / 7))
rect(fa, 6, 1, 9, 2, hx(0xd0d0d0)); save(fa, B, 'flux_accumulator')
an = hx(0xa9a99a)  # andesite-alloy look
ks = base(an, 610); bevel(ks, an); rivets(ks, an)
for x in (4, 11): rect(ks, x, 1, x, 14, shade(an, 0.6))
rect(ks, 6, 5, 9, 10, hx(0xff9a2a)); save(ks, B, 'kinetic_dynamo_side')
ke = base(an, 611); bevel(ke, an); circle(ke, 8, 8, 5, shade(an, 0.55)); circle(ke, 8, 8, 2.5, hx(0x6b4a2a)); save(ke, B, 'kinetic_dynamo_end')
print('compat textures done')

# --- gantry scaffold: hazard-striped steel bar + grated deck ---
gs = Image.new('RGBA', (16, 16))
for y in range(16):
    for x in range(16):
        px(gs, x, y, hx(0xf2c21a) if ((x + y) // 3) % 2 == 0 else hx(0x2a2a2a))
save(gs, B, 'gantry_scaffold')
gd = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
for y in range(16):
    for x in range(16):
        if x % 4 == 0 or y % 4 == 0: px(gd, x, y, hx(0x8a9096))
save(gd, B, 'gantry_scaffold_deck')
print('scaffold textures done')

# --- gearboxes: steel housing with a gear face, accent by type ---
def gear_face(seed, accent, teeth, name):
    im = base(STEEL, seed); bevel(im, STEEL); rivets(im, STEEL)
    for k in range(teeth):
        a = 2 * math.pi * k / teeth
        for r in (5.5, 6.5):
            x = int(round(7.5 + r * math.cos(a))); y = int(round(7.5 + r * math.sin(a)))
            if 0 <= x < 16 and 0 <= y < 16: px(im, x, y, shade(accent, 0.8))
    circle(im, 8, 8, 5, shade(accent, 0.9)); circle(im, 8, 8, 3.5, shade(STEEL, 0.6)); circle(im, 8, 8, 1.6, shade(accent, 1.2))
    save(im, B, name)
gear_face(700, hx(0x46ff7e), 8, 'overdrive_gearbox')
gear_face(701, hx(0xd9d9d9), 10, 'spur_gearbox')
gear_face(702, hx(0x3aa0ff), 14, 'planetary_gearbox')
wd = base(STEEL, 703); bevel(wd, STEEL); rivets(wd, STEEL)
for y in range(3, 13):
    for x in range(3, 13):
        if (x + y) % 3 == 0: px(wd, x, y, hx(0xffb43a))
rect(wd, 7, 2, 8, 13, shade(STEEL, 1.3)); save(wd, B, 'worm_drive')
print('gear textures done')

# --- shaft, gearbox frame, rotor blade, hoist hook ---
sh = Image.new('RGBA', (16, 16))
for y in range(16):
    for x in range(16):
        px(sh, x, y, shade(hx(0xb8c0c8), 1.15 if (x + y) % 4 < 2 else 0.8))
save(sh, B, 'shaft')
gf = base(hx(0x4a525a), 720); bevel(gf, hx(0x4a525a)); save(gf, B, 'gearbox_frame')
rb = base(hx(0x9aa3ab), 721, 0.05)
for x in range(16): px(rb, x, 7, hx(0x6b737b)); px(rb, x, 8, hx(0xc8d0d8))
save(rb, B, 'rotor_blade')
for on in (False, True):
    hk = base(hx(0x3a3a3a), 722); bevel(hk, hx(0x3a3a3a))
    rect(hk, 6, 2, 9, 13, hx(0xf2c21a) if on else hx(0x8a8a8a)); rect(hk, 4, 11, 11, 13, hx(0xf2c21a) if on else hx(0x8a8a8a))
    save(hk, B, 'gantry_hoist_on' if on else 'gantry_hoist')
print('mech textures done')

# --- gear metals (one per gearbox) ---
for name, col in (('overdrive_gearbox_gear', hx(0xe8c34a)), ('spur_gearbox_gear', hx(0xc9a26a)), ('planetary_gearbox_gear', hx(0xb8c4d0)), ('worm_drive_gear', hx(0xd08a52))):
    im = base(col, hash(name) & 1023, 0.08)
    for i in range(16): px(im, i, i, shade(col, 1.25)); px(im, 15 - i, i, shade(col, 0.8))
    save(im, B, name)
print('gear metals done')

# --- gearbox arrows/in face, locomotion controller, jump drive ---
def arrow_tex(name, col):
    im = base(STEEL, hash(name) & 1023); bevel(im, STEEL)
    for y in range(3, 13):
        w2 = max(0, 5 - (y - 3)) if y < 8 else 1
        for x in range(8 - w2, 8 + w2 + (0 if y < 8 else 1)): px(im, x, y, col)
    for y in range(8, 13): px(im, 7, y, col); px(im, 8, y, col)
    save(im, B, name)
for n, col in (('overdrive_gearbox', hx(0x46ff7e)), ('spur_gearbox', hx(0xd9d9d9)), ('planetary_gearbox', hx(0x3aa0ff)), ('worm_drive', hx(0xffb43a))): arrow_tex(n + '_arrow', col)
gi = base(STEEL, 730); bevel(gi, STEEL); circle(gi, 8, 8, 5, shade(STEEL, 0.6)); circle(gi, 8, 8, 5, hx(0xff8a3a), False); save(gi, B, 'gearbox_in')
lf = base(hx(0x2e3640), 731); bevel(lf, hx(0x2e3640)); rect(lf, 2, 2, 13, 11, hx(0x061018))
for (x, y) in ((5, 4), (5, 5), (5, 6), (4, 7), (4, 8), (10, 4), (10, 5), (10, 6), (11, 7), (11, 8), (6, 3), (7, 3), (8, 3), (9, 3)): px(lf, x, y, hx(0x3ad7ff))
rect(lf, 3, 13, 12, 13, hx(0x2cff7a)); save(lf, B, 'locomotion_controller_front')
ls = base(hx(0x4a525a), 732); bevel(ls, hx(0x4a525a)); rivets(ls, hx(0x4a525a)); save(ls, B, 'locomotion_controller_side')
js = base(hx(0x3a2f52), 733); bevel(js, hx(0x3a2f52))
for y in range(2, 14, 3): rect(js, 3, y, 12, y + 1, hx(0xb98cff))
save(js, B, 'jump_drive_side')
jt = base(hx(0x3a2f52), 734); bevel(jt, hx(0x3a2f52)); circle(jt, 8, 8, 5, hx(0x1a1426))
for (x, y) in ((9, 3), (8, 4), (7, 5), (6, 6), (7, 7), (8, 7), (9, 7), (8, 8), (7, 9), (6, 10), (5, 11)): px(jt, x, y, hx(0xffe14a))
save(jt, B, 'jump_drive_top')
print('loco textures done')
