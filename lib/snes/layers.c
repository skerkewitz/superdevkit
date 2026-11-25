/*
 * The optional layers of the console API on the SNES (sdevkit/easy.h, ADR-6FNPPCSX): their logic
 * is ../layers/layers.h, the same as Rachael's; here what the SNES does its own way. A game that
 * uses none of them pays for this file only in ROM and RAM.
 */
#include <dampf.h>
#include "sdevkit/easy.h"
#include "hw.h"

/* The queued sources as channel 0 takes them, split when they are queued (in the tick), so the
 * vertical blank does not pay for it. */
static uint16_t queue_a1t[SDEVKIT_QUEUE_ENTRIES];
static uint8_t queue_a1b[SDEVKIT_QUEUE_ENTRIES];

static void queue_source(uint8_t n, const void *src)
{
    uint32_t a = (uint32_t)src;
    queue_a1t[n] = (uint16_t)a;
    queue_a1b[n] = ((const uint8_t *)&a)[2];
}

/* Rachael's check of the marks; nothing here, and dampf removes the call. */
static void shadow_check(uint8_t marks)
{
    (void)marks;
}

#include "../layers/layers.h"

/* The queue, in order, on channel 0: it fits by its count (layers.h). The registers are written
 * here, not through the core's copies: the vertical blank pays for every cycle of the setup. */
void sdevkit_queue_commit(void)
{
    uint8_t k;
    uint8_t n = queue.n;

    DAMPF_RANGE(n, 0, SDEVKIT_QUEUE_ENTRIES);
    for (k = 0; k < n; k++) {
        uint8_t port = queue.port[k];
        if (port == 0x22) {
            CGADD = (uint8_t)queue.dest[k];
        } else {
            VMAIN = queue.vmain[k];
            VMADD = queue.dest[k];
        }
        DMAP(0) = queue.dmap[k];
        BBAD(0) = port;
        A1T(0) = queue_a1t[k];
        A1B(0) = queue_a1b[k];
        DAS(0) = queue.bytes[k];
        MDMAEN = 1;
    }
    queue_done();
}
