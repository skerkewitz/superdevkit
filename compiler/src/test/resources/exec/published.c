/* The data the tick publishes for interrupt code (sdevkit/publish.h): the tick changes a struct
 * with a palette, a 16-bit value whose two bytes change together and a checksum of all of it,
 * every tick, while an IRQ and the NMI read the published copy (SDEVKIT_PUBLISHED) and check it
 * is one tick's data, complete: the checksum fits, the two bytes are equal, and the tick's number
 * never goes back. The ticks run one after the other without waiting, so the interrupts come at
 * every point of a tick and of the copy after it; the IRQ's line moves on each time, and the NMI
 * also comes while the IRQ handler runs. Both count the times they came while the kit copied
 * (sdevkit_publishing set), which must happen. Without the flag's first write in SDEVKIT_PUBLISH
 * each of those finds the copy half done, and the test fails (ADR-6FPV1EQV).
 * The PC calls the handlers itself, after some ticks, and with sdevkit_publishing set by hand in
 * place of the copy; what both compare is whether each case happened, the errors (none) and the
 * data after the last tick. */
#include <stdint.h>
#include <dampf.h>
#include "sdevkit/publish.h"

#define TICKS 300
#define COLORS 64
#define LINES 3
#define MIN_IRQS 40
#define MIN_NMIS 15
#define MIN_IN_COPY 5

#define REG8(a) (*(volatile uint8_t *)(a))

struct frame {
    uint16_t seq;               /* the tick's number */
    uint16_t twin;              /* its low byte, twice: both bytes always equal */
    uint16_t colors[COLORS];    /* a palette */
    uint16_t sum;               /* of all of the above */
};

uint16_t irq_count, nmi_count, irq_in_copy, nmi_in_copy, irq_last, nmi_last;
uint16_t irq_torn, nmi_torn, irq_back, nmi_back;
uint8_t irq_line;
uint8_t irq_saw_copy, irq_saw_tick, nmi_saw_copy, nmi_saw_tick;

#ifdef __DAMPF__
static const uint8_t lines[LINES] = { 40, 120, 190 };
#endif

SDEVKIT_PUBLISH(struct frame, frame, tick)

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

DAMPF_INTERRUPT(irq) void on_irq(void)
{
    const struct frame *p;
#ifdef __DAMPF__
    irq_line = (uint8_t)(irq_line + 1 == LINES ? 0 : irq_line + 1);
    REG8(0x4209) = lines[irq_line];
#endif
    if (*(volatile uint8_t *)&sdevkit_publishing) {
        irq_in_copy++;
    }
    p = SDEVKIT_PUBLISHED(frame);
    if (!consistent(p)) {
        irq_torn++;
    }
    if (p->seq < irq_last) {
        irq_back++;
    }
    irq_last = p->seq;
    irq_count++;
}

DAMPF_INTERRUPT(nmi) void on_nmi(void)
{
    const struct frame *p;
    if (*(volatile uint8_t *)&sdevkit_publishing) {
        nmi_in_copy++;
    }
    p = SDEVKIT_PUBLISHED(frame);
    if (!consistent(p)) {
        nmi_torn++;
    }
    if (p->seq < nmi_last) {
        nmi_back++;
    }
    nmi_last = p->seq;
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
        game_tick(pad);
#ifndef __DAMPF__
        /* the PC: the handlers after a tick, and as if they came while the kit copies */
        if (t % 4 == 1) {
            on_irq();
            on_nmi();
        } else if (t % 4 == 3) {
            sdevkit_publishing = 1;
            on_irq();
            on_nmi();
            sdevkit_publishing = 0;
        }
#endif
    }
#ifdef __DAMPF__
    REG8(0x4200) = 0;
#endif
    irq_saw_copy = (uint8_t)(peek16(&irq_in_copy) >= MIN_IN_COPY);
    nmi_saw_copy = (uint8_t)(peek16(&nmi_in_copy) >= MIN_IN_COPY);
    irq_saw_tick = (uint8_t)(peek16(&irq_count) - peek16(&irq_in_copy) >= MIN_IRQS);
    nmi_saw_tick = (uint8_t)(peek16(&nmi_count) - peek16(&nmi_in_copy) >= MIN_NMIS);
    irq_count = 0;
    nmi_count = 0;
    irq_in_copy = 0;
    nmi_in_copy = 0;
    irq_last = 0;
    nmi_last = 0;
    irq_line = 0;
}
