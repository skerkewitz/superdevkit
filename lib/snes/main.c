/*
 * The main loop of superdevkit's library on the SNES: the frame rhythm of sdevkit/loop.h.
 *
 * One tick per frame: read the joypads, run the tick, then in the vertical blank that follows
 * the game's game_vblank. The kit writes nothing there itself (ADR-6FNPPCSX): the whole vertical
 * blank is the game's. A tick that takes longer than a frame makes the game run slower, never
 * wrong.
 *
 * The kit has no interrupt handler: the loop waits for the vertical blank itself and calls
 * game_vblank. A game may have an IRQ (sdevkit/irq.h, irq.c), which dampf compiles in a context
 * of its own; the kit's NMI comes later.
 *
 * After game_vblank the sound effects the tick asked for start (sfx.c). Save RAM is snes.c's.
 */
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "hw.h"

void sdevkit__reset_ppu(void);
void sdevkit__wait_vblank(void);
void sdevkit__read_pads(uint16_t *pad1, uint16_t *pad2);
void sdevkit__sfx_frame(void);

/* Before game_init: the console in a known state, the screen off. */
void sdevkit__start(void)
{
    WRIO = 0xFF;        /* the H/V counter latch needs bit 7 set */
    NMITIMEN = 0;
    sdevkit__reset_ppu();
    NMITIMEN = 0x01;    /* automatic joypad read, no interrupts */
}

/* After each game_tick: the mark the checks count ticks by (the tick's checksum ends there), the
 * next vertical blank, the game's writes, then the sound effects of the tick (after game_vblank,
 * so they take none of the vertical blank). */
void sdevkit__frame(void)
{
    DBG_TICK = 0;
    sdevkit__wait_vblank();
    game_vblank();
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

#include "../splash/splash.h"

int main(void)
{
    uint16_t pad[SDEVKIT_PADS];

    sdevkit__start();
    sdevkit__splash();
    sdevkit__reset_ppu();               /* as before the splash: the screen off, every sprite hidden */
    game_init(0);
    DBG_TICK = 1;                       /* the checks: the splash is over, the first tick comes */
    for (;;) {
        sdevkit__read_pads(&pad[0], &pad[1]);
        game_tick(pad);
        sdevkit__frame();
    }
}
