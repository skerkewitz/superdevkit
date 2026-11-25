#ifndef SDEVKIT_EASY_H
#define SDEVKIT_EASY_H

/*
 * All optional layers above the core and one commit for them (ADR-6FNPPCSX): a game that wants
 * none of the vertical blank's rules writes its next picture in game_tick, into the register
 * shadow (sdevkit/shadow.h), the sprite list (sdevkit/sprites.h) and the upload queue
 * (sdevkit/queue.h), and its game_vblank is one line:
 *
 *   void game_vblank(void) { sdevkit_commit(); }
 *
 * sdevkit/input.h's buttons pressed come with it. The layers cost what their functions cost; a
 * game that outgrows one replaces it by the core where it matters, the rest stays.
 */

#include "sdevkit/shadow.h"
#include "sdevkit/sprites.h"
#include "sdevkit/queue.h"
#include "sdevkit/input.h"

/* In game_vblank: a screen to be switched off goes off first; the sprite list, the registers of
 * the groups marked, the queue; then INIDISP, which may switch the screen on. Also in game_init
 * (the screen is off there), for a picture before a long job such as sdevkit_sfx_init. */
void sdevkit_commit(void);

#endif /* SDEVKIT_EASY_H */
