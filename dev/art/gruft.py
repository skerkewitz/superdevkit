"""The graphics of Gruft, drawn by program: the dungeon, its creatures and things, and the font,
as 8 x 8 background tiles in one sheet, and the dim palette for what the hero remembers.

    python3 dev/art/gruft.py examples/gruft/assets

Tile n of the sheet: 0 dark, 1 floor, 2 wall, 3 stairs down, 4 the hero, 5 rat, 6 bat, 7 ghoul,
8 gold, 9 potion; the font from 32 (FONT_ORDER).
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402
from font import FONT_ORDER, glyph  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."

# Palette 0, what the hero sees: 1-3 floor, 4-6 stone, 7-8 hero, 9 rat, 10 bat, 11 ghoul,
# 12 gold, 13 white (text), 14 potion red, 15 the text's shadow.
LIT = [(0, 0, 0),
       (64, 52, 42), (88, 74, 60), (116, 100, 82),
       (70, 72, 96), (110, 114, 146), (160, 166, 200),
       (90, 170, 240), (240, 220, 180),
       (170, 120, 80), (150, 90, 190), (110, 190, 110),
       (250, 210, 60), (250, 250, 250), (220, 50, 60), (10, 10, 20)]
# Palette 1, what it remembers: the same colours, dark and blue-grey.
DIM = [(0, 0, 0)] + [(r // 4 + 6, g // 4 + 8, b // 3 + 12) for r, g, b in LIT[1:]]


def tile(rows, legend):
    t = Canvas(8, 8)
    t.from_text(rows, legend)
    return t


TILES = [
    Canvas(8, 8),                                                   # 0 dark
    tile(["11111111", "11211111", "11111121", "11111111", "12111111", "11111211", "11111111", "11131111"],
         {"1": 1, "2": 2, "3": 3}),                                 # 1 floor
    tile(["66666665", "55555554", "44444444", "65666666", "54555555", "44444444", "66666566", "55555455"],
         {"4": 4, "5": 5, "6": 6}),                                 # 2 wall
    tile(["11111111", "15555551", "14444451", "11444451", "11144451", "11114451", "11111451", "11111111"],
         {"1": 1, "4": 4, "5": 5}),                                 # 3 stairs down
    tile(["...88...", "...88...", "..7777..", ".777777.", "8.7777.8", "..7..7..", "..7..7..", ".77..77."],
         {"7": 7, "8": 8}),                                         # 4 hero
    tile(["........", "........", "....99..", ".999999d", "99999999", ".9....9.", "........", "........"],
         {"9": 9, "d": 15}),                                        # 5 rat
    tile(["........", "a......a", "aa.aa.aa", "aaaaaaaa", ".aa..aa.", "a......a", "........", "........"],
         {"a": 10}),                                                # 6 bat
    tile(["..bbbb..", ".bdbbdb.", ".bbbbbb.", "..b..b..", ".bbbbbb.", "b.bbbb.b", "..b..b..", ".bb..bb."],
         {"b": 11, "d": 15}),                                       # 7 ghoul
    tile(["........", "........", "..cccc..", ".cdccdc.", ".cccccc.", "..cccc..", "........", "........"],
         {"c": 12, "d": 13}),                                       # 8 gold
    tile(["........", "...dd...", "...dd...", "..eeee..", ".eeedee.", ".eeeeee.", "..eeee..", "........"],
         {"d": 13, "e": 14}),                                       # 9 potion
]

sheet = Canvas(128, 48)
for n, t in enumerate(TILES):
    sheet.blit(t, (n % 16) * 8, (n // 16) * 8)
for n, ch in enumerate(FONT_ORDER):
    sheet.blit(glyph(ch, 13), ((32 + n) % 16) * 8, ((32 + n) // 16) * 8)
save_png(f"{out}/dungeon_gfx.png", sheet, LIT)

strip = Canvas(16, 1)
for x in range(16):
    strip.set(x, 0, x)
save_png(f"{out}/pal_dim.png", strip, DIM, transparent0=False)
