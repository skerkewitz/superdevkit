#ifndef SDEVKIT_INPUT_H
#define SDEVKIT_INPUT_H

/*
 * The buttons pressed since the last tick, an optional layer above the core (ADR-6FNPPCSX): one
 * call at the start of game_tick keeps sdevkit_input up to date. A device added later (a
 * multitap's pads, a mouse) adds its fields here.
 */

#include "sdevkit/loop.h"

typedef struct {
    uint16_t pad[SDEVKIT_PADS];        /* the buttons held (SDEVKIT_KEY_*) */
    uint16_t pressed[SDEVKIT_PADS];    /* the buttons held now and not in the tick before */
} sdevkit_input_state;

extern sdevkit_input_state sdevkit_input;

/* With game_tick's pad: the buttons held and the ones pressed since the last call. */
void sdevkit_input_update(const uint16_t pad[SDEVKIT_PADS]);

#endif /* SDEVKIT_INPUT_H */
