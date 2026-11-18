/*
 * The main loop of superdevkit's library on the SNES: the frame rhythm of sdevkit/loop.h.
 *
 * One tick per frame: read the joypads, run the tick, then in the vertical blank that follows
 * copy the OAM shadow and let the game upload what the tick prepared (game_vblank).
 * A tick that takes longer than a frame makes the game run slower, never wrong.
 *
 * There is no interrupt handler: the loop waits for the vertical blank itself. dampf keeps
 * function frames at fixed places, so C code called from an NMI that arrives in the middle of a
 * tick would overwrite the frames of the code it interrupted. Calling game_vblank from the loop
 * gives the same timing without that.
 *
 * Not done here: sound and save RAM.
 *
 * sdevkit__start() and sdevkit__frame() are also what the replay test plays a recording with
 * (dev/replay/driver.c, REPLAY_PLATFORM), so it runs the same code as the ROM you play.
 */
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "hw.h"

void sdevkit__reset_video(void);
void sdevkit__oam_upload(void);
void sdevkit__wait_vblank(void);
void sdevkit__read_pads(uint16_t *pad1, uint16_t *pad2);

/* Before game_init: the console in a known state, the screen off. */
void sdevkit__start(void)
{
    WRIO = 0xFF;        /* the H/V counter latch needs bit 7 set */
    NMITIMEN = 0;
    sdevkit__reset_video();
    NMITIMEN = 0x01;    /* automatic joypad read, no interrupts */
}

/* After each game_tick: the next vertical blank, the OAM shadow, the game's uploads. */
void sdevkit__frame(void)
{
    sdevkit__wait_vblank();
    sdevkit__oam_upload();
    game_vblank();
}

int main(void)
{
    uint16_t pad[SDEVKIT_PADS];

    sdevkit__start();
    game_init(0);
    for (;;) {
        sdevkit__read_pads(&pad[0], &pad[1]);
        game_tick(pad);
        sdevkit__frame();
    }
}
