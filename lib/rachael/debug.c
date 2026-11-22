/*
 * The debug registrations on Rachael (ADR-6F3CK03Z): what the game registered for the desktop's
 * debug view (lib/desktop/view.c), and the boxes of the current tick. The frontend clears the
 * registrations at power on (sdevkit__dbg_reset, before game_init) and the boxes before each
 * game_tick.
 */
#include "rachael.h"

sdevkit__dbg_entry sdevkit__dbg_entries[SDEVKIT__DBG_MAX];
int sdevkit__dbg_count;
sdevkit__dbg_rect sdevkit__dbg_boxes[SDEVKIT__DBG_BOXES];
int sdevkit__dbg_nboxes;

/* A pointer registered again replaces its entry (a game_init that runs twice registers twice). */
static void add(uint8_t kind, const char *name, void *p, int16_t lo, int16_t hi)
{
    int i;

    if (name == 0 || p == 0) {
        return;
    }
    for (i = 0; i < sdevkit__dbg_count; i++) {
        if (sdevkit__dbg_entries[i].p == p) {
            break;
        }
    }
    if (i == sdevkit__dbg_count) {
        if (sdevkit__dbg_count == SDEVKIT__DBG_MAX) {
            return;
        }
        sdevkit__dbg_count++;
    }
    sdevkit__dbg_entries[i].kind = kind;
    sdevkit__dbg_entries[i].name = name;
    sdevkit__dbg_entries[i].p = p;
    sdevkit__dbg_entries[i].lo = lo;
    sdevkit__dbg_entries[i].hi = hi;
}

void sdevkit_dbg_toggle(const char *name, uint8_t *flag)
{
    add(SDEVKIT__DBG_TOGGLE, name, flag, 0, 1);
}

void sdevkit_dbg_range(const char *name, int16_t *value, int16_t lo, int16_t hi)
{
    if (lo > hi) {
        int16_t t = lo;
        lo = hi;
        hi = t;
    }
    add(SDEVKIT__DBG_RANGE, name, value, lo, hi);
}

void sdevkit_dbg_color(const char *name, uint16_t *colour)
{
    add(SDEVKIT__DBG_COLOR, name, colour, 0, 0x7FFF);
}

void sdevkit_dbg_action(const char *name, uint8_t *pressed)
{
    add(SDEVKIT__DBG_ACTION, name, pressed, 0, 1);
}

void sdevkit_dbg_text(const char *name, const char *text)
{
    add(SDEVKIT__DBG_TEXT, name, (void *)(uintptr_t)text, 0, 0);
}

void sdevkit_dbg_value(const char *name, const uint16_t *value)
{
    add(SDEVKIT__DBG_VALUE, name, (void *)(uintptr_t)value, 0, 0);
}

/* The next of the tick's shapes, or null when the list is full. */
static sdevkit__dbg_rect *shape(int16_t x, int16_t y, uint8_t kind, uint8_t style)
{
    sdevkit__dbg_rect *b;

    if (sdevkit__dbg_nboxes == SDEVKIT__DBG_BOXES) {
        return 0;
    }
    b = &sdevkit__dbg_boxes[sdevkit__dbg_nboxes++];
    b->x = x;
    b->y = y;
    b->w = 0;
    b->h = 0;
    b->dx = 0;
    b->dy = 0;
    b->kind = (uint8_t)(kind & 7);
    b->style = style;
    return b;
}

void sdevkit_dbg_box(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    sdevkit__dbg_rect *b = shape(x, y, kind, SDEVKIT__DBG_OUTLINE);

    if (b != 0) {
        b->w = w;
        b->h = h;
    }
}

void sdevkit_dbg_fill(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    sdevkit__dbg_rect *b = shape(x, y, kind, SDEVKIT__DBG_FILLED);

    if (b != 0) {
        b->w = w;
        b->h = h;
    }
}

void sdevkit_dbg_arrow(int16_t x, int16_t y, int8_t dx, int8_t dy, uint8_t kind)
{
    sdevkit__dbg_rect *b = shape(x, y, kind, SDEVKIT__DBG_ARROW);

    if (b != 0) {
        b->dx = dx;
        b->dy = dy;
    }
}

void sdevkit__dbg_reset(void)
{
    sdevkit__dbg_count = 0;
    sdevkit__dbg_nboxes = 0;
}
