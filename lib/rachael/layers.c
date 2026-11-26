/*
 * The optional layers of the console API on Rachael (sdevkit/easy.h, ADR-6FNPPCSX): their logic
 * is ../layers/layers.h, the same as the SNES's; here the queue's commit through the core, the
 * check that finds a register group changed without its mark (sdevkit__rachael.unmarked), and the
 * layers' state at power on.
 */
#include "rachael.h"
#include "sdevkit/easy.h"

#include <stddef.h>
#include <string.h>

static const void *queue_src[SDEVKIT_QUEUE_ENTRIES];

static void queue_source(uint8_t n, const void *src)
{
    queue_src[n] = src;
}

static void shadow_check(uint8_t marks);

#include "../layers/layers.h"

void sdevkit_queue_commit(void)
{
    uint8_t k;

    for (k = 0; k < queue.n; k++) {
        uint16_t d = queue.dest[k];
        if (queue.port[k] == SDEVKIT_PPU_CGDATA) {
            sdevkit__write_reg(SDEVKIT_PPU_CGADD, (uint8_t)d);
        } else {
            sdevkit__write_reg(SDEVKIT_PPU_VMAIN, queue.vmain[k]);
            sdevkit__write_reg(SDEVKIT_PPU_VMADDL, (uint8_t)d);
            sdevkit__write_reg(SDEVKIT_PPU_VMADDH, (uint8_t)(d >> 8));
        }
        sdevkit_dma(queue.dmap[k], queue.port[k], queue_src[k], queue.bytes[k]);
    }
    queue_done();
}

uint8_t sdevkit__queue_refused(void)
{
    return queue.refused;
}

/* --- The marks ----------------------------------------------------------------------------------- */
/* What the last commit of each group wrote: a group that differs from it without its mark was
 * changed and would not reach the SNES. */
static sdevkit_ppu_regs committed;
static sdevkit_hdma_channel committed_hdma[8];

/* The groups of sdevkit_ppu: where each lies (shadow.h keeps a group together, colour math in two
 * pieces). */
static const struct {
    uint8_t mark;
    size_t  from, to;
} groups[] = {
    { SDEVKIT_MARK_SCROLL, offsetof(sdevkit_ppu_regs, hofs), offsetof(sdevkit_ppu_regs, coldata) },
    { SDEVKIT_MARK_COLOR, offsetof(sdevkit_ppu_regs, coldata), offsetof(sdevkit_ppu_regs, m7a) },
    { SDEVKIT_MARK_MODE7, offsetof(sdevkit_ppu_regs, m7a), offsetof(sdevkit_ppu_regs, cgwsel) },
    { SDEVKIT_MARK_COLOR, offsetof(sdevkit_ppu_regs, cgwsel), offsetof(sdevkit_ppu_regs, bgmode) },
    { SDEVKIT_MARK_SETUP, offsetof(sdevkit_ppu_regs, bgmode), offsetof(sdevkit_ppu_regs, w12sel) },
    { SDEVKIT_MARK_WINDOW, offsetof(sdevkit_ppu_regs, w12sel), offsetof(sdevkit_ppu_regs, hdmaen) },
    { SDEVKIT_MARK_HDMA, offsetof(sdevkit_ppu_regs, hdmaen), offsetof(sdevkit_ppu_regs, inidisp) },
};

static uint8_t hdma_differs(void)
{
    unsigned ch;

    for (ch = 1; ch < 8; ch++) {
        const sdevkit_hdma_channel *a = &sdevkit_hdma[ch], *b = &committed_hdma[ch];
        if (a->table != b->table || a->dmap != b->dmap || a->bbad != b->bbad) {
            return 1;
        }
    }
    return 0;
}

/* Before a commit: a marked group is taken as committed; one changed without its mark is counted
 * once, and then taken as compared, though the SNES would not have committed it. */
static void shadow_check(uint8_t marks)
{
    const uint8_t *now = (const uint8_t *)&sdevkit_ppu;
    uint8_t *then = (uint8_t *)&committed;
    uint8_t found = 0;
    unsigned g;

    for (g = 0; g < sizeof groups / sizeof groups[0]; g++) {
        size_t n = groups[g].to - groups[g].from;
        if (!(marks & groups[g].mark) && memcmp(now + groups[g].from, then + groups[g].from, n) != 0) {
            found = (uint8_t)(found | groups[g].mark);
        }
        memcpy(then + groups[g].from, now + groups[g].from, n);
    }
    if (!(marks & SDEVKIT_MARK_HDMA) && hdma_differs()) {
        found = (uint8_t)(found | SDEVKIT_MARK_HDMA);
    }
    memcpy(committed_hdma, sdevkit_hdma, sizeof committed_hdma);
    for (g = 1; g <= SDEVKIT_MARK_ALL; g <<= 1) {
        if (found & g) {
            if (sdevkit__rachael.unmarked == 0) {
                sdevkit__rachael.unmarked_group = (uint8_t)g;
            }
            sdevkit__rachael.unmarked++;
        }
    }
}

/* --- Power on ------------------------------------------------------------------------------------ */
void sdevkit__layers_reset(void)
{
    static const sdevkit_ppu_regs start = {
        { 0, 0, 0, 0 }, { 0, 0, 0, 0 }, 0, 0x100, 0, 0, 0x100, 0, 0, 0, 0, 0, SDEVKIT_BG_MODE1, 0, 0,
        { 0, 0, 0, 0 }, { 0, 0 }, 0, 0, 0, 0, 0, 0, { 0, 0, 0, 0 }, 0, 0, 0, 0, 0, SDEVKIT_FORCED_BLANK
    };

    sdevkit_ppu = start;
    committed = start;
    memset(sdevkit_hdma, 0, sizeof sdevkit_hdma);
    memset(committed_hdma, 0, sizeof committed_hdma);
    sdevkit_marks = SDEVKIT_MARK_ALL;
    memset(sdevkit_oam, 0, sizeof sdevkit_oam);
    sprites_n = 0;
    sprites_last = 0;
    memset(&queue, 0, sizeof queue);
    memset(sdevkit_input.pad, 0, sizeof sdevkit_input.pad);
    memset(sdevkit_input.pressed, 0, sizeof sdevkit_input.pressed);
}
