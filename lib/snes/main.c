/*
 * Blubber Blasen on the SNES, built with dampf: the main loop of the platform layer.
 *
 * One tick per frame, as game.h asks: read the joypads, run the tick, then in the vertical blank
 * that follows copy the OAM shadow and let the game upload what the tick prepared (game_vblank).
 * A tick that takes longer than a frame makes the game run slower, never wrong.
 *
 * There is no interrupt handler: the loop waits for the vertical blank itself. dampf keeps
 * function frames at fixed places, so C code called from an NMI that arrives in the middle of a
 * tick would overwrite the frames of the code it interrupted. Calling game_vblank from the loop
 * gives the same timing without that.
 *
 * Not done here: sound (game_sfx, game_music) and save RAM (the high scores start empty at every
 * power-on).
 *
 * platform_start() and platform_frame() are also what the replay test plays a recording with
 * (dev/replay/driver.c, REPLAY_PLATFORM), so it runs the same code as the ROM you play.
 */
#include "game/game.h"
#include "snes/snes.h"
#include "hw.h"

void platform_reset_video(void);
void platform_oam_upload(void);
void platform_wait_vblank(void);
void platform_read_pads(u16 *pad1, u16 *pad2);

/* Before game_init: the console in a known state, the screen off. */
void platform_start(void)
{
    WRIO = 0xFF;        /* the H/V counter latch needs bit 7 set */
    NMITIMEN = 0;
    platform_reset_video();
    NMITIMEN = 0x01;    /* automatic joypad read, no interrupts */
}

/* After each game_tick: the next vertical blank, the OAM shadow, the game's uploads. */
void platform_frame(void)
{
    platform_wait_vblank();
    platform_oam_upload();
    game_vblank();
}

int main(void)
{
    uint16_t pad[NUM_PLAYERS];

    platform_start();
    game_init(0);
    for (;;) {
        platform_read_pads(&pad[0], &pad[1]);
        game_tick(pad);
        platform_frame();
    }
}
