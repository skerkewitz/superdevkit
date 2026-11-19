"""The splash screen for the SNES, made from the parts of the user's design that
dev/art/splash_parts.py cuts (dev/art/splash/parts/: the text, the sticker, the locomotive, the
stars, and layout.txt with where each is). Writes the tiles, the map, the palettes and the sprites
for the library, and the screen as a PNG to look at:

    python3 dev/art/splash.py              -> lib/splash/splash_logo.h, dev/art/splash/logo_snes.png

The design goes in the middle of the 256 x 224 screen. The text, the sticker and the locomotive are
BG1 tiles, each part with its own palette (0, 1, 2); the design is placed so that no 8 x 8 tile
holds pixels of two parts, which this script checks. The stars are sprites, 8 x 8 or 16 x 16, with
sprite palette 0. Colour 0 of BG palette 0 is the backdrop: the design's background. Identical
tiles are stored once.
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
from pix import Canvas, save_png  # noqa: E402

_spec = importlib.util.spec_from_file_location("png_read", os.path.join(KIT, "dev", "png_read.py"))
png_read = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(png_read)

PARTS = os.path.join(KIT, "dev", "art", "splash", "parts")
W, H = 256, 224                           # the screen
BG_PARTS = ["text", "sticker", "locomotive"]   # BG1, palettes 0, 1, 2 in this order
VRAM_MAP = 0x3C00                         # VRAM words, as lib/splash/splash.h: BG tiles from 0, the map,
VRAM_OBJ = 0x4000                         # the sprite tiles (OBSEL's base, 8K-word steps)
MAX_BG_TILES = VRAM_MAP // 16             # 16 words a tile below the map


def read_part(name):
    w, h, px, pal = png_read.read_png(os.path.join(PARTS, name + ".png"))
    return w, h, px, [tuple(c[:3]) for c in pal[:16]]


def tile_bytes(get, tx, ty):
    """One 8 x 8 tile in the SNES's planar 4-bit format: rows of planes 0/1, then of planes 2/3."""
    out = bytearray(32)
    for y in range(8):
        for x in range(8):
            v = get(tx * 8 + x, ty * 8 + y)
            bit = 0x80 >> x
            if v & 1: out[y * 2] |= bit
            if v & 2: out[y * 2 + 1] |= bit
            if v & 4: out[16 + y * 2] |= bit
            if v & 8: out[16 + y * 2 + 1] |= bit
    return bytes(out)


def bgr555(rgb):
    r, g, b = (v >> 3 for v in rgb)
    return r | (g << 5) | (b << 10)


def pieces(w, h, px):
    """The stars of stars.png: its connected pieces (8 neighbours), each as a box (x0, y0, x1, y1)."""
    seen, out = set(), []
    for y in range(h):
        for x in range(w):
            if px[y][x] and (x, y) not in seen:
                todo, pts = [(x, y)], []
                seen.add((x, y))
                while todo:
                    a, b = todo.pop()
                    pts.append((a, b))
                    for u in range(max(0, a - 1), min(w, a + 2)):
                        for v in range(max(0, b - 1), min(h, b + 2)):
                            if px[v][u] and (u, v) not in seen:
                                seen.add((u, v))
                                todo.append((u, v))
                out.append((min(p[0] for p in pts), min(p[1] for p in pts),
                            max(p[0] for p in pts), max(p[1] for p in pts)))
    return sorted(out, key=lambda b: (b[1], b[0]))


def c_array(f, ctype, name, values, per_line, fmt):
    f.write(f"static const {ctype} {name}[{len(values)}] = {{")
    for i, v in enumerate(values):
        f.write(("\n    " if i % per_line == 0 else " ") + fmt.format(v) + ",")
    f.write("\n};\n")


def main():
    layout = {}
    for line in open(os.path.join(PARTS, "layout.txt")):
        name, x, y = line.split()
        layout[name] = (int(x), int(y))
    dw, dh = layout.pop("design")
    ox, oy = (W - dw) // 2, (H - dh) // 2   # the design in the middle of the screen

    # BG1: every pixel of a part with the part's palette; a tile may hold one part only
    index = [[0] * W for _ in range(H)]
    owner = {}
    palettes = []
    screen = [[None] * W for _ in range(H)]   # the colours, for the PNG
    for p, name in enumerate(BG_PARTS):
        w, h, px, pal = read_part(name)
        palettes.append(pal + [(0, 0, 0)] * (16 - len(pal)))
        x0, y0 = layout[name][0] + ox, layout[name][1] + oy
        for y in range(h):
            for x in range(w):
                if px[y][x]:
                    t = ((x0 + x) // 8, (y0 + y) // 8)
                    if owner.setdefault(t, p) != p:
                        sys.exit(f"tile {t} holds pixels of {BG_PARTS[owner[t]]} and {name}")
                    index[y0 + y][x0 + x] = px[y][x]
                    screen[y0 + y][x0 + x] = pal[px[y][x]]
    backdrop = palettes[0][0]

    blank = bytes(32)                     # tile 0 empty: a cleared map shows the backdrop
    tiles, known = [blank], {blank: 0}
    rows = sorted({ty for _, ty in owner})
    row0, row1 = rows[0], rows[-1]
    mapping = []
    for ty in range(row0, row1 + 1):
        for tx in range(32):
            p = owner.get((tx, ty))
            if p is None:
                mapping.append(0)
                continue
            t = tile_bytes(lambda x, y: index[y][x], tx, ty)
            if t not in known:
                known[t] = len(tiles)
                tiles.append(t)
            mapping.append(known[t] | (p << 10))
    if len(tiles) > MAX_BG_TILES:
        sys.exit(f"{len(tiles)} BG tiles do not fit below the map ({MAX_BG_TILES})")

    # the stars: one sprite each, 8 x 8 or 16 x 16; the sprite tiles 16 to a row, as OBSEL reads them,
    # the large ones two tiles wide in rows 0 and 1, the small ones from row 2 on
    sw, sh, spx, spal = read_part("stars")
    sx0, sy0 = layout["stars"][0] + ox, layout["stars"][1] + oy
    obj = {}                              # tile number -> 32 bytes
    small, large, oam, high = {}, 0, [], [0] * 32
    stars = pieces(sw, sh, spx)
    if len(stars) > 128:
        sys.exit(f"{len(stars)} stars, but 128 sprites")
    for n, (x0, y0, x1, y1) in enumerate(stars):
        big = x1 - x0 >= 8 or y1 - y0 >= 8
        if x1 - x0 >= 16 or y1 - y0 >= 16:
            sys.exit(f"a star of {x1 - x0 + 1} x {y1 - y0 + 1} pixels at ({x0}, {y0}): more than a sprite")

        def get(x, y, x0=x0, y0=y0, x1=x1, y1=y1):
            # only this star's pixels: another star nearby belongs to its own sprite
            u, v = x0 + x, y0 + y
            return spx[v][u] if u <= x1 and v <= y1 else 0

        if big:
            if large == 8:
                sys.exit("more than 8 large stars")
            tile = large * 2
            for k, (dx, dy) in enumerate(((0, 0), (1, 0), (0, 1), (1, 1))):
                obj[tile + dx + dy * 16] = tile_bytes(get, dx, dy)
            large += 1
        else:
            t = tile_bytes(get, 0, 0)
            if t not in small:
                small[t] = 32 + len(small)
                obj[small[t]] = t
            tile = small[t]
        x, y = sx0 + x0, sy0 + y0
        oam += [x & 0xFF, y, tile & 0xFF, 0x30 | ((tile >> 8) & 1)]   # priority 3, palette 0
        high[n >> 2] |= (((x >> 8) & 1) | (2 if big else 0)) << ((n & 3) * 2)
        for v in range(y1 - y0 + 1):
            for u in range(x1 - x0 + 1):
                if spx[y0 + v][x0 + u]:
                    screen[y + v][x + u] = spal[spx[y0 + v][x0 + u]]
    for n in range(len(stars), 128):     # the other sprites hidden, as sdevkit_oam_clear does
        oam += [0, 0xE8, 0, 0]
    obj_tiles = b"".join(obj.get(i, blank) for i in range(max(obj) + 1))

    colours = len({c for row in screen for c in row if c is not None} | {backdrop})
    out = os.path.join(KIT, "lib", "splash", "splash_logo.h")
    with open(out, "w") as f:
        f.write("/* Generated by dev/art/splash.py from dev/art/splash/parts/ (dev/art/splash_parts.py). Do not edit.\n"
                f" * The splash screen: the design ({dw} x {dh} pixels) in the middle of the screen, {colours} colours;\n"
                f" * BG1 map rows {row0} to {row1} with {len(tiles)} different tiles of 4 bits a pixel, and {len(stars)} stars\n"
                " * as sprites. VRAM words: the BG tiles from 0, the map at SDEVKIT__SPLASH_MAP, the sprite tiles at\n"
                " * SDEVKIT__SPLASH_OBJ. */\n")
        f.write("#ifndef SDEVKIT__SPLASH_LOGO_H\n#define SDEVKIT__SPLASH_LOGO_H\n#include <stdint.h>\n\n")
        f.write(f"#define SDEVKIT__SPLASH_MAP 0x{VRAM_MAP:04X}\n#define SDEVKIT__SPLASH_OBJ 0x{VRAM_OBJ:04X}\n")
        f.write(f"#define SDEVKIT__SPLASH_ROW {row0}      /* the map's first row */\n")
        f.write(f"#define SDEVKIT__SPLASH_ROWS {row1 - row0 + 1}\n#define SDEVKIT__SPLASH_STARS {len(stars)}\n\n")
        f.write("/* the BG tiles */\n")
        c_array(f, "uint8_t", "sdevkit__splash_tiles", [b for t in tiles for b in t], 16, "0x{:02X}")
        f.write("\n/* the map from row SDEVKIT__SPLASH_ROW on, 32 entries a row: the tile, the palette in bits 10..12 */\n")
        c_array(f, "uint16_t", "sdevkit__splash_map", mapping, 16, "0x{:04X}")
        f.write("\n/* BG palettes 0..2 (the text, the sticker, the locomotive), BGR 5:5:5; colour 0 is the backdrop */\n")
        c_array(f, "uint16_t", "sdevkit__splash_palette", [bgr555(c) for pal in palettes for c in pal], 8, "0x{:04X}")
        f.write("\n/* the sprite tiles */\n")
        c_array(f, "uint8_t", "sdevkit__splash_obj_tiles", list(obj_tiles), 16, "0x{:02X}")
        f.write("\n/* sprite palette 0: the stars */\n")
        c_array(f, "uint16_t", "sdevkit__splash_obj_palette",
                [bgr555(c) for c in spal + [(0, 0, 0)] * (16 - len(spal))], 8, "0x{:04X}")
        f.write("\n/* the sprite table: the stars are sprites 0 to SDEVKIT__SPLASH_STARS - 1 (x, y, tile, attributes),\n"
                " * the others hidden; then its high table */\n")
        c_array(f, "uint8_t", "sdevkit__splash_oam", oam, 16, "0x{:02X}")
        c_array(f, "uint8_t", "sdevkit__splash_oam_high", high, 16, "0x{:02X}")
        f.write("\n#endif\n")

    allc = sorted({c for row in screen for c in row if c is not None} | {backdrop})
    c = Canvas(W, H)
    for y in range(H):
        for x in range(W):
            c.set(x, y, allc.index(screen[y][x] or backdrop))
    save_png(os.path.join(KIT, "dev", "art", "splash", "logo_snes.png"), c, allc, transparent0=False)
    print(f"{out}: {len(tiles)} BG tiles, rows {row0}..{row1}, {len(stars)} stars "
          f"({large} large, {len(obj_tiles) // 32} sprite tiles), {colours} colours")


main()
