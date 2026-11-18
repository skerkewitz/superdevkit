"""The graphics of CatchMe, drawn by program: the maze tiles and the font (background), the mouse and
the cats (sprites). Writes the PNG files of examples/catchme/assets, which are the assets of the
example; edit those in any paint program, or change this and run it again:

    python3 dev/art/catchme.py examples/catchme/assets
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."

# --- background palette 0: maze, crumbs, chili, door, text ----------------------------------
BG = [(14, 12, 34),                                  # 0 backdrop
      (22, 18, 64), (44, 38, 128), (70, 68, 190), (112, 120, 236), (184, 196, 255),   # 1-5 wall
      (196, 132, 28), (255, 212, 84),                # 6-7 crumb
      (140, 18, 30), (226, 48, 48), (255, 156, 120), (64, 176, 64),   # 8-11 chili
      (255, 150, 204),                               # 12 door
      (250, 250, 250), (255, 222, 80), (40, 30, 90)] # 13-15 text white, yellow, shadow

def wall(mask):
    """A wall cell; mask bits: 1 north, 2 east, 4 south, 8 west have a wall next to them."""
    t = Canvas(8, 8)
    t.rect(0, 0, 7, 7, 3)
    for y in range(8):
        for x in range(8):
            if (x + y * 3) % 7 == 0 and 1 < x < 6 and 1 < y < 6:
                t.set(x, y, 2)                       # a little texture in the stone
    if not mask & 1:
        t.rect(0, 0, 7, 0, 1); t.rect(0, 1, 7, 1, 5)
    if not mask & 4:
        t.rect(0, 7, 7, 7, 1); t.rect(0, 6, 7, 6, 2)
    if not mask & 8:
        t.rect(0, 0, 0, 7, 1); t.rect(1, 1, 1, 6, 4)
    if not mask & 2:
        t.rect(7, 0, 7, 7, 1); t.rect(6, 1, 6, 6, 2)
    for (cx, cy, a, b) in ((0, 0, 1, 8), (7, 0, 1, 2), (7, 7, 4, 2), (0, 7, 4, 8)):
        if not mask & a and not mask & b:            # a round outer corner
            t.set(cx, cy, 0)
            t.set(cx + (1 if cx == 0 else -1), cy, 1)
            t.set(cx, cy + (1 if cy == 0 else -1), 1)
    return t

def crumb():
    """The top left quarter of a cell: the crumb sits at the cell's centre (pixels 6..8)."""
    t = Canvas(8, 8)
    t.from_text(["........", "........", "........", "........", "........", "......7.", ".....776", ".....766"],
                {"7": 7, "6": 6})
    return t

def chili(frame):
    """A whole cell (16 x 16): a red pepper with a green stem; frame 1 shines."""
    t = Canvas(16, 16)
    rows = ["..........gg....", ".........g.g....", "........g.......", "......rrrr......", ".....rhhrrr.....",
            "....rhhrrrrd....", "....rhrrrrrd....", "....rrrrrrrd....", ".....rrrrrrd....", ".....rrrrrd.....",
            "......rrrrd.....", "......rrrd......", ".......rrd......", ".......rd.......", "........d.......", "................"]
    t.from_text(rows, {"g": 11, "r": 9, "h": 10, "d": 8})
    if frame:
        t.from_text(["", "", "", "", ".....hh", "....hhh", "....hh"], {"h": 13})
    return t

def door():
    """The top quarters of a door cell: a bar at the cell's middle."""
    t = Canvas(8, 8)
    t.rect(0, 6, 7, 7, 12)
    return t

FONT = {
    "0": ["01110", "10011", "10101", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "D": ["11100", "10010", "10001", "10001", "10001", "10010", "11100"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["01110", "00100", "00100", "00100", "00100", "00100", "01110"],
    "J": ["00111", "00010", "00010", "00010", "00010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "10001", "11001", "10101", "10011", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "10101", "01010"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "!": ["00100", "00100", "00100", "00100", "00100", "00000", "00100"],
    "-": ["00000", "00000", "00000", "01110", "00000", "00000", "00000"],
    ":": ["00000", "00100", "00000", "00000", "00000", "00100", "00000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "00000", "00100"],
}
FONT_ORDER = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:."

def glyph(ch, colour):
    t = Canvas(8, 8)
    rows = FONT.get(ch)
    if rows:
        for y, row in enumerate(rows):
            for x, bit in enumerate(row):
                if bit == "1":
                    t.set(x + 2, y + 1, 15)          # shadow first
        for y, row in enumerate(rows):
            for x, bit in enumerate(row):
                if bit == "1":
                    t.set(x + 1, y, colour)
    return t

bg = Canvas(128, 64 + 16)
# The background sheet, 16 tiles a row: 0 empty, 1..16 walls (mask 0..15), 17 crumb (top left of a
# cell), 18 door (the top of a cell), 20..23 and 24..27 the chili's two frames (top left, top right,
# bottom left, bottom right), 32.. the font in white (FONT_ORDER), 80.. in yellow.
tiles = [Canvas(8, 8)] + [wall(m) for m in range(16)] + [crumb(), door()]
for n, t in enumerate(tiles):
    bg.blit(t, (n % 16) * 8, (n // 16) * 8)
for f in range(2):
    c = chili(f)
    for q in range(4):
        piece = Canvas(8, 8)
        for y in range(8):
            for x in range(8):
                piece.px[y][x] = c.px[(q // 2) * 8 + y][(q % 2) * 8 + x]
        n = 20 + f * 4 + q
        bg.blit(piece, (n % 16) * 8, (n // 16) * 8)
for n, ch in enumerate(FONT_ORDER):
    bg.blit(glyph(ch, 13), ((32 + n) % 16) * 8, ((32 + n) // 16) * 8)
for n, ch in enumerate(FONT_ORDER):
    bg.blit(glyph(ch, 14), ((80 + n) % 16) * 8, ((80 + n) // 16) * 8)
save_png(f"{out}/maze_gfx.png", bg, BG)

# --- sprite palettes: index 1 outline, 2-4 fur dark to light, 5 white, 6 pink, 7 pink dark,
# 8 black --------------------------------------------------------------------------------------
def pal(fur, outline=(24, 20, 30), white=(250, 250, 250), pink=(244, 136, 168), pinkd=(184, 74, 112), black=(14, 10, 18)):
    return [(0, 0, 0), outline] + list(fur) + [white, pink, pinkd, black] + [(0, 0, 0)] * 7

MOUSE = pal(((96, 96, 118), (152, 152, 176), (212, 212, 228)))
CATS = [pal(((168, 70, 18), (232, 124, 40), (255, 186, 96))),       # ginger
        pal(((34, 34, 46), (66, 66, 90), (110, 110, 140))),          # black
        pal(((132, 132, 148), (190, 190, 204), (240, 240, 250))),    # white
        pal(((96, 58, 30), (148, 98, 58), (200, 150, 100)))]         # brown
SCARED = pal(((28, 40, 150), (56, 82, 220), (116, 150, 255)), white=(255, 255, 255), pink=(255, 255, 255))
HOT = pal(((200, 40, 20), (255, 110, 30), (255, 210, 80)))            # the mouse after a chili

def mouse(frame):
    """Seen from above, facing right."""
    c = Canvas(16, 16)
    # tail behind: a pink curve that wags
    tail = [(0, 9), (1, 9), (2, 8), (3, 8)] if frame == 0 else [(0, 6), (1, 7), (2, 7), (3, 8)]
    for x, y in tail:
        c.set(x, y, 6)
    c.ellipse(7.5, 8.0, 5.0, 4.2, [2, 3, 4])                # body
    for (x, y) in ([(5, 3), (9, 12)] if frame == 0 else [(9, 3), (5, 12)]):
        c.set(x, y, 7)                                       # feet, in step
    c.ellipse(12.0, 8.0, 3.2, 2.8, [2, 3, 4])               # head
    c.ellipse(10.6, 4.4, 1.8, 1.8, [7, 6])                  # ears
    c.ellipse(10.6, 11.6, 1.8, 1.8, [7, 6])
    c.set(13, 6, 8); c.set(13, 10, 8)                       # eyes
    c.set(15, 8, 6)                                          # nose
    c.outline(1)
    return c

def cat(frame, look):
    """From the front; look = 0 right, 1 up, 2 left, 3 down: where the pupils point."""
    c = Canvas(16, 16)
    # tail on the right, up or down
    for x, y in ([(13, 9), (14, 8), (14, 7), (15, 6)] if frame == 0 else [(13, 11), (14, 11), (15, 10), (15, 9)]):
        c.set(x, y, 2)
    c.ellipse(8.0, 12.0, 5.0, 3.6, [2, 3, 4])               # body
    for x in ((5, 10) if frame == 0 else (4, 11)):
        c.set(x, 15, 2); c.set(x + 1, 15, 2)                 # paws
    c.ellipse(8.0, 6.5, 6.0, 4.8, [2, 3, 4])                # head
    for x0 in (2, 10):                                       # ears
        c.from_text(["..a.", ".aa.", "aaba"] if x0 == 2 else [".a..", ".aa.", "abaa"], {"a": 3, "b": 6}, x0, 0)
    c.ellipse(8.0, 8.6, 2.6, 1.6, [5])                       # muzzle
    for ex in (5, 10):                                       # eyes
        c.rect(ex, 5, ex + 1, 7, 5)
        dx, dy = ((1, 0), (0, -1), (0, 0), (0, 1))[look]
        px, py = ex + (1 if look == 0 else 0), 6 + dy
        c.set(px, py, 8)
        c.set(px, py + 1 if look != 3 else py - 1, 8)
    c.set(8, 8, 6)                                           # nose
    c.outline(1)
    return c

def scared(frame):
    c = cat(frame, 3)
    for y in range(16):
        for x in range(16):
            if c.px[y][x] == 8:
                c.px[y][x] = 5
    for x in range(5, 11):                                   # a wavy mouth
        c.set(x, 10, 1 if (x + frame) % 2 else 4)
    return c

def eyes(look):
    c = Canvas(16, 16)
    for ex in (4, 9):
        c.ellipse(ex + 1.5, 7.0, 2.0, 2.6, [5])
        dx, dy = ((1, 0), (0, -1), (-1, 0), (0, 1))[look]
        c.set(ex + 1 + max(dx, 0), 7 + dy, 8); c.set(ex + 1 + max(dx, 0), 8 + dy, 8)
    c.outline(1)
    return c

# The sprite sheet, 128 pixels wide (8 sprites of 16 x 16 a row, VRAM order):
# row 0: mouse right 0/1, up 0/1, left 0/1, down 0/1
# row 1: cat looking right 0/1, up 0/1, left 0/1, down 0/1
# row 2: scared 0/1, eyes right/up/left/down, a cheese wedge (bonus)
spr = Canvas(128, 48)
for d in range(4):                                           # 0 right, 1 up, 2 left, 3 down
    for f in range(2):
        m = mouse(f)
        m = {0: m, 1: m.rotated(3), 2: m.flipped(), 3: m.rotated(1)}[d]
        spr.blit(m, (d * 2 + f) * 16, 0)
        spr.blit(cat(f, d), (d * 2 + f) * 16, 16)
spr.blit(scared(0), 0, 32); spr.blit(scared(1), 16, 32)
for d in range(4):
    spr.blit(eyes(d), (2 + d) * 16, 32)
cheese = Canvas(16, 16)
cheese.from_text(["................", "................", "................", "..........aa....", "........aabba...",
                  "......aabbbbba..", "....aabbbbbbbba.", "..aabbbbbbbbbba.", ".abbbcbbbbbcbba.", ".abbcccbbbbbbba.",
                  ".abbbcbbbbcbbba.", ".abbbbbbbcccbba.", ".abbbbbbbbcbbba.", ".aaaaaaaaaaaaaa.", "................"],
                 {"a": 2, "b": 4, "c": 3})
cheese.outline(1)
spr.blit(cheese, 6 * 16, 32)
save_png(f"{out}/sprites_gfx.png", spr, MOUSE)
for n, p in enumerate(CATS + [SCARED, HOT]):
    strip = Canvas(16, 1)
    for x in range(16):
        strip.set(x, 0, x)
    save_png(f"{out}/pal_{['ginger','black','white','brown','scared','hot'][n]}.png", strip, p, transparent0=False)
