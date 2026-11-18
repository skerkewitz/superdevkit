"""The graphics of hello, drawn by program: the font (background) and a ball with a face (sprite).

    python3 dev/art/hello.py examples/hello/assets
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402
from font import FONT_ORDER, glyph  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."

# Background palette 0: the backdrop, the text in white, its shadow.
BG = [(18, 30, 80)] + [(0, 0, 0)] * 12 + [(250, 250, 250), (255, 222, 80), (8, 12, 40)]
bg = Canvas(128, 48)                 # tile 0 empty; 32.. the font (FONT_ORDER)
for n, ch in enumerate(FONT_ORDER):
    bg.blit(glyph(ch, 13), ((32 + n) % 16) * 8, ((32 + n) // 16) * 8)
save_png(f"{out}/font_gfx.png", bg, BG)

# Sprite palette 0: 1 outline, 2-4 yellow dark to light, 5 white, 8 black.
SPR = [(0, 0, 0), (40, 24, 10), (214, 120, 20), (255, 186, 40), (255, 238, 140), (255, 255, 255),
       (0, 0, 0), (0, 0, 0), (20, 12, 8)] + [(0, 0, 0)] * 7
ball = Canvas(16, 16)
ball.ellipse(8.0, 8.0, 6.8, 6.8, [2, 3, 4])
ball.from_text(["", "", "", "", "", "....88..88", "....88..88", "", "", "....8....8", ".....8888"], {"8": 8})
ball.set(5, 4, 5)
ball.outline(1)
spr = Canvas(128, 16)
spr.blit(ball, 0, 0)
save_png(f"{out}/ball_gfx.png", spr, SPR)
