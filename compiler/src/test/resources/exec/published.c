/* The data the tick publishes for interrupt code (sdevkit/publish.h): the tick changes a struct
 * with a palette, a 16-bit value whose two bytes change together and a checksum of all of it,
 * every tick, then game_vblank, while an IRQ and the NMI read the published copy
 * (SDEVKIT_PUBLISHED) and check it is one tick's data, complete (the checksum fits, the two bytes
 * are equal), that the tick's number never goes back, and that during a tick it is the data of
 * the tick whose game_vblank came last (ADR-6FRDS030), never the one being filled. The ticks run
 * one after the other without waiting, so the interrupts come at every point of a tick, of the
 * copy after it and of game_vblank's switch; the IRQ's line moves on each time, and the NMI also
 * comes while the IRQ handler runs. Both count the times they came during a tick, which must
 * happen often, and outside of it (game_vblank does some work for that). If game_tick filled
 * the copy interrupt code reads, they would find it half done, and the test fails.
 * The PC calls the handlers itself, during a tick (with the phase set by hand) and after
 * game_vblank; what both compare is whether each case happened, the errors (none) and the data
 * after the last tick. */
#include <stdint.h>
#include <dampf.h>
#include "sdevkit/publish.h"

#define TICKS 300
#define COLORS 64
#define LINES 3
#define MIN_IRQS 40
#define MIN_NMIS 15
#define MIN_REST 3

#define REG8(a) (*(volatile uint8_t *)(a))

struct frame {
    uint16_t seq;               /* the tick's number */
    uint16_t twin;              /* its low byte, twice: both bytes always equal */
    uint16_t colors[COLORS];    /* a palette */
    uint16_t sum;               /* of all of the above */
};

uint16_t irq_count, nmi_count, irq_in_tick, nmi_in_tick, irq_last, nmi_last;
uint16_t irq_torn, nmi_torn, irq_back, nmi_back, irq_wrong, nmi_wrong;
uint8_t irq_line;
uint8_t irq_saw_tick, irq_saw_rest, nmi_saw_tick, nmi_saw_rest;
uint8_t phase;                  /* the main code: 1 in game_tick, else 0 */
uint16_t vblank_sum;            /* game_vblank's work, so that interrupts come there too */
uint16_t shown;                 /* the number of the tick whose game_vblank came last */

#ifdef __DAMPF__
static const uint8_t lines[LINES] = { 40, 120, 190 };
#endif

SDEVKIT_PUBLISH(struct frame, frame, tick, vblank)

/* ---- the tick: an ordinary variable, written in an order that is inconsistent for a long time */

static void tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t s = (uint16_t)(frame.seq + 1u + pad[0]);
    uint16_t sum = s;
    uint16_t i;
    frame.seq = s;
    for (i = 0; i < COLORS; i++) {
        uint16_t c = (uint16_t)((uint16_t)(s * 3u + i * 257u) & 0x7FFFu);
        frame.colors[i] = c;
        sum = (uint16_t)(sum + c);
    }
    frame.twin = (uint16_t)((s & 0xFFu) * 0x0101u);
    frame.sum = (uint16_t)(sum ^ frame.twin);
}

static void vblank(void)
{
    uint16_t sum = 0;
    uint8_t i;
    for (i = 0; i < COLORS; i++) {
        sum = (uint16_t)(sum + frame.colors[i]);
    }
    vblank_sum = sum;
}

/* The check, which both handlers call: dampf compiles a copy for each (ADR-6FQ4NSHE). */
static uint8_t consistent(const struct frame *p)
{
    uint16_t sum = p->seq;
    uint16_t i;
    for (i = 0; i < COLORS; i++) {
        sum = (uint16_t)(sum + p->colors[i]);
    }
    return (uint8_t)((uint16_t)(sum ^ p->twin) == p->sum
                     && (uint8_t)p->twin == (uint8_t)(p->twin >> 8)
                     && (uint8_t)p->twin == (uint8_t)p->seq);
}

/* What both handlers check: one tick's data, complete, never older than the last they saw, and
 * during a tick the data of the tick whose game_vblank came last. Returns whether it came during
 * a tick. */
static uint8_t look(const struct frame *p, uint16_t *last, uint16_t *torn, uint16_t *back, uint16_t *wrong)
{
    uint8_t in_tick = *(volatile uint8_t *)&phase;
    if (!consistent(p)) {
        (*torn)++;
    }
    if (p->seq < *last) {
        (*back)++;
    }
    if (in_tick && p->seq != *(volatile uint16_t *)&shown) {
        (*wrong)++;
    }
    *last = p->seq;
    return in_tick;
}

DAMPF_INTERRUPT(irq) void on_irq(void)
{
#ifdef __DAMPF__
    irq_line = (uint8_t)(irq_line + 1 == LINES ? 0 : irq_line + 1);
    REG8(0x4209) = lines[irq_line];
#endif
    if (look(SDEVKIT_PUBLISHED(frame), &irq_last, &irq_torn, &irq_back, &irq_wrong)) {
        irq_in_tick++;
    }
    irq_count++;
}

DAMPF_INTERRUPT(nmi) void on_nmi(void)
{
    if (look(SDEVKIT_PUBLISHED(frame), &nmi_last, &nmi_torn, &nmi_back, &nmi_wrong)) {
        nmi_in_tick++;
    }
    nmi_count++;
}

static uint16_t peek16(const uint16_t *p)
{
    return *(const volatile uint16_t *)p;
}

void test_main(void)
{
    uint16_t pad[SDEVKIT_PADS];
    uint16_t t;
    pad[0] = 0;
    pad[1] = 0;
#ifdef __DAMPF__
    REG8(0x4209) = lines[0];
    REG8(0x420A) = 0;
    REG8(0x4200) = 0xA0;   /* the NMI, and the IRQ at line VTIME */
#endif
    for (t = 0; t < TICKS; t++) {
        *(volatile uint8_t *)&phase = 1;
        game_tick(pad);
#ifndef __DAMPF__
        if (t % 2 == 1) {       /* the PC: the handlers as if they came during the tick */
            on_irq();
            on_nmi();
        }
#endif
        *(volatile uint8_t *)&phase = 0;
        game_vblank();
        *(volatile uint16_t *)&shown = frame.seq;
#ifndef __DAMPF__
        if (t % 4 == 2) {       /* and after game_vblank */
            on_irq();
            on_nmi();
        }
#endif
    }
#ifdef __DAMPF__
    REG8(0x4200) = 0;
#endif
    irq_saw_tick = (uint8_t)(peek16(&irq_in_tick) >= MIN_IRQS);
    nmi_saw_tick = (uint8_t)(peek16(&nmi_in_tick) >= MIN_NMIS);
    irq_saw_rest = (uint8_t)(peek16(&irq_count) - peek16(&irq_in_tick) >= MIN_REST);
    nmi_saw_rest = (uint8_t)(peek16(&nmi_count) - peek16(&nmi_in_tick) >= MIN_REST);
    irq_count = 0;
    nmi_count = 0;
    irq_in_tick = 0;
    nmi_in_tick = 0;
    irq_last = 0;
    nmi_last = 0;
    irq_line = 0;
}
