/* The data the tick publishes for interrupt code, and the game's vertical blank in the kit's NMI
 * (sdevkit/publish.h): the tick changes a struct with a palette, a 16-bit value whose two bytes
 * change together and a checksum of all of it, every tick, then game_vblank, while an IRQ and the
 * NMI read the published copy (SDEVKIT_PUBLISHED). The NMI is the one SDEVKIT_PUBLISH defines: it
 * switches to a ready copy and runs vblank, which checks the copy as the IRQ does: one tick's
 * data, complete (the checksum fits, the two bytes are equal), and the tick's number never going
 * back. The IRQ also checks that it reads the copy the NMI switched to last (ADR-6FRDS030,
 * ADR-6FS0T4WB), never the one being filled. game_vblank waits for the NMI's switch, so the next
 * tick begins after it, and every fourth tick runs longer than a frame, so the NMI comes in the
 * middle of ticks and of the copy after them (with no copy ready, it switches nothing and runs
 * vblank again), and the IRQ, whose line moves on each time, at every point of a tick, of the copy
 * and of the wait. Both count the times they came during a tick, which must happen often, and
 * outside of it. If game_tick filled the copy interrupt code reads, or the NMI switched to a copy
 * being filled, they would find it half done, and the test fails.
 * The PC has no interrupts: its game_vblank switches and runs vblank itself, and the test calls
 * the IRQ and vblank during a tick (from the tick, where the NMI would find no copy ready and only
 * run vblank) and after game_vblank. What both compare is whether each case happened, so an NMI
 * that skipped vblank during a tick fails it, the errors (none) and the data after the last tick. */
#include <stdint.h>
#include <dampf.h>
#include "sdevkit/publish.h"

#define TICKS 120
#define COLORS 64
#define LINES 3
#define LONG_ROUNDS 3000        /* every fourth tick: more than a frame on the SNES */
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
uint16_t irq_torn, nmi_torn, irq_back, nmi_back, irq_wrong;
uint8_t irq_line;
uint8_t irq_saw_tick, irq_saw_rest, nmi_saw_tick, nmi_saw_rest;
uint8_t phase;                  /* the main code: 1 in game_tick, else 0 */
uint16_t shown;                 /* the NMI's: the number of the tick it switched to last */
uint16_t spin;                  /* the long ticks' work */
uint16_t pc_tick;               /* the PC: the tick's number, for when it calls the handlers */

#ifdef __DAMPF__
static const uint8_t lines[LINES] = { 40, 120, 190 };
#endif

/* The kit switches the NMI on (lib/snes/irq.c); here test_main does, with the IRQ. */
void sdevkit_publish_nmi_on(void)
{
}

SDEVKIT_PUBLISH(struct frame, frame, tick, vblank)

/* The check, which the IRQ and the NMI call: dampf compiles a copy for each (ADR-6FQ4NSHE). */
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

/* What both check: one tick's data, complete, never older than the last they saw. Returns
 * whether it came during a tick. */
static uint8_t look(const struct frame *p, uint16_t *last, uint16_t *torn, uint16_t *back)
{
    uint8_t in_tick = *(volatile uint8_t *)&phase;
    if (!consistent(p)) {
        (*torn)++;
    }
    if (p->seq < *last) {
        (*back)++;
    }
    *last = p->seq;
    return in_tick;
}

DAMPF_INTERRUPT(irq) void on_irq(void)
{
    const struct frame *p = SDEVKIT_PUBLISHED(frame);
#ifdef __DAMPF__
    irq_line = (uint8_t)(irq_line + 1 == LINES ? 0 : irq_line + 1);
    REG8(0x4209) = lines[irq_line];
#endif
    if (look(p, &irq_last, &irq_torn, &irq_back)) {
        irq_in_tick++;
        if (p->seq != *(volatile uint16_t *)&shown) {
            irq_wrong++;
        }
    }
    irq_count++;
}

/* In the kit's NMI on the SNES (in game_vblank on the PC), after the switch. */
static void vblank(void)
{
    const struct frame *p = SDEVKIT_PUBLISHED(frame);
    if (look(p, &nmi_last, &nmi_torn, &nmi_back)) {
        nmi_in_tick++;
    }
    nmi_count++;
    *(volatile uint16_t *)&shown = p->seq;
}

/* ---- the tick: an ordinary variable, written in an order that is inconsistent for a long time */

static void tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t s = (uint16_t)(frame.seq + 1u + pad[0]);
    uint16_t sum = s;
    uint16_t i;
    frame.seq = s;
#ifndef __DAMPF__
    if (pc_tick % 2 == 1) {     /* the PC: the handlers as if they came during the tick */
        on_irq();
        vblank();               /* what the NMI runs, with no copy ready */
    }
#endif
    for (i = 0; i < COLORS; i++) {
        uint16_t c = (uint16_t)((uint16_t)(s * 3u + i * 257u) & 0x7FFFu);
        frame.colors[i] = c;
        sum = (uint16_t)(sum + c);
    }
    if ((s & 3u) == 0) {
        for (i = 0; i < LONG_ROUNDS; i++) {
            spin = (uint16_t)(spin + (uint16_t)(spin << 2) + i + s);
        }
    }
    frame.twin = (uint16_t)((s & 0xFFu) * 0x0101u);
    frame.sum = (uint16_t)(sum ^ frame.twin);
}

static uint16_t peek16(const uint16_t *p)
{
    return *(const volatile uint16_t *)p;
}

void test_main(void)
{
    uint16_t pad[SDEVKIT_PADS];
    pad[0] = 0;
    pad[1] = 0;
#ifdef __DAMPF__
    REG8(0x4209) = lines[0];
    REG8(0x420A) = 0;
    REG8(0x4200) = 0xA0;   /* the NMI, and the IRQ at line VTIME */
#endif
    for (pc_tick = 0; pc_tick < TICKS; pc_tick++) {
        *(volatile uint8_t *)&phase = 1;
        game_tick(pad);
        *(volatile uint8_t *)&phase = 0;
        game_vblank();
#ifndef __DAMPF__
        if (pc_tick % 4 == 2) {   /* and after game_vblank */
            on_irq();
            vblank();
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
