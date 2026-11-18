"""The graphics of Schach, drawn by program: the squares of the board with and without each piece
(16 x 16 background tiles), the cursor and the pieces as sprites, and a font with every ASCII
character (moves need lower-case letters and x + # =).

    python3 dev/art/schach.py examples/schach/assets

Every sheet is 128 pixels wide, eight elements of 16 x 16 a row, so that a 16 x 16 tile n is the
8 x 8 tiles n, n + 1, n + 16, n + 17 as the SNES reads them.
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402
from font import FONT  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."

# --- the palette of the board and of the pieces (also the sprites') -------------------------------
# The squares have colours 1 and 2; the game puts other colours there in palettes 1 to 3 (the last
# move, the squares a held piece can go to, the king in check), so the same tiles serve all four.
PAL = [(22, 26, 36),                                      # 0 backdrop, transparent in sprites
       (234, 214, 172), (178, 128, 88),                   # 1 light square, 2 dark square
       (0, 0, 0),
       (24, 18, 14),                                      # 4 outline of every piece
       (168, 166, 160), (232, 230, 222), (255, 255, 250), # 5-7 white piece: shade, body, light
       (30, 28, 34), (62, 58, 70), (118, 112, 128),       # 8-10 black piece: shade, body, light
       (255, 214, 64), (168, 112, 16),                    # 11-12 the cursor
       (110, 200, 255), (0, 0, 0), (0, 0, 0)]             # 13 the cursor holding a piece

# The pieces as masks: o is the piece; the outline and the light from the upper left are added.
PIECES = {
    "P": ["................",
          "................",
          "................",
          "................",
          ".......oo.......",
          "......oooo......",
          "......oooo......",
          ".......oo.......",
          "......oooo......",
          ".......oo.......",
          ".......oo.......",
          "......oooo......",
          ".....oooooo.....",
          "....oooooooo....",
          "................",
          "................"],
    "N": ["................",
          "................",
          ".......o.o......",
          "......ooooo.....",
          ".....ooooooo....",
          "....oo.ooooo....",
          "...oooooooooo...",
          "...ooooooooooo..",
          "....ooo.oooooo..",
          ".......ooooooo..",
          "......oooooooo..",
          "......ooooooo...",
          ".....ooooooooo..",
          "....oooooooooo..",
          "................",
          "................"],
    "B": ["................",
          ".......oo.......",
          "......oooo......",
          ".....oooooo.....",
          ".....ooo.ooo....",
          ".....oo.oooo....",
          ".....oooooo.....",
          "......oooo......",
          ".......oo.......",
          "......oooo......",
          ".....oooooo.....",
          "......oooo......",
          "....oooooooo....",
          "...oooooooooo...",
          "................",
          "................"],
    "R": ["................",
          "................",
          "....oo.oo.oo....",
          "....oooooooo....",
          "....oooooooo....",
          ".....oooooo.....",
          ".....oooooo.....",
          ".....oooooo.....",
          ".....oooooo.....",
          ".....oooooo.....",
          ".....oooooo.....",
          "....oooooooo....",
          "...oooooooooo...",
          "...oooooooooo...",
          "................",
          "................"],
    "Q": ["................",
          "..o...o..o...o..",
          "..oo..oooo..oo..",
          "..ooo.oooo.ooo..",
          "...oooooooooo...",
          "...oooooooooo...",
          "....oooooooo....",
          ".....oooooo.....",
          ".....oooooo.....",
          "......oooo......",
          ".....oooooo.....",
          "....oooooooo....",
          "...oooooooooo...",
          "...oooooooooo...",
          "................",
          "................"],
    "K": ["................",
          ".......oo.......",
          "......oooo......",
          ".......oo.......",
          "....oo.oo.oo....",
          "...oooooooooo...",
          "...oooooooooo...",
          "...oooooooooo...",
          "....oooooooo....",
          ".....oooooo.....",
          ".....oooooo.....",
          "....oooooooo....",
          "...oooooooooo...",
          "...oooooooooo...",
          "................",
          "................"],
}
ORDER = "PNBRQK"


def piece(kind, black):
    """A piece on a transparent 16 x 16 canvas: body, light on the left and top, shade on the
    right and bottom, an outline around it."""
    shade, body, light = (8, 9, 10) if black else (5, 6, 7)
    rows = PIECES[kind]
    c = Canvas(16, 16)
    inside = lambda x, y: 0 <= x < 16 and 0 <= y < 16 and rows[y][x] == "o"
    for y in range(16):
        for x in range(16):
            if not inside(x, y):
                continue
            colour = body
            if not inside(x - 1, y) or not inside(x, y - 1):
                colour = light
            elif not inside(x + 1, y) or not inside(x, y + 1):
                colour = shade
            c.set(x, y, colour)
    c.outline(4)
    return c


def square(dark):
    c = Canvas(16, 16)
    c.rect(0, 0, 15, 15, 2 if dark else 1)
    return c


# --- board.png: element 2 * p + dark, p = 0 empty, 1..6 white PNBRQK, 7..12 black PNBRQK ------------
board = Canvas(128, 64)
for p in range(13):
    for dark in (0, 1):
        e = p * 2 + dark
        cell = square(dark)
        if p > 0:
            cell.blit(piece(ORDER[(p - 1) % 6], p > 6), 0, 0)
        board.blit(cell, (e % 8) * 16, (e // 8) * 16)
# elements 26..31 stay colour 0: the backdrop around the board
save_png(os.path.join(out, "board_gfx.png"), board, PAL, transparent0=False)

# --- sprites.png: 0 the cursor, 1 the cursor holding a piece, 2..7 white PNBRQK, 8..13 black ---------
def cursor(colour, dark):
    """Four corner brackets, two pixels thick, with a dark edge, so they show on every square."""
    c = Canvas(16, 16)
    for x0, y0, dx, dy in ((0, 0, 1, 1), (15, 0, -1, 1), (0, 15, 1, -1), (15, 15, -1, -1)):
        for i in range(4):
            for t in range(2):
                c.set(x0 + dx * i, y0 + dy * t, colour)
                c.set(x0 + dx * t, y0 + dy * i, colour)
    edge = []
    for y in range(16):
        for x in range(16):
            if c.get(x, y) == 0 and any(c.get(x + a, y + b) == colour for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                edge.append((x, y))
    for x, y in edge:
        c.set(x, y, dark)
    return c


sprites = Canvas(128, 32)
sprites.blit(cursor(11, 4), 0, 0)
sprites.blit(cursor(13, 4), 16, 0)
for i in range(12):
    e = 2 + i
    sprites.blit(piece(ORDER[i % 6], i >= 6), (e % 8) * 16, (e // 8) * 16)
save_png(os.path.join(out, "sprites_gfx.png"), sprites, PAL)

# --- font.png: ASCII 32..127, 16 a row; colour 1 the letter, 2 its shadow ----------------------------
LOWER = {
    "a": ["00000", "00000", "01110", "00001", "01111", "10001", "01111"],
    "b": ["10000", "10000", "11110", "10001", "10001", "10001", "11110"],
    "c": ["00000", "00000", "01110", "10000", "10000", "10001", "01110"],
    "d": ["00001", "00001", "01111", "10001", "10001", "10001", "01111"],
    "e": ["00000", "00000", "01110", "10001", "11111", "10000", "01110"],
    "f": ["00110", "01001", "01000", "11100", "01000", "01000", "01000"],
    "g": ["00000", "01111", "10001", "10001", "01111", "00001", "01110"],
    "h": ["10000", "10000", "10110", "11001", "10001", "10001", "10001"],
    "i": ["00100", "00000", "01100", "00100", "00100", "00100", "01110"],
    "j": ["00010", "00000", "00110", "00010", "00010", "10010", "01100"],
    "k": ["10000", "10000", "10010", "10100", "11000", "10100", "10010"],
    "l": ["01100", "00100", "00100", "00100", "00100", "00100", "01110"],
    "m": ["00000", "00000", "11010", "10101", "10101", "10001", "10001"],
    "n": ["00000", "00000", "10110", "11001", "10001", "10001", "10001"],
    "o": ["00000", "00000", "01110", "10001", "10001", "10001", "01110"],
    "p": ["00000", "11110", "10001", "10001", "11110", "10000", "10000"],
    "q": ["00000", "01111", "10001", "10001", "01111", "00001", "00001"],
    "r": ["00000", "00000", "10110", "11001", "10000", "10000", "10000"],
    "s": ["00000", "00000", "01111", "10000", "01110", "00001", "11110"],
    "t": ["01000", "01000", "11100", "01000", "01000", "01001", "00110"],
    "u": ["00000", "00000", "10001", "10001", "10001", "10011", "01101"],
    "v": ["00000", "00000", "10001", "10001", "10001", "01010", "00100"],
    "w": ["00000", "00000", "10001", "10001", "10101", "10101", "01010"],
    "x": ["00000", "00000", "10001", "01010", "00100", "01010", "10001"],
    "y": ["00000", "10001", "10001", "10001", "01111", "00001", "01110"],
    "z": ["00000", "00000", "11111", "00010", "00100", "01000", "11111"],
}
SYMBOLS = {
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
    "#": ["01010", "01010", "11111", "01010", "11111", "01010", "01010"],
    "=": ["00000", "00000", "11111", "00000", "11111", "00000", "00000"],
    "(": ["00010", "00100", "01000", "01000", "01000", "00100", "00010"],
    ")": ["01000", "00100", "00010", "00010", "00010", "00100", "01000"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    ",": ["00000", "00000", "00000", "00000", "00110", "00100", "01000"],
    "'": ["00100", "00100", "01000", "00000", "00000", "00000", "00000"],
    "?": ["01110", "10001", "00001", "00110", "00100", "00000", "00100"],
    ">": ["01000", "00100", "00010", "00001", "00010", "00100", "01000"],
    "<": ["00010", "00100", "01000", "10000", "01000", "00100", "00010"],
    "*": ["00000", "10101", "01110", "11111", "01110", "10101", "00000"],
    "_": ["00000", "00000", "00000", "00000", "00000", "00000", "11111"],
}
GLYPHS = dict(FONT)
GLYPHS.update(LOWER)
GLYPHS.update(SYMBOLS)
FONT_PAL = [(0, 0, 0), (240, 238, 226), (16, 14, 20)] + [(0, 0, 0)] * 13


def glyph(ch):
    t = Canvas(8, 8)
    rows = GLYPHS.get(ch)
    if rows:
        for colour, ox, oy in ((2, 2, 1), (1, 1, 0)):      # the shadow first
            for y, row in enumerate(rows):
                for x, bit in enumerate(row):
                    if bit == "1":
                        t.set(x + ox, y + oy, colour)
    return t


font = Canvas(128, 48)
for code in range(32, 128):
    i = code - 32
    font.blit(glyph(chr(code)), (i % 16) * 8, (i // 16) * 8)
save_png(os.path.join(out, "font_gfx.png"), font, FONT_PAL)
print("wrote board_gfx.png, sprites_gfx.png, font_gfx.png to", out)
