/*
 * The splash screen of every game made with superdevkit (ADR-6F60Z5JJ and ADR-6F60ZA6T): the kit's logo
 * fades in from black, stays three seconds and fades out, then the game starts. A button pressed
 * while it fades in or stays ends it as soon as it has faded in. It runs before game_init, in the
 * library's main loop on every platform: lib/snes/main.c and lib/desktop/main.c include this file,
 * and each defines two functions of it first:
 *
 *   static uint16_t sdevkit__splash_frame(uint8_t brightness);
 *       the next frame, shown with this brightness (INIDISP, 0..15, the screen on); returns the
 *       buttons of pad 1 in that frame
 *   static void sdevkit__splash_sprites(const uint8_t *low, uint16_t bytes, const uint8_t *high);
 *       with the screen off: the first bytes of the sprite table become low, its high table (32
 *       bytes) becomes high, without a DMA to OAM, since the checks count ticks by those
 *
 * The console must be as at power-on (the video reset) before, and the main loop resets it again
 * after and puts the game's sprite table back, so game_init finds what it found before the splash
 * existed. The splash uses the console API, mode 1 with one layer, and the sprites: the text, the
 * sticker and the locomotive are BG1 tiles, the stars sprites. Its registers go into the hardware
 * at once (sdevkit__commit_registers, the commit's without the sprite table and INIDISP): the
 * screen stays off until its first frame. The logo is lib/splash/splash_logo.h,
 * made by dev/art/splash.py.
 */
#ifndef SDEVKIT__SPLASH_H
#define SDEVKIT__SPLASH_H

#include "sdevkit/snes.h"
#include "splash_logo.h"

#define SDEVKIT__SPLASH_FADE   31     /* frames of a fade: brightness 0 to 15, two frames a step */
#define SDEVKIT__SPLASH_HOLD   180    /* frames the logo stays: three seconds */

static uint16_t sdevkit__splash_frame(uint8_t brightness);
static void sdevkit__splash_sprites(const uint8_t *low, uint16_t bytes, const uint8_t *high);
void sdevkit__commit_registers(uint8_t marks);   /* lib/snes/snes.c, lib/rachael/ppu.c */

static void sdevkit__splash(void)
{
    uint16_t f, pad, last = 0;
    uint8_t skip = 0;

    /* the screen is off: BG1 with tiles, map and palettes, the stars as sprites */
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE;
    sdevkit_ppu.bgnba[0] = SDEVKIT_BGNBA(0x0000, 0);
    sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(SDEVKIT__SPLASH_MAP, SDEVKIT_SC_32x32);
    sdevkit_ppu.vofs[0] = 0x3FF;          /* map line y on screen line y */
    sdevkit_ppu.obsel = SDEVKIT_OBSEL(SDEVKIT__SPLASH_OBJ, SDEVKIT_OBJ_SIZE8_L16);
    sdevkit__commit_registers(SDEVKIT_MARK_ALL);
    sdevkit_vram_now(0x0000, sdevkit__splash_tiles, sizeof sdevkit__splash_tiles);
    sdevkit_vram_now(SDEVKIT__SPLASH_MAP + SDEVKIT__SPLASH_ROW * 32, sdevkit__splash_map, sizeof sdevkit__splash_map);
    sdevkit_cgram_now(0, sdevkit__splash_palette, sizeof sdevkit__splash_palette);
    sdevkit_vram_now(SDEVKIT__SPLASH_OBJ, sdevkit__splash_obj_tiles, sizeof sdevkit__splash_obj_tiles);
    sdevkit_cgram_now(128, sdevkit__splash_obj_palette, sizeof sdevkit__splash_obj_palette);
    sdevkit__splash_sprites(sdevkit__splash_oam, sizeof sdevkit__splash_oam, sdevkit__splash_oam_high);

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
