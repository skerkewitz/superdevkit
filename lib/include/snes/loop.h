/*
 * DEPRECATED: the old names of superdevkit's console API, mapped to the sdevkit_ ones (ADR
 * record 34). Only for code that cannot be changed yet (the snapshot of Blubber Blasen); new code
 * includes "sdevkit/loop.h". tools/sdevkit_rename.py converts a game, and writes this file
 * (--write-compat): do not edit it by hand.
 */

#ifndef SNES_LOOP_H
#define SNES_LOOP_H

#include "sdevkit/loop.h"

#define SNES_PADS SDEVKIT_PADS
#define KEY_B SDEVKIT_KEY_B
#define KEY_Y SDEVKIT_KEY_Y
#define KEY_SELECT SDEVKIT_KEY_SELECT
#define KEY_START SDEVKIT_KEY_START
#define KEY_UP SDEVKIT_KEY_UP
#define KEY_DOWN SDEVKIT_KEY_DOWN
#define KEY_LEFT SDEVKIT_KEY_LEFT
#define KEY_RIGHT SDEVKIT_KEY_RIGHT
#define KEY_A SDEVKIT_KEY_A
#define KEY_X SDEVKIT_KEY_X
#define KEY_L SDEVKIT_KEY_L
#define KEY_R SDEVKIT_KEY_R

#endif /* SNES_LOOP_H */
