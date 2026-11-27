"""The graphics of the modes example, drawn by program: the font (sprites, 4 bits a pixel) and a
picture of 256 colours for modes 3 and 4 (superdevkit png tiles8).

    python3 dev/art/modes.py examples/modes/assets
"""
import math
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pix import Canvas, save_png  # noqa: E402
from font import FONT_ORDER, glyph  # noqa: E402

out = sys.argv[1] if len(sys.argv) > 1 else "."

# The font: tile n is FONT_ORDER[n]; colour 1 the letter, 2 its shadow.
FONT_PAL = [(0, 0, 0), (250, 250, 250), (20, 20, 40)] + [(0, 0, 0)] * 13
font = Canvas(128, 24)
for n, ch in enumerate(FONT_ORDER):
    font.blit(glyph(ch, 1, 2), (n % 16) * 8, (n // 16) * 8)
save_png(f"{out}/font_gfx.png", font, FONT_PAL)

# The picture, 128 x 96: a sky from colour 16 down, a sun, hills. Colours 1 to 15 are the frame's
# (BG2 of mode 3 takes them), 240 to 255 stay free for the sprites' palette 7.
W, H = 128, 96
SKY, SUN, HILL = range(16, 112), range(112, 176), range(176, 240)
pal = [(0, 0, 0)] * 256
frame = [(250, 230, 120), (200, 120, 40), (90, 40, 10)]
for i, c in enumerate(frame):
    pal[1 + i] = c
for k, i in enumerate(SKY):                     # deep blue to orange
    t = k / (len(SKY) - 1)
    pal[i] = (int(30 + 220 * t), int(40 + 120 * t), int(140 - 80 * t))
for k, i in enumerate(SUN):                     # white core to red rim
    t = k / (len(SUN) - 1)
    pal[i] = (255, int(250 - 170 * t), int(220 - 200 * t))
for k, i in enumerate(HILL):                    # dark to light green, in bands
    t = k / (len(HILL) - 1)
    pal[i] = (int(20 + 90 * t), int(60 + 150 * t), int(30 + 40 * t))
pic = Canvas(W, H)
for y in range(H):
    for x in range(W):
        sky = SKY[min(len(SKY) - 1, y * len(SKY) // 70)]
        d = math.hypot(x - 84, y - 40) / 22.0
        c = SUN[min(len(SUN) - 1, int(d * len(SUN)))] if d < 1.0 else sky
        ridge = 62 + 10 * math.sin(x / 13.0) + 5 * math.sin(x / 5.0 + 1.0)
        if y >= ridge:
            c = HILL[min(len(HILL) - 1, int((y - ridge) * 3 + (x % 16)))]
        pic.set(x, y, c)
save_png(f"{out}/picture_gfx.png", pic, pal, transparent0=True)
