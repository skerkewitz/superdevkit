/* The data the tick publishes for interrupt code (sdevkit/publish.h) on the kit's frame rhythm.
 *
 * Every tick changes a struct with a palette, a 16-bit value whose two bytes change together and
 * a checksum of all of it, and names for the tick's checksum (sdevkit_dbg_hash) the published
 * copy as it finds it (the tick before's, which the vertical blank made the current one), its own
 * struct after it changed it, and what the interrupts found. On the SNES the kit's NMI runs the
 * game's vblank, which checks the published copy, and an IRQ checks it too; both count the times
 * it was not one tick's data, complete. The desktop and the web have no interrupts: game_vblank
 * runs vblank there, which finds nothing, and the IRQ runs never. The kit's loop starts the tick
 * right after the NMI's switch, so the IRQ, which comes one line later each frame, from line 226
 * to 261 and again, falls on every point of the NMI, of the tick and of the copy after it. So the
 * checksums are the same on all three only if the SNES's interrupts never found torn data and
 * every platform published the same data at the same point.
 *
 * Test code, not game code: its IRQ comes in the vertical blank, where sdevkit_irq_line puts none,
 * so it writes the timer's registers itself, for dampf only, every vertical blank after the kit
 * has switched the NMI on. */
#include <stdint.h>
#include <dampf.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "sdevkit/publish.h"

#define COLORS 64
#define FIRST_LINE 226
#define LAST_LINE 261

struct frame {
    uint16_t seq;               /* the tick's number */
    uint16_t buttons;           /* pad 1 */
    uint16_t twin;              /* the low byte of seq + buttons, twice: both bytes always equal */
    uint16_t colors[COLORS];    /* a palette */
    uint16_t sum;               /* of all of the above */
};

SDEVKIT_PUBLISH(struct frame, frame, tick, vblank)

uint16_t stale;                 /* ticks that found a published copy other than their last data */
uint16_t irq_torn, nmi_torn;    /* the interrupts' finds: published data not one tick's, complete */

#ifdef __DAMPF__
#define REG8(a) (*(volatile uint8_t *)(a))
static uint16_t irq_line = FIRST_LINE;
#endif

void game_init(uint16_t seed)
{
    (void)seed;
#ifdef __DAMPF__
    REG8(0x4209) = (uint8_t)FIRST_LINE;
    REG8(0x420A) = 0;
#endif
}

static void tick(const uint16_t pad[SDEVKIT_PADS])
{
    const struct frame *last = SDEVKIT_PUBLISHED(frame);
    uint16_t s, sum, i;
    if (last->seq != frame.seq || last->sum != frame.sum) {
        stale++;
    }
    sdevkit_dbg_hash(last, sizeof *last);
    s = (uint16_t)(frame.seq + 1u);
    sum = (uint16_t)(s + pad[0]);
    frame.seq = s;
    frame.buttons = pad[0];
    for (i = 0; i < COLORS; i++) {
        uint16_t c = (uint16_t)((uint16_t)(s * 3u + i * 257u + pad[0]) & 0x7FFFu);
        frame.colors[i] = c;
        sum = (uint16_t)(sum + c);
    }
    frame.twin = (uint16_t)((uint8_t)(s + pad[0]) * 0x0101u);
    frame.sum = (uint16_t)(sum ^ frame.twin);
    sdevkit_dbg_hash(&frame, sizeof frame);
    sdevkit_dbg_hash(&stale, sizeof stale);
    sdevkit_dbg_hash(&irq_torn, sizeof irq_torn);
    sdevkit_dbg_hash(&nmi_torn, sizeof nmi_torn);
}

/* The check, which both handlers call: dampf compiles a copy for each (ADR-6FQ4NSHE). */
static uint8_t consistent(const struct frame *p)
{
    uint16_t sum = (uint16_t)(p->seq + p->buttons);
    uint16_t i;
    for (i = 0; i < COLORS; i++) {
        sum = (uint16_t)(sum + p->colors[i]);
    }
    return (uint8_t)((uint16_t)(sum ^ p->twin) == p->sum
                     && (uint8_t)p->twin == (uint8_t)(p->twin >> 8)
                     && (uint8_t)p->twin == (uint8_t)(p->seq + p->buttons));
}

DAMPF_INTERRUPT(irq) void on_irq(void)
{
#ifdef __DAMPF__
    irq_line = (uint16_t)(irq_line == LAST_LINE ? FIRST_LINE : irq_line + 1);
    REG8(0x4209) = (uint8_t)irq_line;
    REG8(0x420A) = (uint8_t)(irq_line >> 8);
#endif
    if (!consistent(SDEVKIT_PUBLISHED(frame))) {
        irq_torn++;
    }
}

/* In the kit's NMI on the SNES, after its switch; in game_vblank on the PC. */
static void vblank(void)
{
#ifdef __DAMPF__
    REG8(0x4200) = 0xA1;   /* the NMI, the IRQ at line VTIME, and the joypads' automatic read */
#endif
    if (!consistent(SDEVKIT_PUBLISHED(frame))) {
        nmi_torn++;
    }
}
