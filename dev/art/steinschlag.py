"""The graphics of Steinschlag, drawn by program: the cave's elements as 16 x 16 background tiles in
three palettes, the miner (sprite), and the font of the status line.

    python3 dev/art/steinschlag.py examples/steinschlag/assets

Every sheet is 128 pixels wide, eight elements of 16 x 16 a row, so that a 16 x 16 tile n is the
8 x 8 tiles n, n + 1, n + 16, n + 17 as the SNES reads them.
"""
import math
import os
import random
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402
from font import FONT_ORDER, glyph  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."
rnd = random.Random(7)

# --- palette A: the cave itself ---------------------------------------------------------------
A = [(8, 6, 12),                                                   # 0 empty cave
     (46, 26, 14), (92, 54, 28), (140, 92, 52), (186, 136, 84),     # 1-4 dirt
     (70, 16, 16), (150, 44, 36), (206, 96, 70),                    # 5-7 brick
     (34, 38, 52), (84, 92, 116), (150, 160, 184), (220, 228, 244), # 8-11 stone and steel
     (20, 22, 30), (255, 255, 255), (0, 0, 0), (0, 0, 0)]           # 12 dark, 13 white

def dirt():
    c = Canvas(16, 16)
    c.rect(0, 0, 15, 15, 2)
    for _ in range(26):
        x, y = rnd.randrange(16), rnd.randrange(16)
        c.set(x, y, rnd.choice([1, 1, 3, 4]))
    return c

def brick():
    c = Canvas(16, 16)
    c.rect(0, 0, 15, 15, 6)
    for row in range(4):
        y = row * 4
        c.rect(0, y + 3, 15, y + 3, 5)                    # mortar
        off = 0 if row % 2 == 0 else 4
        for x in range(off, 16, 8):
            c.rect(x, y, x, y + 2, 5)
        c.rect(0, y, 15, y, 7)                            # the light top edge of each row
        for x in range(off, 16, 8):
            c.set(x, y, 5)
    return c

def steel():
    c = Canvas(16, 16)
    c.rect(0, 0, 15, 15, 9)
    c.rect(0, 0, 15, 0, 11); c.rect(0, 0, 0, 15, 11)
    c.rect(0, 15, 15, 15, 8); c.rect(15, 0, 15, 15, 8)
    c.rect(1, 1, 14, 1, 10); c.rect(1, 1, 1, 14, 10)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):    # rivets
        c.set(x, y, 11); c.set(x + 1, y + 1, 8)
    return c

def boulder():
    c = Canvas(16, 16)
    c.ellipse(8.0, 8.6, 7.2, 6.8, [8, 9, 10, 11])
    for x, y in ((5, 9), (9, 12), (11, 7)):             # cracks
        c.set(x, y, 8)
    c.outline(12)
    return c

def exit_closed():
    c = steel()
    return c

# --- palette B: gems and the exit -------------------------------------------------------------
B = [(0, 0, 0), (10, 40, 70), (20, 110, 160), (50, 190, 230), (150, 240, 255), (255, 255, 255),
     (90, 60, 10), (190, 140, 20), (255, 214, 60), (255, 250, 190),  # 6-9 gold of the exit
     (12, 10, 20)] + [(0, 0, 0)] * 5

def gem(frame):
    """A cut gem; the four frames move a sparkle across it."""
    c = Canvas(16, 16)
    rows = ["......4444......", ".....443334.....", "....44333333....", "...4433333332...",
            "..44333333332...", ".443333333332.2.", "4433333333332..2", "4333333333322...",
            ".33333333332....", "..333333332.....", "...3333332......", "....33332.......",
            ".....332........", "......2.........", "................", "................"]
    c.from_text(rows, {"4": 4, "3": 3, "2": 2})
    for y in range(16):                                   # facets: a darker lower right
        for x in range(16):
            if c.px[y][x] == 3 and x + y > 15:
                c.px[y][x] = 2
    c.outline(1)
    sparkle = [(4, 3), (7, 4), (10, 6), (6, 8)][frame]
    x, y = sparkle
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        c.set(x + dx, y + dy, 5)
    return c

def exit_open(frame):
    c = Canvas(16, 16)
    c.rect(0, 0, 15, 15, 6)
    c.rect(2, 2, 13, 15, 10)                              # the dark doorway
    c.rect(1, 1, 14, 1, 8 if frame else 7); c.rect(1, 1, 1, 15, 8 if frame else 7)
    c.rect(14, 1, 14, 15, 7); c.rect(2, 0, 13, 0, 9 if frame else 8)
    for y in range(4, 15, 3):
        c.set(7, y, 8 if frame else 7)                    # a glow inside
    return c

# --- palette C: beetles and explosions ---------------------------------------------------------
C = [(0, 0, 0), (20, 10, 6), (110, 30, 10), (200, 70, 20), (255, 150, 40), (255, 230, 120),
     (255, 255, 255), (40, 60, 20), (90, 140, 40), (160, 210, 80)] + [(0, 0, 0)] * 6

def beetle(frame):
    """A beetle from above, legs in two steps."""
    c = Canvas(16, 16)
    legs = [(3, 5), (12, 5), (2, 9), (13, 9), (3, 13), (12, 13)] if frame else [(2, 6), (13, 6), (3, 9), (12, 9), (2, 12), (13, 12)]
    for x, y in legs:
        c.set(x, y, 1)
    c.ellipse(8.0, 9.5, 4.6, 5.4, [7, 8, 9])            # the shell
    c.ellipse(8.0, 4.0, 2.6, 2.0, [1, 7])                # the head
    c.rect(8, 6, 8, 14, 7)                                # the line between the wings
    c.set(6, 2, 4); c.set(9, 2, 4)                        # eyes
    c.outline(1)
    return c

def explosion(stage):
    c = Canvas(16, 16)
    r = [4.0, 7.0, 7.5][stage]
    c.ellipse(8.0, 8.0, r, r, [3, 4, 5, 6] if stage < 2 else [2, 3, 4])
    for n in range(10 + stage * 4):
        a = rnd.random() * 2 * math.pi
        d = rnd.random() * (r + 1)
        c.set(int(8 + math.cos(a) * d), int(8 + math.sin(a) * d), rnd.choice([1, 2, 5]))
    return c

def sheet(elements):
    s = Canvas(128, 16 * ((len(elements) + 7) // 8))
    for n, e in enumerate(elements):
        s.blit(e, (n % 8) * 16, (n // 8) * 16)
    return s

# cave_a: 0 empty, 1 dirt, 2 brick, 3 steel, 4 boulder, 5 exit (closed, looks like steel)
save_png(f"{out}/cave_a_gfx.png", sheet([Canvas(16, 16), dirt(), brick(), steel(), boulder(), exit_closed()]), A)
# cave_b: 0..3 gem frames (frame 0 is the tile the map names; the game copies the others over it),
# 4..5 the open exit's frames
save_png(f"{out}/cave_b_gfx.png", sheet([gem(f) for f in range(4)] + [exit_open(f) for f in range(2)]), B)
# cave_c: 0..1 beetle, 2..4 explosion
save_png(f"{out}/cave_c_gfx.png", sheet([beetle(f) for f in range(2)] + [explosion(s) for s in range(3)]), C)

# --- the miner: sprite palette 0 ---------------------------------------------------------------
M = [(0, 0, 0), (20, 14, 10), (230, 180, 40), (255, 220, 90), (255, 255, 200),   # 1 outline, 2-4 helmet
     (240, 180, 140), (200, 130, 100),                                            # 5-6 skin
     (40, 70, 150), (70, 110, 210), (60, 40, 30), (255, 255, 255)] + [(0, 0, 0)] * 5

def miner(frame, front):
    """Facing right (or the front); frame steps the legs."""
    c = Canvas(16, 16)
    c.ellipse(8.0, 4.2, 4.6, 3.6, [2, 3, 4])             # helmet
    c.rect(3, 6, 12, 6, 2)                                # brim
    if not front:
        c.set(12, 3, 10); c.set(13, 3, 10)                # the lamp, shining forwards
    c.ellipse(8.0, 8.2, 3.2, 2.2, [6, 5])                 # face
    if front:
        c.set(6, 8, 1); c.set(9, 8, 1)
    else:
        c.set(10, 8, 1)
    c.rect(5, 10, 10, 13, 8); c.rect(5, 10, 5, 13, 7)    # overalls
    c.rect(4, 10, 4, 12, 5); c.rect(11, 10, 11, 12, 5)   # hands
    legs = [(5, 14), (5, 15), (9, 14), (9, 15)] if frame == 0 else [(6, 14), (6, 15), (10, 14), (10, 15)]
    for x, y in legs:
        c.set(x, y, 9)
    c.outline(1)
    return c

spr = Canvas(128, 16)
for n, (f, front) in enumerate(((0, False), (1, False), (0, True), (1, True))):
    spr.blit(miner(f, front), n * 16, 0)
save_png(f"{out}/miner_gfx.png", spr, M)

# --- the status line: font on layer 2, over a bar of its own colour --------------------------
F = [(0, 0, 0)] + [(0, 0, 0)] * 11 + [(16, 14, 26), (250, 250, 250), (255, 214, 60), (60, 40, 30)]
font = Canvas(128, 48)                                    # 0 empty, 1 the bar, 32.. the font
bar = Canvas(8, 8)
bar.rect(0, 0, 7, 7, 12)
font.blit(bar, 8, 0)
for n, ch in enumerate(FONT_ORDER):
    g = glyph(ch, 13)
    for y in range(8):
        for x in range(8):
            if g.px[y][x] == 0:
                g.px[y][x] = 12                           # the bar behind the letters
    font.blit(g, ((32 + n) % 16) * 8, ((32 + n) // 16) * 8)
save_png(f"{out}/font_gfx.png", font, F)
