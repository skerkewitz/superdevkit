#!/usr/bin/env python3
"""Renames a game's C sources from the old names of superdevkit's console API (PVSnesLib's names,
the snesXxx extensions, snes_vram, the KEY_* bits) to the sdevkit_ ones (ADR record 34).

    python3 -I tools/sdevkit_rename.py FILE_OR_FOLDER...     rewrites .c and .h files in place
    python3 -I tools/sdevkit_rename.py --dry-run FILE...     only prints what it would change

Only identifiers change: strings, character constants and comments stay as they are (with
--comments, identifiers in comments change too). The include of "snes/snes.h" or "snes/loop.h"
becomes "sdevkit/snes.h" or "sdevkit/loop.h". The typedefs u8, u16, s16 and u32 become the
<stdint.h> types. It prints every file it changed, with the number of names replaced.

A file that includes raylib.h is a desktop frontend, not game code: raylib has KEY_* names of its
own, so the file is left as it is, with a warning.
"""
import os
import re
import sys

# --- the table -------------------------------------------------------------------------------
FUNCTIONS = [
    ("setMode", "sdevkit_set_mode"),
    ("setBrightness", "sdevkit_set_brightness"),
    ("setScreenOn", "sdevkit_set_screen_on"),
    ("setScreenOff", "sdevkit_set_screen_off"),
    ("setColorEffect", "sdevkit_set_color_effect"),
    ("setColorIntensity", "sdevkit_set_color_intensity"),
    ("setPaletteColor", "sdevkit_set_palette_color"),
    ("dmaCopyVram", "sdevkit_dma_copy_vram"),
    ("dmaCopyCGram", "sdevkit_dma_copy_cgram"),
    ("dmaClearVram", "sdevkit_dma_clear_vram"),
    ("bgSetGfxPtr", "sdevkit_bg_set_gfx_ptr"),
    ("bgSetMapPtr", "sdevkit_bg_set_map_ptr"),
    ("bgSetScroll", "sdevkit_bg_set_scroll"),
    ("bgSetEnable", "sdevkit_bg_set_enable"),
    ("bgSetDisable", "sdevkit_bg_set_disable"),
    ("bgSetEnableSub", "sdevkit_bg_set_enable_sub"),
    ("bgSetDisableSub", "sdevkit_bg_set_disable_sub"),
    ("bgInitTileSet", "sdevkit_bg_init_tile_set"),
    ("bgInitMapSet", "sdevkit_bg_init_map_set"),
    ("oamInit", "sdevkit_oam_init"),
    ("oamInitGfxSet", "sdevkit_oam_init_gfx_set"),
    ("oamInitGfxAttr", "sdevkit_oam_init_gfx_attr"),
    ("oamSet", "sdevkit_oam_set"),
    ("oamSetXY", "sdevkit_oam_set_xy"),
    ("oamSetEx", "sdevkit_oam_set_ex"),
    ("oamSetVisible", "sdevkit_oam_set_visible"),
    ("oamClear", "sdevkit_oam_clear"),
    ("oamMemory", "sdevkit_oam_memory"),
    ("snesRegWrite", "sdevkit_reg_write"),
    ("snesHdmaSet", "sdevkit_hdma_set"),
    ("snesHdmaEnable", "sdevkit_hdma_enable"),
    ("snesDmaFits", "sdevkit_dma_fits"),
    ("snes_vram", "sdevkit_dbg_vram"),
]

SNES_MACROS = (
    "RGB5 RGB8 BG_MODE1 BG3_MODE1_PRIORITY_HIGH BG1_ENABLE BG2_ENABLE BG3_ENABLE BG4_ENABLE "
    "OBJ_ENABLE SC_32x32 SC_64x32 SC_32x64 SC_64x64 BG_4COLORS BG_16COLORS BG_TIL_NUM BG_TIL_PAL "
    "BG_TIL_PRIO BG_TIL_FLIPX BG_TIL_FLIPY OBJ_SIZE8_L16 OBJ_SIZE8_L32 OBJ_SIZE8_L64 "
    "OBJ_SIZE16_L32 OBJ_SIZE16_L64 OBJ_SIZE32_L64 OBJ_SMALL OBJ_LARGE OBJ_SHOW OBJ_HIDE "
    "ATTR2_DISABLED CM_SUBBGOBJ_ENABLE CM_ALWAYS_ENABLE CM_MATWIN_ENABLE CM_NOMATWIN_ENABLE "
    "CM_NEVER_ENABLE CM_SCBLK_ALWAYS_ENABLE CM_SCBLK_MATWIN_ENABLE CM_SCBLK_NOMATWIN_ENABLE "
    "CM_SCBLK_NEVER_ENABLE CM_SUB_MAINSUB CM_DIV2_DIVIDE CM_MSCR_BACK CM_MSCR_PAL47 CM_MSCR_BG4 "
    "CM_MSCR_BG3 CM_MSCR_BG2 CM_MSCR_BG1 CM_APPLY_BLUE CM_APPLY_GREEN CM_APPLY_RED "
    "HDMA_CHANNEL0 HDMA_CHANNEL1 HDMA_CHANNEL2 HDMA_CHANNEL3 HDMA_CHANNEL4 HDMA_CHANNEL5 "
    "HDMA_CHANNEL6 HDMA_CHANNEL7 PPU_INIDISP PPU_OBSEL PPU_BGMODE PPU_BG1SC PPU_BG2SC PPU_BG3SC "
    "PPU_BG4SC PPU_BG12NBA PPU_BG34NBA PPU_BG1HOFS PPU_BG1VOFS PPU_W12SEL PPU_W34SEL PPU_WOBJSEL "
    "PPU_WH0 PPU_WH1 PPU_WH2 PPU_WH3 PPU_WBGLOG PPU_WOBJLOG PPU_TM PPU_TS PPU_TMW PPU_TSW "
    "PPU_CGWSEL PPU_CGADSUB PPU_COLDATA"
).split()

LOOP_MACROS = [("SNES_PADS", "SDEVKIT_PADS")] + [
    ("KEY_" + k, "SDEVKIT_KEY_" + k)
    for k in "B Y SELECT START UP DOWN LEFT RIGHT A X L R".split()
]

TYPES = [("u8", "uint8_t"), ("u16", "uint16_t"), ("s16", "int16_t"), ("u32", "uint32_t")]

INCLUDES = [('"snes/snes.h"', '"sdevkit/snes.h"'), ('"snes/loop.h"', '"sdevkit/loop.h"')]

SNES_TABLE = FUNCTIONS + [(m, "SDEVKIT_" + m) for m in SNES_MACROS]
TABLE = dict(SNES_TABLE + LOOP_MACROS + TYPES)

# --- renaming ----------------------------------------------------------------------------------
TOKEN = re.compile(r"""
    (?P<comment>//[^\n]*|/\*.*?\*/)
  | (?P<string>"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')
  | (?P<ident>[A-Za-z_][A-Za-z0-9_]*)
  | (?P<other>.)
""", re.S | re.X)

IDENT = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\b")


def rename(text, table=None, comments=False):
    """The text with every identifier in the table replaced; returns (text, count)."""
    table = TABLE if table is None else table
    out, count = [], 0

    def ident(name):
        nonlocal count
        if name in table:
            count += 1
            return table[name]
        return name

    for m in TOKEN.finditer(text):
        kind = m.lastgroup
        s = m.group()
        if kind == "ident":
            out.append(ident(s))
        elif kind == "comment" and comments:
            out.append(IDENT.sub(lambda w: ident(w.group()), s))
        elif kind == "string" and s in dict(INCLUDES):
            count += 1
            out.append(dict(INCLUDES)[s])
        else:
            out.append(s)
    return "".join(out), count


def sources(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in sorted(files):
                    if f.endswith((".c", ".h")):
                        yield os.path.join(root, f)
        else:
            yield p


RAYLIB = re.compile(r'^\s*#\s*include\s*[<"]raylib\.h[>"]', re.M)


def main(argv):
    dry = "--dry-run" in argv
    comments = "--comments" in argv
    paths = [a for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__.strip().split("\n\n")[1], file=sys.stderr)
        return 2
    for path in sources(paths):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        if RAYLIB.search(text):
            print("%s: skipped, it includes raylib.h (a frontend, not game code)" % path, file=sys.stderr)
            continue
        new, count = rename(text, comments=comments)
        if new != text:
            print("%s: %d names" % (path, count))
            if not dry:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(new)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
