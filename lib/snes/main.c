/*
 * The main loop of superdevkit's library on the SNES: the frame rhythm of sdevkit/loop.h.
 *
 * One tick per frame: read the joypads, run the tick, then in the vertical blank that follows
 * commit the shadow state (sdevkit__commit: the sprite table, the registers, the queue). A tick
 * that takes longer than a frame makes the game run slower, never wrong.
 *
 * There is no interrupt handler: the loop waits for the vertical blank itself. dampf keeps
 * function frames at fixed places, so C code called from an NMI that arrives in the middle of a
 * tick would overwrite the frames of the code it interrupted.
 *
 * After the commit the sound effects the tick asked for start (sfx.c). Save RAM is snes.c's.
 */
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "hw.h"

void sdevkit__reset_video(void);
void sdevkit__reset_ppu(void);
void sdevkit__oam_upload(void);
void sdevkit__wait_vblank(void);
void sdevkit__read_input(void);
void sdevkit__read_pads(uint16_t *pad1, uint16_t *pad2);
void sdevkit__end_tick(void);
void sdevkit__begin_tick(void);
void sdevkit__commit(void);
void sdevkit__commit_registers(uint8_t marks);
void sdevkit__sfx_frame(void);
extern uint8_t sdevkit__ticking;

/* Before game_init: the console in a known state, the screen off. */
void sdevkit__start(void)
{
    WRIO = 0xFF;        /* the H/V counter latch needs bit 7 set */
    NMITIMEN = 0;
    sdevkit__reset_video();
    NMITIMEN = 0x01;    /* automatic joypad read, no interrupts */
}

/* After each game_tick: the sprite list closed and the tick's checksum (what the game named, then
 * the shadow state, sdevkit__end_tick), the mark the checks count ticks by, the next vertical
 * blank, the commit, then the sound effects of the tick (after the commit, so they take none of
 * the vertical blank). */
void sdevkit__frame(void)
{
    sdevkit__end_tick();
    DBG_TICK = 0;
    sdevkit__wait_vblank();
    sdevkit__commit();
    sdevkit__begin_tick();
    sdevkit__sfx_frame();
}

/* One frame of the splash screen (lib/splash/splash.h): the next vertical blank, the brightness
 * set in it, the pads read. */
static uint16_t sdevkit__splash_frame(uint8_t brightness)
{
    uint16_t pad1, pad2;

    sdevkit__wait_vblank();
    INIDISP = brightness;
    sdevkit__read_pads(&pad1, &pad2);
    return pad1;
}

/* The splash's sprites (lib/splash/splash.h): written to OAM by the CPU, with the screen off, not
 * by DMA, which the checks count ticks by. Also puts the game's sprite table back after the splash. */
static void sdevkit__splash_sprites(const uint8_t *low, uint16_t bytes, const uint8_t *high)
{
    uint16_t i;

    OAMADD = 0;
    for (i = 0; i < bytes; i++) {
        OAMDATA = low[i];
    }
    OAMADD = 0x100;                     /* the high table */
    for (i = 0; i < 32; i++) {
        OAMDATA = high[i];
    }
}

#include "../splash/splash.h"

int main(void)
{
    sdevkit__start();
    sdevkit__splash();
    sdevkit__reset_ppu();               /* as before the splash, the screen off, the shadow state too */
    sdevkit__splash_sprites(sdevkit_oam, 512, sdevkit_oam + 512);   /* the game's sprite table */
    game_init(0);
    sdevkit_marks = SDEVKIT_MARK_ALL;   /* everything game_init set */
    DBG_TICK = 1;                       /* the checks: the splash is over, the first tick comes */
    for (;;) {
        sdevkit__read_input();
        sdevkit__ticking = 1;
        game_tick();
        sdevkit__ticking = 0;
        sdevkit__frame();
    }
}
