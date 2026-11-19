#!/usr/bin/env python3
"""Makes the integer tables of the sound converter, once (docs/sound.md, "The conversion").

The converter exists twice, in Java (superdevkit sfx) and in JavaScript (tools/brr_lab.html), and
both must give the same bytes. The maths libraries of the two languages do not promise the same
last bits of sin, cos and pow, so every value that needs them is computed here, rounded to an
integer and checked in; the converters only add, multiply, shift and divide integers.

    dev/sfx_tables.py           writes cli/src/main/resources/superdevkit/sfx/tables.txt and the
                                block between the TABLES markers of tools/brr_lab.html
    dev/sfx_tables.py --check   fails if either differs from what this script makes

The tables:
    SINC   windowed sinc for the resampling: 32 zero crossings a side, 128 steps per crossing,
           Blackman window, scaled to 32767 at 0 (4097 values, the side from 0 to 32)
    SHELF  the treble boost, a high shelf at a quarter of the rate (RBJ cookbook, slope 1): per
           step of 0.5 dB from 0 to 18 dB, b0 b1 b2 a1 a2 divided by a0, in units of 1/16384.
           At a quarter of the rate cos(w) = 0, so the coefficients depend on the boost alone.
    GAIN   the gain before encoding: per step of 0.5 dB from -18 to +12 dB, 10^(dB/20) in units
           of 1/65536
    TRIM   the threshold of "trim silence", -50 dB of full scale in the converter's 24 bits
"""
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOURCE = ROOT / "cli/src/main/resources/superdevkit/sfx/tables.txt"
LAB = ROOT / "tools/brr_lab.html"

SINC_RES, SINC_HALF = 128, 32


def sinc_table():
    n = SINC_RES * SINC_HALF
    out = []
    for j in range(n + 1):
        u = j / SINC_RES
        s = 1.0 if j == 0 else math.sin(math.pi * u) / (math.pi * u)
        x = j / n
        w = 0.42 + 0.5 * math.cos(math.pi * x) + 0.08 * math.cos(2 * math.pi * x)
        out.append(round(32767 * s * w))
    return out


def shelf_table():
    out = []
    for t in range(37):
        db = t / 2
        a = 10 ** (db / 40)
        al = math.sqrt(2) / 2          # sin(w) / 2 * sqrt(2), with w = pi / 2
        sa = 2 * math.sqrt(a) * al
        b0 = a * ((a + 1) + sa)
        b1 = -2 * a * (a - 1)
        b2 = a * ((a + 1) - sa)
        a0 = (a + 1) + sa
        a1 = 2 * (a - 1)
        a2 = (a + 1) - sa
        out.append([round(v / a0 * 16384) for v in (b0, b1, b2, a1, a2)])
    return out


def gain_table():
    return [round(65536 * 10 ** (g / 40)) for g in range(-36, 25)]


def trim():
    return math.floor(8388608 * 10 ** (-50 / 20))


def resource_text():
    lines = ["# The sound converter's integer tables, made by dev/sfx_tables.py: do not edit.",
             "SINC " + " ".join(map(str, sinc_table())),
             "SHELF " + " ".join(" ".join(map(str, row)) for row in shelf_table()),
             "GAIN " + " ".join(map(str, gain_table())),
             "TRIM " + str(trim())]
    return "\n".join(lines) + "\n"


def js_block():
    def wrap(values, per=24):
        rows = [", ".join(map(str, values[i:i + per])) for i in range(0, len(values), per)]
        return ",\n  ".join(rows)
    shelf = [v for row in shelf_table() for v in row]
    return ("/* TABLES BEGIN: made by dev/sfx_tables.py, the same as the Java converter's; do not edit. */\n"
            "const SINC = [\n  " + wrap(sinc_table()) + "\n];\n"
            "const SHELF = [\n  " + wrap(shelf, 5) + "\n];\n"
            "const GAIN = [\n  " + wrap(gain_table(), 12) + "\n];\n"
            "const TRIM = " + str(trim()) + ";\n"
            "/* TABLES END */")


BLOCK = re.compile(r"/\* TABLES BEGIN.*?/\* TABLES END \*/", re.S)


def main():
    check = "--check" in sys.argv[1:]
    want_res = resource_text()
    lab = LAB.read_text(encoding="utf-8")
    if not BLOCK.search(lab):
        sys.exit("sfx_tables: no TABLES block in " + str(LAB))
    want_lab = BLOCK.sub(lambda m: js_block(), lab)
    if check:
        bad = []
        if not RESOURCE.exists() or RESOURCE.read_text(encoding="utf-8") != want_res:
            bad.append(str(RESOURCE))
        if lab != want_lab:
            bad.append(str(LAB))
        if bad:
            sys.exit("sfx_tables: not what dev/sfx_tables.py makes: " + ", ".join(bad))
        return
    RESOURCE.parent.mkdir(parents=True, exist_ok=True)
    RESOURCE.write_text(want_res, encoding="utf-8")
    LAB.write_text(want_lab, encoding="utf-8")


if __name__ == "__main__":
    main()
