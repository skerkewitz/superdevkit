#ifndef SDEVKIT__DEBUG_VIEW_H
#define SDEVKIT__DEBUG_VIEW_H

/* The desktop's debug view (view.c, ADR-6F3CK03Z): a panel beside the picture, F1 opens it. */
#include "raylib.h"

#define SDEVKIT__PANEL_W 340   /* the panel's width in window pixels; the picture keeps the rest */

/* What the frontend runs, which the panel shows and changes. */
typedef struct {
    int  paused, step, slow, fast;   /* step: one tick wanted while paused; slow: a tick every 4 frames */
    long tick;
    unsigned lost;                   /* bytes lost so far */
} sdevkit__run_state;

int  sdevkit__debug_is_open(void);
void sdevkit__debug_set_open(int open, int tab);    /* tab: 0 game, 1 palettes, 2 tiles, 3 sprites */
void sdevkit__debug_keys(void);                     /* F1 opens and closes it */
/* Draws the boxes over the picture (at dst, scale window pixels per SNES pixel) and the panel at
 * the window's right edge, and handles the mouse. Returns the name of a registration the panel
 * changed in this frame, or null. Inside BeginDrawing / EndDrawing. */
const char *sdevkit__debug_draw(Rectangle dst, float scale, float window_w, float window_h, sdevkit__run_state *run);
void sdevkit__debug_unload(void);

#endif
