#ifndef SDEVKIT_INPUT_H
#define SDEVKIT_INPUT_H

/**
 * @file input.h
 * @brief The buttons pressed since the last tick, an optional layer above the core
 *        (ADR-6FNPPCSX).
 *
 * game_tick gets the buttons held (sdevkit/loop.h); a menu or a jump wants the ones pressed
 * now and not in the tick before. One call at the start of game_tick keeps sdevkit_input up to
 * date. A device added later (a multitap's pads, a mouse) adds its fields here.
 *
 * @code
 * void game_tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     sdevkit_input_update(pad);
 *     if (sdevkit_input.pressed[0] & SDEVKIT_KEY_A) {
 *         jump();                          // once a press, not once a frame
 *     }
 * }
 * @endcode
 */

#include "sdevkit/loop.h"

/** The joypads as sdevkit_input_update saw them. */
typedef struct {
    uint16_t pad[SDEVKIT_PADS];        /**< The buttons held (SDEVKIT_KEY_*). */
    uint16_t pressed[SDEVKIT_PADS];    /**< The buttons held now and not at the call before. */
} sdevkit_input_state;

/** The joypads of this tick, after sdevkit_input_update. */
extern sdevkit_input_state sdevkit_input;

/**
 * @brief Takes game_tick's pad: the buttons held, and the ones pressed since the last call.
 * @param pad game_tick's parameter
 */
void sdevkit_input_update(const uint16_t pad[SDEVKIT_PADS]);

#endif /* SDEVKIT_INPUT_H */
