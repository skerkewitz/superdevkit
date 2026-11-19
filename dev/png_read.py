#!/usr/bin/env python3
"""A small PNG reader for the dev scripts (compare_shots.py, art/splash.py, art/splash_parts.py):
8-bit images of the colour types grey, RGB, palette, grey with alpha and RGBA, without interlacing.
It was the reader of tools/png2snes.py, whose converter is Java now (superdevkit png,
cli/src/main/java/superdevkit/gfx).
"""
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

