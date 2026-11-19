"""Cuts the splash screen's design (dev/art/splash/logo_source.png, the user's picture, 230 x 174
pixels with 255 colours) into its elements, each with few colours on one solid background, so they
can be fitted into tiles one by one:

    python3 dev/art/splash_parts.py   -> dev/art/splash/parts/{text,sticker,locomotive,stars}.png,
                                         dev/art/splash/parts/preview.png (all put together again),
                                         dev/art/splash/parts/layout.txt (where each part is)

Each part is an indexed PNG whose colour 0 is the background (BG); its other colours are found
here (k-means on the part's pixels), at most COLOURS[part], so a part fits one 16-colour palette of
the SNES. A pixel is part of the design when it differs from the background by more than EDGE;
its neighbours that differ by more than SOFT are kept too (the soft edges of the scaled picture).
A connected piece belongs to the part whose box holds its middle, and a part is cut as large as its\npieces; a piece of fewer than STAR pixels is a star, except the smoke in the locomotive's box.
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

BG = (32, 40, 56)                         # the background, one colour (the design's is about 31, 38, 56)
EDGE, SOFT = 14, 6

# the parts: their boxes in the design (x0, y0, x1, y1, inclusive) and their number of colours;
# the first box that holds a piece's middle owns it
PARTS = {"locomotive": ((124, 102, 202, 168), 12),
         "text":       ((9, 24, 221, 101), 8),
         "sticker":    ((35, 106, 112, 143), 6)}
STARS = 4                                 # the stars: the pieces smaller than STAR pixels
STAR = 60


def dist(a, b):
    return max(abs(x - y) for x, y in zip(a, b))


def kmeans(pixels, k):
    """k colours for the pixels: starts from the commonest colour and the ones farthest from those
    chosen, then ten rounds of k-means."""
    uniq = sorted(set(pixels), key=lambda c: c[0] * 3 + c[1] * 6 + c[2])
    if len(uniq) <= k:
        return uniq
    count = {}
    for p in pixels:
        count[p] = count.get(p, 0) + 1
    cent = [max(uniq, key=lambda c: count[c])]        # the commonest colour, then the farthest ones
    while len(cent) < k:
        cent.append(max(uniq, key=lambda c: min(sum((a - b) ** 2 for a, b in zip(c, d)) for d in cent)))
    for _ in range(10):
        sums = [[0, 0, 0, 0] for _ in cent]
        for p in pixels:
            i = min(range(len(cent)), key=lambda j: sum((a - b) ** 2 for a, b in zip(p, cent[j])))
            s = sums[i]
            s[0] += p[0]; s[1] += p[1]; s[2] += p[2]; s[3] += 1
        cent = [(s[0] // s[3], s[1] // s[3], s[2] // s[3]) if s[3] else cent[i] for i, s in enumerate(sums)]
    cent = sorted(set(cent), key=lambda c: c[0] * 3 + c[1] * 6 + c[2])
    return [(r & 0xF8, g & 0xF8, b & 0xF8) for r, g, b in cent]   # 5 bits a channel, as on the SNES


def main():
    w, h, px, pal = png2snes.read_png(os.path.join(KIT, "dev", "art", "splash", "logo_source.png"))
    src = [[tuple((pal[px[y][x]] if pal else px[y][x])[:3]) for x in range(w)] for y in range(h)]
    hard = [[dist(src[y][x], BG) > EDGE for x in range(w)] for y in range(h)]
    keep = [[hard[y][x] or (dist(src[y][x], BG) > SOFT and any(
        hard[v][u] for u in range(max(0, x - 1), min(w, x + 2)) for v in range(max(0, y - 1), min(h, y + 2))))
        for x in range(w)] for y in range(h)]

    # the connected pieces (8 neighbours); a piece belongs to the first box that holds its middle,
    # a small piece is a star, except the locomotive's smoke; a part is as large as its pieces
    piece = [[None] * w for _ in range(h)]
    pieces = []                           # the pixels of each piece
    for y in range(h):
        for x in range(w):
            if keep[y][x] and piece[y][x] is None:
                n, todo, pts = len(pieces), [(x, y)], []
                piece[y][x] = n
                while todo:
                    a, b = todo.pop()
                    pts.append((a, b))
                    for u in range(max(0, a - 1), min(w, a + 2)):
                        for v in range(max(0, b - 1), min(h, b + 2)):
                            if keep[v][u] and piece[v][u] is None:
                                piece[v][u] = n
                                todo.append((u, v))
                pieces.append(pts)

    def owner(pts):
        mx = (min(x for x, _ in pts) + max(x for x, _ in pts)) // 2
        my = (min(y for _, y in pts) + max(y for _, y in pts)) // 2
        for name, ((x0, y0, x1, y1), _) in PARTS.items():
            if x0 <= mx <= x1 and y0 <= my <= y1 and (len(pts) >= STAR or name == "locomotive"):
                return name
        return "stars"

    members = dict((n, []) for n in list(PARTS) + ["stars"])
    for pts in pieces:
        members[owner(pts)] += pts
    counts = dict((n, c) for n, (_, c) in PARTS.items())
    counts["stars"] = STARS
    out = os.path.join(KIT, "dev", "art", "splash", "parts")
    os.makedirs(out, exist_ok=True)
    preview = [[BG] * w for _ in range(h)]
    layout = [f"design {w} {h}"]
    for name, pts in members.items():
        if name == "stars":
            x0, y0, x1, y1 = 0, 0, w - 1, h - 1
        else:
            x0, y0 = min(x for x, _ in pts), min(y for _, y in pts)
            x1, y1 = max(x for x, _ in pts), max(y for _, y in pts)
        colours = kmeans([src[y][x] for x, y in pts], counts[name])
        c = Canvas(x1 - x0 + 1, y1 - y0 + 1)
        for x, y in pts:
            i = min(range(len(colours)), key=lambda j: sum((a - b) ** 2 for a, b in zip(src[y][x], colours[j])))
            c.set(x - x0, y - y0, i + 1)
            preview[y][x] = colours[i]
        palette = [BG] + colours
        save_png(os.path.join(out, name + ".png"), c, palette + [(0, 0, 0)] * (16 - len(palette)), transparent0=False)
        layout.append(f"{name} {x0} {y0}")
        print(f"{name}: {c.w} x {c.h} at ({x0}, {y0}), {len(colours)} colours + background")
    with open(os.path.join(out, "layout.txt"), "w") as f:
        f.write("\n".join(layout) + "\n")
    allc = sorted({p for row in preview for p in row})
    c = Canvas(w, h)
    for y in range(h):
        for x in range(w):
            c.set(x, y, allc.index(preview[y][x]))
    if len(allc) <= 256:
        save_png(os.path.join(out, "preview.png"), c, allc, transparent0=False)
    print(f"preview: {len(allc)} colours")


main()
