#!/usr/bin/env python3
"""Compares the screenshots of an example's check in Mesen (the SNES build, dampf) with those of
its desktop build (gcc, Rachael), at the SNES's 5 bits per colour: the same game code must
give the same picture, pixel for pixel.

    python3 -I dev/compare_shots.py examples/<name>/build/check examples/<name>/build/desktop-check
"""
import glob
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools"))
import png2snes  # noqa: E402


def five(px):
    return tuple(v >> 3 for v in px[:3])


def main(snes_dir, desktop_dir):
    bad = 0
    shots = sorted(glob.glob(os.path.join(snes_dir, "example_splash_*.png")), key=lambda p: int(p.rsplit("_", 1)[1][:-4]))
    shots += sorted(glob.glob(os.path.join(snes_dir, "example_shot_*.png")), key=lambda p: int(p.rsplit("_", 1)[1][:-4]))
    if not shots:
        print(f"compare: no screenshots in {snes_dir}")
        return 1
    for s in shots:
        d = os.path.join(desktop_dir, os.path.basename(s))
        if not os.path.exists(d):
            print(f"{os.path.basename(s)}: no desktop screenshot")
            bad += 1
            continue
        w, h, a, _ = png2snes.read_png(s)
        w2, h2, b, _ = png2snes.read_png(d)
        if (w, h) != (w2, h2):
            print(f"{os.path.basename(s)}: {w} x {h} on the SNES, {w2} x {h2} on the desktop")
            bad += 1
            continue
        diff = [(x, y) for y in range(h) for x in range(w) if five(a[y][x]) != five(b[y][x])]
        print(f"{os.path.basename(s)}: {len(diff)} pixels differ" + (f", the first at {diff[0]}" if diff else ""))
        bad += 1 if diff else 0
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
