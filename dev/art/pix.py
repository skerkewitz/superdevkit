"""A tiny raster for drawing pixel art: indexed canvases, shapes, outlines, PNG output."""
import math, struct, zlib

class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[0] * w for _ in range(h)]
    def get(self, x, y):
        return self.px[y][x] if 0 <= x < self.w and 0 <= y < self.h else 0
    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c
    def blit(self, other, ox, oy):
        for y in range(other.h):
            for x in range(other.w):
                if other.px[y][x]:
                    self.set(ox + x, oy + y, other.px[y][x])
    def ellipse(self, cx, cy, rx, ry, shades, light=(-0.6, -0.7)):
        """Fills an ellipse; shades = colours from dark to bright, picked by the light direction."""
        lx, ly = light
        for y in range(self.h):
            for x in range(self.w):
                dx, dy = (x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry
                d = dx * dx + dy * dy
                if d <= 1.0:
                    nz = math.sqrt(max(0.0, 1 - d))
                    lum = -(dx * lx + dy * ly) * 0.8 + nz * 0.6
                    k = int((lum + 0.35) / 1.25 * len(shades))
                    self.set(x, y, shades[max(0, min(len(shades) - 1, k))])
    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)
    def outline(self, c, diagonal=False):
        """Every empty pixel next to a filled one becomes c."""
        add = []
        for y in range(self.h):
            for x in range(self.w):
                if self.px[y][x]:
                    continue
                n = [(1, 0), (-1, 0), (0, 1), (0, -1)] + ([(1, 1), (-1, -1), (1, -1), (-1, 1)] if diagonal else [])
                if any(self.get(x + a, y + b) not in (0, c) for a, b in n):
                    add.append((x, y))
        for x, y in add:
            self.px[y][x] = c
    def rotated(self, quarter):
        out = self
        for _ in range(quarter % 4):
            r = Canvas(out.h, out.w)
            for y in range(out.h):
                for x in range(out.w):
                    r.px[x][out.h - 1 - y] = out.px[y][x]
            out = r
        return out
    def flipped(self):
        r = Canvas(self.w, self.h)
        r.px = [list(reversed(row)) for row in self.px]
        return r
    def from_text(self, rows, legend, ox=0, oy=0):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in legend and legend[ch] is not None:
                    self.set(ox + x, oy + y, legend[ch])

def save_png(path, canvas, palette, transparent0=True, scale=1):
    w, h = canvas.w * scale, canvas.h * scale
    rows = []
    for y in range(h):
        rows.append(bytes(canvas.px[y // scale][x // scale] for x in range(w)))
    raw = b"".join(b"\x00" + r for r in rows)
    def chunk(k, d):
        return struct.pack(">I", len(d)) + k + d + struct.pack(">I", zlib.crc32(k + d) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 3, 0, 0, 0))
    png += chunk(b"PLTE", b"".join(bytes(c) for c in palette))
    if transparent0:
        png += chunk(b"tRNS", b"\x00")
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    open(path, "wb").write(png)
