/*
 * hello: the smallest game of superdevkit. Text on the screen, and a ball that the joypad moves.
 *
 * Every superdevkit game defines three functions, which the library's main loop calls
 * (sdevkit/loop.h):
 *
 *   game_init     once, with the screen off: graphics into VRAM, palettes into CGRAM
 *   game_tick     once per frame, while the picture is drawn: move things, prepare the picture
 *   game_vblank   in the vertical blank, the only time the PPU takes new data: copy it there
 *
 * Step through it in Mesen: build/hello.dbg next to the ROM shows this file while you step.
 */
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "font_gfx.h"
#include "ball_gfx.h"

#define VRAM_FONT    0x0000    /* background tiles, in 16-bit words */
#define VRAM_MAP     0x1000    /* the background map: 32 x 32 entries */
#define VRAM_SPRITES 0x4000    /* sprite tiles */
#define TILE_FONT    32        /* the first character of assets/font_gfx.png */

static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";

static uint16_t map[32 * 32];  /* the background map in RAM; copied in the vertical blank */
static uint8_t map_changed;
static int16_t ball_x = 120;
static int16_t ball_y = 104;

/* Writes text into the map, at column x and row y (in tiles of 8 x 8 pixels). */
static void print(uint8_t x, uint8_t y, const char *s)
{
    while (*s != 0) {
        uint8_t i = 0;
        while (FONT[i] != 0 && FONT[i] != *s) {
            i++;
        }
        map[(uint16_t)y * 32 + x] = (uint16_t)(TILE_FONT + (FONT[i] != 0 ? i : 0));
        x++;
        s++;
    }
    map_changed = 1;
}

/* Writes a number of three digits. */
static void print_number(uint8_t x, uint8_t y, uint16_t n)
{
    char digits[4];
    digits[0] = (char)('0' + n / 100 % 10);
    digits[1] = (char)('0' + n / 10 % 10);
    digits[2] = (char)('0' + n % 10);
    digits[3] = 0;
    print(x, y, digits);
}

/* Moves the ball two pixels a frame in the direction the joypad points, inside the screen. */
static void move_ball(uint16_t pad)
{
    if ((pad & SDEVKIT_KEY_LEFT) && ball_x > 0) {
        ball_x -= 2;
    }
    if ((pad & SDEVKIT_KEY_RIGHT) && ball_x < 240) {
        ball_x += 2;
    }
    if ((pad & SDEVKIT_KEY_UP) && ball_y > 0) {
        ball_y -= 2;
    }
    if ((pad & SDEVKIT_KEY_DOWN) && ball_y < 208) {
        ball_y += 2;
    }
}

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);                    /* mode 1: two layers of 16 colours, one of 4 */
    sdevkit_bg_set_disable(1);                         /* only the first layer is used */
    sdevkit_bg_set_disable(2);
    sdevkit_bg_init_tile_set(0, font_gfx_tiles, font_gfx_pal, 0, FONT_GFX_TILES_SIZE,
                             FONT_GFX_PAL_SIZE, SDEVKIT_BG_16COLORS, VRAM_FONT);
    sdevkit_bg_set_map_ptr(0, VRAM_MAP, SDEVKIT_SC_32x32);
    sdevkit_bg_set_scroll(0, 0, 0x3FF);                /* the map's first line on the screen's first line */
    sdevkit_oam_init_gfx_set(ball_gfx_tiles, BALL_GFX_TILES_SIZE, ball_gfx_pal, BALL_GFX_PAL_SIZE,
                             0, VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_oam_init();
    print(10, 6, "HELLO SNES!");
    print(5, 9, "MOVE THE BALL WITH THE");
    print(5, 10, "JOYPAD.");
    print(10, 24, "X:");
    print(17, 24, "Y:");
    sdevkit_dma_copy_vram((const uint8_t *)map, VRAM_MAP, sizeof map);   /* the screen is off: copy at once */
    map_changed = 0;
    sdevkit_set_brightness(15);                       /* the screen on, at full brightness */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    move_ball(pad[0]);
    print_number(12, 24, (uint16_t)ball_x);
    print_number(19, 24, (uint16_t)ball_y);
    /* sprite 0: x, y (one line higher, as the SNES draws sprites one line lower), priority 3,
     * no flips, tile 0, palette 0; small (16 x 16), shown */
    sdevkit_oam_set(0, (uint16_t)ball_x, (uint16_t)(ball_y - 1), 3, 0, 0, 0, 0);
    sdevkit_oam_set_ex(0, SDEVKIT_OBJ_SMALL, SDEVKIT_OBJ_SHOW);
}

void game_vblank(void)
{
    if (map_changed) {
        sdevkit_dma_copy_vram((const uint8_t *)map, VRAM_MAP, sizeof map);
        map_changed = 0;
    }
}
