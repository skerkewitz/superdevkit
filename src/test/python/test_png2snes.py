#!/usr/bin/env python3
"""Tests of png2snes.py: tiles and colours survive the round trip, for indexed and RGBA images.

    python3 -I src/test/python/test_png2snes.py      (the Gradle build runs it, ToolsTest)
"""
import os
import random
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "tools"))
import png2snes  # noqa: E402


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png(path, width, rows, ctype, palette=None, filt=0):
    """Writes rows (lists of bytes per pixel) as a PNG with the given colour type and filter."""
    channels = {2: 3, 3: 1, 6: 4}[ctype]
    raw = b""
    prev = bytes(width * channels)
    for r in rows:
        line = bytes(b for px in r for b in (px if isinstance(px, tuple) else (px,)))
        if filt == 2:   # up: store the difference to the row above
            line = bytes((line[i] - prev[i]) & 0xFF for i in range(len(line)))
            raw += b"\x02" + line
            prev = bytes(b for px in r for b in (px if isinstance(px, tuple) else (px,)))
        else:
            raw += b"\x00" + line
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, len(rows), 8, ctype, 0, 0, 0))
    if palette:
        data += chunk(b"PLTE", b"".join(bytes(c) for c in palette)) + chunk(b"tRNS", b"\x00")
    data += chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    open(path, "wb").write(data)


def decode(tile, x, y):
    """The colour index of a pixel of a tile in the SNES's 4-bit format."""
    return sum(((tile[(p // 2) * 16 + y * 2 + (p & 1)] >> (7 - x)) & 1) << p for p in range(4))


class Png2SnesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def test_indexed_tiles_round_trip(self):
        random.seed(1)
        rows = [[random.randrange(16) for _ in range(16)] for _ in range(16)]
        pal = [(i * 16, 255 - i * 16, (i * 37) % 256) for i in range(16)]
        path = os.path.join(self.dir, "a.png")
        png(path, 16, rows, 3, pal, filt=2)
        w, h, px, colours = png2snes.indexed(path)
        self.assertEqual(px, rows)
        self.assertEqual(colours, pal)
        for ty in range(2):
            for tx in range(2):
                t = png2snes.tile_bytes(px, tx, ty)
                for y in range(8):
                    for x in range(8):
                        self.assertEqual(decode(t, x, y), rows[ty * 8 + y][tx * 8 + x])

    def test_two_bit_tiles(self):
        random.seed(2)
        rows = [[random.randrange(4) for _ in range(8)] for _ in range(8)]
        pal = [(i * 60, 0, 0) for i in range(4)] + [(0, 0, 0)] * 12
        path = os.path.join(self.dir, "t2.png")
        png(path, 8, rows, 3, pal)
        _, _, px, _ = png2snes.indexed(path)
        t = png2snes.tile_bytes(px, 0, 0, 2)
        self.assertEqual(len(t), 16)
        for y in range(8):
            for x in range(8):
                self.assertEqual(decode(t + bytes(16), x, y), rows[y][x])
        png2snes.main(["png2snes.py", "tiles2", path, "t2", self.dir])
        c = open(os.path.join(self.dir, "t2.c")).read()
        self.assertIn("t2_tiles[16]", c)
        self.assertIn("t2_pal[8]", c)
        rows[3][3] = 4
        png(path, 8, rows, 3, pal)
        with self.assertRaises(SystemExit):
            png2snes.main(["png2snes.py", "tiles2", path, "t2", self.dir])

    def test_rgba_gets_its_colours_in_order_of_appearance(self):
        red, blue = (255, 0, 0, 255), (0, 0, 255, 255)
        clear = (9, 9, 9, 0)
        rows = [[clear, red, blue, red, clear, clear, clear, clear]] * 8
        path = os.path.join(self.dir, "b.png")
        png(path, 8, rows, 6)
        _, _, px, colours = png2snes.indexed(path)
        self.assertEqual(px[0], [0, 1, 2, 1, 0, 0, 0, 0])
        self.assertEqual(colours[1:3], [(255, 0, 0), (0, 0, 255)])

    def test_output_files_and_bgr555(self):
        rows = [[1] * 8] * 8
        path = os.path.join(self.dir, "c.png")
        png(path, 8, rows, 3, [(0, 0, 0), (248, 128, 8)] + [(0, 0, 0)] * 14)
        png2snes.main(["png2snes", "tiles", path, "thing", self.dir])
        h = open(os.path.join(self.dir, "thing.h")).read()
        c = open(os.path.join(self.dir, "thing.c")).read()
        self.assertIn("#define THING_TILE_COUNT 1", h)
        self.assertIn("#define THING_TILES_SIZE 32", h)
        self.assertIn("const uint8_t thing_pal[32]", c)
        # colour 1: r 31, g 16, b 1 -> 0b00001_10000_11111 = 0x061F, low byte first
        self.assertIn("0x00, 0x00, 0x1F, 0x06", c)

    def test_more_than_16_colours_is_an_error(self):
        rows = [[(i * 10, 0, 0, 255) for i in range(8)] + [(0, i * 10 + 5, 0, 255) for i in range(8)]] * 8
        path = os.path.join(self.dir, "d.png")
        png(path, 16, rows, 6)
        with self.assertRaises(SystemExit):
            png2snes.indexed(path)

    def test_size_not_a_multiple_of_8_is_an_error(self):
        path = os.path.join(self.dir, "e.png")
        png(path, 12, [[1] * 12] * 8, 3, [(0, 0, 0)] * 16)
        with self.assertRaises(SystemExit):
            png2snes.main(["png2snes", "tiles", path, "e", self.dir])

    def test_mode7_map_tiles_and_first_colour(self):
        # 1024 x 16: tile row 0 has a pattern in tiles 0 and 2 (the same) and 1 (another), the rest
        # colour 1; row 1 all colour 2
        rows = [[1] * 1024 for _ in range(16)]
        for y in range(8):
            for x in range(8):
                rows[y][x] = rows[y][16 + x] = (x + y) % 3 + 1
                rows[y][8 + x] = 3 if x == y else 1
        for y in range(8, 16):
            rows[y] = [2] * 1024
        pal = [(0, 0, 0), (248, 0, 0), (0, 248, 0), (0, 0, 248)]
        path = os.path.join(self.dir, "m7.png")
        png(path, 1024, rows, 3, pal)
        tmap, tiles, colours, count = png2snes.mode7(path, 33)
        self.assertEqual(count, 4)                       # pattern, diagonal, all 1, all 2
        self.assertEqual(len(tmap), 256)
        self.assertEqual(list(tmap[:4]), [0, 1, 0, 2])
        self.assertEqual(set(tmap[128:]), {3})
        self.assertEqual(tiles[0], 33 + (0 % 3))         # index 1 -> CGRAM 33
        self.assertEqual(tiles[64 + 9], 32 + 3)          # the diagonal: index 3 at (1, 1)
        self.assertEqual(len(colours), 6)                # indices 1..3
        png2snes.main(["png2snes", "mode7", path, "m7", self.dir, "33"])
        h = open(os.path.join(self.dir, "m7.h")).read()
        self.assertIn("#define M7_PAL_FIRST 33", h)
        self.assertIn("#define M7_MAP_SIZE 256", h)
        self.assertIn("#define M7_TILES_SIZE 256", h)

    def test_mode7_limits(self):
        pal = [(i, i, i) for i in range(256)]
        path = os.path.join(self.dir, "m7b.png")
        png(path, 1024, [[200] * 1024] * 8, 3, pal)
        with self.assertRaises(SystemExit):                 # 200 from colour 100 on: past 255
            png2snes.mode7(path, 100)
        rows = [[1] * 1024 for _ in range(24)]
        for k in range(384):                                # 384 tiles, all different
            rows[(k // 128) * 8][(k % 128) * 8] = k // 32 + 1
            rows[(k // 128) * 8][(k % 128) * 8 + 1] = k % 32 + 1
        png(path, 1024, rows, 3, pal)
        with self.assertRaises(SystemExit):                 # more than 256 tiles
            png2snes.mode7(path, 1)
        png(path, 512, [[1] * 512] * 8, 3, pal)
        with self.assertRaises(SystemExit):                 # not 1024 wide
            png2snes.mode7(path, 1)


if __name__ == "__main__":
    unittest.main(verbosity=1)
