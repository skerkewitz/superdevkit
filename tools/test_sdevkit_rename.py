#!/usr/bin/env python3
"""Tests of sdevkit_rename.py: identifiers change, strings and comments do not, files are
rewritten in place, the compatibility headers come from the table.

    python3 -I tools/test_sdevkit_rename.py      (the Gradle build runs it, ToolsTest)
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sdevkit_rename as r  # noqa: E402


class Rename(unittest.TestCase):

    def test_identifiers(self):
        src = 'if (pad & KEY_A) { oamSet(0, 1, 2, 3, 0, 0, 4, 0); oamSetEx(0, OBJ_SMALL, OBJ_SHOW); }'
        out, n = r.rename(src)
        self.assertEqual(out, 'if (pad & SDEVKIT_KEY_A) { sdevkit_oam_set(0, 1, 2, 3, 0, 0, 4, 0); '
                              'sdevkit_oam_set_ex(0, SDEVKIT_OBJ_SMALL, SDEVKIT_OBJ_SHOW); }')
        self.assertEqual(n, 5)

    def test_whole_identifiers_only(self):
        # oamSetXY is its own name, not oamSet + XY; my_oamSet and KEY_AB are not in the table
        out, _ = r.rename("oamSetXY(1, 2, 3); my_oamSet(); KEY_AB; u8 x; u80 y;")
        self.assertEqual(out, "sdevkit_oam_set_xy(1, 2, 3); my_oamSet(); KEY_AB; uint8_t x; u80 y;")

    def test_strings_and_comments_stay(self):
        src = 'puts("oamSet KEY_A"); c = \'u\'; /* setMode(1) */ // bgSetScroll\nsetMode(1, 0);'
        out, _ = r.rename(src)
        self.assertEqual(out, 'puts("oamSet KEY_A"); c = \'u\'; /* setMode(1) */ // bgSetScroll\n'
                              'sdevkit_set_mode(1, 0);')

    def test_comments_on_request(self):
        out, _ = r.rename("/* call setMode with BG_MODE1 */ x;", comments=True)
        self.assertEqual(out, "/* call sdevkit_set_mode with SDEVKIT_BG_MODE1 */ x;")

    def test_escaped_quotes(self):
        out, _ = r.rename(r'puts("a \" oamSet"); oamInit();')
        self.assertEqual(out, r'puts("a \" oamSet"); sdevkit_oam_init();')

    def test_includes_and_types(self):
        out, _ = r.rename('#include "snes/snes.h"\n#include "snes/loop.h"\nu16 a[SNES_PADS]; s16 b; u32 c;')
        self.assertEqual(out, '#include "sdevkit/snes.h"\n#include "sdevkit/loop.h"\n'
                              'uint16_t a[SDEVKIT_PADS]; int16_t b; uint32_t c;')

    def test_debug_vram(self):
        self.assertEqual(r.rename("const u16 *v = snes_vram();")[0], "const uint16_t *v = sdevkit_dbg_vram();")

    def test_every_new_name_has_the_prefix(self):
        for old, new in r.TABLE.items():
            if (old, new) not in r.TYPES:
                self.assertTrue(new.startswith(("sdevkit_", "SDEVKIT_")), new)
        self.assertEqual(len(set(r.TABLE.values())), len(r.TABLE), "two old names map to one new name")

    def test_files_in_place(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "src"))
            c = os.path.join(d, "src", "a.c")
            txt = os.path.join(d, "src", "notes.txt")
            with open(c, "w") as f:
                f.write("void f(void) { setScreenOn(); }\n")
            with open(txt, "w") as f:
                f.write("setScreenOn\n")
            self.assertEqual(r.main(["--dry-run", d]), 0)
            with open(c) as f:
                self.assertIn("setScreenOn", f.read())
            self.assertEqual(r.main([d]), 0)
            with open(c) as f:
                self.assertEqual(f.read(), "void f(void) { sdevkit_set_screen_on(); }\n")
            with open(txt) as f:
                self.assertEqual(f.read(), "setScreenOn\n")

    def test_compat_maps_every_name(self):
        h = r.compat_headers()
        for old, new in r.SNES_TABLE:
            self.assertIn("#define %s %s\n" % (old, new), h["snes.h"])
        for old, new in r.LOOP_MACROS:
            self.assertIn("#define %s %s\n" % (old, new), h["loop.h"])
        self.assertIn("typedef uint8_t  u8;", h["snes.h"])


if __name__ == "__main__":
    unittest.main()
