"""The graphics of Er-Tüp, drawn by program: the player and its items, the enemies, the stage
tiles of the three rooms of the pub, the parallax walls behind them and the status line.

    python3 dev/art/ertuep.py examples/ertuep/assets

Writes sprites_a.png, sprites_b.png, stage_bar.png, stage_cellar.png, stage_kitchen.png,
wall_bar.png, wall_cellar.png, wall_kitchen.png and hud.png into the folder given, and the cell
patterns of the walls into ../src/walls.c next to it.

Every sheet is 128 pixels wide and cut into cells of 16 x 16; cell k is at (16 * (k % 8),
16 * (k // 8)), so that a 16 x 16 sprite at cell k is the 8 x 8 tiles t, t + 1, t + 16, t + 17 with
t = 4k - 2 (k % 8), as the SNES reads them. Each sheet has its own 16 colours, index 0 transparent.
Sprites face right (the player) or left (the enemies), have a dark outline and their light from
the upper left. A wall is a picture of 256 x 224 drawn whole, cut into cells and stored once per
distinct cell (a mirrored copy counts as the same, bit 7 in the pattern). Nothing is random. The status line uses 2 bits a pixel. The layout
(cell numbers, palettes) is what the game's code relies on: change it only together with it.

Stage 3 adds sprites_c.png (copied over rows 4..7 of sprites_b.png while it runs) and two pictures
for mode 7, festzelt.png and karussell.png: 1024 x 1024, a byte a pixel, at most 255 distinct
8 x 8 tiles (mode 7 cannot mirror a tile, so they are drawn for repetition on the 8 pixel grid) and
at most 95 colours at indices 1..95, all opaque. The script stops when a picture breaks the limits.
"""
import math
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402
from font import FONT  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."

# --- helpers ------------------------------------------------------------------------------------
HEX = {ch: int(ch, 16) for ch in "123456789abcdef"}


def art(rows, legend=HEX):
    """A canvas from rows of characters; '.' (and anything not in the legend) is transparent."""
    w = len(rows[0])
    for n, r in enumerate(rows):
        if len(r) != w:
            raise ValueError(f"row {n} has {len(r)} characters, not {w}: {r!r}")
    c = Canvas(w, len(rows))
    c.from_text(rows, legend)
    return c


def put(sheet, cell, c, dx=0, dy=0):
    sheet.blit(c, 16 * (cell % 8) + dx, 16 * (cell // 8) + dy)


def line(c, x0, y0, x1, y1, col):
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x1 > x0 else -1), (1 if y1 > y0 else -1)
    err = dx + dy
    while True:
        c.set(x0, y0, col)
        if x0 == x1 and y0 == y1:
            return
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def shade_mask(c, mask, light, body, dark):
    """Colours the pixels of mask (a set of (x, y)): light where the upper or left neighbour is
    outside, dark where the lower or right one is, body elsewhere."""
    for x, y in mask:
        if (x - 1, y) not in mask or (x, y - 1) not in mask:
            c.set(x, y, light)
        elif (x + 1, y) not in mask or (x, y + 1) not in mask:
            c.set(x, y, dark)
        else:
            c.set(x, y, body)


def disk(cx, cy, r):
    return {(x, y) for y in range(int(cy - r - 1), int(cy + r + 2)) for x in range(int(cx - r - 1), int(cx + r + 2))
            if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r}


def ball(c, mask, cx, cy, r, ramp):
    """Shades a round mask as a ball lit from the upper left; ramp from dark to bright."""
    for x, y in mask:
        dx, dy = (x + 0.5 - cx) / r, (y + 0.5 - cy) / r
        lum = -(dx * -0.6 + dy * -0.75) * 0.9 + 0.25
        k = int((lum + 0.6) / 1.6 * len(ramp))
        c.set(x, y, ramp[max(0, min(len(ramp) - 1, k))])


def lathe(c, cx, cy, angle, length, parts):
    """A round thing (bottle, can) seen from the side, its axis turned by angle degrees clockwise
    from upright, centred at (cx, cy). parts: (u0, u1, half width at u0, at u1, ramp) along the
    axis from the bottom (u = 0) to the top (u = length); ramp = dark, mid, light, highlight."""
    a = math.radians(angle)
    ax, ay = math.sin(a), -math.cos(a)          # the axis, towards the top of the thing
    nx, ny = -ay, ax                              # across it, to the right of an upright thing
    face = nx * -0.6 + ny * -0.8                  # how much the right side faces the light
    for y in range(c.h):
        for x in range(c.w):
            px, py = x + 0.5 - cx, y + 0.5 - cy
            u = px * ax + py * ay + length / 2
            v = px * nx + py * ny
            for u0, u1, h0, h1, ramp in parts:
                if u0 <= u < u1:
                    hw = h0 + (h1 - h0) * (u - u0) / (u1 - u0)
                    if abs(v) <= hw + 0.01:
                        b = (v / hw if hw > 0.3 else 0.0) * face
                        k = 0 if b < -0.35 else 1 if b < 0.12 else 2 if b < 0.45 else 3
                        c.set(x, y, ramp[k])
                    break


# =================================================================================================
# 1. sprites_a.png: the player and its items
# =================================================================================================
SPA = [(0, 0, 0), (24, 14, 10), (72, 82, 100), (138, 150, 170), (214, 224, 238), (255, 255, 255),
       (150, 78, 12), (228, 148, 32), (255, 214, 96), (22, 78, 34), (44, 150, 62), (156, 232, 150),
       (118, 22, 22), (198, 44, 36), (232, 192, 64), (188, 138, 88)]


def ship(tilt):
    """The flying beer tap, 32 x 16, facing right; tilt -1 climbs, +1 dives."""
    b = Canvas(32, 16)
    for y, col in zip(range(9, 12), (4, 3, 2)):          # the coupling at the back
        b.rect(2, y, 4, y, col)
    for y, col in zip(range(8, 13), (4, 4, 3, 3, 2)):     # its nut
        b.set(5, y, col)
        b.set(6, y, {4: 3, 3: 2, 2: 2}[col])
    for y, col in zip(range(7, 13), (4, 5, 4, 3, 3, 2)):  # the barrel
        b.rect(7, y, 22, y, col)
    b.rect(7, 8, 9, 8, 4)
    b.rect(20, 8, 22, 8, 4)
    b.set(7, 7, 0)
    b.set(7, 12, 0)
    for y, col in zip(range(7, 13), (14, 14, 14, 14, 6, 6)):   # a brass band
        b.set(11, y, col)
        b.set(12, y, 6)
    for y, col in zip(range(8, 12), (4, 3, 3, 2)):        # the rounded nose
        b.set(23, y, col)
    for y, col in zip(range(9, 12), (4, 3, 2)):           # the spout, forward ...
        b.rect(24, y, 26, y, col)
    for x, col in zip(range(26, 29), (4, 3, 2)):          # ... and down
        b.rect(x, 10, x, 12, col)
    b.set(26, 9, 4)
    b.set(27, 9, 3)
    b.rect(25, 13, 29, 13, 3)                             # the mouth
    b.set(25, 13, 4)
    b.set(29, 13, 2)
    b.set(27, 14, 7)                                      # the drop of beer
    c = Canvas(32, 16)
    for x in range(32):
        d = tilt if x >= 21 else -tilt if x <= 8 else 0
        for y in range(16):
            if b.px[y][x]:
                c.set(x, y + d, b.px[y][x])
    # the handle: a chrome collar, a black lever, a red knob; it leans back when climbing
    lean = tilt
    c.rect(13, 6, 18, 6, 3)                               # the collar
    c.rect(13, 6, 14, 6, 4)
    c.set(18, 6, 2)
    c.rect(15, 5, 16, 5, 1)                               # the black ferrule
    for y, cols in ((1, (13, 5, 13, 12)), (2, (13, 13, 12, 12))):   # the knob ...
        for i, col in enumerate(cols):
            c.set(14 + 2 * lean + i, y, col)
    for y in (3, 4):                                      # ... on a lever
        c.set(15 + lean, y, 13)
        c.set(16 + lean, y, 12)
    c.outline(1)
    return c


def crate(necks):
    c = Canvas(16, 16)
    c.rect(0, 2, 15, 15, 1)
    c.rect(1, 3, 14, 3, 12)                               # the far rim
    c.rect(1, 5, 14, 14, 13)                              # the front side
    c.rect(1, 6, 14, 6, 12)                               # the shadow under the front rim
    c.rect(14, 5, 14, 14, 12)                             # the right side in shade
    c.rect(6, 7, 9, 7, 1)                                 # the handle hole
    c.rect(5, 8, 10, 8, 1)
    c.rect(6, 9, 9, 9, 12)
    for x in (3, 6, 9, 12):                               # the grating
        c.rect(x, 11, x, 12, 12)
    c.rect(1, 13, 14, 13, 12)
    c.rect(1, 14, 14, 14, 13)
    c.set(1, 14, 13)
    c.rect(1, 5, 1, 13, 13)
    xs = {0: [], 2: [4, 10], 4: [2, 5, 9, 12], 6: [2, 4, 6, 8, 10, 12]}[necks]
    for n, x in enumerate(xs):
        top = 1 if (necks < 6 or n % 2 == 0) else 2
        c.set(x, top, 14)
        c.set(x + 1, top, 14 if necks == 6 else 6)
        for y in range(top + 1, 5):
            c.set(x, y, 11 if y == top + 1 else 10)
            c.set(x + 1, y, 9)
        if necks == 6:
            c.set(x + 1, top, 6)
    # the necks need their outline above them
    for n, x in enumerate(xs):
        top = 1 if (necks < 6 or n % 2 == 0) else 2
        for xx in (x, x + 1):
            c.set(xx, top - 1, 1)
        if necks < 6:
            c.set(x - 1, top, 1)
            c.set(x + 2, top, 1)
            for y in range(top + 1, 4):
                c.set(x - 1, y, 1)
                c.set(x + 2, y, 1)
    return c


GREEN = (9, 10, 10, 11)
DKGREEN = (1, 9, 10, 11)
BEER_PARTS = [(0, 8, 2.6, 2.6, GREEN), (8, 10.5, 2.6, 1.1, GREEN), (10.5, 13.5, 1.1, 1.1, GREEN)]


def beer_bottle(angle, cap):
    c = Canvas(16, 16)
    parts = list(BEER_PARTS)
    if cap:
        parts.append((13.5, 14.6, 1.6, 1.6, (6, 14, 14, 8)))
    lathe(c, 8.0, 8.0, angle, 14.6 if cap else 13.5, parts)
    c.outline(1)
    return c


def shot_bottle():
    """The bottle shot, horizontal, neck right, gold cap; 15 x 5."""
    rows = ["...............",
            ".111111111.111.",
            "1bbbbbbbb1111e1",
            "1aaaaaaaaaaaae1",
            "1999999991111e1",
            ".111111111..11."]
    # legend: b highlight, a glass, 9 dark glass, e gold
    leg = {"1": 1, "b": 11, "a": 10, "9": 9, "e": 14}
    c = Canvas(16, 16)
    c.from_text(rows[1:], leg, 0, 6)
    return c


def sekt_bottle(foam):
    """The bottle of sparkling wine, upright; foam 0 calm, 1 rising, 2 foaming over."""
    c = Canvas(16, 16)
    parts = [(0, 7.5, 3.0, 3.0, DKGREEN), (7.5, 9.5, 3.0, 1.6, DKGREEN), (9.5, 10.5, 1.6, 1.6, (6, 14, 14, 8)),
             (10.5, 12.5, 1.1, 1.1, (6, 14, 14, 8))]
    lathe(c, 8.0, 9.25, 0, 12.5, parts)
    # the label
    for y in (10, 11):
        for x in range(6, 10):
            c.set(x, y, 8 if x < 9 else 7)
    c.rect(6, 12, 9, 12, 9)
    if foam == 0:
        c.set(7, 2, 15)                                    # the cork under the foil
        c.set(8, 2, 15)
    elif foam == 1:
        for x, y, col in ((7, 2, 5), (8, 2, 5), (7, 1, 5), (8, 1, 8), (6, 1, 5), (8, 0, 5), (7, 0, 5)):
            c.set(x, y, col)
    c.outline(1)
    if foam == 2:
        foam_c = Canvas(16, 16)
        m = disk(8.0, 2.6, 3.4) | disk(4.8, 4.0, 2.0) | disk(11.2, 4.0, 2.0)
        m |= {(4, 6), (4, 7), (11, 6), (11, 7), (12, 6), (3, 6), (11, 8), (4, 8)}
        m = {p for p in m if 0 <= p[0] < 16 and p[1] >= 0}
        for x, y in m:
            foam_c.set(x, y, 5)
        for x, y in m:
            if (x + 1, y + 1) not in m or (x, y + 1) not in m:
                foam_c.set(x, y, 8)
        foam_c.outline(1)
        for y in range(16):
            for x in range(16):
                if foam_c.px[y][x] and (y < 9 or c.px[y][x] in (0, 1)):
                    c.px[y][x] = foam_c.px[y][x]
    return c


def can(lying):
    c = Canvas(16, 16)
    chrome = (2, 3, 4, 5)
    red = (12, 13, 13, 4)
    parts = [(0, 1, 2.6, 2.6, (2, 2, 3, 3)), (1, 3, 3.0, 3.0, chrome), (3, 7, 3.0, 3.0, red),
             (7, 9, 3.0, 3.0, chrome), (9, 10, 2.6, 2.6, (2, 3, 3, 4))]
    lathe(c, 8.0, 9.0 if not lying else 8.0, 90 if lying else 0, 10, parts)
    c.outline(1)
    return c


def foam_burst(frame):
    """A burst of beer foam: four frames, growing, then breaking up into wisps."""
    c = Canvas(16, 16)
    blobs = [
        [(8, 8, 3.4), (6.2, 6.6, 2.2), (10.2, 6.8, 1.8), (9.6, 10, 2.0)],
        [(8, 8, 4.4), (4.8, 6.2, 2.6), (11.0, 5.6, 2.4), (5.2, 10.8, 2.4), (11, 10.8, 2.6), (8, 3.6, 1.8)],
        [(4.4, 4.8, 2.8), (11.4, 4.4, 2.4), (4.2, 11.4, 2.4), (11.6, 11.2, 2.8), (8, 2.6, 1.5), (13.4, 8, 1.4),
         (2.4, 8, 1.4)],
        [(3.0, 3.5, 1.5), (12.8, 3.2, 1.3), (2.6, 12.6, 1.3), (13.2, 12.8, 1.5), (8, 1.6, 0.9), (8, 14, 1.0)],
    ][frame]
    m = set()
    for bx, by, r in blobs:
        m |= {p for p in disk(bx, by, r) if 1 <= p[0] < 15 and 1 <= p[1] < 15}
    for x, y in m:
        c.set(x, y, 5)
    for x, y in m:                                         # shade and rim on the lower right
        if (x + 1, y + 1) not in m:
            c.set(x, y, 7 if ((x + 1, y) not in m or (x, y + 1) not in m) else 8)
        elif (x + 2, y + 2) not in m and frame < 3:
            c.set(x, y, 8)
    for x, y in ((7, 7), (9, 6), (6, 10)) if frame == 1 else ((5, 5), (11, 11)) if frame == 2 else ():
        if (x, y) in m:
            c.set(x, y, 8)                                  # bubbles in the foam
    if frame < 3:
        for y in range(16):
            for x in range(16):
                if c.px[y][x] == 0 and (c.get(x - 1, y) == 7 or c.get(x, y - 1) == 7 or c.get(x - 1, y - 1) == 7):
                    c.set(x, y, 1)
    return c


def sixpack(shine):
    c = Canvas(16, 16)
    # three bottles behind the carrier (front row), caps on top
    for i, x in enumerate((3, 7, 11)):
        c.set(x, 2, 14)
        c.set(x + 1, 2, 6)
        c.set(x, 3, 11)
        c.set(x + 1, 3, 9)
        c.rect(x - 1, 4, x + 2, 6, 10)
        c.rect(x - 1, 4, x - 1, 6, 11)
        c.rect(x + 2, 4, x + 2, 6, 9)
    # the carrier: card with a handle in the middle
    c.rect(2, 7, 13, 13, 7)
    c.rect(2, 7, 13, 7, 8)
    c.rect(2, 7, 2, 13, 8)
    c.rect(13, 8, 13, 13, 6)
    c.rect(3, 13, 13, 13, 6)
    c.rect(6, 1, 9, 1, 7)                                  # handle
    c.set(6, 2, 7); c.set(9, 2, 7)
    c.set(6, 3, 7); c.set(9, 3, 7)
    c.rect(3, 9, 12, 9, 5)                                 # a label band
    c.rect(3, 10, 12, 10, 4)
    c.rect(3, 11, 12, 11, 6)
    c.outline(1)
    # the bright outline of an item
    c.outline(8)
    if shine:
        for i in range(16):
            for x, y in ((i, 14 - i), (i + 1, 14 - i)):
                if c.get(x, y) not in (0, 1, 8):
                    c.set(x, y, 5)
    return c


def sekt_pickup():
    c = sekt_bottle(0)
    bow = art(["....1....1......",
               "...1d1..1d1.....",
               "...1dd11dc1.....",
               "....1dddc1......",
               "...1dc1.1c1.....",
               "...1c1...1c1....",
               "....1.....1....."], {**HEX, "d": 13, "c": 12})
    c.blit(bow, 1, 3)
    c.outline(8)
    return c


def espresso():
    return art(["................",
                "......5.........",
                ".....4..........",
                "......4.5.......",
                ".......4........",
                "......4.........",
                "...111111111....",
                "..15444444431...",
                "..1566666643111.",
                "..154444443141.1".replace("1.1", "11.")[:16],
                "..15444444431111",
                "...154444431....",
                ".11115444311111.",
                "1554444444443321",
                ".11222222222211.",
                "................"], {**HEX, "6": 6})


def small_sprites_30():
    c = Canvas(16, 16)
    cap = art(["..1111..",
               ".1e8ee1.",
               "1e8eeee1",
               "1eea6ee1",
               "1eeeeea1",
               ".1eeea1.",
               "..1111..",
               "........"], {**HEX, "a": 6})
    c.blit(cap, 0, 0)
    edge = art(["....11..",
                "...1e1..",
                "...1e1..",
                "..1ea1..",
                "..1ea1..",
                ".1ea1...",
                ".1a1....",
                "..1....."], {**HEX, "a": 6})
    c.blit(edge, 8, 0)
    big = art(["..111...",
               ".15551..",
               "1555581.",
               "1555881.",
               "1558881.",
               ".18881..",
               "..111...",
               "........"])
    c.blit(big, 0, 8)
    small = art(["........",
                 "........",
                 "...11...",
                 "..1551..",
                 "..1581..",
                 "...11...",
                 "........",
                 "........"])
    c.blit(small, 8, 8)
    return c


def small_sprites_31():
    c = Canvas(16, 16)
    c.blit(art(["........",
                "...1....",
                "..1b1...",
                "..1ba1..",
                ".1aa91..",
                ".1991...",
                "..11....",
                "........"], {**HEX, "a": 10, "b": 11, "9": 9}), 0, 0)
    c.blit(art(["........",
                "........",
                ".11.....",
                "1ba11...",
                ".1aa91..",
                "..1991..",
                "...11...",
                "........"], {**HEX, "a": 10, "b": 11, "9": 9}), 8, 0)
    c.blit(art(["........",
                "........",
                "..44....",
                ".45554..",
                "4555554.",
                ".43334..",
                "........",
                "........"]), 0, 8)
    c.blit(art(["...e....",
                "...5....",
                ".e555e..",
                "...5....",
                "...e....",
                "........",
                "........",
                "........"]), 8, 9)
    return c


# --- the title logo -------------------------------------------------------------------------------
LOGO = {
    "E": ["1111111", "1111111", "111....", "111....", "111111.", "111111.", "111....", "111....",
          "1111111", "1111111"],
    "R": ["1111111.", "11111111", "111..111", "111..111", "11111111", "1111111.", "111.111.",
          "111..111", "111..111", "111..111"],
    "-": ["....", "....", "....", "....", "1111", "1111", "....", "....", "....", "...."],
    "T": ["11111111", "11111111", "..1111..", "..1111..", "..1111..", "..1111..", "..1111..",
          "..1111..", "..1111..", "..1111.."],
    "U": ["111..111", "111..111", "111..111", "111..111", "111..111", "111..111", "111..111",
          "111..111", "11111111", ".111111."],
    "P": ["1111111.", "11111111", "111..111", "111..111", "11111111", "1111111.", "111.....",
          "111.....", "111.....", "111....."],
}


def logo():
    c = Canvas(128, 32)
    word = ["E", "R", "-", "T", "U", "P"]
    widths = [len(LOGO[ch][0]) * 2 for ch in word]
    gap = 4
    total = sum(widths) + gap * (len(word) - 1)
    x0 = (128 - total) // 2
    top = 8
    mask = set()
    starts = []
    x = x0
    for ch, w in zip(word, widths):
        starts.append(x)
        for gy, row in enumerate(LOGO[ch]):
            for gx, bit in enumerate(row):
                if bit == "1":
                    for sy in range(2):
                        for sx in range(2):
                            mask.add((x + gx * 2 + sx, top + gy * 2 + sy))
        x += w + gap
    # round the outer corners
    corners = [p for p in mask if sum(((p[0] + a, p[1] + b) in mask) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))) == 2
               and ((p[0] - 1, p[1]) not in mask) != ((p[0] + 1, p[1]) not in mask)
               and ((p[0], p[1] - 1) not in mask) != ((p[0], p[1] + 1) not in mask)]
    mask -= set(corners)
    for x, y in mask:
        if (x - 1, y) not in mask or (x, y - 1) not in mask:
            c.set(x, y, 8)
        elif (x + 1, y) not in mask or (x, y + 1) not in mask or (x + 1, y + 1) not in mask:
            c.set(x, y, 6)
        else:
            c.set(x, y, 7)
    # a highlight line: one pixel in from the left edge of the strokes
    for x, y in mask:
        if (x - 2, y) not in mask and (x - 1, y) in mask and (x, y - 1) in mask and (x + 1, y) in mask \
                and (x, y + 1) in mask and c.px[y][x] == 7:
            c.set(x, y, 8)
    # foam on every top edge, dripping here and there
    drips = {}
    for x, y in sorted(mask):
        if (x, y - 1) not in mask:
            depth = 2 + (1 if (x * 7 + y) % 5 == 0 else 0)
            if (x * 13 + y * 3) % 11 == 0:
                depth = 5
            for d in range(depth):
                if (x, y + d) in mask:
                    c.set(x, y + d, 5)
            if (x, y + depth) in mask:
                c.set(x, y + depth, 8)
            # bubbles above the edge
            if (x // 3 + y) % 2 == 0:
                c.set(x, y - 1, 5)
    # the dots of the U: two blobs of foam
    ux = starts[4]
    for bx in (ux + 3, ux + 12):
        m = disk(bx + 0.0, 3.5, 2.6)
        for x, y in m:
            c.set(x, y, 5)
        for x, y in m:
            if (x + 1, y + 1) not in m:
                c.set(x, y, 8)
    c.outline(1)
    return c


def subtitle(text):
    c = Canvas(128, 16)
    glyphs = dict(FONT)
    glyphs["Ä"] = ["01010", "00000", "01110", "10001", "11111", "10001", "10001"]
    w = len(text) * 7 - 1
    x0 = (128 - w) // 2
    ramp = [5, 4, 4, 3, 3, 2, 2]
    for n, ch in enumerate(text):
        rows = glyphs.get(ch)
        if not rows:
            continue
        for y, row in enumerate(rows):
            for x, bit in enumerate(row):
                if bit == "1":
                    c.set(x0 + n * 7 + x + 1, 1 + y + 1, 1)
                    c.set(x0 + n * 7 + x + 1, 1 + y, 1)
        for y, row in enumerate(rows):
            for x, bit in enumerate(row):
                if bit == "1":
                    c.set(x0 + n * 7 + x, 1 + y, ramp[y])
    return c


def sprites_a():
    s = Canvas(128, 128)
    for n, tilt in enumerate((0, -1, 1)):
        sh = ship(tilt)
        s.blit(sh, 32 * n, 0)
    for n, necks in enumerate((0, 2, 4, 6)):
        put(s, 6 + n, crate(necks))
    put(s, 10, shot_bottle())
    put(s, 11, beer_bottle(45, True))
    put(s, 12, cork())
    put(s, 13, sekt_bottle(0))
    shaking = sekt_bottle(1)
    for x, y in ((2, 8), (2, 10), (13, 7), (13, 9)):      # marks of the shaking
        shaking.set(x, y, 3)
        shaking.set(x, y + 1, 3)
    put(s, 14, shaking)
    put(s, 15, sekt_bottle(2))
    for n, a in enumerate((0, 45, 90, 135)):
        put(s, 16 + n, beer_bottle(a, False))
    put(s, 20, can(False))
    put(s, 21, can(True))
    for f in range(4):
        put(s, 22 + f, foam_burst(f))
    put(s, 26, sixpack(False))
    put(s, 27, sixpack(True))
    put(s, 28, sekt_pickup())
    put(s, 29, espresso())
    put(s, 30, small_sprites_30())
    put(s, 31, small_sprites_31())
    s.blit(logo(), 0, 64)
    s.blit(subtitle("DER PFANDJÄGER"), 0, 96)
    for frame in range(2):                                      # stage 2's boss: the head, 2 x 2 cells
        head = mett_head(frame == 1)
        for q, (qx, qy) in enumerate(((0, 0), (16, 0), (0, 16), (16, 16))):
            quarter = Canvas(16, 16)
            for y in range(16):
                for x in range(16):
                    quarter.set(x, y, head.get(qx + x, qy + y))
            put(s, 56 + 4 * frame + q, quarter)
    return s


def cork():
    """The cork of the sparkling wine, flying right, a tail of foam behind it."""
    c = Canvas(16, 16)
    for y, col in zip(range(6, 11), (8, 15, 15, 15, 6)):  # the shank
        c.rect(6, y, 9, y, col)
    c.rect(7, 6, 7, 10, 3)                                # the wire around it
    head = {(x, y) for x, y in disk(10.0, 8.5, 4.5) if x >= 10}
    ball(c, head, 9.5, 7.5, 4.5, [6, 15, 15, 8])
    for x, y, col in ((5, 7, 5), (5, 8, 5), (5, 9, 8), (4, 8, 5), (4, 7, 8), (3, 8, 8), (2, 8, 7),
                      (3, 9, 7), (4, 9, 8)):
        c.set(x, y, col)
    c.outline(1)
    return c


# =================================================================================================
# 2. sprites_b.png: the enemies (all face left)
# =================================================================================================
SPB = [(0, 0, 0), (22, 12, 12), (242, 226, 190), (198, 166, 116), (204, 34, 30), (118, 16, 16),
       (104, 52, 18), (172, 98, 42), (250, 250, 250), (78, 44, 20), (142, 88, 42), (92, 94, 108),
       (184, 204, 214), (228, 148, 32), (8, 8, 10), (40, 140, 62)]


def beer_mat(frame):
    """The beer mat spinning: face-on, tilted, edge-on, tilted showing its back."""
    c = Canvas(16, 16)
    rx, ry = 7.0, [7.0, 4.0, 1.0, 4.0][frame]
    cx, cy = 8.0, 8.0
    m = {(x, y) for y in range(16) for x in range(16) if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0}
    if frame == 2:
        c.rect(1, 7, 14, 7, 2)
        c.rect(1, 8, 14, 8, 3)
        c.outline(1)
        return c
    back = frame == 3
    for x, y in m:
        d = math.sqrt(((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2)
        if back:
            c.set(x, y, 3)
        elif 0.62 <= d <= 0.8:
            c.set(x, y, 4)
        else:
            c.set(x, y, 2)
    # the edge, the thickness of the card, below the face
    for x, y in m:
        if (x, y + 1) not in m:
            c.set(x, y + 1, 3 if not back else 2)
    if back:
        for x, y in m:
            if (x, y - 1) not in m:
                c.set(x, y, 7)
    if frame == 0:
        c.from_text(["4444", "4...", "444.", "4...", "4444"], {"4": 4}, 6, 6)
        for x, y in m:                                      # a little shade on the lower right
            if ((x + 1, y) not in m or (x, y + 1) not in m) and c.px[y][x] == 2:
                c.set(x, y, 3)
    elif frame == 1:
        c.from_text(["444", "44.", "444"], {"4": 4}, 7, 7)
    c.outline(1)
    return c


PRETZEL = ["...oooo..oooo...",
           "..oo..oooo..oo..",
           ".oo....oo....oo.",
           ".o....oooo....o.",
           ".oo..oo..oo..oo.",
           "..oooo....oooo..",
           "...oo......oo...",
           "....oo....oo....",
           ".....oooooo....."]


def pretzel(frame):
    """A pretzel with little white wings, flapping; a mean eye on its left."""
    c = Canvas(16, 16)
    mask = {(x, y + 6) for y, r in enumerate(PRETZEL) for x, ch in enumerate(r) if ch == "o"}
    shade_mask(c, mask, 7, 7, 6)
    for x, y in mask:                                       # the glaze catches the light
        if (x - 1, y) not in mask and (x, y - 1) not in mask:
            c.set(x, y, 3)
    for x, y in ((4, 6), (11, 6), (8, 9), (13, 10), (6, 14), (10, 14)):    # salt
        c.set(x, y, 8)
    if frame == 0:
        wing = ["......c.",
                "....8.c8",
                "...88c88",
                "..888c8.",
                ".8888c..",
                "..88c..."]
        c.from_text(wing, {"8": 8, "c": 12}, 7, 0)
    else:
        wing = ["........",
                "........",
                "........",
                "..888888",
                ".888c888",
                "..8c8c8c"]
        c.from_text(wing, {"8": 8, "c": 12}, 7, 1)
    c.outline(1)
    c.set(1, 9, 8)                                          # the eye, looking left
    c.set(2, 9, 8)
    c.set(1, 10, 14)
    c.set(2, 10, 8)
    c.set(1, 8, 1)                                          # an angry brow
    c.set(2, 8, 1)
    c.set(3, 9, 1)
    return c


def dice_cup(lifted):
    c = Canvas(16, 16)
    lift = 4 if lifted else 0
    cup = Canvas(16, 16)
    parts = [(0, 2, 5.0, 5.0, (9, 6, 7, 3)), (2, 9, 5.0, 4.0, (9, 6, 7, 7)), (9, 10, 4.0, 4.0, (9, 6, 7, 3))]
    lathe(cup, 8.0, 10.0, 0, 10, parts)
    for x in range(3, 13):                                  # the stitched rim
        if cup.get(x, 13) and x % 2 == 0:
            cup.set(x, 13, 2)
    cup.outline(1)
    if lifted:
        die = art([".11111.",
                   "1888881",
                   "18e8881",
                   "1888e81",
                   "1333331",
                   ".11111."], {**HEX, "e": 14, "8": 8, "3": 3})
        c.blit(die, 5, 10)
    for y in range(16):
        for x in range(16):
            if cup.px[y][x] and 0 <= y - lift:
                c.set(x, y - lift, cup.px[y][x])
    return c


def barrel(frame):
    """A barrel lying on its side, its round end to the viewer, rolling left."""
    c = Canvas(16, 16)
    cx = cy = 7.5
    for y in range(15):
        for x in range(15):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d > 7.5:
                continue
            dx, dy = (x + 0.5 - cx) / 7.5, (y + 0.5 - cy) / 7.5
            lit = -(dx * -0.6 + dy * -0.75)
            if d > 6.5:
                col = 1
            elif d > 5.3:
                col = 12 if lit > 0.45 else 11
            elif 3.0 < d <= 3.8:
                col = 11
            else:
                col = 7 if lit > 0.4 else 10 if lit > -0.35 else 9
                k = (x if frame % 2 == 0 else y)
                if k % 3 == 1:                               # the staves of the head
                    col = 9 if col != 7 else 10
            c.set(x, y, col)
    a = math.radians(90 + 90 * frame)                       # the bung goes round anticlockwise
    bx, by = cx + math.cos(a) * 4.6, cy - math.sin(a) * 4.6
    for x, y in disk(bx, by, 1.2):
        c.set(x, y, 14)
    return c


def fly(frame):
    c = Canvas(16, 16)
    body = disk(9.0, 10.0, 3.0) | disk(11.5, 10.5, 2.0)
    for x, y in body:
        c.set(x, y, 14)
    for x, y in ((8, 8), (9, 8), (7, 9)):
        c.set(x, y, 11)
    head = disk(5.0, 10.0, 2.0)
    for x, y in head:
        c.set(x, y, 14)
    c.set(4, 9, 4)
    c.set(4, 10, 4)
    c.set(5, 9, 4)
    c.set(3, 10, 5)
    c.set(4, 9, 8)
    for x, y in ((7, 13), (10, 13), (12, 13)):              # legs
        c.set(x, y, 1)
        c.set(x - 1, y + 1, 1)
    if frame == 0:
        wing = disk(10.5, 5.5, 2.2) | disk(12.5, 4.5, 1.6)
    else:
        wing = disk(12.0, 8.8, 2.0) | disk(14.0, 9.8, 1.4)
    c.outline(1)
    for x, y in wing:
        if c.get(x, y) in (0, 1):
            c.set(x, y, 12)
    for x, y in wing:
        if (x - 1, y) not in wing or (x, y - 1) not in wing:
            c.set(x, y, 8)
    c.outline(1)
    return c


def bullets_14():
    c = Canvas(16, 16)
    c.blit(art(["........",
                ".11.11..",
                "1331331.",
                "1733771.",
                "1776761.",
                ".11.11..",
                "........",
                "........"]), 0, 0)
    c.blit(art(["........",
                "..111...",
                ".13371..",
                ".17761..",
                "..171...",
                ".13371..",
                ".17661..",
                "..111..."]), 8, 0)
    c.blit(art(["..11....",
                ".1d21...",
                "1dd221..",
                "1d2221..",
                ".1221...",
                "..11....",
                "........",
                "........"], {**HEX, "d": 13}), 9, 9)
    # the dart: iron tip on the left, red flights on the right
    c.blit(art(["........",
                "......11",
                ".....141",
                "1bbb1441",
                ".....141",
                "......11",
                "........",
                "........"], {**HEX, "b": 11}), 0, 8)
    return c


def dartboard(rot):
    c = Canvas(32, 32)
    cx = cy = 16.0
    for y in range(32):
        for x in range(32):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            r = math.hypot(dx, dy)
            if r > 15.0:
                continue
            ang = (math.degrees(math.atan2(dx, -dy)) + 9 + rot) % 360
            seg = int(ang // 18) % 2
            if r > 14.0:
                col = 1
            elif r > 11.6:
                col = 11 if (r > 13.2 and dx + dy < -8) else 14
            elif r > 10.4:
                col = 4 if seg else 15
            elif r > 6.6:
                col = 14 if seg else 2
            elif r > 5.4:
                col = 4 if seg else 15
            elif r > 2.4:
                col = 14 if seg else 2
            elif r > 1.3:
                col = 15
            else:
                col = 4
            c.set(x, y, col)
    # two darts stuck in it, flights towards the viewer and to the upper right
    for bx, by in ((10, 12), (19, 20)):
        c.set(bx, by, 11)
        c.set(bx + 1, by - 1, 11)
        c.set(bx + 2, by - 2, 12)
        c.set(bx + 3, by - 3, 4)
        c.set(bx + 4, by - 3, 4)
        c.set(bx + 3, by - 4, 4)
        c.set(bx + 4, by - 4, 5)
    return c


def stein(slosh):
    """The one-litre beer stein, 28 x 30, handle on the right, two angry eyes."""
    c = Canvas(32, 32)
    # the glass body x 2..21, y 9..29
    for y in range(9, 30):
        for x in range(2, 22):
            c.set(x, y, 13)
    for y in range(9, 30):                                  # the glass walls
        c.set(2, y, 12)
        c.set(21, y, 12)
    c.rect(3, 9, 3, 26, 2)                                  # highlight left
    c.rect(18, 10, 20, 27, 7)                               # the beer in shade on the right
    c.rect(2, 27, 21, 29, 12)                               # the thick glass base
    c.rect(3, 28, 20, 28, 2)
    c.rect(2, 29, 21, 29, 11)
    # dimples: a regular grid of little hollows, lit on the upper left
    for row, y0 in enumerate((19, 23)):
        for x0 in ((5, 10, 15) if row == 0 else (7, 12)):
            c.set(x0, y0, 2)
            c.set(x0 + 1, y0, 12)
            c.set(x0, y0 + 1, 12)
            c.set(x0 + 2, y0 + 1, 7)
            c.set(x0 + 1, y0 + 2, 7)
    c.rect(16, 9, 16, 26, 12)                               # a second highlight streak
    # the handle on the right
    for y in range(11, 26):
        for x in range(22, 29):
            inner = 24 <= x <= 26 and 14 <= y <= 22
            corner = (x == 28 and y in (11, 25))
            if not inner and not corner:
                c.set(x, y, 12 if (x < 27 or y < 13) else 11)
    c.rect(23, 12, 26, 12, 2)
    c.rect(27, 13, 27, 24, 2)
    # the foam crown, sloshing to the left (frame 0) or to the right (frame 1)
    foam = {(x, y) for x in range(2, 22) for y in range(8, 11)}
    if not slosh:
        blobs = [(5.5, 5.5, 4.4), (11.5, 5.0, 4.4), (17.5, 7.0, 3.4), (2.5, 9.5, 2.0)]
        drip = [(1, 11), (2, 11), (1, 12), (2, 12), (1, 13)]
    else:
        blobs = [(6.0, 7.0, 3.4), (12.0, 5.0, 4.4), (18.0, 5.5, 4.4), (21.5, 9.0, 2.0)]
        drip = [(21, 11), (22, 11), (21, 12), (22, 12), (22, 13), (22, 14)]
    for bx, by, r in blobs:
        foam |= {(x, y) for x, y in disk(bx, by, r) if 1 <= x <= 22 and y >= 1}
    foam |= set(drip)
    for x, y in foam:
        c.set(x, y, 8)
    for x, y in foam:
        if (x + 1, y + 1) not in foam or (x, y + 1) not in foam:
            c.set(x, y, 2)
    # two angry eyes in the glass, looking left, brows down towards the middle
    for ex in (5, 13):
        c.rect(ex, 13, ex + 3, 16, 8)
        c.rect(ex, 14, ex + 1, 16, 14)
        c.set(ex + 3, 16, 2)
    for x, y in ((4, 11), (5, 11), (6, 12), (7, 12), (8, 13), (9, 13),
                 (17, 11), (16, 11), (15, 12), (14, 12), (13, 13), (12, 13)):
        c.set(x, y, 1)
    for x, y in ((5, 13), (6, 13), (7, 13), (14, 13), (15, 13), (16, 13)):
        c.set(x, y, 1)
    c.set(5, 13, 13)
    c.set(16, 13, 13)
    c.set(5, 12, 1)
    c.set(16, 12, 1)
    c.outline(1)
    return c


def sprites_b():
    s = Canvas(128, 128)
    for f in range(4):
        put(s, f, beer_mat(f))
    put(s, 4, pretzel(0))
    put(s, 5, pretzel(1))
    put(s, 6, dice_cup(False))
    put(s, 7, dice_cup(True))
    for f in range(4):
        put(s, 8 + f, barrel(f))
    put(s, 12, fly(0))
    put(s, 13, fly(1))
    put(s, 14, bullets_14())
    s.blit(dartboard(0), 0, 32)
    s.blit(dartboard(18), 32, 32)
    s.blit(stein(False), 64, 32)
    s.blit(stein(True), 96, 32)
    s.blit(slot_machine(0), 0, 64)                              # cells 32-34, 40-42, 48-50, 56-58
    s.blit(slot_machine(1), 48, 64)                             # cells 35-37, 43-45, 51-53, 59-61
    put(s, 38, reel_symbols())
    put(s, 39, coins())
    put(s, 46, mettigel(0))                                     # stage 2
    put(s, 47, mettigel(1))
    put(s, 54, hop_cone(0))
    put(s, 55, hop_cone(1))
    put(s, 62, tentacle_knot(False))
    put(s, 63, tentacle_knot(True))
    return s


# =================================================================================================
# 3. The stage tiles: the bar, the cellar, the kitchen (16 x 16 cells, index 0 shows the wall)
# =================================================================================================
BAR = [(0, 0, 0), (26, 14, 8), (58, 30, 14), (96, 54, 24), (142, 86, 40), (190, 126, 64), (128, 92, 24),
       (204, 160, 52), (250, 224, 130), (24, 64, 40), (40, 104, 62), (236, 220, 180), (164, 36, 30),
       (255, 236, 160), (150, 190, 170), (220, 140, 30)]


def cell():
    return Canvas(16, 16)


def planks(c, y0, y1, face, lit, joint, grain, phase=0):
    """Horizontal planks four pixels high from y0 to y1, staggered joints, a little grain."""
    for y in range(y0, y1 + 1):
        band, r = (y - y0) // 4, (y - y0) % 4
        for x in range(16):
            c.set(x, y, joint if r == 3 else lit if r == 0 else face)
        jx = (3 + 8 * ((band + phase) % 2) + 5 * band) % 16
        if r != 3:
            c.set(jx, y, joint)
            if r == 0:
                c.set((jx + 1) % 16, y, lit)
        if r == 1:
            gx = (jx + 4 + 3 * band) % 16
            for k in range(4):
                c.set((gx + k) % 16, y, grain)


def bar_floor_top():
    c = cell()
    planks(c, 3, 15, 4, 5, 2, 3)
    c.rect(0, 0, 15, 0, 5)
    c.rect(0, 1, 15, 1, 4)
    c.rect(0, 2, 15, 2, 3)
    for x in (2, 9):
        c.set(x, 1, 5)
    return c


def bar_floor_mid():
    c = cell()
    planks(c, 0, 15, 3, 4, 2, 2, phase=1)
    return c


def bar_ceiling():
    c = cell()
    planks(c, 0, 3, 3, 4, 2, 2, phase=1)
    c.rect(0, 4, 15, 4, 2)
    c.rect(0, 5, 15, 5, 5)
    c.rect(0, 6, 15, 13, 4)
    for x0, y in ((1, 8), (9, 10), (4, 12), (12, 7)):          # grain of the beam
        c.rect(x0, y, x0 + 4, y, 3)
    for x in (3, 11):                                           # bolts
        c.set(x, 9, 7)
        c.set(x + 1, 10, 6)
        c.set(x, 10, 6)
    c.rect(0, 14, 15, 14, 3)
    c.rect(0, 15, 15, 15, 1)
    return c


def counter_mid():
    c = cell()
    c.rect(0, 0, 15, 15, 3)
    c.rect(0, 0, 0, 15, 4)
    c.rect(3, 3, 12, 12, 4)
    c.rect(3, 3, 12, 3, 5)
    c.rect(3, 3, 3, 12, 5)
    c.rect(3, 12, 12, 12, 2)
    c.rect(12, 4, 12, 12, 2)
    c.rect(2, 2, 13, 2, 2)                                      # shadow in the frame above the panel
    c.rect(2, 2, 2, 13, 2)
    c.rect(5, 5, 10, 10, 3)                                     # the raised centre's edge
    c.rect(6, 6, 10, 10, 4)
    c.rect(6, 6, 9, 6, 5)
    c.rect(6, 6, 6, 9, 5)
    return c


def counter_top():
    c = cell()
    c.rect(0, 0, 15, 0, 8)
    c.rect(0, 1, 15, 1, 5)
    c.rect(0, 2, 15, 2, 5)
    c.rect(0, 3, 15, 3, 4)
    for x in (2, 3, 10):                                        # polish glints
        c.set(x, 1, 13)
    c.rect(0, 4, 15, 4, 1)
    c.rect(0, 5, 15, 13, 3)
    c.rect(0, 5, 15, 5, 2)
    for x in (3, 12):                                           # the rail's brackets
        c.rect(x, 6, x + 1, 8, 6)
    c.rect(0, 8, 15, 8, 8)                                      # the brass rail
    c.rect(0, 9, 15, 9, 7)
    c.rect(0, 10, 15, 10, 6)
    c.rect(0, 11, 15, 11, 1)
    c.rect(0, 12, 15, 15, 3)
    c.rect(0, 12, 0, 15, 4)
    c.rect(2, 14, 13, 14, 2)
    c.rect(2, 15, 2, 15, 2)
    return c


def counter_bottom():
    c = cell()
    c.rect(0, 0, 15, 6, 3)
    c.rect(0, 0, 0, 6, 4)
    c.rect(3, 0, 12, 3, 4)
    c.rect(3, 0, 3, 3, 5)
    c.rect(12, 0, 12, 3, 2)
    c.rect(3, 3, 12, 3, 2)
    c.rect(2, 0, 2, 4, 2)
    c.rect(2, 4, 13, 4, 2)
    for x in (4, 11):                                           # posts of the foot rail
        c.rect(x, 9, x + 1, 11, 6)
    c.rect(0, 6, 15, 6, 8)                                      # the brass foot rail
    c.rect(0, 7, 15, 7, 7)
    c.rect(0, 8, 15, 8, 6)
    c.rect(0, 9, 15, 9, 1)
    c.rect(0, 10, 15, 14, 2)
    c.rect(0, 10, 15, 10, 3)
    for x in (4, 11):
        c.rect(x, 10, x + 1, 11, 6)
    c.rect(0, 15, 15, 15, 1)
    return c


def table_top():
    c = cell()
    c.rect(0, 4, 15, 4, 5)
    c.rect(0, 5, 15, 6, 4)
    c.rect(0, 7, 15, 7, 3)
    c.rect(0, 8, 15, 8, 2)
    for x in (3, 4, 11):
        c.set(x, 4, 13)
    c.rect(6, 9, 9, 15, 4)
    c.rect(6, 9, 6, 15, 5)
    c.rect(9, 9, 9, 15, 3)
    c.rect(6, 9, 9, 9, 2)
    c.outline(1)
    return c


def table_post():
    c = cell()
    c.rect(6, 0, 9, 15, 4)
    c.rect(6, 0, 6, 15, 5)
    c.rect(9, 0, 9, 15, 3)
    for x in (5, 10):
        c.rect(x, 0, x, 15, 1)
    return c


def table_foot():
    """The post ending in a cross foot on the floor line, seen from the side."""
    c = cell()
    c.rect(6, 0, 9, 10, 4)
    c.rect(6, 0, 6, 10, 5)
    c.rect(9, 0, 9, 10, 3)
    c.rect(4, 11, 11, 11, 4)
    c.rect(2, 12, 13, 12, 4)
    c.rect(1, 13, 14, 13, 4)
    c.rect(1, 13, 7, 13, 5)
    c.set(4, 11, 5)
    c.set(2, 12, 5)
    c.set(11, 11, 3)
    c.set(13, 12, 3)
    c.rect(1, 14, 2, 14, 3)                                     # the ends of the cross touch the floor
    c.rect(13, 14, 14, 14, 2)
    c.outline(1)
    return c


def tap_tower():
    c = cell()
    for y in range(4, 14):                                      # the column
        for x, col in zip(range(6, 10), (8, 7, 7, 6)):
            c.set(x, y, col)
    c.rect(5, 2, 10, 3, 7)                                      # the cap
    c.rect(5, 2, 10, 2, 8)
    c.set(10, 3, 6)
    c.rect(6, 1, 9, 1, 8)
    c.rect(4, 14, 11, 15, 6)                                    # the foot
    c.rect(4, 14, 11, 14, 7)
    c.set(4, 14, 8)
    for side in (-1, 1):                                        # two taps, spouts down
        x0 = 5 if side < 0 else 10
        x1 = 2 if side < 0 else 13
        c.rect(min(x0, x1), 7, max(x0, x1), 7, 7)
        c.rect(min(x0, x1), 8, max(x0, x1), 8, 6)
        c.rect(x1, 9, x1, 10, 6)
        c.set(x1, 9, 7 if side < 0 else 6)
        hx = 3 if side < 0 else 12
        handle = 12 if side < 0 else 11
        c.rect(hx, 3, hx, 6, handle)
        c.set(hx, 2, handle)
        c.set(hx + (1 if side < 0 else -1), 3, handle)
    c.outline(1)
    return c


def lamp_shade():
    c = cell()
    c.rect(7, 0, 7, 2, 3)
    c.rect(8, 0, 8, 2, 2)
    rows = [(6, 9), (5, 10), (4, 11), (3, 12), (2, 13), (1, 14)]
    for i, (x0, x1) in enumerate(rows):
        y = 3 + i
        c.rect(x0, y, x1, y, 10)
        c.set(x0, y, 10)
        for x in range(x0, x1 + 1):
            if x > (x0 + x1) // 2 + 1:
                c.set(x, y, 9)
        if i < 4:
            c.set(x0 + 1, y, 10)
    c.set(6, 4, 7)
    c.set(5, 5, 7)
    c.rect(1, 9, 14, 9, 7)                                       # the brass rim
    c.rect(9, 9, 14, 9, 6)
    c.rect(5, 10, 10, 10, 13)                                    # the bulb under it
    c.rect(6, 11, 9, 11, 13)
    c.outline(1)
    return c


def lamp_cord():
    c = cell()
    c.rect(7, 0, 7, 15, 3)
    c.rect(8, 0, 8, 15, 1)
    return c


def pillar(part):
    c = cell()
    shaft = [(3, 1), (4, 5), (5, 5), (6, 4), (7, 3), (8, 4), (9, 4), (10, 3), (11, 3), (12, 2)]
    for y in range(16):
        for x, col in shaft:
            c.set(x, y, col if x not in (3, 12) else 1)
    if part == "top":
        c.rect(0, 0, 15, 0, 1)
        c.rect(0, 1, 15, 1, 5)
        c.rect(0, 2, 15, 3, 4)
        c.rect(0, 1, 0, 3, 1)
        c.rect(15, 1, 15, 3, 1)
        c.rect(0, 4, 15, 4, 1)
        c.rect(1, 5, 14, 5, 3)
        c.rect(1, 5, 1, 5, 1)
        c.rect(14, 5, 14, 5, 1)
        c.rect(2, 6, 13, 6, 4)
        c.rect(2, 6, 2, 6, 1)
        c.rect(13, 6, 13, 6, 1)
        c.rect(3, 7, 12, 7, 2)
        c.rect(3, 7, 3, 7, 1)
        c.rect(12, 7, 12, 7, 1)
    if part == "bottom":
        c.rect(1, 11, 14, 11, 4)
        c.rect(1, 12, 14, 12, 3)
        c.rect(0, 13, 15, 13, 5)
        c.rect(0, 14, 15, 14, 3)
        c.rect(0, 15, 15, 15, 1)
        c.set(1, 11, 5)
        c.rect(0, 11, 0, 12, 1)
        c.rect(15, 11, 15, 12, 1)
        c.rect(1, 10, 14, 10, 1)
        c.rect(4, 10, 11, 10, 2)
    return c


def brass_pipe():
    c = cell()
    for y, col in zip(range(5, 11), (7, 8, 7, 7, 6, 6)):
        c.rect(0, y, 15, y, col)
    for y, col in zip(range(4, 12), (7, 8, 8, 7, 7, 6, 6, 6)):   # a joint
        c.rect(12, y, 13, y, col)
        c.set(13, y, 6 if col != 8 else 7)
    c.outline(1)
    return c


def bottles(c, y_board, kinds, x0=0):
    """Bottles standing on a board whose top row is y_board; kinds: (x, shape) pairs."""
    shapes = {
        "green": (10, 9, 14, 7, 3),           # glass light, dark, a highlight, cap, width
        "amber": (15, 3, 13, 12, 3),
        "clear": (14, 3, 11, 7, 2),
        "red": (12, 2, 13, 11, 3),
    }
    for x, kind, h in kinds:
        lit, dark, hi, cap, w = shapes[kind]
        top = y_board - h
        for y in range(top + 3, y_board):                        # the body
            for dx in range(w):
                c.set(x + dx, y, lit if dx < w - 1 else dark)
            c.set(x, y, hi if y < top + 6 else lit)
        neck = x + w // 2 - (1 if w == 2 else 0)
        c.rect(neck, top + 1, neck, top + 2, lit)
        c.set(neck, top, cap)
        if kind in ("amber", "green"):
            c.rect(x, y_board - 4, x + w - 1, y_board - 3, 11)   # a label
            c.set(x + w - 1, y_board - 3, 3)


def shelf(part):
    c = cell()
    c.rect(0, 0, 15, 15, 2)
    if part == "top":
        c.rect(0, 0, 15, 0, 5)
        c.rect(0, 1, 15, 1, 4)
        c.rect(0, 2, 15, 2, 3)
        c.rect(0, 3, 15, 3, 1)
    kinds = {"top": [(1, "green", 8), (5, "amber", 6), (9, "clear", 7), (12, "red", 6)],
             "mid": [(1, "amber", 7), (5, "red", 6), (9, "green", 8), (13, "clear", 6)],
             "bottom": [(1, "clear", 6), (4, "green", 8), (8, "amber", 6), (12, "green", 7)]}[part]
    board = 12 if part != "bottom" else 9
    bottles(c, board, kinds)
    c.rect(0, board, 15, board, 5)
    c.rect(0, board + 1, 15, board + 1, 4)
    c.rect(0, board + 2, 15, board + 2, 3)
    c.rect(0, board + 3, 15, board + 3, 1)
    if part == "bottom":
        c.rect(0, 13, 15, 13, 4)
        c.rect(0, 14, 15, 14, 3)
        c.rect(0, 15, 15, 15, 1)
        c.rect(0, 12, 15, 12, 1)
        c.rect(0, 13, 15, 13, 5)
    return c


def stage_bar():
    s = Canvas(128, 64)
    cells = {1: bar_floor_top(), 2: bar_floor_mid(), 3: bar_ceiling(), 4: counter_top(), 5: counter_mid(),
             6: counter_bottom(), 7: table_top(), 8: table_post(), 9: table_foot(), 10: tap_tower(),
             11: lamp_shade(), 12: lamp_cord(), 13: pillar("top"), 14: pillar("mid"), 15: pillar("bottom"),
             16: brass_pipe(), 17: shelf("top"), 18: shelf("mid"), 19: shelf("bottom")}
    for k, c in cells.items():
        put(s, k, c)
    return s


CELLAR = [(0, 0, 0), (14, 10, 10), (54, 22, 18), (98, 40, 30), (146, 64, 44), (186, 100, 70), (90, 80, 74),
          (60, 62, 70), (104, 106, 116), (150, 152, 160), (70, 42, 20), (128, 80, 40), (80, 84, 96),
          (60, 110, 50), (255, 230, 140), (184, 104, 52)]

# which bricks are a shade darker, so that the wall is not flat; (row, brick) of a 4 x 2 grid
DARK_BRICKS = {(0, 1), (1, 0), (2, 1), (3, 1)}


def bricks(c, y0=0, y1=15):
    for y in range(y0, y1 + 1):
        row, r = (y // 4) % 4, y % 4
        off = 0 if row % 2 == 0 else 4
        for x in range(16):
            if r == 3:
                c.set(x, y, 6)
                continue
            bx = (x - off) % 16
            brick = bx // 8
            if bx % 8 == 7:
                c.set(x, y, 6)
                continue
            dark = (row, brick) in DARK_BRICKS
            col = 3 if dark else 4
            if r == 0:
                col = 4 if dark else 5
            elif bx % 8 == 6 or r == 2:
                col = 2 if dark else 3
            c.set(x, y, col)


def brick_top():
    c = cell()
    bricks(c)
    c.rect(0, 0, 15, 0, 13)
    for x in (1, 2, 5, 6, 7, 11, 12, 14):
        c.set(x, 1, 13)
    for x in (6, 12):
        c.set(x, 2, 13)
    return c


def brick_mid():
    c = cell()
    bricks(c)
    return c


def brick_bottom():
    c = cell()
    bricks(c)
    c.rect(0, 13, 15, 13, 3)
    c.rect(0, 14, 15, 14, 2)
    c.rect(0, 15, 15, 15, 1)
    c.set(10, 14, 9)
    c.set(10, 15, 9)
    return c


def barrel_end(c, transparent_top):
    cx, cy = 8.0, 8.0
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            lit = -((x + 0.5 - cx) * -0.6 + (y + 0.5 - cy) * -0.75) / 8
            if d > 8.0:
                if not (transparent_top and y < 8):
                    c.set(x, y, 1 if d < 9 else 2)
                continue
            if d > 7.2:
                col = 1
            elif d > 6.0:
                col = 9 if lit > 0.5 else 12 if lit > -0.3 else 7
            elif d > 5.4:
                col = 10
            else:
                col = 11 if lit > -0.2 else 10
                if x % 4 == 1:
                    col = 10 if col == 11 else 2
            c.set(x, y, col)
    c.rect(6, 9, 9, 10, 10)                                       # the tap hole with its plug
    c.rect(7, 9, 8, 9, 15)


def barrel_top():
    c = cell()
    barrel_end(c, True)
    return c


def barrel_mid():
    c = cell()
    barrel_end(c, False)
    return c


def barrel_bottom():
    c = cell()
    c.rect(0, 0, 15, 2, 2)
    for x in range(16):
        d = abs(x + 0.5 - 8.0)
        if d < 6:
            c.set(x, 0, 10)
    c.rect(0, 3, 15, 3, 11)                                      # the rack's beam
    c.rect(0, 4, 15, 6, 10)
    c.rect(0, 7, 15, 7, 1)
    for x0 in (1, 11):                                           # its feet
        c.rect(x0, 8, x0 + 3, 15, 10)
        c.rect(x0, 8, x0, 15, 11)
        c.rect(x0 + 3, 8, x0 + 3, 15, 2)
    c.outline(1)
    return c


def flagstones(c, face, lit, joint, top_offset=0):
    bands = [(0, 4, 0), (5, 10, 6), (11, 15, 3)]
    for y0, y1, off in bands:
        for y in range(y0, y1 + 1):
            for x in range(16):
                bx = (x + off) % 16
                if y == y1:
                    col = joint
                elif bx == 0 or bx == 9:
                    col = joint
                elif y == y0 or bx in (1, 10):
                    col = lit
                elif bx in (8, 15) or y == y1 - 1:
                    col = 7 if face != 7 else 6
                else:
                    col = face
                c.set(x, y, col)


def stone_floor_top():
    c = cell()
    flagstones(c, 8, 9, 6)
    c.rect(0, 0, 15, 0, 9)
    c.rect(0, 1, 15, 1, 8)
    return c


def stone_floor_mid():
    c = cell()
    flagstones(c, 7, 8, 1)
    return c


def copper_h():
    c = cell()
    for y, col in zip(range(5, 11), (15, 14, 15, 4, 3, 3)):
        c.rect(0, y, 15, y, col)
    for y, col in zip(range(4, 12), (15, 14, 14, 15, 15, 4, 3, 3)):
        c.rect(11, y, 12, y, col)
    c.outline(1)
    return c


def copper_v():
    c = cell()
    for x, col in zip(range(5, 11), (15, 14, 15, 4, 3, 3)):
        c.rect(x, 0, x, 15, col)
    for x, col in zip(range(4, 12), (15, 14, 14, 15, 15, 4, 3, 3)):
        c.rect(x, 11, x, 12, col)
    c.outline(1)
    return c


def valve():
    c = cell()
    for y, col in zip(range(9, 15), (15, 14, 15, 4, 3, 3)):
        c.rect(0, y, 15, y, col)
    c.rect(5, 6, 10, 15, 8)                                      # the valve body
    c.rect(5, 6, 5, 15, 9)
    c.rect(10, 6, 10, 15, 7)
    c.rect(7, 3, 8, 5, 12)                                       # the stem
    c.set(7, 3, 9)
    c.rect(2, 1, 13, 2, 4)                                       # the wheel, edge-on
    c.rect(2, 1, 13, 1, 5)
    c.rect(4, 0, 11, 0, 5)
    c.set(13, 2, 3)
    c.rect(4, 3, 11, 3, 3)
    c.rect(6, 1, 9, 2, 3)
    c.outline(1)
    return c


def crate_side(c, y0):
    """A wooden beer crate from the side, 16 high from y0."""
    c.rect(0, y0, 15, y0 + 15, 11)
    c.rect(0, y0, 15, y0, 5)                                      # the rim
    c.rect(0, y0 + 1, 15, y0 + 1, 11)
    c.rect(0, y0 + 2, 15, y0 + 2, 10)
    c.rect(0, y0 + 7, 15, y0 + 8, 2)                              # the gap between the slats
    c.rect(0, y0 + 9, 15, y0 + 9, 11)
    c.rect(0, y0 + 14, 15, y0 + 14, 10)
    c.rect(0, y0 + 15, 15, y0 + 15, 1)
    c.rect(5, y0 + 4, 10, y0 + 5, 1)                              # the handle hole
    c.rect(5, y0 + 6, 10, y0 + 6, 10)
    for x in (0, 15):                                             # the corner posts
        c.rect(x, y0, x, y0 + 15, 10 if x else 5)
    c.rect(1, y0 + 11, 4, y0 + 12, 15)                            # a brand mark
    c.rect(2, y0 + 11, 3, y0 + 11, 14)


def crate_top():
    """The top crate of a stack: shorter, the necks of its bottles standing out of it."""
    c = cell()
    crate_side(c, 4)
    c.rect(0, 3, 15, 3, 1)
    c.rect(0, 15, 15, 15, 1)
    for x in (2, 6, 10, 13):
        c.set(x, 0, 15)
        c.set(x + 1, 0, 14)
        c.rect(x, 1, x + 1, 2, 13)
        c.set(x + 1, 2, 3)
    c.outline(1)
    return c


def crate_mid():
    c = cell()
    crate_side(c, 0)
    return c


def crate_bottom():
    c = cell()
    crate_side(c, 0)
    c.rect(0, 14, 15, 14, 2)
    c.rect(0, 15, 15, 15, 1)
    return c


def cobweb():
    c = cell()
    for x1, y1 in ((15, 3), (12, 10), (4, 15), (9, 14)):
        line(c, 0, 0, x1, y1, 9)
    for r in (4.5, 8.5, 12.5):
        for a in range(0, 91, 3):
            t = math.radians(a)
            x, y = int(r * math.cos(t)), int(r * math.sin(t) * 0.95)
            if c.get(x, y) == 0:
                c.set(x, y, 7)
    return c


def hanging_bulb():
    c = cell()
    c.rect(7, 0, 7, 6, 12)
    c.rect(6, 7, 9, 8, 8)
    c.rect(6, 7, 6, 8, 9)
    c.rect(9, 7, 9, 8, 7)
    m = disk(7.5, 11.5, 3.2)
    for x, y in m:
        c.set(x, y, 14)
    for x, y in m:
        if (x + 1, y) not in m or (x, y + 1) not in m:
            c.set(x, y, 15)
    c.set(6, 10, 14)
    c.set(7, 11, 15)
    c.set(8, 11, 15)
    c.outline(1)
    return c


def stone_ceiling():
    c = cell()
    flagstones(c, 7, 8, 1)
    edge = [12, 13, 13, 14, 12, 11, 12, 13, 14, 14, 13, 12, 12, 13, 12, 12]
    for x in range(16):
        e = edge[x]
        for y in range(e, 16):
            c.set(x, y, 0)
        c.set(x, e - 1, 1)
        c.set(x, e - 2, 2 if c.get(x, e - 2) != 1 else 1)
    return c


def stage_cellar():
    s = Canvas(128, 64)
    cells = {1: brick_top(), 2: brick_mid(), 3: brick_bottom(), 4: barrel_top(), 5: barrel_mid(),
             6: barrel_bottom(), 7: stone_floor_top(), 8: stone_floor_mid(), 9: copper_h(), 10: copper_v(),
             11: valve(), 12: crate_top(), 13: crate_mid(), 14: crate_bottom(), 15: cobweb(), 16: hanging_bulb(),
             17: stone_ceiling()}
    for k, c in cells.items():
        put(s, k, c)
    return s


KITCHEN = [(0, 0, 0), (16, 18, 22), (150, 158, 170), (210, 218, 226), (246, 248, 250), (64, 70, 82),
           (120, 128, 142), (184, 192, 206), (190, 40, 34), (36, 38, 46), (70, 130, 230), (250, 190, 60),
           (184, 104, 52), (170, 210, 190), (70, 150, 70), (236, 224, 196)]


def tiles(c, y0=0, y1=15):
    for y in range(y0, y1 + 1):
        for x in range(16):
            tx, ty = x % 8, y % 8
            if tx == 7 or ty == 7:
                col = 2
            elif tx == 0 or ty == 0:
                col = 4
            elif tx == 6 or ty == 6:
                col = 3
            else:
                col = 3
            c.set(x, y, col)
    for y in range(y0, y1 + 1):
        for x in range(16):
            if x % 8 == 1 and y % 8 == 1:
                c.set(x, y, 4)


def tile_top():
    c = cell()
    tiles(c)
    c.rect(0, 0, 15, 0, 7)
    c.rect(0, 1, 15, 1, 6)
    c.rect(0, 2, 15, 2, 5)
    c.rect(0, 3, 15, 3, 2)
    return c


def tile_mid():
    c = cell()
    tiles(c)
    return c


def tile_bottom():
    c = cell()
    tiles(c)
    c.rect(0, 12, 15, 12, 7)
    c.rect(0, 13, 15, 13, 6)
    c.rect(0, 14, 15, 14, 5)
    c.rect(0, 15, 15, 15, 1)
    return c


def door(c, y0, y1, handle=True):
    c.rect(0, y0, 15, y1, 6)
    c.rect(0, y0, 0, y1, 9)
    c.rect(1, y0, 1, y1, 7)
    c.rect(15, y0, 15, y1, 5)
    if handle:
        c.rect(11, max(y0, 0), 12, min(y1, 15), 7)
        c.rect(12, max(y0, 0), 12, min(y1, 15), 5)


def worktop():
    c = cell()
    door(c, 5, 15, handle=False)
    c.rect(0, 0, 15, 0, 4)
    c.rect(0, 1, 15, 1, 7)
    c.rect(0, 2, 15, 2, 6)
    c.rect(0, 3, 15, 3, 5)
    c.rect(0, 4, 15, 4, 1)
    c.rect(1, 5, 14, 5, 7)
    c.rect(3, 8, 12, 8, 7)                                       # a drawer handle
    c.rect(3, 9, 12, 9, 5)
    c.rect(0, 12, 15, 12, 9)
    c.rect(1, 13, 14, 13, 7)
    return c


def cabinet_mid():
    c = cell()
    door(c, 0, 15)
    c.rect(11, 0, 12, 1, 6)
    c.rect(11, 14, 12, 15, 6)
    c.set(11, 2, 4)
    return c


def cabinet_bottom():
    c = cell()
    door(c, 0, 9)
    c.rect(11, 0, 12, 1, 6)
    c.rect(11, 6, 12, 9, 6)
    c.rect(0, 9, 15, 9, 5)
    c.rect(0, 10, 15, 14, 9)
    c.rect(0, 10, 15, 10, 1)
    c.rect(0, 15, 15, 15, 1)
    return c


def stove_top():
    c = cell()
    c.rect(0, 10, 15, 10, 7)
    c.rect(0, 11, 15, 11, 6)
    c.rect(0, 12, 15, 14, 9)
    c.rect(0, 15, 15, 15, 5)
    for x in (3, 12):                                            # knobs
        c.rect(x, 12, x + 1, 13, 8)
        c.set(x, 12, 4)
    c.rect(1, 9, 5, 9, 5)                                        # burner left, its flames
    for x in (1, 3, 5):
        c.set(x, 8, 10)
    c.set(2, 7, 10)
    c.set(4, 7, 10)
    c.set(3, 7, 11)
    c.set(2, 8, 11)
    c.set(4, 8, 11)
    c.rect(8, 9, 15, 9, 5)                                       # burner right, a pot on it
    c.set(8, 8, 10)
    c.set(15, 8, 10)
    c.rect(9, 3, 14, 8, 6)
    c.rect(9, 3, 9, 8, 7)
    c.rect(10, 3, 10, 8, 4)
    c.rect(14, 3, 14, 8, 5)
    c.rect(8, 3, 15, 3, 7)                                       # the lid
    c.rect(11, 2, 12, 2, 5)
    c.set(15, 4, 5)
    c.set(8, 4, 5)
    tmp = Canvas(16, 16)
    tmp.px = [r[:] for r in c.px]
    tmp.outline(1)
    for y in range(10, 16):
        for x in range(16):
            tmp.px[y][x] = c.px[y][x]
    return tmp


def oven():
    c = cell()
    c.rect(0, 0, 15, 15, 6)
    c.rect(0, 0, 0, 15, 9)
    c.rect(1, 0, 1, 15, 7)
    c.rect(15, 0, 15, 15, 5)
    c.rect(2, 0, 14, 0, 5)
    for x in (4, 8, 12):
        c.rect(x, 1, x + 1, 2, 8)
        c.set(x, 1, 4)
    c.rect(3, 4, 12, 4, 7)                                       # the handle
    c.rect(3, 5, 12, 5, 5)
    c.rect(3, 7, 12, 14, 1)                                      # the window
    c.rect(4, 8, 11, 13, 9)
    c.rect(4, 12, 11, 13, 11)
    c.rect(5, 11, 10, 11, 12)
    for i in range(3):
        c.set(5 + i, 10 - i, 7)
    return c


def hood(part):
    c = cell()
    c.rect(0, 0, 15, 15, 6)
    c.rect(0, 0, 1, 15, 7)
    c.rect(14, 0, 15, 15, 5)
    c.rect(7, 0, 7, 15, 5)
    c.rect(8, 0, 8, 15, 7)
    if part == "bottom":
        c.rect(0, 8, 15, 8, 7)
        c.rect(0, 9, 15, 9, 6)
        c.rect(0, 10, 15, 10, 5)
        c.rect(0, 11, 15, 14, 9)
        for x in range(1, 16, 2):
            c.rect(x, 12, x, 13, 5)
        c.rect(0, 15, 15, 15, 1)
    return c


def hanging_pans():
    c = cell()
    c.rect(0, 0, 15, 0, 7)
    c.rect(0, 1, 15, 1, 5)
    c.rect(4, 2, 4, 3, 5)                                        # hooks
    c.rect(11, 2, 11, 3, 5)
    c.rect(4, 4, 4, 7, 12)                                       # the pan: handle and body
    pan = disk(4.5, 11.5, 3.6)
    ball(c, pan, 4.5, 11.5, 3.6, [5, 12, 12, 15])
    for x, y in disk(4.5, 11.5, 2.2):
        c.set(x, y, 9)
    c.set(3, 10, 5)
    c.rect(11, 4, 11, 10, 7)                                     # the ladle
    bowl = disk(11.5, 12.5, 2.2)
    ball(c, bowl, 11.5, 12.5, 2.2, [5, 6, 7, 4])
    tmp = Canvas(16, 16)
    tmp.px = [r[:] for r in c.px]
    tmp.rect(0, 0, 15, 1, 0)
    tmp.outline(1)
    tmp.rect(0, 0, 15, 0, 7)
    tmp.rect(0, 1, 15, 1, 5)
    tmp.rect(0, 2, 15, 2, 1)
    tmp.rect(4, 2, 4, 3, 5)
    tmp.rect(11, 2, 11, 3, 5)
    return tmp


def jar_shelf():
    c = cell()
    tiles(c)
    for x0, fill, lid in ((1, 14, 12), (6, 8, 6), (11, 15, 12)):
        c.rect(x0, 4, x0 + 3, 4, lid)
        c.set(x0 + 3, 4, 5)
        c.rect(x0, 5, x0 + 3, 11, 13)
        c.rect(x0, 7, x0 + 3, 11, fill)
        c.rect(x0, 5, x0, 10, 4)
        c.rect(x0 + 3, 5, x0 + 3, 11, 2)
        c.rect(x0 - 1, 4, x0 - 1, 11, 1)
        c.rect(x0 + 4, 4, x0 + 4, 11, 1)
        c.rect(x0, 3, x0 + 3, 3, 1)
    c.rect(0, 12, 15, 12, 7)
    c.rect(0, 13, 15, 13, 6)
    c.rect(0, 14, 15, 14, 5)
    c.rect(0, 15, 15, 15, 2)
    return c


def fridge(part):
    c = cell()
    c.rect(0, 0, 15, 15, 15)
    c.rect(0, 0, 0, 15, 1)
    c.rect(15, 0, 15, 15, 1)
    c.rect(1, 0, 1, 15, 4)
    c.rect(13, 0, 14, 15, 2)
    c.rect(11, 0, 11, 15, 7)                                     # the handle
    c.rect(12, 0, 12, 15, 5)
    if part == "top":
        c.rect(0, 0, 15, 0, 1)
        c.rect(1, 1, 14, 1, 4)
        c.set(0, 0, 0)
        c.set(15, 0, 0)
        c.set(1, 1, 1)
        c.set(14, 1, 1)
        c.rect(11, 1, 12, 4, 15)
        c.rect(11, 1, 12, 1, 4)
        c.rect(1, 12, 14, 12, 1)                                 # the freezer's door
        c.rect(1, 13, 14, 13, 2)
        c.rect(3, 4, 6, 5, 8)                                    # a badge
        c.rect(3, 4, 6, 4, 4)
    if part == "bottom":
        c.rect(11, 8, 12, 11, 15)
        c.rect(1, 11, 14, 11, 1)
        c.rect(1, 12, 14, 14, 9)
        for x in range(2, 14, 2):
            c.set(x, 13, 5)
        c.rect(0, 15, 15, 15, 1)
    return c


def stage_kitchen():
    s = Canvas(128, 64)
    cells = {1: tile_top(), 2: tile_mid(), 3: tile_bottom(), 4: worktop(), 5: cabinet_mid(), 6: cabinet_bottom(),
             7: stove_top(), 8: oven(), 9: hood("bottom"), 10: hood("mid"), 11: hanging_pans(), 12: jar_shelf(),
             13: fridge("top"), 14: fridge("mid"), 15: fridge("bottom")}
    for k, c in cells.items():
        put(s, k, c)
    return s


# =================================================================================================
# 4. The parallax walls: a picture of 256 x 224 per room, cut into cells, each distinct cell once
# =================================================================================================
def cut(picture):
    """The distinct cells of the picture (a mirrored copy counts as the same) and the pattern."""
    cells, pattern = [], []
    for cy in range(14):
        row = []
        for cx in range(16):
            px = tuple(tuple(picture.px[16 * cy + y][16 * cx:16 * cx + 16]) for y in range(16))
            if any(0 in r for r in px):
                raise ValueError(f"cell ({cx}, {cy}) of a wall uses colour 0")
            mirror = tuple(tuple(reversed(r)) for r in px)
            if px in cells:
                row.append(cells.index(px))
            elif mirror in cells:
                row.append(cells.index(mirror) | 0x80)
            else:
                cells.append(px)
                row.append(len(cells) - 1)
        pattern.append(row)
    if len(cells) > 32:
        raise ValueError(f"a wall has {len(cells)} distinct cells, more than 32")
    s = Canvas(128, 64)
    for k, px in enumerate(cells):
        for y in range(16):
            for x in range(16):
                s.set(16 * (k % 8) + x, 16 * (k // 8) + y, px[y][x])
    return s, pattern, len(cells)


WALLBAR = [(0, 0, 0), (14, 9, 7), (34, 20, 12), (52, 32, 18), (74, 46, 26), (98, 64, 36), (14, 32, 24),
           (20, 42, 31), (28, 54, 40), (40, 70, 52), (84, 66, 30), (130, 104, 50), (164, 150, 116),
           (112, 66, 26), (92, 30, 26), (70, 88, 100)]


def wallpaper(c, x0, y0, x1, y1):
    """Dark green wallpaper: stripes and a small lozenge every 16 pixels."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            u, v = x % 16, y % 16
            col = 7 if u < 3 or u > 12 else 6
            d = abs(u - 7.5) + abs(v - 7.5)
            if d < 2.6:
                col = 8
            elif 3.4 < d < 4.6 and (u + v) % 2 == 0:
                col = 8
            if u in (3, 12):
                col = 8 if v % 2 == 0 else 7
            c.set(x, y, col)


def framed_picture(c, x0, y0, w, h):
    """A mountain landscape in a gilt frame, a shadow on its lower right."""
    c.rect(x0 + 2, y0 + 2, x0 + w + 1, y0 + h + 1, 1)            # shadow on the wall
    c.rect(x0, y0, x0 + w - 1, y0 + h - 1, 11)
    c.rect(x0, y0, x0 + w - 1, y0, 12)
    c.rect(x0, y0, x0, y0 + h - 1, 12)
    c.rect(x0 + w - 1, y0 + 1, x0 + w - 1, y0 + h - 1, 10)
    c.rect(x0 + 1, y0 + h - 1, x0 + w - 1, y0 + h - 1, 10)
    c.rect(x0 + 3, y0 + 3, x0 + w - 4, y0 + h - 4, 1)
    ix0, iy0, ix1, iy1 = x0 + 4, y0 + 4, x0 + w - 5, y0 + h - 5
    for y in range(iy0, iy1 + 1):                                 # sky
        for x in range(ix0, ix1 + 1):
            c.set(x, y, 15 if y < iy0 + 9 else 12 if y == iy0 + 9 else 15)
    peaks = [(ix0 + 8, iy0 + 5, 11), (ix0 + 22, iy0 + 2, 14), (ix0 + 34, iy0 + 7, 10)]
    for y in range(iy0, iy1 + 1):
        for x in range(ix0, ix1 + 1):
            for px, py, r in peaks:
                if y >= py and abs(x - px) <= (y - py) * 1.2:
                    snow = y < py + 4
                    lit = x <= px
                    c.set(x, y, 12 if snow and lit else 5 if snow else 4 if lit else 3)
    for y in range(iy1 - 5, iy1 + 1):                             # the meadow and a lake
        for x in range(ix0, ix1 + 1):
            c.set(x, y, 9 if y < iy1 - 3 else 8)
    c.rect(ix0 + 14, iy1 - 2, ix0 + 26, iy1 - 1, 15)
    c.rect(ix0 + 15, iy1 - 2, ix0 + 18, iy1 - 2, 12)


def wall_clock(c, cx, cy):
    for y in range(cy - 16, cy + 16):
        for x in range(cx - 16, cx + 16):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            sd = math.hypot(x + 0.5 - cx - 2, y + 0.5 - cy - 2)
            if d <= 13:
                lit = (x - cx) + (y - cy) < 0
                c.set(x, y, 1 if d > 12.4 else (5 if lit else 3) if d > 10.6 else 2 if d > 9.8 else 12)
            elif sd <= 13:
                c.set(x, y, 1)
    for h in range(12):                                            # hour marks
        a = math.radians(h * 30)
        x, y = cx + math.sin(a) * 8.2, cy - math.cos(a) * 8.2
        c.set(int(math.floor(x)), int(math.floor(y)), 3 if h % 3 else 1)
    line(c, cx, cy, cx, cy - 7, 1)                                 # ten past ten: closing time soon
    line(c, cx, cy, cx - 5, cy - 3, 1)
    c.set(cx, cy, 14)


def shelf_bottles(c, x0, x1, y_board):
    shapes = [(4, 13, 13, 3), (3, 8, 9, 12), (5, 14, 14, 9), (3, 12, 12, 8), (4, 9, 8, 13), (3, 13, 5, 11),
              (5, 15, 15, 7), (3, 14, 14, 10)]
    x = x0 + 3
    n = 0
    while x < x1 - 6:
        w, lit, dark, h = shapes[n % len(shapes)]
        top = y_board - h
        for y in range(top + 3, y_board):
            for dx in range(w):
                c.set(x + dx, y, lit if dx == 0 else dark if dx == w - 1 else lit if dx < w // 2 else dark)
        nx = x + w // 2
        c.rect(nx, top, nx, top + 2, dark)
        c.set(nx, top, 12 if n % 3 == 0 else 10)
        if w >= 4 and h > 9:
            c.rect(x, y_board - 5, x + w - 1, y_board - 4, 12)
        x += w + 2 + (n % 2)
        n += 1
    c.rect(x0, y_board, x1, y_board, 5)                            # the board
    c.rect(x0, y_board + 1, x1, y_board + 2, 4)
    c.rect(x0, y_board + 3, x1, y_board + 3, 1)
    c.rect(x0 + 2, y_board + 4, x1 + 2, y_board + 4, 1)
    for bx in (x0 + 4, x1 - 6):                                     # brackets
        for i in range(6):
            c.rect(bx, y_board + 4 + i, bx + 5 - i, y_board + 4 + i, 3 if i else 4)
            c.set(bx + 5 - i, y_board + 4 + i, 1)


def wainscot(c, y0):
    """Dark panelling from y0 to the bottom, panels 32 wide."""
    c.rect(0, y0, 255, y0 + 1, 5)                                   # the dado rail
    c.rect(0, y0 + 2, 255, y0 + 3, 4)
    c.rect(0, y0 + 4, 255, y0 + 4, 1)
    for x in range(256):
        for y in range(y0 + 5, 208):
            u, v = x % 32, y - (y0 + 5)
            col = 3
            if 4 <= u <= 27 and 4 <= v <= 39:
                col = 4
                if u == 4 or v == 4:
                    col = 5
                elif u == 27 or v == 39:
                    col = 2
                elif 7 <= u <= 24 and 7 <= v <= 36:
                    col = 4 if (u + v * 0) % 6 else 3
                    if u == 7 or v == 7:
                        col = 3
                    elif u == 24 or v == 36:
                        col = 5
            elif u == 0:
                col = 2
            c.set(x, y, col)
    c.rect(0, 208, 255, 208, 5)                                     # the skirting board
    c.rect(0, 209, 255, 219, 3)
    c.rect(0, 220, 255, 223, 1)


def wall_bar():
    c = Canvas(256, 224)
    wallpaper(c, 0, 0, 255, 223)
    framed_picture(c, 16 + 4, 48 + 4, 48 - 8, 32 - 6)
    wall_clock(c, 13 * 16 + 16, 3 * 16 + 16)
    shelf_bottles(c, 6 * 16, 11 * 16 - 1, 5 * 16 + 12)
    wainscot(c, 9 * 16 + 8)
    return c


WALLCELLAR = [(0, 0, 0), (6, 6, 10), (16, 16, 22), (30, 30, 38), (44, 44, 54), (60, 60, 70), (78, 78, 88),
              (22, 20, 24), (34, 22, 14), (54, 36, 22), (76, 52, 30), (48, 52, 60), (72, 76, 84),
              (56, 34, 26), (78, 50, 36), (112, 94, 56)]


def rough_stones(c, x0, y0, x1, y1):
    """Rough stone in a pattern 32 x 32 that is its own mirror image (so that its cells repeat
    mirrored), lit from above. The joints are given for the left half, folded at x = 16."""
    rows = [(0, 6, [4]), (7, 15, [9]), (16, 23, [2, 13]), (24, 31, [6])]
    for y in range(y0, y1 + 1):
        v = y % 32
        for x in range(x0, x1 + 1):
            u = x % 32
            uu = u if u < 16 else 31 - u
            for ry0, ry1, joints in rows:
                if ry0 <= v <= ry1:
                    break
            if uu in joints or v == ry1:
                col = 2
            elif v == ry0:
                col = 5 if (uu - 1) not in joints and (uu + 1) not in joints else 4
            elif v == ry1 - 1:
                col = 3
            else:
                col = 4
            c.set(x, y, col)


def arch(c, x0, x1, y_spring, rise):
    """A segmental arch from x0 to x1, springing at y_spring, rising by rise; dark inside."""
    cx = (x0 + x1 + 1) / 2
    half = (x1 + 1 - x0) / 2
    r = (half * half + rise * rise) / (2 * rise)
    cy = y_spring - rise + r
    for y in range(y_spring - rise - 8, y_spring + 1):
        for x in range(x0 - 8, x1 + 9):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if y >= y_spring:
                continue
            if d <= r:
                c.set(x, y, 2 if d > r - 3 else 1)
            elif d <= r + 7:
                ang = math.degrees(math.atan2(x + 0.5 - cx, -(y + 0.5 - cy)))
                k = int((ang + 90) // 9)
                edge = abs(((ang + 90) % 9)) < 1.2
                col = 2 if edge else (6 if d <= r + 1.5 else 5 if (k % 2) else 4)
                if d > r + 6:
                    col = 2
                c.set(x, y, col)


def far_barrels(c, x0, x1, y):
    """A row of small barrel ends, far away, centres every 16 pixels."""
    for bx in range(x0, x1 + 1, 16):
        cx, cy = bx + 8, y + 8
        for yy in range(y, y + 15):
            for xx in range(bx, bx + 16):
                d = math.hypot(xx + 0.5 - cx, yy + 0.5 - cy)
                if d <= 5.6:
                    lit = (xx - cx) + (yy - cy) < -2
                    col = 1 if d > 5.0 else 11 if d > 4.0 else (9 if lit else 8)
                    c.set(xx, yy, col)
                else:
                    c.set(xx, yy, 1)


def wall_cellar():
    c = Canvas(256, 224)
    rough_stones(c, 0, 0, 255, 223)
    for ax in (16, 144):
        c.rect(ax, 96, ax + 95, 207, 1)
        arch(c, ax, ax + 95, 96, 26)
        c.rect(ax, 96, ax + 95, 127, 1)
        for y in range(96, 128, 4):                                 # the dark fades towards the back
            c.rect(ax, y, ax + 95, y, 2 if y < 100 else 1)
        far_barrels(c, ax, ax + 95, 128)
        c.rect(ax, 143, ax + 95, 143, 9)                            # the rack
        c.rect(ax, 144, ax + 95, 146, 8)
        c.rect(ax, 147, ax + 95, 159, 1)
        for lx in range(ax + 4, ax + 96, 16):
            c.rect(lx, 147, lx + 1, 159, 8)
        far_barrels(c, ax, ax + 95, 160)
        c.rect(ax, 175, ax + 95, 175, 9)
        c.rect(ax, 176, ax + 95, 178, 8)
        c.rect(ax, 179, ax + 95, 207, 1)
        for lx in range(ax + 4, ax + 96, 16):
            c.rect(lx, 179, lx + 1, 191, 8)
        c.rect(ax, 192, ax + 95, 207, 1)
        mx = ax + 48                                                # a dim lamp under the arch
        c.rect(mx - 1, 76, mx, 97, 11)
        c.rect(mx - 2, 98, mx + 1, 99, 12)
        for x, y in disk(mx, 102.5, 6.0):
            if c.get(x, y) in (1, 2):
                c.set(x, y, 13)
        for x, y in disk(mx, 102.5, 4.2):
            if c.get(x, y) == 13:
                c.set(x, y, 14)
        for x, y in disk(mx, 102.0, 2.4):
            c.set(x, y, 15)
    for y in range(208, 224):                                       # the floor
        for x in range(256):
            c.set(x, y, 3 if y == 208 else 2 if (x % 32 == 0 or y == 216) else 7)
    return c


WALLKITCHEN = [(0, 0, 0), (20, 22, 28), (56, 62, 72), (80, 86, 96), (98, 106, 116), (120, 128, 138),
               (50, 56, 66), (84, 92, 104), (120, 128, 140), (12, 18, 38), (24, 34, 62), (196, 196, 164),
               (54, 92, 60), (82, 122, 78), (118, 74, 44), (146, 136, 112)]


def wall_tiles(c, x0, y0, x1, y1, face=4, lit=5, grout=3, shade=3, size=16):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            u, v = x % size, y % size
            col = grout if (u == size - 1 or v == size - 1) else lit if (u == 0 or v == 0) else \
                shade if (u == size - 2 or v == size - 2) else face
            c.set(x, y, col)


def window(c, x0, y0, w, h):
    c.rect(x0, y0, x0 + w - 1, y0 + h - 1, 6)
    c.rect(x0 + 1, y0 + 1, x0 + w - 2, y0 + h - 2, 8)
    c.rect(x0 + 2, y0 + 2, x0 + w - 3, y0 + h - 3, 7)
    ix0, iy0, ix1, iy1 = x0 + 4, y0 + 4, x0 + w - 5, y0 + h - 5
    for y in range(iy0, iy1 + 1):
        for x in range(ix0, ix1 + 1):
            c.set(x, y, 9 if y < iy0 + (iy1 - iy0) * 0.55 else 10)
    for sx, sy in ((ix0 + 5, iy0 + 4), (ix0 + 30, iy0 + 9), (ix0 + 12, iy0 + 15), (ix0 + 36, iy0 + 3),
                   (ix0 + 3, iy0 + 22)):
        c.set(sx, sy, 11)
    for x, y in disk(ix0 + 27.5, iy0 + 9.5, 4.2):                   # the moon
        c.set(x, y, 11)
    for x, y in disk(ix0 + 29.5, iy0 + 8.0, 3.6):
        c.set(x, y, 9)
    roofs = [(0, 8), (6, 6), (12, 10), (19, 4), (24, 7), (31, 9), (36, 5)]
    for i, (rx, rh) in enumerate(roofs):
        nx = roofs[i + 1][0] if i + 1 < len(roofs) else ix1 - ix0 + 1
        for x in range(ix0 + rx, min(ix0 + nx, ix1 + 1)):
            for y in range(iy1 - rh, iy1 + 1):
                c.set(x, y, 1)
    c.set(ix0 + 8, iy1 - 4, 15)                                     # a lit window over there
    c.set(ix0 + 27, iy1 - 3, 15)
    mx = x0 + w // 2                                                # the bars
    c.rect(mx - 1, iy0, mx, iy1, 7)
    c.rect(mx - 1, iy0, mx - 1, iy1, 8)
    my = y0 + h // 2
    c.rect(ix0, my - 1, ix1, my, 7)
    c.rect(ix0, my - 1, ix1, my - 1, 8)
    c.rect(x0 - 2, y0 + h, x0 + w + 1, y0 + h + 1, 8)                # the sill
    c.rect(x0 - 2, y0 + h + 2, x0 + w + 1, y0 + h + 3, 6)
    c.rect(x0, y0 + h + 4, x0 + w + 1, y0 + h + 4, 2)
    for i, (px, ph) in enumerate(((x0 + 4, 9), (x0 + w - 12, 7))):  # herbs on the sill
        c.rect(px, y0 + h - 4, px + 6, y0 + h - 1, 14)
        c.rect(px, y0 + h - 4, px + 6, y0 + h - 4, 15)
        for k in range(ph):
            c.set(px + 1 + (k % 5), y0 + h - 5 - (k * 7) % ph // 2 - (k % 3), 13 if k % 2 else 12)
            c.set(px + 3, y0 + h - 5 - k // 2, 12)


def kitchen_shelf(c, x0, x1, y_board):
    items = [("pot", 12), ("jar", 3), ("jar", 2), ("pot", 9), ("jar", 3), ("jug", 9)]
    x = x0 + 3
    n = 0
    while x < x1 - 10:
        kind, h = items[n % len(items)]
        if kind == "pot":
            c.rect(x, y_board - 8, x + 9, y_board - 1, 14 if n % 2 == 0 else 7)
            c.rect(x, y_board - 8, x + 9, y_board - 8, 15 if n % 2 == 0 else 8)
            c.rect(x + 9, y_board - 7, x + 9, y_board - 1, 6 if n % 2 else 1)
            c.rect(x - 1, y_board - 7, x - 1, y_board - 6, 6)
            c.rect(x + 10, y_board - 7, x + 10, y_board - 6, 6)
            c.rect(x + 3, y_board - 10, x + 6, y_board - 9, 6)
            x += 13
        elif kind == "jar":
            fill = [12, 15, 14][n % 3]
            c.rect(x, y_board - 9, x + 4, y_board - 9, 6)
            c.rect(x, y_board - 8, x + 4, y_board - 1, 5)
            c.rect(x, y_board - 5, x + 4, y_board - 1, fill)
            c.rect(x + 4, y_board - 8, x + 4, y_board - 1, 3)
            c.rect(x, y_board - 8, x, y_board - 4, 11 if n % 2 else 5)
            x += 7
        else:
            c.rect(x, y_board - 9, x + 5, y_board - 1, 15)
            c.rect(x + 5, y_board - 9, x + 5, y_board - 1, 3)
            c.rect(x + 6, y_board - 7, x + 7, y_board - 4, 15)
            c.rect(x, y_board - 9, x + 5, y_board - 9, 11)
            x += 10
        n += 1
    c.rect(x0, y_board, x1, y_board, 8)
    c.rect(x0, y_board + 1, x1, y_board + 2, 7)
    c.rect(x0, y_board + 3, x1, y_board + 3, 1)
    c.rect(x0 + 1, y_board + 4, x1 + 1, y_board + 4, 2)
    for bx in (x0 + 5, x1 - 8):
        for i in range(5):
            c.rect(bx, y_board + 4 + i, bx + 4 - i, y_board + 4 + i, 6)
            c.set(bx + 4 - i, y_board + 4 + i, 1)


def wall_kitchen():
    c = Canvas(256, 224)
    wall_tiles(c, 0, 0, 255, 223)
    window(c, 2 * 16 + 6, 3 * 16 + 4, 48 - 12 + 16, 48 - 8)
    kitchen_shelf(c, 8 * 16, 13 * 16 - 1, 4 * 16 + 12)
    kitchen_shelf(c, 9 * 16, 14 * 16 - 1, 7 * 16 + 12)
    for y in range(9 * 16, 9 * 16 + 16):                            # a green border of tiles
        for x in range(256):
            u, v = x % 8, y % 8
            c.set(x, y, 2 if (u == 7 or v == 7) else 13 if (u == 0 or v == 0) else 12)
    wall_tiles(c, 0, 160, 255, 207, face=3, lit=4, grout=2, shade=2, size=8)
    c.rect(0, 208, 255, 223, 6)
    c.rect(0, 208, 255, 208, 8)
    c.rect(0, 209, 255, 209, 7)
    c.rect(0, 222, 255, 223, 1)
    return c


# =================================================================================================
# 5. hud.png: the status line, 2 bits a pixel
# =================================================================================================
HUD = [(0, 0, 0), (40, 24, 14), (246, 232, 196), (236, 160, 40)]

HUD_TILES = {
    3: ["...22...", "...22...", "...22...", "..2..2..", ".2....2.", ".2....2.", ".2....2.", "..2222.."],
    4: ["...22...", "...33...", "...33...", "..3333..", ".333333.", ".333333.", ".333333.", "..3333.."],
    5: ["........", "....222.", "....2...", "....2...", "....2...", "....2...", "....222.", "........"],
    6: ["........", ".222....", "...2....", "...2....", "...2....", "...2....", ".222....", "........"],
    7: ["........", "..2222..", ".2333322", "23333333", "23333333", ".2333322", "..2222..", "........"],
    8: ["........", "........", "22222222", "........", "........", "22222222", "........", "........"],
    9: ["........", "........", "22222222", "2222....", "2222....", "22222222", "........", "........"],
    10: ["........", "........", "22222222", "22222222", "22222222", "22222222", "........", "........"],
    11: ["3...3...", ".3.3.3..", "2.323...", "2333333.", "2.323...", ".3.3.3..", "3...3...", "........"],
    12: ["..333...", "...3....", "...2....", ".22222..", "2222222.", ".222222.", ".....22.", ".....3.."],
}

HUD_GLYPHS = dict(FONT)
HUD_GLYPHS.update({
    ",": ["00000", "00000", "00000", "00000", "00110", "00100", "01000"],
    "€": ["00111", "01000", "11110", "01000", "11110", "01000", "00111"],
    "Ü": ["01010", "00000", "10001", "10001", "10001", "10001", "01110"],
    "Ä": ["01010", "00000", "01110", "10001", "11111", "10001", "10001"],
    "Ö": ["01010", "00000", "01110", "10001", "10001", "10001", "01110"],
    "?": ["01110", "10001", "00001", "00110", "00100", "00000", "00100"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    "'": ["00100", "00100", "01000", "00000", "00000", "00000", "00000"],
})
HUD_FONT = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.,€ÜÄÖ™?/'"
TRADEMARK = ["111.1...", ".1..11.1", ".1..1.11", "........"]    # a tiny TM, three rows


def hud():
    s = Canvas(128, 64)

    def tile(t, rows, bg=1):
        ox, oy = 8 * (t % 16), 8 * (t // 16)
        for y in range(8):
            for x in range(8):
                s.set(ox + x, oy + y, bg)
        for y, r in enumerate(rows):
            for x, ch in enumerate(r):
                if ch != ".":
                    s.set(ox + x, oy + y, int(ch))
    tile(1, [])
    tile(2, ["", "", "", "", "", "", "", "33333333"])
    for t, rows in HUD_TILES.items():
        tile(t, rows)
    for n, ch in enumerate(HUD_FONT):
        colour = "3" if ch == "€" else "2"
        if ch == "™":
            rows = [r.replace("1", colour) for r in TRADEMARK]
        else:
            g = HUD_GLYPHS.get(ch, ["00000"] * 7)
            rows = ["." + r.replace("0", ".").replace("1", colour) + ".." for r in g]
        tile(32 + n, rows)
    return s


def write_walls_c(path, patterns):
    lines = ["/* Written by dev/art/ertuep.py. Do not edit.", "",
             "   The parallax walls: the cell of wall_<room>.png for each cell of the 256 x 224 picture,",
             "   bit 7 set when the stored cell is to be mirrored horizontally. */", "#include <stdint.h>", ""]
    for room, pattern in patterns:
        lines.append(f"const uint8_t WALL_{room.upper()}[14][16] = {{")
        for row in pattern:
            lines.append("    {" + ", ".join(f"0x{v:02X}" for v in row) + "},")
        lines.append("};")
        lines.append("")
    open(path, "w").write("\n".join(lines))


# =================================================================================================
# 6. The bosses of stage 1: der Pfandautomat on BG2 (boss.png, bossmap.c) and der Spielautomat
#    (sprites, rows 4 to 7 of sprites_b.png)
# =================================================================================================
BOSS = [(0, 0, 0), (16, 16, 22), (50, 54, 62), (84, 90, 102), (124, 130, 142), (166, 172, 184),
        (212, 218, 228), (248, 248, 242), (6, 6, 8), (26, 86, 46), (44, 146, 72), (120, 206, 126),
        (124, 22, 20), (226, 44, 34), (255, 212, 92), (18, 46, 32)]

TINY = {  # a 3 x 5 font for the plate
    "A": ["010", "101", "111", "101", "101"], "B": ["110", "101", "110", "101", "110"],
    "C": ["011", "100", "100", "100", "011"], "E": ["111", "100", "110", "100", "111"],
    "F": ["111", "100", "110", "100", "100"], "H": ["101", "101", "111", "101", "101"],
    "I": ["111", "010", "010", "010", "111"], "L": ["100", "100", "100", "100", "111"],
    "N": ["101", "111", "111", "111", "101"], "R": ["110", "101", "110", "101", "101"],
    "S": ["011", "100", "010", "001", "110"], "T": ["111", "010", "010", "010", "010"],
    "U": ["101", "101", "101", "101", "111"], "Z": ["111", "001", "010", "100", "111"],
}


def tiny_text(c, x, y, text, col):
    for ch in text:
        for gy, row in enumerate(TINY.get(ch, ["000"] * 5)):
            for gx, bit in enumerate(row):
                if bit == "1":
                    c.set(x + gx, y + gy, col)
        x += 4


def boss_picture(state):
    """The bottle-return machine, 160 x 192; state 0 shutter closed, 1 open, 2 broken."""
    c = Canvas(160, 192)
    # --- the body: brushed steel, light from the upper left ---
    c.rect(0, 41, 159, 177, 4)
    for y in range(44, 176):
        for x in range(2, 157):
            u, v = x % 16, y % 16
            if v == 9 and 3 <= u <= 12:
                c.set(x, y, 5)                                   # brushed lines, symmetric in a cell
    c.rect(0, 41, 159, 41, 1)
    c.rect(1, 42, 158, 42, 6)
    c.rect(1, 43, 158, 43, 5)
    c.rect(0, 41, 0, 177, 1)
    c.rect(1, 42, 1, 177, 6)
    c.rect(2, 43, 2, 177, 5)
    c.rect(3, 45, 156, 45, 11)                                     # the brand's green stripe
    c.rect(3, 46, 156, 46, 10)
    c.rect(3, 47, 156, 47, 9)
    c.rect(157, 43, 157, 177, 3)
    c.rect(158, 42, 158, 177, 2)
    c.rect(159, 41, 159, 177, 1)
    # --- the light box on top with PFAND and a bottle, two warning lamps on it ---
    c.rect(16, 10, 143, 40, 1)
    c.rect(17, 11, 142, 39, 3)
    c.rect(17, 11, 142, 11, 5)
    c.rect(17, 11, 17, 39, 5)
    c.rect(142, 12, 142, 39, 2)
    c.rect(18, 39, 142, 39, 2)
    c.rect(19, 13, 140, 37, 9)
    c.rect(20, 14, 139, 36, 7)
    word = "PFAND"
    x0 = 52
    mask = set()
    for n, ch in enumerate(word):
        for gy, row in enumerate(FONT[ch]):
            for gx, bit in enumerate(row):
                if bit == "1":
                    for sy in range(2):
                        for sx in range(2):
                            mask.add((x0 + n * 13 + gx * 2 + sx, 16 + gy * 2 + sy))
    mask |= {(x + 1, y + 1) for x, y in mask}                     # bold: strokes of three pixels
    shade_mask(c, mask, 10, 10, 9)
    for x, y in mask:                                              # a highlight inside each stroke
        if (x - 1, y) not in mask and (x, y - 1) in mask and (x + 1, y) in mask and (x, y + 1) in mask:
            c.set(x + 1, y, 11)
    bottle = ["..99..", "..aa..", "..aa..", ".9aa9.", "9abaa9", "9abaa9", "9abaa9", "9abaa9", "9aaaa9",
              "9aaaa9", "9aaaa9", ".9999."]
    c.from_text(bottle, {"9": 9, "a": 10, "b": 11}, 37, 19)
    lit = state == 2
    for lx in (24, 135):
        for x, y in disk(lx + 0.5, 9.5, 5.6):
            if y <= 10:
                c.set(x, y, 1)
        for x, y in disk(lx + 0.5, 9.5, 4.6):
            if y <= 10:
                d = math.hypot(x - lx + 1.5, y - 7)
                c.set(x, y, (14 if d < 1.6 else 13) if lit else (13 if d < 1.6 else 12))
        c.rect(lx - 6, 10, lx + 7, 10, 1)
        if lit:                                                    # the glow on the light box
            for x, y in ((lx - 6, 5), (lx + 7, 5), (lx, 1), (lx + 1, 1), (lx - 5, 2), (lx + 6, 2)):
                c.set(x, y, 14)
    # --- the display: an empty dark green LCD, cells (2..7, 3) ---
    c.rect(32, 48, 127, 63, 1)
    c.rect(33, 49, 126, 62, 2)
    c.rect(34, 50, 126, 62, 6)
    c.rect(34, 50, 125, 61, 2)
    c.rect(35, 51, 125, 61, 15)
    c.rect(35, 51, 125, 51, 9)
    # --- ventilation slits on the left ---
    for y in range(66, 128, 4):
        c.rect(9, y, 26, y, 1)
        c.rect(9, y + 1, 26, y + 1, 6)
    # --- the receipt printer on the right, its slot at (116, 104) ---
    c.rect(102, 96, 133, 109, 1)
    c.rect(103, 97, 132, 108, 3)
    c.rect(103, 97, 132, 97, 5)
    c.rect(103, 97, 103, 108, 5)
    c.rect(105, 102, 130, 104, 8)
    c.rect(105, 105, 130, 105, 6)
    c.rect(109, 103, 124, 120, 7)                                  # the receipt sticking out
    c.rect(124, 103, 124, 120, 5)
    for y in range(107, 119, 3):
        c.rect(111, y, 111 + (7 if y % 2 else 10), y, 4)
    for x in range(109, 125):
        if x % 2:
            c.set(x, 120, 4 if c.get(x, 120) == 7 else 4)
        else:
            c.set(x, 120, 7)
            c.set(x, 121, 7)
    for x in range(108, 126):
        if c.get(x, 121) == 4:
            pass
    for y in range(104, 123):
        if c.get(125, y) == 4:
            c.set(125, y, 3)
    # --- the plate: BITTE FLASCHE EINZELN EINFUEHREN ---
    c.rect(36, 129, 123, 142, 1)
    c.rect(37, 130, 122, 141, 7)
    c.rect(37, 141, 122, 141, 5)
    c.rect(122, 130, 122, 141, 5)
    for sx in (39, 119):
        c.set(sx, 132, 3)
        c.set(sx, 139, 3)
    tiny_text(c, 54, 130, "BITTE FLASCHE", 2)
    tiny_text(c, 44, 136, "EINZELN EINFUEHREN", 2)
    # --- two grilles, a crate's worth each ---
    for gx in (16, 80):
        c.rect(gx, 146, gx + 63, 173, 1)
        c.rect(gx + 1, 147, gx + 62, 172, 2)
        for x in range(gx + 2, gx + 62):
            for y in range(148, 172):
                k = (x - gx - 2) % 4
                c.set(x, y, 8 if k == 3 else 5 if k == 0 else 4 if k == 1 else 3)
        c.rect(gx + 2, 148, gx + 61, 148, 1)
        c.rect(gx + 1, 173, gx + 62, 173, 6)
        c.rect(gx + 63, 147, gx + 63, 173, 6)
    # --- the plinth on the floor ---
    c.rect(0, 176, 159, 191, 2)
    c.rect(0, 176, 159, 176, 1)
    c.rect(0, 177, 159, 177, 6)
    c.rect(0, 178, 159, 178, 4)
    c.rect(0, 179, 159, 179, 3)
    c.rect(4, 183, 155, 188, 8)
    c.rect(4, 183, 155, 183, 1)
    c.rect(0, 189, 159, 190, 3)
    c.rect(0, 191, 159, 191, 1)
    c.rect(0, 176, 0, 191, 1)
    c.rect(159, 176, 159, 191, 1)
    c.rect(1, 177, 1, 190, 5)
    # --- the intake: a round hole of 48 pixels, its centre at (64, 96) ---
    cx, cy = 64.0, 96.0
    for y in range(64, 128):
        for x in range(32, 96):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            up = (y + 0.5 - cy) / max(d, 0.01)                     # -1 at the top, symmetric
            if d <= 27.0 and d > 24.0:                             # the steel bezel
                c.set(x, y, 1 if d > 26.2 else 6 if up < -0.3 else 5 if up < 0.4 else 3)
            elif d <= 24.0 and d > 16.0:                           # the rubber gasket
                col = 2
                if d > 23.2 or d <= 16.8:
                    col = 1
                elif up < -0.55 and 19.0 < d < 21.0:
                    col = 3
                c.set(x, y, col)
            elif d <= 16.0:
                if state == 0:                                     # the shutter: slats
                    k = (y - 80) % 4
                    col = (6, 5, 4, 1)[k]
                    if d > 15.0:
                        col = 1
                    c.set(x, y, col)
                else:
                    c.set(x, y, 8)
    if state == 1:                                                 # a conveyor of rollers inside
        for y0, half in ((99, 7), (103, 10), (107, 12)):
            for x in range(int(cx - half), int(cx + half)):
                c.set(x, y0, 5)
                c.set(x, y0 + 1, 3)
        for x, y in ((56, 88), (71, 88)):
            c.set(x, y, 10)
    if state == 2:
        for x in range(48, 80):                                    # a red glow inside
            for y in range(80, 112):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - (cy + 4))
                if c.get(x, y) == 8 and math.hypot(x + 0.5 - cx, y + 0.5 - cy) < 15.5:
                    c.set(x, y, 14 if d < 5 else 13 if d < 9 else 12 if d < 13 else 8)
        for x0, y0, x1, y1 in ((52, 104, 60, 100), (66, 107, 75, 106), (58, 109, 64, 106)):
            line(c, x0, y0, x1, y1, 3)                             # smashed rollers
            line(c, x0, y0 - 1, x1, y1 - 1, 5)
        for i in range(10):                                        # a bent slat hanging in
            c.set(48 + i, 81 + i // 2, 5)
            c.set(48 + i, 82 + i // 2, 3)
        # cracks, a bent panel and sparks: only in the cells (2, 4), (5, 4) and (5, 7) around the
        # intake, so that the broken state costs few cells of its own
        for path in (((33, 65), (38, 70), (37, 74), (43, 78)), ((94, 65), (88, 70), (90, 75), (85, 79)),
                     ((94, 125), (88, 120), (90, 115), (84, 113))):
            for (ax, ay), (bx, by) in zip(path, path[1:]):
                line(c, ax, ay, bx, by, 1)
        line(c, 81, 72, 92, 66, 6)                                 # the bent edge of the front panel
        line(c, 82, 73, 93, 67, 2)
        for x, y, col in ((35, 69, 14), (34, 68, 7), (41, 66, 14), (88, 68, 14), (89, 67, 7), (60, 86, 14),
                          (61, 85, 7), (67, 84, 14), (88, 123, 14), (87, 124, 7), (92, 118, 14)):
            c.set(x, y, col)                                       # sparks
    return c


def boss_cut(pictures):
    """The distinct cells of all pictures, the empty cell first, mirrored copies stored once."""
    empty = tuple(tuple([0] * 16) for _ in range(16))
    cells, maps = [empty], []
    for pic in pictures:
        rows = []
        for cy in range(pic.h // 16):
            row = []
            for cx in range(pic.w // 16):
                px = tuple(tuple(pic.px[16 * cy + y][16 * cx:16 * cx + 16]) for y in range(16))
                mirror = tuple(tuple(reversed(r)) for r in px)
                if px in cells:
                    row.append(cells.index(px))
                elif mirror in cells:
                    row.append(cells.index(mirror) | 0x80)
                else:
                    cells.append(px)
                    row.append(len(cells) - 1)
            rows.append(row)
        maps.append(rows)
    if len(cells) > 64:
        raise ValueError(f"the boss has {len(cells)} distinct cells, more than 64")
    s = Canvas(128, 128)
    for k, px in enumerate(cells):
        for y in range(16):
            for x in range(16):
                s.set(16 * (k % 8) + x, 16 * (k // 8) + y, px[y][x])
    return s, maps


def write_bossmap_c(path, maps, maps2=None):
    lines = ["/* Written by dev/art/ertuep.py. Do not edit.", "",
             "   Der Pfandautomat: the cell of boss.png for each cell of the 160 x 192 picture in its three",
             "   states (closed, open, broken), bit 7 set when the stored cell is to be mirrored",
             "   horizontally. Cell 0 is empty. */", "#include <stdint.h>", "",
             "const uint8_t BOSS_MAP[3][12][10] = {"]
    for state in maps:
        lines.append("    {")
        for row in state:
            lines.append("        {" + ", ".join(f"0x{v:02X}" for v in row) + "},")
        lines.append("    },")
    lines += ["};", ""]
    if maps2 is not None:
        lines += ["/* Die Sudpfanne (stage 2): the cell of boss2.png for each cell of its 160 x 192 picture in",
                  "   its three states (lid closed, lid open, broken), as above. */",
                  "const uint8_t BOSS2_MAP[3][12][10] = {"]
        for state in maps2:
            lines.append("    {")
            for row in state:
                lines.append("        {" + ", ".join(f"0x{v:02X}" for v in row) + "},")
            lines.append("    },")
        lines += ["};", ""]
    open(path, "w").write("\n".join(lines))


def slot_machine(frame):
    """Der Spielautomat, 48 x 64, flying; the reel windows empty (the reels are sprites on top):
    their insides are x 5..14, 19..28 and 33..42, y 24..35 of the 48 x 64 picture."""
    c = Canvas(48, 64)
    # the cabinet: wood, lit from the upper left
    c.rect(2, 21, 45, 59, 10)
    c.rect(2, 21, 3, 59, 7)
    c.rect(44, 21, 45, 59, 9)
    c.rect(2, 58, 45, 59, 9)
    # the header: an arch of lights around a dark red panel
    for x, y in disk(24.0, 22.0, 21.4):
        if y <= 22 and 2 <= x <= 45:
            c.set(x, y, 7 if x < 14 else 10)
    for x, y in disk(24.0, 22.0, 17.6):
        if y <= 22 and 6 <= x <= 41:
            c.set(x, y, 5)
    bulbs = [(5, 18), (7, 11), (11, 6), (17, 3), (23, 2), (29, 3), (35, 6), (39, 11), (41, 18)]
    for n, (bx, by) in enumerate(bulbs):
        on = (n + frame) % 2 == 0
        c.rect(bx, by, bx + 1, by + 1, 13 if on else 4)
        c.set(bx, by, 8 if on else 13)
    seven = ["44444", "....4", "...4.", "..4..", "..4..", "..4.."]
    c.from_text(seven, {"4": 4}, 21, 9)
    c.from_text(seven, {"4": 13}, 22, 8)
    # the chrome frame around the reels, three windows with cream insides
    c.rect(3, 21, 44, 39, 11)
    c.rect(3, 21, 44, 21, 12)
    c.rect(3, 21, 3, 39, 12)
    c.rect(3, 39, 44, 39, 1)
    for wx in (4, 18, 32):
        c.rect(wx, 23, wx + 11, 36, 1)
        c.rect(wx + 1, 24, wx + 10, 35, 2)
        c.rect(wx + 1, 35, wx + 10, 35, 3)
    for x in (7, 21, 35):                                          # the buttons under the reels
        c.rect(x, 37, x + 5, 38, 4)
        c.rect(x, 37, x + 5, 37, 13 if frame == 0 else 4)
    # the payout table and the coin slot
    c.rect(6, 41, 27, 49, 9)
    c.rect(6, 41, 27, 41, 1)
    for y in (43, 45, 47):
        c.rect(8, y, 14, y, 13)
        c.rect(18, y, 25, y, 2)
    c.rect(31, 41, 41, 49, 11)
    c.rect(31, 41, 41, 41, 12)
    c.rect(31, 41, 31, 49, 12)
    c.rect(35, 43, 36, 47, 14)                                     # the slot
    c.set(37, 43, 12)
    # the payout tray with a few coins
    c.rect(6, 51, 41, 57, 1)
    c.rect(7, 52, 40, 56, 14)
    c.rect(7, 51, 40, 51, 12)
    c.rect(6, 57, 41, 57, 11)
    for x in (12, 15, 30):
        c.rect(x, 55, x + 2, 56, 13)
        c.set(x, 55, 2)
    c.rect(8, 60, 11, 61, 11)                                      # little feet
    c.rect(36, 60, 39, 61, 11)
    c.outline(1)
    return c


def reel_symbols():
    c = Canvas(16, 16)
    c.blit(art(["........",
                ".2222...",
                "1222221.",
                "1dddd11.",
                "1dddd1.1",
                "1dddd11.",
                "1dddd1..",
                ".1111..."], {**HEX, "d": 13}), 0, 0)
    c.blit(art(["........",
                ".37..37.",
                "7..78..6",
                "7..76..6",
                ".76..66.",
                "..6..6..",
                "...66...",
                "........"], {"7": 7, "3": 3, "6": 6, "8": 8}), 8, 0)
    c.blit(art(["...1....",
                "..1d1...",
                ".1ddd1..",
                ".12dd1..",
                ".1ddd1..",
                "1ddddd1.",
                "1111111.",
                "...1...."], {**HEX, "d": 13}), 0, 8)
    c.blit(art(["........",
                ".444444.",
                ".5555 4.".replace(" ", "4"),
                "....45..",
                "...45...",
                "..45....",
                "..45....",
                "........"], {"4": 4, "5": 5}), 8, 8)
    return c


def coins():
    c = Canvas(16, 16)
    c.blit(art(["..1111..",
                ".1dddd1.",
                "1d2222d1",
                "1d2dd2d1",
                "1d2222d1",
                ".1dddd1.",
                "..1111..",
                "........"], {**HEX, "d": 13}), 0, 0)
    c.blit(art(["...11...",
                "..1d21..",
                "..1d21..",
                "..1d21..",
                "..1d21..",
                "..1dd1..",
                "...11...",
                "........"], {**HEX, "d": 13}), 8, 0)
    return c


# =================================================================================================
# 7. Stage 2, das Sudhaus: the brewhouse tiles, the Mett walls (four frames of a pulse), the wall
# =================================================================================================
BREW = [(0, 0, 0), (32, 16, 10), (110, 50, 24), (170, 86, 40), (222, 134, 72), (255, 204, 146),
        (70, 76, 88), (130, 138, 150), (198, 204, 214), (250, 250, 246), (232, 196, 110), (166, 126, 60),
        (120, 52, 34), (176, 86, 56), (70, 150, 80), (10, 10, 12)]
COPPER = (2, 3, 3, 4, 5, 4, 3, 2)                                 # across a pipe of 8, lit from the top or left


def brew_floor(top):
    c = cell()
    for y in range(16):
        for x in range(16):
            u, v = x % 8, (y + (0 if top else 4)) % 8
            col = 12 if (u == 7 or v == 7) else 13
            if not top:
                col = 1 if (u == 7 or v == 7) else 12
            elif u == 0 and v != 7:
                col = 13
            c.set(x, y, col)
    if top:
        c.rect(0, 0, 15, 0, 8)
        c.rect(0, 1, 15, 1, 7)
        c.rect(0, 2, 15, 2, 6)
        c.rect(0, 3, 15, 3, 1)
        for x in (3, 11):
            c.set(x, 1, 9)
    return c


def girder():
    c = cell()
    c.rect(0, 0, 15, 9, 7)
    c.rect(0, 0, 15, 0, 6)
    c.rect(0, 9, 15, 9, 6)
    for x in (2, 10):                                             # rivets on the web
        c.set(x, 4, 8)
        c.set(x + 1, 5, 6)
        c.set(x + 1, 4, 7)
        c.set(x, 5, 7)
    c.rect(0, 10, 15, 10, 8)
    c.rect(0, 11, 15, 12, 7)
    c.rect(0, 13, 15, 13, 6)
    for x in (6, 14):
        c.set(x, 12, 9)
        c.set(x, 13, 1)
    c.rect(0, 14, 15, 14, 1)
    return c


def copper_pipe(vertical):
    c = cell()
    for i, col in enumerate(COPPER):
        if vertical:
            c.rect(4 + i, 0, 4 + i, 15, col)
        else:
            c.rect(0, 4 + i, 15, 4 + i, col)
    c.outline(1)
    return c


def copper_flange():
    c = copper_pipe(False)
    for y in range(1, 15):
        for x, col in zip(range(6, 10), (8, 7, 7, 6)):
            c.set(x, y, col)
    for y in (2, 13):
        c.set(7, y, 9)
        c.set(8, y, 6)
    c.outline(1)
    return c


def kettle_dome():
    c = cell()
    m = {(x, y) for x, y in disk(8.0, 17.0, 16.0) if 0 <= x < 16 and 0 <= y < 16}
    for x, y in m:
        nx, ny = (x + 0.5 - 8.0) / 16.0, (y + 0.5 - 17.0) / 16.0
        lum = -(nx * -0.6 + ny * -0.75) + 0.1 * (y > 11)
        c.set(x, y, 5 if lum > 0.62 and x < 7 else 4 if lum > 0.5 else 3 if lum > 0.2 else 2)
    c.rect(6, 1, 9, 2, 7)                                         # a little vent on the top
    c.rect(6, 1, 6, 2, 8)
    c.outline(1)
    for x in range(16):                                           # the sides stay open below
        if c.get(x, 15) == 1:
            c.set(x, 15, 2 if x > 8 else 3)
    c.rect(0, 15, 0, 15, 1)
    c.rect(15, 15, 15, 15, 1)
    return c


KETTLE_COLS = (1, 4, 5, 4, 4, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 1)   # across the kettle, lit from the left


def kettle_wall():
    c = cell()
    for x, col in enumerate(KETTLE_COLS):
        c.rect(x, 0, x, 15, col)
    for y in (3, 11):                                             # rows of rivets
        for x in range(2, 15, 3):
            c.set(x, y, 5 if x < 8 else 4)
            c.set(x, y + 1, 2)
    return c


def kettle_bottom():
    c = cell()
    for y in range(9):
        inset = (y * y) // 12
        for x in range(inset, 16 - inset):
            col = KETTLE_COLS[x] if x not in (inset, 15 - inset) else 1
            c.set(x, y, col)
    c.rect(4, 9, 11, 9, 1)
    for x0 in (3, 10):                                            # steel feet
        c.rect(x0, 9, x0 + 2, 14, 7)
        c.rect(x0, 9, x0, 14, 8)
        c.rect(x0 + 2, 9, x0 + 2, 14, 6)
        c.rect(x0 - 1, 14, x0 + 3, 15, 6)
        c.rect(x0 - 1, 14, x0 + 3, 14, 7)
    tmp = Canvas(16, 16)
    tmp.px = [r[:] for r in c.px]
    tmp.outline(1)
    for y in range(0, 9):                                         # keep the copper's own sides
        tmp.px[y] = c.px[y][:]
        for x in range(16):
            if tmp.px[y][x] == 0 and (c.get(x + 1, y) or c.get(x - 1, y)) and y > 0:
                tmp.px[y][x] = 1
    return tmp


def malt_sack(c, y0, h, tied):
    """A sack of malt lying, 16 wide from y0, h high; tied: its neck tied up on top."""
    for y in range(y0, y0 + h):
        for x in range(16):
            edge = x in (0, 15) or y in (y0, y0 + h - 1)
            c.set(x, y, 1 if edge else 10)
    for x in range(1, 15):
        c.set(x, y0 + 1, 10)
        c.set(x, y0 + h - 2, 11)
    c.rect(14, y0 + 1, 14, y0 + h - 2, 11)
    c.rect(1, y0 + 1, 1, y0 + h - 3, 9)
    c.rect(5, y0 + 3, 10, y0 + 3, 11)                             # a stamp stripe
    c.rect(7, y0 + 2, 8, y0 + 4, 12)
    for x, y in ((0, y0), (1, y0), (14, y0), (15, y0), (0, y0 + h - 1), (15, y0 + h - 1)):
        c.set(x, y, 0)                                          # soft, rounded corners
    for x, y in ((1, y0 + 1), (14, y0 + 1), (2, y0), (13, y0)):
        c.set(x, y, 1)
    c.set(0, y0 + 1, 1)
    c.set(15, y0 + 1, 1)
    if tied:
        c.rect(6, y0 - 3, 9, y0, 10)
        c.rect(7, y0 - 1, 8, y0 - 1, 11)
        c.set(6, y0 - 1, 12)
        c.set(9, y0 - 1, 12)
        c.set(5, y0 - 4, 10)
        c.set(10, y0 - 4, 10)


def malt(part):
    c = cell()
    if part == "top":
        malt_sack(c, 8, 8, True)
        tmp = Canvas(16, 16)
        tmp.px = [r[:] for r in c.px]
        tmp.outline(1)
        return tmp
    malt_sack(c, 0, 8, False)
    if part == "mid":
        malt_sack(c, 8, 8, False)
    else:
        malt_sack(c, 8, 5, False)
        c.rect(0, 13, 15, 13, 11)                                 # the pallet
        c.rect(0, 14, 15, 14, 2)
        for x in (1, 7, 13):
            c.rect(x, 15, x + 1, 15, 2)
        c.rect(0, 15, 0, 15, 1)
    return c


def catwalk():
    c = cell()
    c.rect(0, 0, 15, 0, 8)
    c.rect(0, 1, 15, 1, 7)
    for x in range(16):
        for y in (2, 3, 4):
            c.set(x, y, 15 if (x + (y == 3)) % 3 == 1 else 7 if y == 2 else 6)
    c.rect(0, 5, 15, 5, 6)
    c.rect(0, 6, 15, 6, 1)
    for x in (3, 11):                                             # brackets under the grate
        c.rect(x, 7, x + 1, 8, 6)
        c.set(x + 1, 9, 6)
        c.rect(x - 1, 7, x - 1, 8, 1)
        c.rect(x + 2, 7, x + 2, 9, 1)
        c.set(x, 9, 1)
        c.set(x + 1, 10, 1)
    return c


def valve_wheel():
    c = cell()
    c.rect(6, 11, 9, 15, 3)                                       # the stem from a pipe below
    c.rect(6, 11, 6, 15, 4)
    c.rect(9, 11, 9, 15, 2)
    ring = {(x, y) for x, y in disk(8.0, 6.5, 6.0)} - disk(8.0, 6.5, 4.4)
    for x, y in ring:                                             # green, a glint on the upper left
        c.set(x, y, 14 if (x - 8) + (y - 6.5) > -3 else 9)
    for i in range(-4, 5):                                        # spokes
        c.set(8 + i, 7, 14)
    c.set(8, 6, 14)
    line(c, 4, 6, 12, 6, 14)
    line(c, 8, 2, 8, 11, 14)
    c.rect(7, 5, 8, 6, 7)                                         # the hub
    c.set(7, 5, 8)
    c.outline(1)
    return c


def pressure_gauge():
    c = cell()
    c.rect(7, 12, 8, 15, 3)
    c.set(7, 12, 4)
    m = disk(8.0, 7.0, 6.4)
    for x, y in m:
        c.set(x, y, 7)
    for x, y in m:
        if (x - 1, y) not in m or (x, y - 1) not in m:
            c.set(x, y, 8)
        elif (x + 1, y) not in m or (x, y + 1) not in m:
            c.set(x, y, 6)
    for x, y in disk(8.0, 7.0, 4.8):
        c.set(x, y, 9)
    for x, y in ((5, 5), (5, 4), (6, 3)):                         # the green zone
        c.set(x, y, 14)
    for x, y in ((10, 3), (11, 4), (11, 5)):                      # the danger zone
        c.set(x, y, 12)
    line(c, 8, 7, 5, 5, 15)                                       # the needle, safely in the green
    c.set(8, 7, 1)
    c.outline(1)
    return c


def steam_vent():
    c = cell()
    c.rect(5, 11, 10, 11, 4)                                      # the pipe end
    c.rect(6, 12, 9, 15, 3)
    c.rect(6, 12, 6, 15, 4)
    c.rect(9, 12, 9, 15, 2)
    c.rect(5, 11, 10, 11, 5)
    puff = disk(8.0, 6.5, 3.4) | disk(5.0, 4.5, 2.4) | disk(11.0, 4.0, 2.2) | disk(8.5, 2.5, 2.0)
    for x, y in puff:
        if 1 <= x <= 14 and 1 <= y <= 9:
            c.set(x, y, 9)
    for x, y in puff:
        if c.get(x, y) == 9 and (c.get(x + 1, y + 1) == 0):
            c.set(x, y, 8)
    c.outline(1)
    return c


def steel_wall(part):
    c = cell()
    c.rect(0, 0, 15, 15, 7)
    c.rect(0, 0, 0, 15, 8)
    c.rect(15, 0, 15, 15, 6)
    c.rect(1, 7, 14, 7, 6)                                        # a seam between plates
    c.rect(1, 8, 14, 8, 8)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        c.set(x, y, 8)
        c.set(x + 1, y + 1, 6)
    if part == "top":
        c.rect(0, 0, 15, 0, 9)
        c.rect(0, 1, 15, 1, 8)
        c.rect(0, 2, 15, 2, 6)
    if part == "bottom":
        c.rect(0, 13, 15, 13, 6)
        c.rect(0, 14, 15, 14, 15)
        c.rect(0, 15, 15, 15, 1)
    return c


def stage_brew():
    s = Canvas(128, 64)
    cells = {1: brew_floor(True), 2: brew_floor(False), 3: girder(), 4: copper_pipe(False), 5: copper_pipe(True),
             6: copper_flange(), 7: kettle_dome(), 8: kettle_wall(), 9: kettle_bottom(), 10: malt("top"),
             11: malt("mid"), 12: malt("bottom"), 13: catwalk(), 14: valve_wheel(), 15: pressure_gauge(),
             16: steam_vent(), 17: steel_wall("top"), 18: steel_wall("mid"), 19: steel_wall("bottom")}
    for k, c in cells.items():
        put(s, k, c)
    return s


# --- the Mett walls: a field of lumps that repeats every 16 pixels, swelling in four frames ----------
METT = [(0, 0, 0), (62, 16, 16), (132, 32, 38), (192, 62, 66), (228, 114, 116), (250, 172, 168),
        (252, 242, 230), (30, 18, 18), (244, 238, 196), (196, 184, 130), (255, 222, 220), (164, 44, 52),
        (44, 20, 20), (218, 176, 92), (156, 116, 48), (112, 168, 70)]
# lumps of minced meat in one 16 x 16 period: (x, y, radius); the ones away from the cell's edges
# swell with the pulse, the ones on the edges stay as they are
LUMPS = [(2, 2, 2.4), (7, 1, 2.2), (12, 3, 2.5), (4.5, 6.5, 2.4), (10, 7.5, 2.6), (14.5, 8, 2.0),
         (1.5, 10.5, 2.3), (6.5, 11, 2.4), (11.5, 12.5, 2.4), (3.5, 15, 2.2), (8.5, 15.5, 2.0), (14.5, 14, 2.2)]
SWELL = (0.0, 0.35, 0.7, 0.35)


def mett_field(x, y, frame):
    """The colour of the packed Mett at (x, y), a pattern 16 x 16."""
    best = None
    for n, (lx, ly, r) in enumerate(LUMPS):
        inner = 3.5 <= lx <= 12.5 and 3.5 <= ly <= 12.5
        rr = r + (SWELL[frame] if inner else 0.0)
        dx = (x + 0.5 - lx + 8) % 16 - 8
        dy = (y + 0.5 - ly + 8) % 16 - 8
        f = math.hypot(dx, dy) / rr
        if f <= 1.0 and (best is None or f < best[0]):
            best = (f, dx / rr, dy / rr, n, inner)
    if best is None:
        return 2
    f, dx, dy, n, inner = best
    lum = -(dx * -0.6 + dy * -0.75)
    col = 5 if lum > 0.55 else 4 if lum > 0.05 else 3 if lum > -0.5 else 11
    if f > 0.86 and lum < 0.0:
        col = 2
    shift = frame if inner else 0                                 # the fat moves with the pulse
    if n % 3 == 0 and abs(dx + 0.15 * (shift % 2)) < 0.3 and abs(dy - 0.1 * shift) < 0.3:
        col = 6
    if n % 4 == 1 and abs(dx - 0.35) < 0.22 and abs(dy - 0.3) < 0.22:
        col = 7                                                   # pepper
    return col


def mett_cell(part, frame):
    c = cell()
    for y in range(16):
        for x in range(16):
            c.set(x, y, mett_field(x, y, frame))
    edge0 = [[mett_field(x, y, 0) for x in range(16)] for y in range(16)]
    for y in range(16):                                            # the edges never move
        for x in (0, 15):
            c.set(x, y, edge0[y][x])
    if part == "mid":
        for x in range(16):
            for y in (0, 15):
                c.set(x, y, edge0[y][x])
    swell = (0, 1, 2, 1)[frame]
    if part == "top":
        for x in range(16):
            bump = 1.6 * abs(math.sin((x + 0.5) * math.pi / 8))       # two lumps a cell
            mid = math.sin((x + 0.5) * math.pi / 16)                  # nothing moves at the edges
            top = int(round(4.5 - bump - swell * mid))
            for y in range(0, top):
                c.set(x, y, 0)
            c.set(x, top, 1)
            c.set(x, top + 1, 5 if c.get(x, top + 1) in (3, 4) else 4)
        for x in range(16):
            c.set(x, 15, edge0[15][x])
    if part == "bottom":
        for x in range(16):
            droop = 1.8 * abs(math.sin((x + 0.5) * math.pi / 8 + 1.2))
            mid = math.sin((x + 0.5) * math.pi / 16)
            bot = int(round(11.0 + droop + swell * mid))
            for y in range(bot + 1, 16):
                c.set(x, y, 0)
            c.set(x, bot, 1)
            c.set(x, bot - 1, 2)
        for x in range(16):
            c.set(x, 0, edge0[0][x])
    return c


def mett_hole():
    """Mett pushing up out of a hole in the floor, curling over."""
    c = cell()
    for x, y in disk(8.0, 13.0, 6.5):
        if y >= 11:
            c.set(x, y, 12)
    for x, y in disk(8.0, 13.0, 6.5) - disk(8.0, 13.0, 5.4):
        if y >= 11:
            c.set(x, y, 2 if x < 8 else 11)
    tongue = disk(8.0, 9.0, 3.4) | disk(7.0, 5.5, 3.0) | disk(9.5, 3.0, 2.4) | disk(12.0, 3.5, 1.6)
    tongue = {(x, y) for x, y in tongue if y <= 12}
    for x, y in tongue:
        lum = -((x - 7.0) * -0.6 + (y - 5.0) * -0.75) / 4
        c.set(x, y, 5 if lum > 0.55 else 4 if lum > 0.0 else 3 if lum > -0.6 else 11)
    for x, y in ((6, 8), (9, 10), (10, 3)):
        c.set(x, y, 6)
    c.set(7, 4, 7)
    c.set(5, 10, 7)
    c.outline(1)
    return c


def malt_sprouts():
    c = cell()
    grains = [(3, 13), (7, 14), (11, 13), (5, 11), (9, 12), (13, 14)]
    for n, (gx, gy) in enumerate(grains):
        c.rect(gx, gy, gx + 1, gy + 1, 13)
        c.set(gx + 1, gy + 1, 14)
        h = 3 + (n * 5) % 5
        for k in range(h):                                        # a sprout, bending a little
            c.set(gx + (k // 3) * (1 if n % 2 else -1), gy - 1 - k, 15)
        c.set(gx + (h // 3) * (1 if n % 2 else -1) + (1 if n % 2 else -1), gy - h, 15)
    c.outline(1)
    return c


def mett_drip():
    c = cell()
    c.rect(4, 0, 11, 1, 3)
    c.rect(4, 0, 11, 0, 4)
    c.rect(6, 2, 9, 4, 3)
    c.rect(7, 5, 8, 8, 3)
    c.rect(7, 2, 7, 7, 4)
    m = disk(7.5, 11.0, 2.6)
    for x, y in m:
        c.set(x, y, 3)
    c.set(6, 10, 5)
    c.set(7, 10, 4)
    c.set(8, 12, 2)
    c.set(8, 11, 6)
    c.outline(1)
    c.rect(3, 0, 12, 0, 4)
    return c


WALLBREW = [(0, 0, 0), (10, 8, 8), (24, 20, 18), (40, 35, 32), (54, 47, 42), (68, 59, 52), (66, 36, 22),
            (100, 58, 34), (138, 86, 52), (36, 40, 48), (62, 68, 78), (18, 26, 46), (36, 52, 82),
            (150, 136, 104), (34, 66, 44), (98, 36, 42)]


def brew_window(c, x0):
    """A tall arched window two cells wide from x0, rows 2 to 8, symmetric about its middle."""
    cx = x0 + 16
    for y in range(32, 144):
        for x in range(x0, x0 + 32):
            u = abs(x + 0.5 - cx)
            top = 32 + 14 - math.sqrt(max(0.0, 14 * 14 - u * u)) if u < 14 else 999
            if u >= 14 or y < top:
                continue
            if u >= 12.0 or y < top + 2:
                c.set(x, y, 10 if y < top + 1 or u < 13 else 9)
            elif u < 1.0 or (y % 16 == 8 and y > 50):
                c.set(x, y, 9)
            else:
                c.set(x, y, 12 if (y % 16 > 9 or y > 128) else 11)
    for x in range(x0 + 1, x0 + 31):                              # the sill
        c.set(x, 140, 10)
        c.set(x, 141, 9)
        c.set(x, 142, 1)
    for sx, sy in ((x0 + 8, 60), (x0 + 22, 78), (x0 + 6, 98)):     # a few stars, mirrored
        c.set(sx, sy, 13)
        c.set(2 * cx - sx - 1, sy, 13)


def far_kettle(c, cx, vent):
    """A copper kettle in the distance, 46 wide, centred on cx, symmetric."""
    for y in range(108, 208):
        for x in range(cx - 23, cx + 23):
            u = abs(x + 0.5 - cx) / 23.0
            if y < 130 and (y - 130) ** 2 / 22.0 ** 2 + u * u > 1.0:   # the dome
                continue
            if y >= 200:                                          # the legs
                if not (14 <= abs(x + 0.5 - cx) <= 18):
                    continue
                c.set(x, y, 9 if y < 206 else 2)
                continue
            col = 8 if u < 0.3 else 7 if u < 0.72 else 6
            if u > 0.93 or y >= 198:
                col = 1
            if y in (142, 182) and u < 0.93:
                col = 9
            if y in (143, 183) and u < 0.93:
                col = 6
            c.set(x, y, col)
    for y in range(108, 130):                                     # the dome's outline
        for x in range(cx - 23, cx + 23):
            if c.get(x, y) in (6, 7, 8) and c.get(x, y - 1) not in (6, 7, 8, 1):
                c.set(x, y, 1)
    for y in range(156, 170):                                     # a round manhole on the front
        for x in range(cx - 7, cx + 7):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - 163)
            if d <= 6.5:
                c.set(x, y, 1 if d > 5.6 else 9 if d > 4.4 else 6)
    if vent:
        for y in range(0, 110):
            for x, col in zip(range(cx - 2, cx + 2), (1, 8, 7, 1)):
                c.set(x, y, col)


def wall_brew():
    c = Canvas(256, 224)
    for y in range(224):                                          # tiles that are their own mirror image
        for x in range(256):
            u, v = x % 16, y % 16
            c.set(x, y, 2 if (u in (0, 15) or v == 15) else 5 if v == 0 else 3 if v == 14 else 4)
    brew_window(c, 16)
    brew_window(c, 144)
    far_kettle(c, 88, True)
    far_kettle(c, 216, False)
    for px in (112, 240):                                         # pipes up the wall
        for y in range(0, 210):
            for x, col in zip(range(px + 5, px + 11), (1, 8, 7, 7, 6, 1)):
                c.set(x, y, col)
    for y, col in zip(range(209, 219), (1, 8, 7, 7, 6, 6, 6, 1, 2, 2)):
        c.rect(0, y, 255, y, col)                                 # and along the floor
    c.rect(0, 219, 255, 223, 2)
    for x in range(0, 256, 16):
        c.rect(x + 7, 209, x + 8, 216, 9)
    # the sign SUDHAUS over the second kettle
    c.rect(178, 64, 237, 78, 1)
    c.rect(179, 65, 236, 77, 14)
    c.rect(180, 66, 235, 76, 13)
    c.rect(181, 67, 234, 75, 14)
    for n, ch in enumerate("SUDHAUS"):
        for gy, row in enumerate(FONT[ch]):
            for gx, bit in enumerate(row):
                if bit == "1":
                    c.set(187 + n * 7 + gx, 68 + gy, 13)
    # Mett creeping up the foot of the first pipe
    for x, y in disk(120.0, 207.0, 7.5) | disk(115.5, 200.0, 3.5) | disk(124.0, 197.0, 2.6):
        if y < 209 and 112 <= x < 128:
            c.set(x, y, 15)
    for x, y in ((116, 200), (121, 203), (124, 197), (114, 205)):
        c.set(x, y, 13)
    return c


def stage_mett():
    s = Canvas(128, 64)
    for frame in range(4):
        for i, part in enumerate(("top", "mid", "bottom")):
            put(s, 8 * frame + 1 + i, mett_cell(part, frame))
    put(s, 4, mett_hole())
    put(s, 5, malt_sprouts())
    put(s, 6, mett_drip())
    return s


# --- stage 2's enemies: sprites_b cells 46, 47, 54, 55, 62, 63 ---------------------------------------
def mett_lumps(c, mask, cx, cy, r, lit, body, dark, spec=None, phase=0):
    """Shades a mask as a heap of minced meat lit from the upper left: rounded lumps about 4 pixels
    across, each dark on its lower right, the whole heap darker on its lower right."""
    for x, y in mask:
        gy = (y + phase) // 3
        best = None
        for jy in (gy - 1, gy, gy + 1):
            off = 2 if jy % 2 else 0
            for jx in range((x - off) // 4 - 1, (x - off) // 4 + 2):
                lx = jx * 4 + off + 1.5 + ((jx * 7 + jy * 3) % 3 - 1) * 0.4
                ly = jy * 3 - phase + 1.5
                d = math.hypot(x + 0.5 - lx, y + 0.5 - ly)
                if best is None or d < best[0]:
                    best = (d, x + 0.5 - lx, y + 0.5 - ly)
        d, dx, dy = best
        local = -(dx * -0.6 + dy * -0.75) / 2.2
        gl = -((x + 0.5 - cx) * -0.6 + (y + 0.5 - cy) * -0.75) / r
        if local < -0.5 or (gl < -0.55 and local < 0.3):
            col = dark
        elif local > 0.45 and gl > -0.1:
            col = lit
        else:
            col = body
        c.set(x, y, col)
    if spec is not None:
        for x, y in spec:
            if (x, y) in mask:
                c.set(x, y, 2)


def mettigel(frame):
    """A hedgehog of Mett with onion spikes and olive eyes, crawling left by bulging forward."""
    c = Canvas(16, 16)
    front = 1 if frame else 0
    body = {(x, y) for x, y in disk(9.5 - front * 0.5, 12.0, 5.8 + front * 0.4) if y <= 14}
    body |= {(x, y) for x, y in disk(3.6 - front, 12.6, 2.4)}       # the snout
    body |= {(x, 14) for x in range(4 - front, 15)}
    spikes = [(6, 6), (9, 5), (12, 6), (14, 8), (4, 8)]
    for n, (sx, sy) in enumerate(spikes):                           # onion slices as spikes
        sx -= front if n < 2 else 0
        c.set(sx, sy, 8)
        c.set(sx + 1, sy, 2)
        c.set(sx, sy + 1, 8)
        c.set(sx + 1, sy + 1, 8)
        c.set(sx + 1, sy - 1, 8)
    mett_lumps(c, body, 8.0, 10.0, 6.0, 4, 4, 5, {(10, 10), (13, 12), (7, 13)}, frame)
    c.outline(1)
    c.set(2 - front, 12, 14)                                        # the nose: a peppercorn
    c.rect(5 - front, 9, 6 - front, 10, 14)                          # an olive eye
    c.set(5 - front, 9, 12)
    return c


def hop_cone(frame):
    """A hop cone with tiny wings: rows of overlapping green scales, pointing down."""
    c = Canvas(16, 16)
    scales = [(8, 4), (6, 6), (10, 6), (5, 8.5), (8, 8), (11, 8.5), (6, 10.5), (10, 10.5), (8, 12.5)]
    for sx, sy in scales:                                           # back to front, top to bottom
        m = {(x, y) for y in range(16) for x in range(16)
             if ((x + 0.5 - sx) / 2.4) ** 2 + ((y + 0.5 - sy) / 2.0) ** 2 <= 1.0}
        for x, y in m:
            c.set(x, y, 15)
        for x, y in m:
            if (x, y + 1) not in m:
                c.set(x, y, 1)
            elif (x - 1, y - 1) not in m and (x, y - 1) not in m and x < sx:
                c.set(x, y, 2)
    c.rect(8, 1, 8, 2, 15)                                          # the stalk
    c.set(9, 0, 15)
    if frame == 0:
        wing = [(3, 3), (2, 2), (3, 2), (1, 1), (2, 1), (12, 3), (13, 2), (12, 2), (14, 1), (13, 1)]
    else:
        wing = [(3, 6), (2, 6), (1, 7), (2, 7), (3, 7), (12, 6), (13, 6), (14, 7), (13, 7), (12, 7)]
    for x, y in wing:
        c.set(x, y, 8)
    c.outline(1)
    c.set(5, 7, 14)                                                 # a beady eye, looking left
    c.set(5, 6, 8)
    return c


def tentacle_knot(tip):
    c = Canvas(16, 16)
    r = 4.2 if tip else 6.0
    knot = disk(8.0, 8.0, r)
    if tip:
        ring = disk(8.0, 8.0, 6.6) - disk(8.0, 8.0, 5.0)
        for x, y in ring:
            c.set(x, y, 8 if (x - 8) + (y - 8) < 2 else 2)
    mett_lumps(c, knot, 8.0, 8.0, r, 4, 4, 5, {(6, 7), (9, 10), (10, 6)} if not tip else {(7, 7)})
    c.outline(1)
    return c


# --- the Mett creature's head: sprites_a cells 56-59 (frame 0) and 60-63 (frame 1) -----------------
def mett_head(roar):
    """A 32 x 32 head of Mett with an onion-ring crown, two angry olive eyes and a mouth."""
    c = Canvas(32, 32)
    blob = disk(16.0, 19.0, 13.5) | disk(9.0, 12.0, 6.0) | disk(23.0, 12.0, 6.0) | disk(16.0, 9.0, 7.0)
    blob |= {(x, y) for x in range(2, 30) for y in range(24, 32)}
    blob = {(x, y) for x, y in blob if 0 < x < 31 and 0 < y < 32}
    mett_lumps(c, blob, 14.0, 13.0, 15.0, 13, 13, 12)
    for x, y in ((6, 20), (24, 22), (11, 27), (21, 10), (5, 14), (27, 18), (19, 28)):   # fat
        c.set(x, y, 8)
        c.set(x - 1, y, 5)
    # the crown: three onion rings standing on the head
    for rx, ry in ((9.0, 6.0), (23.0, 6.0), (16.0, 4.0)):            # flat rings, lying on the head
        outer = {(x, y) for y in range(32) for x in range(32)
                 if ((x + 0.5 - rx) / 5.0) ** 2 + ((y + 0.5 - ry) / 2.6) ** 2 <= 1.0}
        inner = {(x, y) for y in range(32) for x in range(32)
                 if ((x + 0.5 - rx) / 2.8) ** 2 + ((y + 0.5 - ry) / 1.0) ** 2 <= 1.0}
        for x, y in outer:
            c.set(x, y, 12 if (x, y) in inner else 5 if y + 0.5 < ry else 8)
        for x, y in outer - inner:
            if (x, y + 1) not in outer:
                c.set(x, y, 15)
    # the mouth
    if roar:
        mouth = {(x, y) for x, y in disk(16.0, 24.0, 5.5) if y >= 20}
        for x, y in mouth:
            c.set(x, y, 1)
        for x, y in disk(16.0, 28.5, 3.0):
            if (x, y) in mouth:
                c.set(x, y, 12)
        for x in (12, 15, 18):                                      # bits of onion for teeth
            c.set(x, 20, 5)
            c.set(x + 1, 20, 8)
    else:
        for x in range(10, 23):
            y = 24 + (1 if x in (10, 22) else 0) - (1 if 14 <= x <= 18 else 0)
            c.set(x, y, 1)
            c.set(x, y + 1, 12)
    c.outline(1)
    for x in range(1, 31):                                          # it rises out of the kettle: no edge below
        c.set(x, 31, 12 if c.get(x, 31) == 1 else c.get(x, 31))
    # two angry olive eyes with red pimento pupils, brows down towards the middle
    for ex in (8, 19):
        olive = disk(ex + 2.5, 15.0, 3.0)
        for x, y in olive:
            c.set(x, y, 10)
        for x, y in olive:
            if (x + 1, y) not in olive or (x, y + 1) not in olive:
                c.set(x, y, 9)
        c.set(ex + 1, 13, 11)
        look = -1                                                   # looking left, at the ship
        c.rect(ex + 2 + look, 15, ex + 3 + look, 16, 13)
        c.set(ex + 2 + look, 15, 12)
    for i in range(6):
        c.set(7 + i, 10 + i // 2, 1)
        c.set(7 + i, 11 + i // 2, 1)
        c.set(25 - i, 10 + i // 2, 1)
        c.set(25 - i, 11 + i // 2, 1)
    return c


# --- the boss of stage 2: die Sudpfanne (boss2.png, BOSS2_MAP) -------------------------------------
BOSS2 = [(0, 0, 0), (30, 12, 8), (100, 44, 22), (160, 80, 38), (214, 128, 66), (252, 200, 140),
         (62, 66, 78), (120, 126, 140), (190, 196, 208), (8, 6, 6), (128, 30, 36), (192, 62, 66),
         (234, 128, 126), (240, 240, 236), (222, 40, 30), (250, 236, 208)]


def sudpfanne_copper(x):
    """The copper's colour across the kettle, lit from the left; the middle columns all alike."""
    if x < 12 or x > 147:
        return 1
    if x < 16:
        return 4
    if x < 22:
        return 5
    if x < 32:
        return 4
    if x < 128:                                   # the shades change on cell edges: fewer cells
        return 3
    return 2


def superellipse(cx, cy, rx, ry, n=8.0):
    return lambda x, y: abs((x + 0.5 - cx) / rx) ** n + abs((y + 0.5 - cy) / ry) ** n <= 1.0


def sudpfanne(state):
    """The brew kettle, 160 x 192: 0 lid closed and steaming, 1 lid open with Mett oozing over the
    rim, 2 broken (dented, a crack with Mett bursting out, the gauge in the red)."""
    c = Canvas(160, 192)
    # --- the body: a copper cylinder with two steel hoops ---
    for y in range(52, 160):
        for x in range(11, 149):
            c.set(x, y, sudpfanne_copper(x))
    for y0 in (74, 138):
        for y, col in zip(range(y0, y0 + 5), (1, 8, 7, 6, 1)):
            c.rect(12, y, 147, y, col)
        for x in range(16, 146, 8):
            c.set(x, y0 + 2, 13)
            c.set(x + 1, y0 + 3, 6)
    # --- the bottom, narrowing onto a steel stand, three legs ---
    for y in range(160, 176):
        inset = ((y - 158) ** 2) // 6
        for x in range(11 + inset, 149 - inset):
            c.set(x, y, sudpfanne_copper(x) if x not in (11 + inset, 148 - inset) else 1)
        c.set(11 + inset, y, 1)
    for y, col in zip(range(172, 178), (1, 8, 7, 7, 6, 1)):
        c.rect(30, y, 129, y, col)
    for lx in (20, 74, 130):                                         # legs, symmetric
        for y in range(176, 192):
            for x, col in zip(range(lx, lx + 10), (1, 7, 8, 7, 7, 7, 7, 8, 7, 1)):
                c.set(x, y, col)
        c.rect(lx - 2, 188, lx + 11, 190, 6)
        c.rect(lx - 2, 188, lx + 11, 188, 7)
        c.rect(lx - 2, 191, lx + 11, 191, 1)
        c.set(lx - 2, 189, 1)
        c.set(lx - 2, 190, 1)
        c.set(lx + 11, 189, 1)
        c.set(lx + 11, 190, 1)
    # --- the rolled rim at the top: all of it in cell row 3 (y 48..63), the same in every state ---
    for y, col in zip(range(48, 59), (1, 5, 5, 4, 4, 3, 3, 3, 2, 2, 1)):
        for x in range(6, 154):
            c.set(x, y, col)
    for y in range(48, 59):
        c.set(6, y, 1)
        c.set(153, y, 1)
        c.set(7, y, 1 if y in (48, 58) else 5)
    # --- a sight glass with Mett in it (cell (2, 9)) and a bolted hatch (cells (3, 9), (4, 9)) ---
    c.rect(36, 145, 42, 158, 1)
    c.rect(37, 146, 41, 157, 7)
    c.rect(38, 147, 40, 156, 9)
    c.rect(38, 152, 40, 156, 11)
    c.set(38, 152, 12)
    c.set(40, 147, 13)
    hatch = {(x, y) for y in range(144, 160) for x in range(48, 80)
             if ((x + 0.5 - 64) / 13.0) ** 2 + ((y + 0.5 - 152) / 6.6) ** 2 <= 1.0}
    for x, y in hatch:
        rr = ((x + 0.5 - 64) / 13.0) ** 2 + ((y + 0.5 - 152) / 6.6) ** 2
        c.set(x, y, 1 if rr > 0.86 else (8 if y < 152 else 6) if rr > 0.55 else 3 if y < 150 else 2)
    for x in (54, 64, 73):
        c.set(x, 146 if x == 64 else 148, 13)
    c.rect(60, 151, 67, 152, 7)                                       # its handle
    c.rect(60, 151, 67, 151, 8)
    # --- the gauge on the right of the body ---
    gx, gy = 120, 88                                                 # the gauge, all in cell (7, 5)
    for y in range(gy - 8, gy + 8):
        for x in range(gx - 8, gx + 8):
            d = math.hypot(x + 0.5 - gx, y + 0.5 - gy)
            if d <= 7.5:
                c.set(x, y, 1 if d > 6.8 else 8 if d > 5.6 and (x - gx) + (y - gy) < 0 else 6 if d > 5.6 else 13)
    for a in range(30, 80, 6):                                       # the red zone of the dial
        t = math.radians(a)
        c.set(int(gx + math.cos(t) * 4.2), int(gy - math.sin(t) * 4.2), 14)
    angle = math.radians(20 if state == 2 else 135)
    for k in range(1, 5):
        c.set(int(round(gx - 0.5 + math.cos(angle) * k)), int(round(gy - 0.5 - math.sin(angle) * k)), 9)
    c.set(gx - 1, gy - 1, 1)
    # --- the top: lid or opening, all in cell rows 0 to 2 ---
    if state == 0:
        lid = superellipse(80, 48, 71, 21, 2.0)
        for y in range(26, 48):
            for x in range(8, 152):
                if lid(x, y):
                    col = sudpfanne_copper(x + 2)
                    edge = not lid(x, y - 1) or not lid(x - 1, y) or not lid(x + 1, y)
                    c.set(x, y, 1 if edge else (5 if x < 120 else 4) if not lid(x, y - 2) else
                          (col if col != 1 else 2))
        for y, col in zip(range(21, 29), (1, 8, 7, 7, 7, 6, 6, 1)):  # the handle, symmetric
            for x in range(72, 88):
                c.set(x, y, col if 73 <= x <= 86 else 1)
        for px, py, r in ((14, 42, 4.6), (8, 35, 3.6), (12, 27, 2.6), (146, 42, 4.6), (152, 35, 3.4),
                          (149, 27, 2.4)):
            for x, y in disk(px, py, r):                             # steam leaking at the edges
                if c.get(x, y) in (0, 1) and y < 48:
                    c.set(x, y, 13 if (x - px) + (y - py) < 1 else 8)
        for px, py, r in ((80, 16, 4.2), (78, 9, 3.4), (82, 3, 2.6)):
            for x, y in disk(px, py, r):                             # and from the handle's valve
                c.set(x, y, 13 if y + 0.5 < py + 1 else 8)
    else:
        hole = superellipse(80, 44, 70, 4.5)
        for y in range(38, 48):
            for x in range(8, 152):
                if hole(x, y):
                    c.set(x, y, 9)
                elif hole(x, y + 1) or hole(x, y + 2):
                    c.set(x, y, 2 if hole(x, y + 2) and not hole(x, y + 1) else 1)
        # the lid, swung open on its hinge at the right, standing on its edge
        lid = superellipse(146, 24, 9, 23, 3.0)
        for y in range(0, 48):
            for x in range(134, 160):
                if lid(x, y):
                    edge = not lid(x - 1, y) or not lid(x + 1, y) or not lid(x, y - 1)
                    c.set(x, y, 1 if edge else 4 if x < 143 else 3 if x < 149 else 2)
        c.rect(149, 41, 156, 47, 7)                                  # the hinge
        c.rect(149, 41, 156, 41, 8)
        c.rect(148, 40, 157, 40, 1)
        c.rect(148, 40, 148, 47, 1)
        c.rect(157, 40, 157, 47, 1)
        # Mett oozing over the rim, in two places: a heap on the rim draping down its front
        for ox in (40, 104):
            heap = {(x, y) for x, y in disk(ox, 47.5, 6.0) if y <= 48} | {(x, y) for x, y in disk(ox + 5, 46.5, 3.4)}
            drape = {(x, y) for x in range(ox - 4, ox + 3) for y in range(48, 56)}
            drape |= disk(ox - 1.0, 56.5, 3.4)
            m = {(x, y) for x, y in heap | drape if y <= 61}
            for x, y in m:
                lum = (x - ox) + (y - 48) * 0.6
                c.set(x, y, 12 if lum < -3 else 11 if lum < 3 else 10)
            for x, y in ((ox - 2, 46), (ox + 3, 45), (ox - 2, 54)):
                c.set(x, y, 15)
            for x, y in m:
                if any((x + a, y + b) not in m for a, b in ((1, 0), (-1, 0), (0, 1))) and y >= 48:
                    c.set(x, y, 1)
    # --- broken: a dent and a crack with Mett bursting out, each kept inside its own cells ---
    if state == 2:
        dent = disk(40.0, 104.0, 7.0)                                 # the dent, in cell (2, 6)
        for x, y in dent:
            d = ((x + 0.5 - 40) + (y + 0.5 - 104)) / 7.0
            c.set(x, y, 1 if d < -1.0 else 2 if d < -0.4 else 3 if d < 0.4 else 4 if d < 0.9 else 5)
        for x, y in dent:
            if (x - 1, y) not in dent or (x, y - 1) not in dent:
                c.set(x, y, 1)
        burst = disk(88.0, 111.0, 6.4) | disk(90.0, 102.5, 4.0) | disk(86.5, 120.0, 4.2)
        burst = {(x, y) for x, y in burst if 81 <= x <= 94 and 97 <= y <= 126}
        for x, y in burst:                                           # in the cells (5, 6) and (5, 7)
            k = (x - 88) + (y - 112)
            c.set(x, y, 12 if k < -3 else 11 if k < 4 else 10)
        for x, y in ((86, 110), (90, 114), (90, 104)):
            c.set(x, y, 15)
        for x, y in burst:
            if any((x + a, y + b) not in burst for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                c.set(x, y, 1)
        crack = [(86, 98), (90, 101), (88, 106)]
        for (ax, ay), (bx, by) in zip(crack, crack[1:]):
            line(c, ax, ay, bx, by, 1)
        line(c, 87, 121, 84, 126, 1)
    return c


# =================================================================================================
# 8. Stage 3, das Festzelt: two pictures for mode 7 (the beer tent and the chain carousel, each
#    1024 x 1024 of at most 255 distinct 8 x 8 tiles and 95 colours, indices 1..95, opaque, no
#    mirrored tiles) and the sprites of the stage (sprites_c.png)
# =================================================================================================
def m7_palette(colours):
    """The 96 entries of a mode 7 picture's palette from (name, rgb) pairs, and the names' indices:
    entry 0 stays black and unused, the colours follow from 1 on, the rest is black."""
    if len(colours) > 95:
        raise ValueError(f"{len(colours)} colours, mode 7 has room for 95")
    pal = [(0, 0, 0)] + [rgb for _, rgb in colours]
    return pal + [(0, 0, 0)] * (96 - len(pal)), {name: i + 1 for i, (name, _) in enumerate(colours)}


def m7_tiles(c):
    """The distinct 8 x 8 tiles of a picture, as a dict from the tile's bytes to its first place."""
    seen = {}
    for ty in range(0, c.h, 8):
        rows = c.px[ty:ty + 8]
        for tx in range(0, c.w, 8):
            key = b"".join(bytes(r[tx:tx + 8]) for r in rows)
            seen.setdefault(key, (tx, ty))
    return seen


FEST_COLOURS = [
    ("ink", (30, 18, 14)), ("shadow", (54, 38, 30)),
    ("blue0", (34, 52, 104)), ("blue1", (56, 88, 158)), ("blue2", (86, 128, 202)), ("blue3", (134, 174, 232)),
    ("white0", (140, 146, 166)), ("white1", (190, 196, 210)), ("white2", (228, 230, 234)), ("white3", (252, 250, 242)),
    ("rope0", (112, 86, 54)), ("rope1", (186, 154, 104)),
    ("grass0", (64, 80, 34)), ("grass1", (96, 112, 44)), ("straw0", (140, 130, 60)), ("straw1", (190, 170, 88)),
    ("straw2", (230, 210, 136)),
    ("plank0", (92, 54, 26)), ("plank1", (138, 86, 42)), ("plank2", (176, 118, 62)), ("plank3", (204, 148, 86)),
    ("plank4", (230, 184, 120)),
    ("floor0", (46, 30, 22)), ("floor1", (76, 50, 32)), ("floor2", (86, 58, 36)),
    ("table0", (146, 94, 48)), ("table1", (196, 146, 86)), ("table2", (226, 186, 124)), ("table3", (246, 218, 164)),
    ("bench0", (110, 66, 32)), ("bench1", (158, 104, 54)), ("bench2", (194, 140, 82)),
    ("glass0", (112, 132, 142)), ("glass1", (172, 194, 202)), ("glass2", (228, 240, 242)),
    ("amber0", (164, 90, 16)), ("amber1", (224, 146, 32)), ("amber2", (250, 198, 78)),
    ("foam0", (222, 208, 172)), ("foam1", (255, 250, 232)),
    ("pretz0", (96, 46, 16)), ("pretz1", (152, 82, 32)), ("pretz2", (200, 126, 58)),
    ("felt0", (34, 56, 38)), ("felt1", (60, 90, 58)), ("felt2", (94, 124, 82)),
    ("red0", (116, 22, 20)), ("red1", (184, 40, 34)), ("red2", (228, 90, 72)),
    ("brass0", (108, 76, 20)), ("brass1", (178, 134, 40)), ("brass2", (228, 190, 78)), ("brass3", (255, 238, 160)),
    ("iron0", (48, 50, 58)), ("iron1", (88, 92, 104)), ("iron2", (138, 142, 154)),
    ("skin0", (196, 182, 152)), ("skin1", (230, 220, 194)), ("skin2", (248, 242, 224)),
    ("stage0", (84, 42, 28)), ("stage1", (128, 70, 44)), ("stage2", (160, 94, 58)), ("stage3", (188, 122, 78)),
    ("barrel0", (82, 46, 20)), ("barrel1", (120, 72, 32)), ("barrel2", (158, 102, 50)), ("barrel3", (194, 138, 78)),
    ("wicker0", (120, 84, 40)), ("wicker1", (176, 132, 70)), ("wicker2", (216, 178, 108)),
    ("flame", (255, 214, 72)), ("card", (244, 238, 222)),
]
FEST, F = m7_palette(FEST_COLOURS)

# the loop of aisles: their bands, the inside, the tables, the bars and the band stand
AISLES = ((80, 176), (848, 944))                    # the two bands in each direction (x and y)
TABLE_ROWS = (208, 432, 656)                        # the tables' tops (y); each table 160 long
SET_X0, SET_STEP = 216, 56                          # bench, table (24), bench, then a walkway of 16
BARS = ((176, 176), (752, 752))                     # the top left corners of the two bars, 96 x 96
STAND = 512                                         # the band stand's centre (x and y)


def straw_patch():
    """32 x 16 of trampled straw over grass, seamless in both directions."""
    p = [[F["straw0"]] * 32 for _ in range(16)]
    seed = 7
    def rnd(n):
        nonlocal seed
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        return (seed >> 8) % n
    for _ in range(9):                                         # flattened grass in clumps
        x, y = rnd(32), rnd(16)
        for a, b in ((0, 0), (1, 0), (0, 1), (1, 1), (2, 0)):
            p[(y + b) % 16][(x + a) % 32] = F["grass1"]
        p[(y + 2) % 16][(x + 1) % 32] = F["grass0"]
    for _ in range(14):                                        # straws, 3 or 4 long, lying about
        x, y, d = rnd(32), rnd(16), rnd(3)
        for k in range(3 + rnd(2)):
            xx, yy = (x + k) % 32, (y + (k if d == 1 else -k if d == 2 else 0) // 2) % 16
            p[yy][xx] = F["straw2"] if k < 2 else F["straw1"]
            if p[(yy + 1) % 16][xx] == F["straw0"]:
                p[(yy + 1) % 16][xx] = F["grass0"] if k % 2 else F["straw0"]
    return p


def canvas_wall(u, v, lit):
    """The tent's wall from above at u along the wall, v in from its outer edge (0..63): stripes of
    blue and white running outwards, a dark fold at the outer edge, scallops at the inner one."""
    blue = (u // 8) % 2 == 0
    ramp = ("blue0", "blue1", "blue2", "blue3") if blue else ("white0", "white1", "white2", "white3")
    level = (1, 2, 2, 2, 1, 1, 1, 0)[u % 8] + (1 if lit else 0)
    if v == 0:
        return F["ink"]
    if v == 1:
        level -= 1
    if v >= 56:                                                # the scalloped valance
        def inside(vv):
            return ((u % 8) - 3.5) ** 2 / 16.0 + ((vv - 55.5) / 6.5) ** 2 <= 1.0
        if not inside(v):
            return F["shadow"]
        if not inside(v + 1):
            return F["ink"]
        level += 1 if v < 59 else 0
    return F[ramp[max(0, min(3, level))]]


def plank_run(along, across, origin):
    """Planks lying along a walkway (along = the coordinate the planks run in, across = across
    them, origin = where the walkway begins across): beams at both edges, joints every 64."""
    r, k = (across - origin) // 8, (across - origin) % 8
    if r == 0:
        return F[("plank4", "plank1", "plank1", "plank1", "plank1", "plank1", "plank1", "plank0")[k]]
    if r == 11:
        return F[("plank2", "plank1", "plank1", "plank1", "plank1", "plank1", "plank0", "ink")[k]]
    lit = r % 2 == 0
    if (along - (r * 3 % 8) * 8) % 64 == 0 and 0 < k < 7:      # a joint between two planks
        return F["plank0"]
    if (along - (r * 3 % 8) * 8) % 64 in (2, 61) and k in (2, 5):   # nails
        return F["plank1"]
    if k == 7:
        return F["plank0"]
    if k == 0:
        return F["plank4" if lit else "plank3"]
    return F["plank3" if lit else "plank2"]


def parquet(x, y):
    """Basket weave: squares of 8 with two planks each, turned by 90 degrees from square to square."""
    flat = ((x // 8) + (y // 8)) % 2 == 0
    a, b = (x % 8, y % 8) if not flat else (y % 8, x % 8)
    if a % 4 == 3:
        return F["plank0"]
    if b == 7:
        return F["plank1"]
    if a % 4 == 0:
        return F["plank4"]
    return F["plank3" if a < 4 else "plank2"]


def floor(x, y):
    """The tent's floor: darker planks running north-south, 8 wide, joints every 64."""
    col = x // 8
    if x % 8 == 7 or (y - (col * 3 % 8) * 8) % 64 == 0:
        return F["floor0"]
    return F["floor2" if col % 2 else "floor1"]


def fest_stamp(rows, legend):
    """A small picture from rows of characters, its colours named by legend; '.' is left alone."""
    return [[legend.get(ch) for ch in r] for r in rows]


def put_stamp(c, stamp, x0, y0):
    for y, row in enumerate(stamp):
        for x, col in enumerate(row):
            if col is not None:
                c.px[y0 + y][x0 + x] = F[col]


def round_thing(c, cx, cy, r, colour_of):
    """Paints colour_of(dx, dy, d) (a name, or None to keep) on the pixels within r of (cx, cy)."""
    for y in range(int(cy - r - 1), int(cy + r + 2)):
        for x in range(int(cx - r - 1), int(cx + r + 2)):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d <= r:
                col = colour_of(dx, dy, d)
                if col is not None:
                    c.px[y][x] = F[col]


def stein_top(c, cx, cy, r=5.0, full=True, handle=True):
    """A one-litre stein from above: the glass rim, an amber ring of beer, a cap of foam."""
    if handle:
        for y in range(int(cy - 2), int(cy + 2)):
            for x in range(int(cx + r - 1), int(cx + r + 3)):
                edge = y in (int(cy - 2), int(cy + 1)) or x == int(cx + r + 2)
                c.px[y][x] = F["ink" if edge else "glass1"]
        c.px[int(cy - 1)][int(cx + r + 1)] = F["glass2"]
    def col(dx, dy, d):
        if d > r - 1:
            return "ink"
        if d > r - 2:
            return "glass2" if dx + dy < -1 else "glass0" if dx + dy > 2 else "glass1"
        if d > r - 3 or not full:
            return "amber2" if dx + dy < 0 else "amber1"
        return "foam1" if dx + dy < 1 else "foam0"
    round_thing(c, cx, cy, r, col)


def pretzel_top(c, x0, y0):
    """The pretzel of the enemies (PRETZEL), lying on the table, glazed, salted."""
    mask = {(x0 + x, y0 + y) for y, r in enumerate(PRETZEL) for x, ch in enumerate(r) if ch == "o"}
    for x, y in mask:
        c.px[y][x] = F["pretz2" if (x - 1, y) not in mask or (x, y - 1) not in mask else
                       "pretz0" if (x + 1, y) not in mask or (x, y + 1) not in mask else "pretz1"]
    for x, y in ((3, 0), (11, 1), (7, 4), (13, 3), (5, 6), (10, 6)):
        if (x0 + x, y0 + y) in mask:
            c.px[y0 + y][x0 + x] = F["foam1"]
    for x, y in mask:                                          # a dark edge on the lower right
        for a, b in ((1, 0), (0, 1)):
            if (x + a, y + b) not in mask:
                c.px[y + b][x + a] = F["ink"]


def decor(kind):
    """A 16 x 16 thing standing on a table, drawn on a scrap of canvas: returns {(x, y): colour}."""
    c = Canvas(16, 16)
    if kind == "steins":
        stein_top(c, 5.0, 5.0, 4.6)
        stein_top(c, 8.0, 11.0, 4.6, full=False)
    elif kind == "stein":
        stein_top(c, 6.0, 8.0, 5.2)
    elif kind == "pretzel":
        pretzel_top(c, 0, 3)
    elif kind == "basket":
        def wick(dx, dy, d):
            if d > 6.5:
                return "ink"
            if d > 5.0:
                return "wicker2" if (int(math.atan2(dy, dx) * 6) % 2) else "wicker1"
            return "wicker0"
        round_thing(c, 8.0, 8.0, 7.5, wick)
        for px, py in ((4, 5), (8, 9)):
            for y, r in enumerate([".oo.oo.", "o..o..o", ".ooooo."]):
                for x, ch in enumerate(r):
                    if ch == "o":
                        c.px[py + y][px + x] = F["pretz1" if y < 2 else "pretz0"]
            c.px[py][px + 1] = F["pretz2"]
            c.px[py + 1][px] = F["pretz2"]
            c.px[py][px + 4] = F["foam1"]
    elif kind == "hat":
        def brim(dx, dy, d):
            if d > 6.6:
                return "ink"
            if d > 4.2:
                return "felt2" if dx + dy < -2 else "felt0" if dx + dy > 3 else "felt1"
            if d > 3.4:
                return "red1"                                  # the hat band
            return "felt2" if dx + dy < 0 else "felt1"
        round_thing(c, 8.0, 9.0, 7.0, brim)
        for x, y, col in ((2, 1, "iron0"), (3, 1, "iron0"), (1, 2, "iron0"), (2, 2, "white2"),
                          (3, 2, "iron0"), (4, 2, "iron0"), (2, 3, "white2"), (3, 3, "white3"),
                          (4, 3, "iron0"), (3, 4, "iron0"), (4, 4, "white2"), (5, 5, "felt0"),
                          (1, 1, "ink"), (2, 0, "ink"), (3, 0, "ink"), (0, 2, "ink"), (5, 3, "ink"),
                          (5, 4, "ink")):
            c.px[y][x] = F[col]                                # a tuft of chamois hair (Gamsbart)
    elif kind == "puddle":
        blob = disk(7.0, 8.0, 5.0) | disk(11.0, 10.0, 3.5) | disk(4.0, 11.0, 3.0)
        for x, y in blob:
            c.px[y][x] = F["amber1"]
        for x, y in blob:
            if (x + 1, y) not in blob or (x, y + 1) not in blob:
                c.px[y][x] = F["amber0"]
        for x, y in ((5, 6), (6, 6), (5, 7), (10, 9)):
            c.px[y][x] = F["amber2"]
        c.px[5][4] = F["foam1"]
    elif kind == "candle":
        round_thing(c, 6.0, 6.0, 5.0, lambda dx, dy, d: "ink" if d > 4.0 else
                    "brass3" if dx + dy < -2 else "brass1" if d > 2.6 else "card")
        c.px[5][5] = F["flame"]
        c.px[5][6] = F["flame"]
        c.px[6][5] = F["flame"]
        c.px[4][5] = F["foam1"]
        c.rect(9, 9, 15, 15, F["ink"])                          # a table number on a card
        c.rect(10, 10, 14, 14, F["card"])
        c.rect(10, 14, 14, 14, F["skin0"])
        for x, y in ((11, 11), (12, 11), (13, 11), (13, 12), (12, 13)):
            c.px[y][x] = F["red1"]
    return {(x, y): c.px[y][x] for y in range(16) for x in range(16) if c.px[y][x]}


# what stands on the tables, in turn, and where across the table (0: left and middle board, 8:
# middle and right); None leaves a place empty
DECOR = (("steins", 0), ("steins", 8), ("stein", 0), ("stein", 8), ("pretzel", 8), ("steins", 8),
         ("basket", 8), ("stein", 0), ("hat", 0), ("steins", 0), ("puddle", 8), ("candle", 0), None, None)


def table_set(c, x0, y0):
    """A beer table with its two benches, 40 x 160, from (x0, y0): bench 8, table 24, bench 8."""
    for q in range(160):
        y = y0 + q
        for p in range(8):                                     # the benches, 6 wide, 2 of floor
            if 1 <= q <= 158:
                left = ("bench2", "bench1", "bench1", "bench1", "bench1", "bench0", "floor0", "floor0")[p]
                right = ("floor0", "floor0", "bench2", "bench1", "bench1", "bench1", "bench1", "bench0")[p]
                if q == 1:                                     # the lit ends
                    left = "bench2" if p < 6 else "floor0"
                    right = "bench2" if p >= 2 else "floor0"
                if q == 158:                                   # the ends in shade
                    left = "bench0" if p < 6 else "floor0"
                    right = "bench0" if p >= 2 else "floor0"
            else:
                left = right = "floor0"
            c.px[y][x0 + p] = F[left]
            c.px[y][x0 + 32 + p] = F[right]
        for p in range(24):
            if q == 0 or q == 159 or p == 0 or p == 23:
                col = "table0" if q < 159 and p < 23 else "ink"
            elif q == 1 or (p % 8 == 0):
                col = "table3"
            elif p % 8 == 7 or q == 158:
                col = "table1"
            else:
                col = "table2"
            c.px[y][x0 + 8 + p] = F[col]


def bar(c, x0, y0):
    """The bar (Ausschank), 96 x 96 from (x0, y0): three barrels and a tray of clean steins upside
    down behind an L-shaped counter along the east and the south side, full steins standing on it."""
    for y in range(y0, y0 + 64):                               # the floor behind the counter
        for x in range(x0, x0 + 64):
            c.px[y][x] = F["floor0"]
    def head(dx, dy, d):                                       # a barrel's head: boards, a hoop, a bung
        if d > 11.0:
            return "ink"
        if d > 9.4:
            return "iron2" if dx + dy < -5 else "iron1" if dx + dy < 5 else "iron0"
        if d > 8.4:
            return "barrel0"
        if 1.2 < math.hypot(dx - 3, dy + 3) < 2.9:
            return "barrel0"
        if (dy + 12) % 6 < 1.0:
            return "barrel0"
        return "barrel3" if dx + dy < -7 else "barrel2" if dx + dy < 5 else "barrel1"
    for bx, by in ((12, 12), (36, 12), (12, 36)):
        round_thing(c, x0 + bx, y0 + by, 11.8, head)
    for y in range(y0 + 32, y0 + 64):                          # the tray, 32 x 32, steins upside down
        for x in range(x0 + 32, x0 + 64):
            e = min(x - x0 - 32, y - y0 - 32, x0 + 63 - x, y0 + 63 - y)
            c.px[y][x] = F["ink" if e == 0 else "iron2" if e == 1 else "iron1"]
    def bottom(dx, dy, d):
        return "ink" if d > 3.0 else "glass2" if dx + dy < -1.5 else "glass1" if d > 1.4 else "glass0"
    for sy in range(4):
        for sx in range(4):
            round_thing(c, x0 + 36 + 8 * sx, y0 + 36 + 8 * sy, 3.6, bottom)
    for y in range(y0, y0 + 80):                               # the counter: east arm, south arm
        for x in range(x0, x0 + 80):
            p = x - x0 - 64
            q = y - y0 - 64
            if p < 0 and q < 0:
                continue
            e = max(p, q)
            col = ("ink", "table2", "table1", "table1", "table1", "table1", "table1", "table0",
                   "table2", "table1", "table1", "table1", "table0", "ink", "brass2", "brass0")[e]
            c.px[y][x] = F[col]
    for k in range(4):                                         # full steins along the counter
        stein_top(c, x0 + 70.0, y0 + 8.0 + 16 * k, 4.6, handle=False)
        stein_top(c, x0 + 8.0 + 16 * k, y0 + 70.0, 4.6, handle=False)


def band_stand(c):
    """The band stand: an octagonal wooden stage with a painted skirting and a brass rail, chairs
    with music stands in two rows, the bass drum, the conductor's podium; the tuba is a sprite."""
    cx = cy = STAND
    def octagon(x, y, inset):
        dx, dy = abs(x + 0.5 - cx), abs(y + 0.5 - cy)
        return max(dx, dy) <= 104 - inset and dx + dy <= 144 - inset * 1.414
    for y in range(cy - 112, cy + 112):
        for x in range(cx - 112, cx + 112):
            if not octagon(x, y, 0):
                continue
            if not octagon(x, y, 1.0):
                col = "ink"
            elif not octagon(x, y, 3.0):
                col = "blue2"
            elif not octagon(x, y, 4.0):
                col = "blue1"
            elif not octagon(x, y, 5.0):
                col = "ink"
            elif not octagon(x, y, 6.0):
                col = "brass2"
            elif not octagon(x, y, 7.0):
                col = "brass0"
            else:
                k = y % 8
                col = "stage0" if k == 7 else "stage3" if k == 0 else "stage2" if k < 4 else "stage1"
            c.px[y][x] = F[col]
    chair = fest_stamp(["................",
                        "...xxxxxxxxxx...",
                        "...xbbbbbbbbx...",
                        "...xaaaaaaaax...",
                        "...xccccccccx...",
                        "...xccccccccx...",
                        "...xcccccccdx...",
                        "...xddddddddx...",
                        "...xxxxxxxxxx...",
                        "................",
                        ".....xxxxxx.....",
                        "....xwwwwwwx....",
                        "....xwkwwkwx....",
                        "....xxxxxxxx....",
                        ".......xx.......",
                        "................"],
                       {"x": "ink", "a": "stage0", "b": "stage3", "c": "stage2", "d": "stage1",
                        "w": "card", "k": "iron0"})
    for x0, y0 in ((432, 432), (464, 416), (496, 416), (528, 416), (560, 432),
                   (448, 464), (480, 448), (544, 448), (576, 464)):
        put_stamp(c, chair, x0, y0)
    def drum(dx, dy, d):
        if d > 11.0:
            return "ink"
        if d > 8.6:
            f = (math.atan2(dy, dx) / (2 * math.pi) * 10) % 1     # ten brass tension lugs
            if f < 0.13 or f > 0.87:
                return "brass2"
            return "red2" if dx + dy < -4 else "red1" if dx + dy < 6 else "red0"
        if d > 7.8:
            return "ink"
        return "skin2" if dx + dy < -4 else "skin1" if dx + dy < 5 else "skin0"
    round_thing(c, 436.0, 516.0, 11.8, drum)
    for x, y in ((430, 510), (431, 510), (430, 511)):
        c.px[y][x] = F["white3"]
    podium = fest_stamp(["xxxxxxxxxxxxxxxxxxxxxxxx",
                         "xbbbbbbbbbbbbbbbbbbbbbbx",
                         "xbcccccccxxxxxxxcccccccx",
                         "xbccccccxwwwwwwwxccccccx",
                         "xbccccccxwkwkwkwxccccccx",
                         "xbccccccxxxxxxxxxccccccx",
                         "xbcccccccccxxccccccccccx",
                         "xbccccccccccccccccccccdx",
                         "xbcccccccccbbcccccccccdx",
                         "xbccccccccbbbbccccccccdx",
                         "xbcccccccccbbcccccccccdx",
                         "xbccccccccccccccccccccdx",
                         "xbccccccccccccccccccccdx",
                         "xbccccccccccccccccccccdx",
                         "xddddddddddddddddddddddx",
                         "xxxxxxxxxxxxxxxxxxxxxxxx"],
                        {"x": "ink", "b": "red2", "c": "red1", "d": "red0", "w": "card", "k": "iron0"})
    put_stamp(c, podium, 500, 568)                              # the conductor's podium


def aisle_litter(c):
    """Spilt beer and a lost pretzel on the walkways, each always at the same place in the 64 px
    pattern of the planks, so that every copy is made of the same tiles."""
    for y0 in (80, 848):                                       # the walkways running east-west
        for x in (264, 456, 712):
            for (dx, dy), col in decor("puddle").items():
                c.px[y0 + 24 + dy][x + dx] = col
        for x in (360, 616):
            pretzel_top(c, x, y0 + 60)
    for x0 in (80, 848):                                       # and north-south
        for y in (328, 584, 776):
            for (dx, dy), col in decor("puddle").items():
                c.px[y + dx][x0 + 32 + dy] = col


def festzelt():
    """The beer tent from above, 1024 x 1024: the striped canvas wall around it, a strip of straw,
    the loop of plank walkways the camera flies along, and inside the beer tables, the two bars
    and the band stand."""
    c = Canvas(1024, 1024)
    straw = straw_patch()
    (a0, a1), (b0, b1) = AISLES
    def band(v):                                               # which aisle band v lies in, or None
        return a0 if a0 <= v < a1 else b0 if b0 <= v < b1 else None
    for y in range(1024):
        row = c.px[y]
        for x in range(1024):
            v = min(x, y, 1023 - x, 1023 - y)
            if v < 64:
                sides = [(y, x, True), (x, y, True), (1023 - y, x, False), (1023 - x, y, False)]
                sides.sort(key=lambda s: s[0])
                (v0, u0, lit0), (v1, _, _) = sides[0], sides[1]
                row[x] = F["ink"] if v1 == v0 and v0 > 0 else canvas_wall(u0, v0, lit0)
            elif v < 80:
                row[x] = straw[y % 16][x % 32]
            else:
                bx, by = band(x), band(y)
                if bx is not None and by is not None:
                    row[x] = parquet(x, y)
                elif by is not None:
                    row[x] = plank_run(x, y, by)
                elif bx is not None:
                    row[x] = plank_run(y, x, bx)
                else:
                    row[x] = floor(x, y)
    # the poles of the wall, every 128, and their guy ropes running out over the canvas
    def pole(dx, dy, d):
        if d > 5.0:
            return "ink"
        return "brass3" if dx + dy < -3 else "brass2" if dx + dy < 1 else "brass1" if dx + dy < 4 else "brass0"
    for u in range(128, 1024 - 64, 128):
        for side in range(4):
            def at(uu, vv):
                return [(uu, vv), (vv, uu), (uu, 1023 - vv), (1023 - vv, uu)][side]
            for vv in range(2, 43):
                for du, col in ((2, "rope0"), (3, "rope1"), (4, "rope0")):
                    x, y = at(u + 8 + du, vv)
                    c.px[y][x] = F[col]
            px, py = at(u + 8, 48)
            round_thing(c, px + (side == 3), py + (side == 2), 5.5, pole)
    # the inside: tables in three rows, the bars in two corners, the band stand in the middle
    for k in range(11):
        x0 = SET_X0 + SET_STEP * k
        for y0 in TABLE_ROWS:
            if y0 == 208 and k < 2 or y0 == 656 and k > 8 or y0 == 432 and 3 <= k <= 7:
                continue
            table_set(c, x0, y0)
            for i, q in enumerate(range(16, 144, 16)):
                pick = DECOR[(i * 5 + k * 3 + y0 // 16 * 7) % len(DECOR)]
                if pick:
                    kind, col = pick
                    for (x, y), v in decor(kind).items():
                        c.px[y0 + q + y][x0 + 8 + col + x] = v
    aisle_litter(c)
    for bx, by in BARS:
        bar(c, bx, by)
    band_stand(c)
    return c


KARUSSELL_COLOURS = [
    ("ink", (14, 8, 16)),
    ("gnd0", (18, 19, 34)), ("gnd1", (25, 27, 46)), ("gnd2", (33, 35, 58)), ("gnd3", (44, 44, 72)),
    ("glow0", (58, 48, 62)), ("glow1", (98, 76, 68)), ("glow2", (156, 118, 78)),
    ("lan0", (128, 30, 34)), ("lan1", (206, 70, 40)), ("lan2", (250, 140, 56)), ("lan3", (255, 220, 140)),
    ("bulb0", (255, 206, 96)), ("bulb1", (255, 250, 220)), ("wire", (8, 6, 12)),
    ("plat0", (34, 22, 24)), ("plat1", (54, 36, 34)), ("plat2", (72, 48, 42)), ("plat3", (96, 66, 52)),
    ("trim0", (110, 22, 38)), ("trim1", (170, 40, 52)),
    ("red0", (104, 14, 26)), ("red1", (160, 24, 36)), ("red2", (214, 46, 50)), ("red3", (248, 104, 92)),
    ("white0", (168, 156, 170)), ("white1", (210, 202, 206)), ("white2", (240, 234, 228)), ("white3", (255, 254, 246)),
    ("yel0", (180, 110, 12)), ("yel2", (255, 220, 76)),
    ("gold1", (156, 108, 30)), ("gold2", (212, 162, 52)), ("gold3", (250, 214, 112)),
    ("crown0", (78, 54, 22)), ("crown1", (108, 78, 30)),
    ("blue2", (86, 140, 222)),
]
KARUSSELL, K = m7_palette(KARUSSELL_COLOURS)
CANOPY_R = 56


def gravel_patch():
    """16 x 16 of dark gravel at night, seamless, very low contrast, specks of 2 pixels."""
    p = [[K["gnd1"]] * 16 for _ in range(16)]
    seed = 11
    def rnd(n):
        nonlocal seed
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        return (seed >> 8) % n
    for k in range(14):
        x, y = rnd(16), rnd(16)
        col = K["gnd2"] if k % 3 else K["gnd3"]
        p[y][x] = col
        p[y][(x + 1) % 16] = col
        p[(y + 1) % 16][x] = K["gnd0"]
        p[(y + 1) % 16][(x + 1) % 16] = K["gnd0"]
    return p


def canopy_pixel(dx, dy):
    """The carousel's canopy at (dx, dy) from its centre, or None outside it: a roof of twelve
    flat segments, red and white, each with an ornament, a gilded rim with light bulbs, a scalloped
    valance; the middle a plain dark gold disk where the crown (a sprite) sits."""
    r = math.hypot(dx, dy)
    th = math.atan2(dy, dx)
    turn = (th / (2 * math.pi) + 1 / 24) % 1.0                 # 0..1 around, segment 0 centred east
    seg = int(turn * 12)
    across = turn * 12 - seg                                   # 0..1 across a segment
    facing = math.cos(th - math.radians(-135))                 # 1 towards the light (upper left)
    red = seg % 2 == 0
    if r <= 11.5:
        return "crown1" if r <= 10.5 else "crown0"
    if r <= 13.0:
        return "ink"
    scallop = (turn * 24) % 1.0
    edge = 51.5 + 4.5 * math.sqrt(max(0.0, 1 - (2 * scallop - 1) ** 2))
    if r > edge:
        return None
    if r > edge - 1.2:
        return "ink"
    level = 2 if facing > 0.45 else 1 if facing > -0.45 else 0
    ramp = ("red1", "red2", "red3") if red else ("white0", "white1", "white2")
    if r > 49.0:                                               # the valance, a shade darker
        if scallop < 0.06 or scallop > 0.94:
            return "ink"
        return ramp[max(0, level - 1)] if r > edge - 2.5 else ramp[level]
    if r > 47.5:
        return "ink"
    if r > 41.0:                                               # the gilded rim and its bulbs
        k = (th / (2 * math.pi) * 36 + 0.5) % 1.0
        bx = math.hypot(r - 44.25, (k - 0.5) * 2 * math.pi * 44.25 / 36)
        if bx < 1.3:
            return "bulb1"
        if bx < 2.4:
            return "bulb0"
        if r > 46.5 or r < 42.0:
            return "gold1" if r > 46.5 else "gold3" if facing > 0 else "gold2"
        return "gold3" if facing > 0.5 else "gold2" if facing > -0.5 else "gold1"
    if r > 39.6:
        return "ink"
    if across < 0.035 or across > 0.965 or (r < 22 and (across < 0.06 or across > 0.94)):
        return "ink"                                           # the seams between the segments
    # the ornament, in the segment's own frame: u outwards, w across
    mid = (seg / 12.0) * 2 * math.pi
    u = dx * math.cos(mid) + dy * math.sin(mid) - 27.0
    w = -dx * math.sin(mid) + dy * math.cos(mid)
    kind = seg % 3
    hit = None
    if kind == 0:                                              # a five-pointed star
        rr = math.hypot(u, w)
        a = math.atan2(w, u)
        lim = 3.0 + 3.6 * (math.cos(5 * a) * 0.5 + 0.5) ** 1.6
        if rr <= lim + 0.9:
            hit = "edge" if rr > lim else "fill"
    elif kind == 1:                                            # a heart, its point inwards
        def heart(scale):
            hx, hy = w / scale, u / scale + 0.15
            return (hx * hx + hy * hy - 1) ** 3 - hx * hx * hy ** 3 <= 0
        if heart(5.6):
            hit = "fill"
        elif heart(6.8):
            hit = "edge"
    else:                                                      # a spiral
        rr = math.hypot(u, w)
        a = math.atan2(w, u)
        if rr <= 7.6:
            ph = (rr - a / (2 * math.pi) * 3.8) % 3.8
            hit = "fill" if ph < 2.0 or rr < 1.6 else None
    if hit == "fill" and kind == 2:                            # spirals: white on red, blue on white
        return "white3" if red else "blue2"
    if hit:                                                    # stars and hearts: yellow on red, red on white
        return ("yel2" if hit == "fill" else "yel0") if red else ("red2" if hit == "fill" else "red0")
    return ramp[level]


def octagon_in(dx, dy, inset, half=96, diag=136):
    return max(abs(dx), abs(dy)) <= half - inset and abs(dx) + abs(dy) <= diag - inset * 1.414


def platform_pixel(dx, dy, y):
    """The carousel's round floor far below the canopy: planks, a painted edge; None outside."""
    if not octagon_in(dx, dy, 0):
        return None
    if not octagon_in(dx, dy, 1):
        return "ink"
    if not octagon_in(dx, dy, 4):
        return "trim1" if octagon_in(dx, dy, 2) else "gold2"
    if not octagon_in(dx, dy, 5):
        return "trim0"
    if not octagon_in(dx, dy, 6):
        return "ink"
    shadow = math.hypot(dx - 6, dy - 6) < CANOPY_R + 1
    k = y % 8
    if k == 7:
        return "plat0"
    if shadow:
        return "plat1" if k else "plat2"
    return "plat3" if k == 0 else "plat2"


def light_patch(c, cx, cy, kind):
    """A lit thing on the ground around (cx, cy): a lamp post's pool of light (24 across, centred
    in a tile) or a paper lantern seen from above (16 across, centred on a tile corner)."""
    for y in range(int(cy) - 12, int(cy) + 12):
        for x in range(int(cx) - 12, int(cx) + 12):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            col = None
            if kind == "lamp":
                if d < 11.6:
                    col = "glow0"
                if d < 8.6:
                    col = "glow1"
                if d < 5.6:
                    col = "glow2"
                if d < 2.6:
                    col = "ink"
                if d < 1.6:
                    col = "bulb1"
            elif d < 7.8:
                col = "glow0"
                if d < 6.6:
                    col = "ink"
                if d < 5.7:
                    a = math.atan2(dy, dx)
                    rib = abs(((a / (2 * math.pi) * 8) % 1.0) - 0.5) > 0.42
                    col = "lan0" if rib or d > 4.9 else "lan2" if dx + dy < -1 else "lan1"
                if d < 2.0:
                    col = "lan3"
            if col:
                c.px[y][x] = K[col]


def bulb_string(c, x0, x1, y):
    """A string of light bulbs from x0 to x1 (multiples of 16) along the row y (y % 8 == 3)."""
    for x in range(x0, x1):
        c.px[y][x] = K["wire"]
        if x % 16 == 7:
            for b, r in enumerate((".gg.", "g11g", "g11g", ".gg.")):
                for a, ch in enumerate(r):
                    if ch != ".":
                        c.px[y - 1 + b][x - 1 + a] = K["bulb1" if ch == "1" else "bulb0"]


def karussell():
    """The chain carousel from above at night, 1024 x 1024: the canopy in the middle, the
    platform below it, the dark fairground around with a few lights."""
    c = Canvas(1024, 1024)
    g = gravel_patch()
    for y in range(1024):
        row = c.px[y]
        gy = g[y % 16]
        for x in range(1024):
            row[x] = gy[x % 16]
    for j in range(16):                                        # lamps and lanterns every 64
        for i in range(16):
            cx, cy = 32 + 64 * i, 32 + 64 * j
            if math.hypot(cx - 512, cy - 512) < 190 or (i + j) % 2:
                continue
            if (i // 2 + j // 2) % 2:
                light_patch(c, cx + 4, cy + 4, "lamp")
            else:
                light_patch(c, cx, cy, "lantern")
                if (i + 2 * j) % 3 and i < 14 and math.hypot(cx + 64 - 512, cy - 512) > 200:
                    bulb_string(c, cx + 16, cx + 112, cy + 3)
    for y in range(512 - 140, 512 + 140):
        for x in range(512 - 140, 512 + 140):
            dx, dy = x + 0.5 - 512, y + 0.5 - 512
            col = canopy_pixel(dx, dy) if dx * dx + dy * dy < 57 * 57 else None
            if col is None:
                col = platform_pixel(dx, dy, y)
            if col is not None:
                c.px[y][x] = K[col]
    return c


# --- sprites_c.png: the sprites of stage 3, copied over rows 4..7 of sprites_b while it runs ------
SPC = [(0, 0, 0), (30, 14, 10), (136, 88, 18), (212, 160, 44), (255, 230, 130), (104, 48, 20),
       (176, 92, 34), (232, 156, 60), (146, 74, 40), (252, 248, 238), (240, 136, 170), (214, 38, 42),
       (128, 18, 28), (255, 214, 40), (12, 10, 16), (250, 124, 32)]


HENDL = ["................",
         "................",
         "...........99...",
         "..........699...",
         ".........6766...",
         "........7766....",
         ".......7766.....",
         "....4777666.....",
         "..47747777666...",
         ".4777777477665..",
         ".7747777777665..",
         ".6777777776655..",
         ".6667774666555..",
         "..66666665555...",
         "...555555555....",
         "................"]
HENDL_WINGS = ((["..7.....", "..77....", "..776...", "...776..", "...7766.", "....766."], 0, 2),
               (["..6666..", ".57776..", ".5776...", "..576...", "..55...."], 2, 10))


def hendl(flap):
    """A roast chicken (Hendl), golden and crisp, a drumstick up at the back, flying left on a
    little roast wing: flap 0 wing up, 1 down."""
    c = art(HENDL, {"4": 4, "5": 5, "6": 6, "7": 7, "9": 9})
    rows, dx, dy = HENDL_WINGS[flap]
    c.blit(art(rows, {"5": 5, "6": 6, "7": 7}), dx, dy)
    c.outline(1)
    return c


def gingerbread_heart(tilt):
    """A gingerbread heart from the fair on its string, swinging: tilt in degrees (+ clockwise),
    turned about the top of the string."""
    c = Canvas(16, 16)
    a = math.radians(tilt)
    px, py = 8.0, 0.0                                                  # the pivot
    def local(x, y):
        dx, dy = x + 0.5 - px, y + 0.5 - py
        return px + dx * math.cos(a) + dy * math.sin(a), py - dx * math.sin(a) + dy * math.cos(a)
    def heart(lx, ly, s):                                              # two round lobes on a point
        hx, hy = (lx - 8.0) / s, -(ly - 8.4) / s
        return (math.hypot(hx - 0.47, hy - 0.3) < 0.53 or math.hypot(hx + 0.47, hy - 0.3) < 0.53
                or -1.0 <= hy <= 0.3 and abs(hx) <= (hy + 1.0) * 0.76)
    for y in range(16):
        for x in range(16):
            lx, ly = local(x, y)
            if heart(lx, ly, 5.5):
                if not heart(lx, ly, 4.4):
                    c.set(x, y, 9)                                       # the icing border
                else:
                    c.set(x, y, 6 if (lx - 8) + (ly - 8) < -5 else 8 if (lx - 8) + (ly - 8) < 5 else 5)
                    wave = ly - (9.4 + 0.9 * math.sin((lx - 4.0) * 1.3))
                    if abs(wave) < 0.6 and 4.0 < lx < 12.0:
                        c.set(x, y, 9)                                   # a squiggle of icing
                    if math.hypot(lx - 5.6, ly - 6.8) < 0.8 or math.hypot(lx - 10.4, ly - 6.8) < 0.8:
                        c.set(x, y, 10)                                  # pink dots
            if math.hypot((lx - 6.6) / 1.2, (ly - 3.6) / 0.8) < 1 or math.hypot((lx - 9.4) / 1.2, (ly - 3.6) / 0.8) < 1:
                c.set(x, y, 11)                                          # the ribbon's bow
            if math.hypot(lx - 8.0, ly - 3.9) < 0.8:
                c.set(x, y, 12)
            if abs(lx - 8.0) < 0.5 and 0.0 <= ly < 3.2:
                c.set(x, y, 11)                                          # the string
    c.outline(1)
    return c


def small_sprites_c4():
    """Four 8 x 8: a link of the chains (TL), a quarter note (TR), an eighth note (BL), a sparkle (BR)."""
    c = Canvas(16, 16)
    c.from_text(["..111...", ".14331..", ".13321..", ".13221..", "..111..."], {"1": 1, "2": 2, "3": 3, "4": 4}, 1, 1)
    c.from_text(["...ee.", "...ee.", "...ee.", "...ee.", ".eeee.", "e9eee.", ".eee.."], {"e": 14, "9": 9}, 9, 0)
    c.from_text(["..eeee.", "..ee.ee", "..ee..e", "..ee...", "eeee...", "9eee...", "eee...."], {"e": 14, "9": 9}, 0, 9)
    c.from_text(["..d..", "..9..", "d999d", "..9..", "..d.."], {"d": 13, "9": 9}, 10, 10)
    c.set(11, 11, 4)
    c.set(13, 11, 4)
    c.set(11, 13, 4)
    c.set(13, 13, 4)
    return c


def pretzel_bullet():
    c = Canvas(16, 16)
    c.from_text([".67.67.", "6..7..6", "6.7.6.5", ".6...5.", "..655.."], {"6": 6, "7": 7, "5": 5}, 0, 1)
    c.set(2, 1, 9)
    c.set(5, 3, 9)
    c.outline(1)
    return c


def carousel_seat(stein):
    """A seat of the chain carousel from above, a little from the front, 12 x 12: the red padded
    back with a gilded frame and two rings for the chains, the seat, the footrest below on two
    rods; stein: a full beer stein rides in it."""
    c = Canvas(16, 16)
    c.from_text([".4........4.",
                 "4.4......4.4",
                 ".43333333342",
                 ".3bbbbbbbb2.",
                 ".3bccccccb2.",
                 ".3bbbbbbbb2.",
                 "3bbbbbbbbbb2",
                 "3bbbbbbbbbc2",
                 "3bbbbbbbbcc2",
                 ".2222222222.",
                 "..2......2..",
                 ".33333333332"], {"2": 2, "3": 3, "4": 4, "b": 11, "c": 12}, 2, 2)
    if stein:
        foam = disk(8.0, 9.5, 2.7)
        for x, y in disk(8.0, 9.5, 3.6):
            c.set(x, y, 3 if (x - 7) + (y - 9) < 0 else 2)
        for x, y in foam:
            c.set(x, y, 9 if (x - 7) + (y - 9) < 1 else 4)
        c.set(11, 9, 3)
        c.set(11, 10, 2)
    c.outline(1)
    return c


def tuba(blow):
    """Die Tuba, 64 x 32, lying on its side: the bell (dark inside) at the left with two angry
    eyes by its rim, the coiled tubing and three valves in the middle, the mouthpiece at the right.
    blow: the bell's lips flared, a puff and notes in the opening, the tubing squeezed."""
    c = Canvas(64, 32)
    cy = 16.0
    def tube(x, y, v, glint=True):
        """Brass across a tube: v from -1 (top) to 1 (bottom)."""
        c.set(x, y, 9 if -0.8 < v < -0.45 and glint else 4 if v < -0.2 else 3 if v < 0.5 else 2)
    # the loop of tubing, squeezed when blowing
    lx, ly, lrx, lry = (46.0, 19.0, 13.0, 9.0) if not blow else (46.0, 19.5, 13.6, 7.8)
    for y in range(32):
        for x in range(30, 62):
            e = math.hypot((x + 0.5 - lx) / lrx, (y + 0.5 - ly) / lry)
            d = (e - 1.0) * min(lrx, lry)
            if abs(d) < 3.2:
                side = (y + 0.5 - ly) / lry
                tube(x, y, -d / 3.2 if side < 0 else d / 3.2)
    for x in range(36, 57):                                          # a slide across the loop
        for y in range(int(ly) - 1, int(ly) + 2):
            if c.get(x, y) == 0:
                tube(x, y, (y - ly + 0.5) / 1.5)
    # the three valves on the top of the loop, finger buttons on them
    top = int(ly - lry)
    for vx in (39, 45, 51):
        for y in range(top - 6, top + 2):
            for x, col in zip(range(vx, vx + 4), (4, 3, 3, 2)):
                c.set(x, y, col)
        c.rect(vx - 1, top - 7, vx + 4, top - 7, 3)
        c.set(vx - 1, top - 7, 4)
        c.rect(vx, top - 9, vx + 3, top - 8, 4)
        c.set(vx, top - 9, 9)
    # the mouthpiece: a thin pipe up out of the loop's right side, a little cup
    for x, y in ((58, 13), (59, 12), (60, 11), (61, 10)):
        c.set(x, y, 4)
        c.set(x, y + 1, 2)
    c.rect(61, 7, 63, 8, 3)
    c.set(61, 7, 4)
    # the bell flaring out to the left, its rim, the dark opening
    rcx, rrx, rry = (6.0, 5.0, 14.5) if not blow else (6.5, 6.0, 15.5)
    for x in range(int(rcx), 37):
        h = 3.8 + (rry - 3.8) * ((36.0 - x) / (36.0 - rcx)) ** 1.6
        for y in range(32):
            v = (y + 0.5 - cy) / h
            if abs(v) <= 1.0:
                tube(x, y, v, x > 22)
    for y in range(32):
        for x in range(16):
            e = math.hypot((x + 0.5 - rcx) / rrx, (y + 0.5 - cy) / rry)
            if e <= 1.0:
                inner = math.hypot((x + 0.5 - rcx - 0.6) / (rrx - 1.8), (y + 0.5 - cy) / (rry - 2.0))
                if inner <= 1.0:
                    c.set(x, y, 14 if inner < 0.8 else 1)
                else:
                    c.set(x, y, 9 if y + 0.5 < cy - 8 and x + 0.5 < rcx else 4 if y + 0.5 < cy - 2 else
                          3 if y + 0.5 < cy + 7 else 2)
    if blow:                                                         # a puff and two notes in it
        for px, py, r in ((4.0, 16.0, 2.4), (6.0, 14.0, 1.8), (5.5, 18.2, 1.7)):
            for x, y in disk(px, py, r):
                c.set(x, y, 9)
        c.from_text(["..dd", "..dd", "..d.", "ddd.", "dd.."], {"d": 13}, 4, 4)
        c.from_text(["..ddd", "..d.d", "..d..", "ddd..", "dd..."], {"d": 13}, 3, 22)
    c.outline(1)
    # two angry eyes by the rim, looking left, brows down towards the middle
    for ex in (11, 17):
        c.rect(ex - 1, 8, ex + 4, 13, 1)
        c.rect(ex, 9, ex + 3, 12, 9)
        c.rect(ex, 10, ex + 1, 12, 14)
    for i in range(5):
        c.set(9 + i, 5 + i // 2, 1)
        c.set(9 + i, 6 + i // 2, 1)
        c.set(22 - i, 5 + i // 2, 1)
        c.set(22 - i, 6 + i // 2, 1)
    return c


def crown(open_):
    """The top of the carousel's pole, from above, 32 x 32: a golden dome with a small flag, or
    opened into four petals around a glowing heart (the weak point)."""
    c = Canvas(32, 32)
    if not open_:
        for x, y in disk(16.0, 16.0, 13.5):
            dx, dy = x + 0.5 - 16, y + 0.5 - 16
            d = math.hypot(dx, dy)
            lum = -(dx + dy) / 19.0 + (1 - d / 13.5) * 0.6
            ramp = (2, 3, 4, 9)
            k = 0 if lum < -0.2 else 1 if lum < 0.3 else 2 if lum < 0.78 else 3
            a = (math.atan2(dy, dx) / (2 * math.pi) * 8) % 1.0
            if 4.0 < d < 12.0 and (a < 0.07 or a > 0.93):
                k = max(0, k - 1)                                       # eight ribs
            if d > 12.4:
                k = 0 if dx + dy > -6 else 1                            # the rim
            c.set(x, y, ramp[k])
        for k in range(8):                                              # jewels in a ring
            a = (k + 0.5) * math.pi / 4
            x, y = round(15.5 + math.cos(a) * 9.5), round(15.5 + math.sin(a) * 9.5)
            c.rect(x, y, x + 1, y + 1, 11)
            c.set(x, y, 10)
            c.set(x + 1, y + 1, 12)
        c.from_text([".11......",
                     "1bbbbb...",
                     "1bbbbbbb.",
                     "1ccbbbbbb",
                     "1cccbb...",
                     ".1c......"], {"1": 1, "b": 11, "c": 12}, 16, 11)
        for x, y in disk(16.0, 16.0, 2.6):                              # the knob of the flagpole
            c.set(x, y, 9 if (x - 15) + (y - 15) < 0 else 4 if (x - 15) + (y - 15) < 2 else 3)
    else:
        for k in range(4):                                              # four petals, opened out
            a = k * math.pi / 2 + math.pi / 4
            for y in range(32):
                for x in range(32):
                    dx, dy = x + 0.5 - 16, y + 0.5 - 16
                    u = dx * math.cos(a) + dy * math.sin(a)
                    w = -dx * math.sin(a) + dy * math.cos(a)
                    if 4.0 < u < 16.5:
                        half = 7.0 * math.sin(math.pi * (u - 4.0) / 12.5) ** 0.6
                        if abs(w) < half:
                            col = 4 if abs(w) > half - 1.4 else 2 if abs(w) < 0.8 and u < 13 else 3
                            if abs(w) > half - 1.4 and (dx + dy) > 4:
                                col = 3
                            c.set(x, y, col)
        for x, y in disk(16.0, 16.0, 8.4):                              # the socket, dark, glowing
            c.set(x, y, 1 if math.hypot(x + 0.5 - 16, y + 0.5 - 16) > 7.6 else 14 if math.hypot(x + 0.5 - 16, y + 0.5 - 16) > 6.6 else 12)
        c.from_text([".rrr...rrr.",
                     "rooor.rooor",
                     "royyoroooor",
                     "roywyooooor",
                     "royyyyoooor",
                     ".royyyooor.",
                     "..roooooor.",
                     "...roooor..",
                     "....rorr...",
                     ".....r....."], {"r": 11, "o": 15, "y": 13, "w": 9}, 11, 11)
    c.outline(1)
    return c


def sprites_c():
    s = Canvas(128, 64)
    put(s, 0, hendl(0))
    put(s, 1, hendl(1))
    put(s, 2, gingerbread_heart(-15))
    put(s, 3, gingerbread_heart(15))
    put(s, 4, small_sprites_c4())
    put(s, 5, pretzel_bullet())
    put(s, 6, carousel_seat(False))
    put(s, 7, carousel_seat(True))
    s.blit(tuba(False), 0, 16)                                      # cells 8-11 and 16-19
    s.blit(tuba(True), 64, 16)                                      # cells 12-15 and 20-23
    for k, (cells, opened) in enumerate((((24, 25, 28, 29), False), ((26, 27, 30, 31), True))):
        cr = crown(opened)
        for i, cell in enumerate(cells):                            # TL, TR, BL, BR of the crown
            part = Canvas(16, 16)
            part.px = [row[16 * (i % 2):16 * (i % 2) + 16] for row in cr.px[16 * (i // 2):16 * (i // 2) + 16]]
            put(s, cell, part)
    return s


# =================================================================================================
# main
# =================================================================================================
def main():
    os.makedirs(out, exist_ok=True)
    save_png(os.path.join(out, "sprites_a.png"), sprites_a(), SPA)
    save_png(os.path.join(out, "sprites_b.png"), sprites_b(), SPB)
    save_png(os.path.join(out, "stage_bar.png"), stage_bar(), BAR)
    save_png(os.path.join(out, "stage_cellar.png"), stage_cellar(), CELLAR)
    save_png(os.path.join(out, "stage_kitchen.png"), stage_kitchen(), KITCHEN)
    patterns = []
    for room, draw, pal in (("bar", wall_bar, WALLBAR), ("cellar", wall_cellar, WALLCELLAR),
                            ("kitchen", wall_kitchen, WALLKITCHEN), ("brew", wall_brew, WALLBREW)):
        cells, pattern, _ = cut(draw())
        save_png(os.path.join(out, f"wall_{room}.png"), cells, pal)
        patterns.append((room, pattern))
    src = os.path.join(out, "..", "src")
    os.makedirs(src, exist_ok=True)
    write_walls_c(os.path.join(src, "walls.c"), patterns)
    save_png(os.path.join(out, "hud.png"), hud(), HUD)
    cells, maps = boss_cut([boss_picture(state) for state in range(3)])
    save_png(os.path.join(out, "boss.png"), cells, BOSS)
    cells2, maps2 = boss_cut([sudpfanne(state) for state in range(3)])
    save_png(os.path.join(out, "boss2.png"), cells2, BOSS2)
    write_bossmap_c(os.path.join(src, "bossmap.c"), maps, maps2)
    save_png(os.path.join(out, "stage_brew.png"), stage_brew(), BREW)
    save_png(os.path.join(out, "stage_mett.png"), stage_mett(), METT)
    save_png(os.path.join(out, "sprites_c.png"), sprites_c(), SPC)
    for name, draw, pal in (("festzelt", festzelt, FEST), ("karussell", karussell, KARUSSELL)):
        c = draw()
        count = len(m7_tiles(c))
        if count > 255 or any(0 in row for row in c.px):
            raise ValueError(f"{name}: {count} distinct tiles (mode 7 has room for 255), or index 0 used")
        save_png(os.path.join(out, name + ".png"), c, pal, transparent0=False)


main()
