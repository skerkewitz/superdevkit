/*
 * The splash screen of every game made with superdevkit (ADR record 37): the kit's logo fades in
 * from black, stays three seconds and fades out, then the game starts. A button pressed while it
 * fades in or stays ends it as soon as it has faded in. It runs before game_init, in the library's
 * main loop on every platform: lib/snes/main.c and lib/desktop/main.c include this file, and each
 * defines one frame of it first:
 *
 *   static uint16_t sdevkit__splash_frame(uint8_t brightness);
 *       the next frame, shown with this brightness (INIDISP, 0..15, the screen on); returns the
 *       buttons of pad 1 in that frame
 *
 * The console must be as at power-on (the video reset) before, and the main loop resets it again
 * after, so game_init finds what it found before the splash existed. The splash uses only the
 * console API, mode 1 and one layer, and never the sprite table, whose copy the checks count ticks
 * by. The logo is lib/splash/splash_logo.h, made by dev/art/splash.py.
 */
#ifndef SDEVKIT__SPLASH_H
#define SDEVKIT__SPLASH_H

#include "sdevkit/snes.h"
#include "splash_logo.h"

#define SDEVKIT__SPLASH_FADE   31     /* frames of a fade: brightness 0 to 15, two frames a step */
#define SDEVKIT__SPLASH_HOLD   180    /* frames the logo stays: three seconds */
#define SDEVKIT__SPLASH_MAP    0x1000 /* VRAM words: the tiles from 0, the map here */
#define SDEVKIT__SPLASH_ROW    10     /* the logo's first map row: 7 rows from y 80, scrolled to 84 */

static uint16_t sdevkit__splash_frame(uint8_t brightness);

static uint16_t sdevkit__splash_rows[SDEVKIT__SPLASH_ROWS * 32];

static void sdevkit__splash(void)
{
    uint16_t f, pad, last = 0;
    uint8_t skip = 0, r, c;

    /* the screen is off: tiles, map, palette, one layer, centred */
    for (r = 0; r < SDEVKIT__SPLASH_ROWS; r++) {
        for (c = 0; c < SDEVKIT__SPLASH_COLUMNS; c++) {
            sdevkit__splash_rows[r * 32 + 1 + c] = sdevkit__splash_map[r * SDEVKIT__SPLASH_COLUMNS + c];
        }
    }
    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);
    sdevkit_bg_set_disable(1);
    sdevkit_bg_set_disable(2);
    sdevkit_bg_set_gfx_ptr(0, 0x0000);
    sdevkit_bg_set_map_ptr(0, SDEVKIT__SPLASH_MAP, SDEVKIT_SC_32x32);
    sdevkit_dma_copy_vram(sdevkit__splash_tiles, 0x0000, sizeof sdevkit__splash_tiles);
    sdevkit_dma_copy_vram((const uint8_t *)sdevkit__splash_rows, SDEVKIT__SPLASH_MAP + SDEVKIT__SPLASH_ROW * 32,
                          sizeof sdevkit__splash_rows);
    sdevkit_dma_copy_cgram((const uint8_t *)sdevkit__splash_palette, 0, sizeof sdevkit__splash_palette);
    sdevkit_bg_set_scroll(0, 0, 0x3FB);   /* down by 4: the logo's 56 lines in the middle of 224 */

    /* fade in; a button pressed meanwhile ends the splash once it has faded in */
    for (f = 0; f < SDEVKIT__SPLASH_FADE; f++) {
        pad = sdevkit__splash_frame((uint8_t)(f >> 1));
        if (pad & ~last) {
            skip = 1;
        }
        last = pad;
    }
    if (skip) {
        return;
    }
    for (f = 0; f < SDEVKIT__SPLASH_HOLD; f++) {
        pad = sdevkit__splash_frame(15);
        if (pad & ~last) {
            return;
        }
        last = pad;
    }
    for (f = 0; f < SDEVKIT__SPLASH_FADE; f++) {
        (void)sdevkit__splash_frame((uint8_t)(15 - (f >> 1)));
    }
}

#endif
