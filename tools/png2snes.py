#!/usr/bin/env python3
"""PNG to SNES graphics data, as C for dampf: tiles of 4 bits per pixel and their palette.

    tools/png2snes.py tiles   sheet.png  NAME  out_dir    tiles and palette of one image
    tools/png2snes.py tiles2  sheet.png  NAME  out_dir    the same with 2 bits a pixel (4 colours),
                                                          for BG3 of mode 1
    tools/png2snes.py palette strip.png  NAME  out_dir    only the colours, for another palette
    tools/png2snes.py mode7   map.png    NAME  out_dir [FIRST]
                                                          a picture for mode 7: its map and tiles

The image has at most 16 colours: an indexed PNG uses its palette as it is (index 0 is the
transparent colour of sprites and the backdrop of backgrounds); an RGB or RGBA image gets the
colours in the order they first appear, with transparent pixels (alpha 0) as colour 0, which is
then black. `tiles` cuts the image into 8 x 8 tiles in reading order; for sprites, a sheet 128
pixels wide has the order of VRAM, so a 16 x 16 sprite at tile n is tiles n, n + 1, n + 16 and
n + 17. `palette` reads the colours of the first row of pixels, left to right.

Writes NAME.c and NAME.h into out_dir: `const uint8_t NAME_tiles[]` (32 bytes a tile, the SNES's
planar 4-bit format; with tiles2 16 bytes a tile, the 2-bit format, and the image may use only
indices 0 to 3), `const uint8_t NAME_pal[]` (16 colours, BGR 5:5:5, 2 bytes each, low byte
first) and the sizes as NAME_TILES_SIZE, NAME_PAL_SIZE and NAME_TILE_COUNT.

`mode7` reads an indexed PNG 1024 pixels wide and up to 1024 high (the map of mode 7 is 128 x 128
tiles of 8 x 8 pixels), with one byte a pixel: palette index i > 0 becomes the byte FIRST - 1 + i
(FIRST, default 1, is where its colours go in CGRAM; mode 7 shares CGRAM with the sprites and,
when the screen switches modes, with the other layers), index 0 stays 0, transparent. Identical
8 x 8 blocks are stored once, at most 256 of them (mode 7 cannot flip a tile). Writes
`NAME_map[]` (one byte a tile, 128 a row, for sdevkit_mode7_load_map), `NAME_tiles[]` (64 bytes a
tile, for sdevkit_mode7_load_tiles), `NAME_pal[]` (the colours from index 1 on, for CGRAM from
NAME_PAL_FIRST) and NAME_MAP_SIZE, NAME_TILES_SIZE, NAME_TILE_COUNT, NAME_PAL_SIZE.

Needs Python 3 only (no image library): a small PNG reader of its own, for 8-bit images of the
colour types grey, RGB, palette, grey with alpha and RGBA, without interlacing.
"""
import os
import struct
import sys
import zlib


def read_png(path):
    """Returns (width, height, pixels, palette): pixels as rows of indices when the PNG has a
    palette, else of (r, g, b, a) tuples; palette as a list of (r, g, b, a) or None."""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"{path}: not a PNG file")
    pos, idat, plte, trns = 8, b"", None, None
    width = height = depth = ctype = interlace = None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            plte = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if depth != 8 or interlace != 0 or ctype not in (0, 2, 3, 4, 6):
        raise SystemExit(f"{path}: only 8-bit PNG images without interlacing are read "
                         f"(this one has depth {depth}, colour type {ctype}, interlace {interlace})")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    stride = width * channels
    raw = zlib.decompress(idat)
    rows, prev = [], bytearray(stride)
    for y in range(height):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = prev[x]
            c = prev[x - channels] if x >= channels else 0
            if f == 1:
                line[x] = (line[x] + a) & 0xFF
            elif f == 2:
                line[x] = (line[x] + b) & 0xFF
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        rows.append(bytes(line))
        prev = line
    if ctype == 3:
        alpha = list(trns or b"") + [255] * (len(plte) - len(trns or b""))
        palette = [plte[i] + (alpha[i],) for i in range(len(plte))]
        return width, height, [list(r) for r in rows], palette
    pixels = []
    for r in rows:
        line = []
        for x in range(width):
            v = r[x * channels:(x + 1) * channels]
            if ctype == 0:
                line.append((v[0], v[0], v[0], 255))
            elif ctype == 2:
                line.append((v[0], v[1], v[2], 255))
            elif ctype == 4:
                line.append((v[0], v[0], v[0], v[1]))
            else:
                line.append(tuple(v))
        pixels.append(line)
    return width, height, pixels, None


def indexed(path):
    """Returns (width, height, rows of indices 0..15, 16 colours as (r, g, b))."""
    width, height, pixels, palette = read_png(path)
    if palette is not None:
        used = max(max(r) for r in pixels)
        if used > 15:
            raise SystemExit(f"{path}: uses palette index {used}; a 4-bit tile has 16 colours")
        colours = [p[:3] for p in palette[:16]]
    else:
        colours, rows = [(0, 0, 0)], []
        for line in pixels:
            out = []
            for (r, g, b, a) in line:
                if a == 0:
                    out.append(0)
                    continue
                if (r, g, b) not in colours[1:]:
                    colours.append((r, g, b))
                    if len(colours) > 16:
                        raise SystemExit(f"{path}: more than 15 colours besides the transparent one")
                out.append(colours.index((r, g, b), 1))
            rows.append(out)
        pixels = rows
    colours += [(0, 0, 0)] * (16 - len(colours))
    return width, height, pixels, colours


def tile_bytes(pixels, tx, ty, planes=4):
    """One 8 x 8 tile in the SNES's 4-bit format: rows of planes 0 and 1, then of planes 2 and 3
    (with planes=2 only the first half: the 2-bit format)."""
    out = bytearray(8 * planes)
    for y in range(8):
        row = pixels[ty * 8 + y][tx * 8:tx * 8 + 8]
        for plane in range(planes):
            bits = 0
            for x in range(8):
                bits |= ((row[x] >> plane) & 1) << (7 - x)
            out[(plane // 2) * 16 + y * 2 + (plane & 1)] = bits
    return out


def bgr555(colours):
    out = bytearray()
    for (r, g, b) in colours:
        v = (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10)
        out += bytes((v & 0xFF, v >> 8))
    return out


def c_array(name, data):
    lines = [", ".join(f"0x{b:02X}" for b in data[i:i + 16]) for i in range(0, len(data), 16)]
    return f"const uint8_t {name}[{len(data)}] = {{\n    " + ",\n    ".join(lines) + "\n};\n"


def write(out_dir, name, source, tiles, pal, count):
    os.makedirs(out_dir, exist_ok=True)
    guard = name.upper() + "_H"
    h = [f"/* Generated by tools/png2snes.py from {source}. Do not edit. */",
         f"#ifndef {guard}", f"#define {guard}", "#include <stdint.h>"]
    c = [f"/* Generated by tools/png2snes.py from {source}. Do not edit. */",
         f'#include "{name}.h"', ""]
    if tiles is not None:
        h += [f"#define {name.upper()}_TILE_COUNT {count}", f"#define {name.upper()}_TILES_SIZE {len(tiles)}",
              f"extern const uint8_t {name}_tiles[{len(tiles)}];"]
        c.append(c_array(f"{name}_tiles", tiles))
    h += [f"#define {name.upper()}_PAL_SIZE {len(pal)}", f"extern const uint8_t {name}_pal[{len(pal)}];", "#endif", ""]
    c.append(c_array(f"{name}_pal", pal))
    open(os.path.join(out_dir, name + ".h"), "w").write("\n".join(h))
    open(os.path.join(out_dir, name + ".c"), "w").write("\n".join(c))


def mode7(path, first):
    """Returns (map, tiles, palette bytes, tile count) of a mode 7 picture."""
    width, height, pixels, palette = read_png(path)
    if palette is None:
        raise SystemExit(f"{path}: a mode 7 picture is an indexed PNG (one byte a pixel)")
    if width != 1024 or height % 8 or height > 1024:
        raise SystemExit(f"{path}: {width} x {height}; a mode 7 picture is 1024 wide and up to 1024 high, "
                         "in whole tiles of 8 x 8")
    used = max(max(r) for r in pixels)
    if first < 1 or first - 1 + used > 255:
        raise SystemExit(f"{path}: palette index {used} from CGRAM colour {first} on goes past colour 255")
    if used >= len(palette):
        raise SystemExit(f"{path}: uses palette index {used}, but the palette has {len(palette)} colours")
    tiles, index, tmap = bytearray(), {}, bytearray()
    for ty in range(height // 8):
        for tx in range(128):
            block = bytes((first - 1 + v) if v else 0
                          for y in range(8) for v in pixels[ty * 8 + y][tx * 8:tx * 8 + 8])
            if block not in index:
                if len(index) == 256:
                    raise SystemExit(f"{path}: more than 256 different tiles of 8 x 8 (at tile {tx}, {ty}); "
                                     "mode 7 has 256 and cannot flip them")
                index[block] = len(index)
                tiles += block
            tmap.append(index[block])
    return tmap, tiles, bgr555([c[:3] for c in palette[1:used + 1]]), len(index)


def write_mode7(out_dir, name, source, tmap, tiles, pal, count, first):
    os.makedirs(out_dir, exist_ok=True)
    up, guard = name.upper(), name.upper() + "_H"
    h = [f"/* Generated by tools/png2snes.py from {source}. Do not edit. */",
         f"#ifndef {guard}", f"#define {guard}", "#include <stdint.h>",
         f"#define {up}_MAP_SIZE {len(tmap)}", f"#define {up}_TILE_COUNT {count}",
         f"#define {up}_TILES_SIZE {len(tiles)}", f"#define {up}_PAL_SIZE {len(pal)}",
         f"#define {up}_PAL_FIRST {first}",
         f"extern const uint8_t {name}_map[{len(tmap)}];", f"extern const uint8_t {name}_tiles[{len(tiles)}];",
         f"extern const uint8_t {name}_pal[{len(pal)}];", "#endif", ""]
    c = [f"/* Generated by tools/png2snes.py from {source}. Do not edit. */", f'#include "{name}.h"', "",
         c_array(f"{name}_map", tmap), c_array(f"{name}_tiles", tiles), c_array(f"{name}_pal", pal)]
    open(os.path.join(out_dir, name + ".h"), "w").write("\n".join(h))
    open(os.path.join(out_dir, name + ".c"), "w").write("\n".join(c))


def main(argv):
    if len(argv) == 6 and argv[1] == "mode7" or len(argv) == 5 and argv[1] == "mode7":
        first = int(argv[5]) if len(argv) == 6 else 1
        tmap, tiles, pal, count = mode7(argv[2], first)
        write_mode7(argv[4], argv[3], os.path.basename(argv[2]), tmap, tiles, pal, count, first)
        return
    if len(argv) != 5 or argv[1] not in ("tiles", "tiles2", "palette"):
        raise SystemExit(__doc__.split("\n\n")[1])
    mode, path, name, out_dir = argv[1:]
    width, height, pixels, colours = indexed(path)
    if mode == "palette":
        if width > 16 and any(pixels[0][16:]):
            raise SystemExit(f"{path}: a palette strip has at most 16 colours in its first row")
        strip = [colours[i] for i in pixels[0][:16]]
        write(out_dir, name, os.path.basename(path), None, bgr555(strip + [(0, 0, 0)] * (16 - len(strip))), 0)
        return
    if width % 8 or height % 8:
        raise SystemExit(f"{path}: {width} x {height} is not a multiple of 8 x 8")
    planes = 2 if mode == "tiles2" else 4
    if planes == 2:
        used = max(max(r) for r in pixels)
        if used > 3:
            raise SystemExit(f"{path}: uses colour {used}; a 2-bit tile has 4 colours")
        colours = colours[:4]
    tiles = bytearray()
    for ty in range(height // 8):
        for tx in range(width // 8):
            tiles += tile_bytes(pixels, tx, ty, planes)
    write(out_dir, name, os.path.basename(path), tiles, bgr555(colours), (width // 8) * (height // 8))


if __name__ == "__main__":
    main(sys.argv)
