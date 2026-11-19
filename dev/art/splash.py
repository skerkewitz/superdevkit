"""The splash screen's picture for the SNES, made from the design in dev/art/splash/logo_source.png
(the user's picture, 239 x 52 pixels with 63 colours): a fixed palette, everything near black made
black, single specks removed, the letters with clean edges, and "MADE WITH" in place of the
design's "DONE WITH". Writes the result as tiles for the library and as a PNG to look at.

    python3 dev/art/splash.py              -> lib/splash/splash_logo.h, dev/art/splash/logo_snes.png

The picture is 240 x 56 pixels, 30 x 7 tiles of 8 x 8; identical tiles are stored once. The colours
of the palette are chosen here: a colour is changed in this script, not in an image editor.
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
from pix import Canvas, save_png  # noqa: E402

_spec = importlib.util.spec_from_file_location("png2snes", os.path.join(KIT, "tools", "png2snes.py"))
png2snes = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(png2snes)

W, H = 240, 56
TOP = 2                                   # the design's rows start this far down in the picture

PALETTE = [(0, 0, 0),                     # 0 black, also the backdrop
           (252, 252, 252),               # 1 white: "DEVKIT"
           (182, 184, 187),               # 2 light grey: "SUPER", "MADE WITH", the pad
           (150, 151, 153),               # 3
           (118, 120, 124),               # 4
           (100, 100, 108),               # 5
           (72, 72, 73),                  # 6
           (40, 40, 42),                  # 7 dark grey
           (138, 126, 186),               # 8 lilac: the cartridge, a button
           (104, 94, 150),                # 9 dark lilac
           (176, 70, 66),                 # 10 red button
           (212, 180, 68),                # 11 yellow button
           (40, 148, 84),                 # 12 green button
           (128, 112, 208)]               # 13 blue button

TEXT_X = 76                               # the text starts here in the design; left of it is the pad


def lum(c):
    return (c[0] * 3 + c[1] * 6 + c[2]) / 10


def nearest(c):
    if max(c) < 34:
        return 0
    return min(range(1, len(PALETTE)), key=lambda i: sum((a - b) ** 2 for a, b in zip(c, PALETTE[i])))


# M and A in the style of the design's top line: strokes of three pixels, 9 wide, 14 high
M = ["###...###",
     "####.####",
     "#########",
     "#########",
     "###.#.###",
     "###...###", "###...###", "###...###", "###...###", "###...###",
     "###...###", "###...###", "###...###", "###...###"]
A = [".#######.",
     "#########",
     "#########",
     "###...###", "###...###", "###...###",
     "#########",
     "#########",
     "###...###", "###...###", "###...###", "###...###", "###...###", "###...###"]


def picture():
    w, h, px, pal = png2snes.read_png(os.path.join(KIT, "dev", "art", "splash", "logo_source.png"))
    src = [[tuple((pal[px[y][x]] if pal else px[y][x])[:3]) for x in range(w)] for y in range(h)]
    c = Canvas(W, H)
    for y in range(h):
        for x in range(w):
            col = src[y][x]
            if x >= TEXT_X:               # text: one colour per line, edges by a threshold
                l = lum(col)
                if y >= 19:               # "SUPERDEVKIT": DEVKIT white, SUPER grey
                    i = 1 if l > 225 else 2 if l > 100 else 0
                else:                     # "MADE WITH"
                    i = 2 if l > 100 else 0
            else:
                i = nearest(col)
            c.set(x, y + TOP, i)
    # single specks in the pad: a pixel unlike all four neighbours takes their colour if they agree
    for y in range(H):
        for x in range(TEXT_X):
            n = [c.get(x + 1, y), c.get(x - 1, y), c.get(x, y + 1), c.get(x, y - 1)]
            if all(v == n[0] for v in n) and c.get(x, y) != n[0]:
                c.set(x, y, n[0])
    # "DONE" becomes "MADE": D and E of the design move, M and A are drawn; WITH stays
    d = [[c.get(TEXT_X + 2 + x, TOP + 4 + y) for x in range(10)] for y in range(14)]
    e = [[c.get(TEXT_X + 39 + x, TOP + 4 + y) for x in range(9)] for y in range(14)]
    for y in range(14):
        for x in range(2, 50):
            c.set(TEXT_X + x, TOP + 4 + y, 0)
    x0 = TEXT_X + 2
    for rows, width in ((M, 9), (A, 9)):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch == "#":
                    c.set(x0 + x, TOP + 4 + y, 2)
        x0 += width + 3
    for glyph, width in ((d, 10), (e, 9)):
        for y in range(14):
            for x in range(width):
                if glyph[y][x]:
                    c.set(x0 + x, TOP + 4 + y, glyph[y][x])
        x0 += width + 3
    return c


def tile_bytes(c, tx, ty):
    """One 8 x 8 tile in the SNES's planar 4-bit format: rows of planes 0/1, then of planes 2/3."""
    out = bytearray(32)
    for y in range(8):
        for x in range(8):
            v = c.get(tx * 8 + x, ty * 8 + y)
            bit = 0x80 >> x
            if v & 1: out[y * 2] |= bit
            if v & 2: out[y * 2 + 1] |= bit
            if v & 4: out[16 + y * 2] |= bit
            if v & 8: out[16 + y * 2 + 1] |= bit
    return bytes(out)


def bgr555(rgb):
    r, g, b = (v >> 3 for v in rgb)
    return r | (g << 5) | (b << 10)


def main():
    c = picture()
    blank = bytes(32)                     # tile 0 empty: a cleared map shows black
    tiles, index, mapping = [blank], {blank: 0}, []
    for ty in range(H // 8):
        for tx in range(W // 8):
            t = tile_bytes(c, tx, ty)
            if t not in index:
                index[t] = len(tiles)
                tiles.append(t)
            mapping.append(index[t])
    colours = len({c.get(x, y) for y in range(H) for x in range(W)})
    out = os.path.join(KIT, "lib", "splash", "splash_logo.h")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write("/* Generated by dev/art/splash.py from dev/art/splash/logo_source.png. Do not edit.\n"
                f" * The splash screen's logo: {W} x {H} pixels, {W // 8} x {H // 8} tiles of 4 bits a pixel"
                f" ({len(tiles)} different), {colours} colours. */\n")
        f.write("#ifndef SDEVKIT__SPLASH_LOGO_H\n#define SDEVKIT__SPLASH_LOGO_H\n#include <stdint.h>\n\n")
        f.write(f"#define SDEVKIT__SPLASH_COLUMNS {W // 8}\n#define SDEVKIT__SPLASH_ROWS {H // 8}\n")
        f.write(f"#define SDEVKIT__SPLASH_TILES {len(tiles)}\n\n")
        f.write(f"static const uint8_t sdevkit__splash_tiles[{len(tiles) * 32}] = {{")
        for i, b in enumerate(b for t in tiles for b in t):
            f.write(("\n    " if i % 16 == 0 else " ") + f"0x{b:02X},")
        f.write("\n};\n\n/* the map: one byte a cell, the tile's number */\n")
        f.write(f"static const uint8_t sdevkit__splash_map[{len(mapping)}] = {{")
        for i, t in enumerate(mapping):
            f.write(("\n    " if i % 15 == 0 else " ") + f"{t:3d},")
        f.write("\n};\n\n/* 16 colours, BGR 5:5:5 */\n")
        f.write("static const uint16_t sdevkit__splash_palette[16] = {")
        for i in range(16):
            rgb = PALETTE[i] if i < len(PALETTE) else (0, 0, 0)
            f.write(("\n    " if i % 8 == 0 else " ") + f"0x{bgr555(rgb):04X},")
        f.write("\n};\n\n#endif\n")
    save_png(os.path.join(KIT, "dev", "art", "splash", "logo_snes.png"), c,
             PALETTE + [(0, 0, 0)] * (16 - len(PALETTE)), transparent0=False)
    print(f"{out}: {len(tiles)} tiles, {colours} colours")


main()
