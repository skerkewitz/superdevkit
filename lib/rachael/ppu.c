/*
 * Rachael, the SNES rebuilt in portable C for the desktop and the web (lib/README.md): the memories,
 * the picture registers, and the console API (sdevkit/snes.h): the commit of the shadow state into
 * them at the vertical blank, and the writes now. render.c draws the picture from this state;
 * rachael.h is what the two share. The shadow state's logic, the same as lib/snes's, is
 * lib/shadow/shadow.h. Taken over from Blubber Blasen's src/snes.
 *
 * Portable C99: no raylib, no heap, no floating point. Everything is static, like
 * the console's memory.
 */

#include "rachael.h"

#include <stddef.h>
#include <string.h>

#define SDEVKIT__QUEUE_SOURCES
#include "../shadow/shadow.h"

sdevkit__rachael_state sdevkit__rachael;

/* The registers and channels as the last commit wrote them: a group the tick changed without its
 * mark differs from them (sdevkit__rachael.unmarked). */
static sdevkit_ppu_regs committed;
static sdevkit_hdma_channel committed_hdma[8];

/* --- Registers ----------------------------------------------------------------------- */
uint8_t sdevkit__undrawn(uint8_t reg, uint8_t value)
{
    switch (reg) {
    case SDEVKIT_PPU_BGMODE:
        return (uint8_t)((value & 7) != 1 && (value & 7) != 7);   /* modes 1 and 7 are drawn */
    case SDEVKIT_PPU_CGWSEL:
        return (uint8_t)(value & 1);                  /* direct colour */
    case SDEVKIT_PPU_MOSAIC:                           /* a size and a layer */
        return (uint8_t)((value & 0xF0) != 0 && (value & 0x0F) != 0);
    case SDEVKIT_PPU_SETINI:
        return (uint8_t)(value != 0);
    case 0x02: case 0x03: case 0x04:                   /* OAMADDL, OAMADDH, OAMDATA */
    case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:   /* VMAIN, VMADDL/H, VMDATAL/H */
    case 0x21: case 0x22:                              /* CGADD, CGDATA */
        return 1;
    default:                                           /* drawn, or without effect in mode 1 */
        return 0;
    }
}

static void count_undrawn(uint8_t reg, uint8_t value)
{
    if (sdevkit__rachael.undrawn == 0) {
        sdevkit__rachael.undrawn_reg = reg;
        sdevkit__rachael.undrawn_value = value;
    }
    sdevkit__rachael.undrawn++;
}

void sdevkit__write_reg(uint8_t reg, uint8_t value)
{
    if (sdevkit__undrawn(reg, value)) {
        count_undrawn(reg, value);
    }
    /* Mode 7's registers, and BG1's scroll as mode 7 reads it, share a latch: each write is the
     * high byte, the byte written before it the low one. */
    if (reg == SDEVKIT_PPU_BG1HOFS || reg == SDEVKIT_PPU_BG1VOFS || (reg >= SDEVKIT_PPU_M7A && reg <= SDEVKIT_PPU_M7Y)) {
        uint16_t v = (uint16_t)(((uint16_t)value << 8) | sdevkit__rachael.m7_latch);
        if (reg == SDEVKIT_PPU_BG1HOFS) {
            sdevkit__rachael.m7hofs = (uint16_t)(v & 0x1FFF);
        } else if (reg == SDEVKIT_PPU_BG1VOFS) {
            sdevkit__rachael.m7vofs = (uint16_t)(v & 0x1FFF);
        } else {
            sdevkit__rachael.m7[reg - SDEVKIT_PPU_M7A] = v;
        }
        sdevkit__rachael.m7_latch = value;
    }
    if (reg >= SDEVKIT_PPU_BG1HOFS && reg <= SDEVKIT_PPU_BG1HOFS + 7) {
        /* BGnHOFS / BGnVOFS: written twice, low byte then high byte, through a
         * shared latch (simplified from the SNES formula; same result for
         * two writes in a row). */
        uint8_t bg = (uint8_t)((reg - SDEVKIT_PPU_BG1HOFS) >> 1);
        uint16_t v = (uint16_t)(((uint16_t)value << 8) | sdevkit__rachael.scroll_latch);
        if ((reg - SDEVKIT_PPU_BG1HOFS) & 1) {
            sdevkit__rachael.vofs[bg] = (uint16_t)(v & 0x3FF);
        } else {
            sdevkit__rachael.hofs[bg] = (uint16_t)(v & 0x3FF);
        }
        sdevkit__rachael.scroll_latch = value;
    } else if (reg == SDEVKIT_PPU_COLDATA) {
        uint8_t i = (uint8_t)(value & 0x1F);
        if (value & 0x20) sdevkit__rachael.fixed_r = i;
        if (value & 0x40) sdevkit__rachael.fixed_g = i;
        if (value & 0x80) sdevkit__rachael.fixed_b = i;
    }
    sdevkit__rachael.regs[reg] = value;
}

uint8_t sdevkit__reg(uint8_t reg)
{
    return sdevkit__rachael.regs[reg];
}

static void write_twice(uint8_t reg, uint16_t v)
{
    sdevkit__write_reg(reg, (uint8_t)v);
    sdevkit__write_reg(reg, (uint8_t)(v >> 8));
}

/* --- Copies into VRAM and CGRAM ------------------------------------------------------------ */
/* One copy as the SNES's DMA and ports make it: CGRAM a colour from two bytes; VRAM by the port
 * (the low byte $2118 or the high one $2119, or both in turn with DMAP's mode 1) and VMAIN (step
 * after the low or the high byte, by 1, 32 or 128 words). What she does not model (VMAIN's
 * address remapping, another DMAP mode, another port) is counted as a write she does not draw. */
static void port_copy(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const uint8_t *src, uint16_t bytes)
{
    static const uint16_t steps[4] = { 1, 32, 128, 128 };
    uint16_t i, a = dest;
    uint8_t mode = (uint8_t)(dmap & 7);

    if (port == 0x22 && mode == 0) {
        uint8_t c = (uint8_t)dest;
        for (i = 0; i + 1 < bytes; i += 2) {
            const uint8_t *s = (dmap & 0x08) ? src : src + i;
            sdevkit__rachael.cgram[c] = (uint16_t)((s[0] | ((dmap & 0x08) ? s[0] : s[1]) << 8) & 0x7FFF);
            c++;
        }
        return;
    }
    if ((port != 0x18 && port != 0x19) || mode > 1 || (vmain & 0x0C) || (dmap & 0x80)) {
        count_undrawn(port, vmain);
        return;
    }
    for (i = 0; i < bytes; i++) {
        uint8_t p = (uint8_t)(port + (mode == 1 ? (i & 1) : 0));
        uint8_t b = (dmap & 0x08) ? src[0] : src[i];
        uint16_t *w = &sdevkit__rachael.vram[a & (SDEVKIT__VRAM_WORDS - 1)];
        if (p == 0x18) {
            *w = (uint16_t)((*w & 0xFF00) | b);
            if (!(vmain & 0x80)) {
                a = (uint16_t)(a + steps[vmain & 3]);
            }
        } else {
            *w = (uint16_t)((*w & 0x00FF) | (b << 8));
            if (vmain & 0x80) {
                a = (uint16_t)(a + steps[vmain & 3]);
            }
        }
    }
}

/* A write now: it arrives only with the screen off, as on the SNES; else its bytes are lost. */
static uint8_t screen_off(void)
{
    return (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] & 0x80) != 0);
}

void sdevkit_dma_now(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    if (!screen_off()) {
        sdevkit__rachael.dma_lost = (uint16_t)(sdevkit__rachael.dma_lost + bytes);
        return;
    }
    port_copy(port, dmap, vmain, dest, (const uint8_t *)src, bytes);
}

void sdevkit_color_now(uint8_t index, uint16_t bgr)
{
    if (!screen_off()) {
        sdevkit__rachael.dma_lost = (uint16_t)(sdevkit__rachael.dma_lost + 2);
        return;
    }
    sdevkit__rachael.cgram[index] = (uint16_t)(bgr & 0x7FFF);
}

void sdevkit_screen_off_now(void)
{
    sdevkit_ppu.inidisp = (uint8_t)(sdevkit_ppu.inidisp | SDEVKIT_FORCED_BLANK);
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
}

/* --- The commit ---------------------------------------------------------------------------- */
/* The groups of sdevkit_ppu: where each lies (snes.h keeps a group together, colour math in two
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

static void unmarked(uint8_t group)
{
    if (sdevkit__rachael.unmarked == 0) {
        sdevkit__rachael.unmarked_group = group;
    }
    sdevkit__rachael.unmarked++;
}

/* A group the tick changed without its mark: the SNES would not commit it. Counted once a change:
 * the change is then taken as compared, though not committed. */
static void check_marks(uint8_t marks)
{
    const uint8_t *now = (const uint8_t *)&sdevkit_ppu;
    uint8_t *then = (uint8_t *)&committed;
    uint8_t found = 0;
    unsigned g, ch;

    for (g = 0; g < sizeof groups / sizeof groups[0]; g++) {
        size_t n = groups[g].to - groups[g].from;
        if (!(marks & groups[g].mark) && memcmp(now + groups[g].from, then + groups[g].from, n) != 0) {
            found = (uint8_t)(found | groups[g].mark);
            memcpy(then + groups[g].from, now + groups[g].from, n);
        }
    }
    if (!(marks & SDEVKIT_MARK_HDMA)) {
        for (ch = 1; ch < 8; ch++) {
            const sdevkit_hdma_channel *a = &sdevkit_hdma[ch], *b = &committed_hdma[ch];
            if (a->table != b->table || a->dmap != b->dmap || a->bbad != b->bbad || a->dasb != b->dasb) {
                found = (uint8_t)(found | SDEVKIT_MARK_HDMA);
            }
        }
        if (found & SDEVKIT_MARK_HDMA) {
            memcpy(committed_hdma, sdevkit_hdma, sizeof committed_hdma);
        }
    }
    for (g = 1; g <= SDEVKIT_MARK_ALL; g <<= 1) {
        if (found & g) {
            unmarked((uint8_t)g);
        }
    }
}

/* The registers of the groups marked, in lib/snes/snes.c's order. INIDISP is the caller's. A
 * group not marked keeps in the PPU what its last marked commit wrote, as on the SNES. */
void sdevkit__commit_registers(uint8_t marks)
{
    uint8_t i;
    uint16_t c = sdevkit_ppu.coldata;

    check_marks(marks);
    if (marks & SDEVKIT_MARK_SCROLL) {
        for (i = 0; i < 4; i++) {
            write_twice((uint8_t)(SDEVKIT_PPU_BG1HOFS + 2 * i), sdevkit_ppu.hofs[i]);
            write_twice((uint8_t)(SDEVKIT_PPU_BG1VOFS + 2 * i), sdevkit_ppu.vofs[i]);
        }
        memcpy(committed.hofs, sdevkit_ppu.hofs, sizeof committed.hofs);
        memcpy(committed.vofs, sdevkit_ppu.vofs, sizeof committed.vofs);
    }
    if (marks & SDEVKIT_MARK_COLOR) {
        sdevkit__write_reg(SDEVKIT_PPU_CGWSEL, sdevkit_ppu.cgwsel);
        sdevkit__write_reg(SDEVKIT_PPU_CGADSUB, sdevkit_ppu.cgadsub);
        sdevkit__write_reg(SDEVKIT_PPU_COLDATA, (uint8_t)(0x20 | (c & 0x1F)));
        sdevkit__write_reg(SDEVKIT_PPU_COLDATA, (uint8_t)(0x40 | ((c >> 5) & 0x1F)));
        sdevkit__write_reg(SDEVKIT_PPU_COLDATA, (uint8_t)(0x80 | ((c >> 10) & 0x1F)));
        committed.coldata = sdevkit_ppu.coldata;
        committed.cgwsel = sdevkit_ppu.cgwsel;
        committed.cgadsub = sdevkit_ppu.cgadsub;
    }
    if (marks & SDEVKIT_MARK_SETUP) {
        sdevkit__write_reg(SDEVKIT_PPU_OBSEL, sdevkit_ppu.obsel);
        sdevkit__write_reg(SDEVKIT_PPU_BGMODE, sdevkit_ppu.bgmode);
        sdevkit__write_reg(SDEVKIT_PPU_MOSAIC, sdevkit_ppu.mosaic);
        for (i = 0; i < 4; i++) {
            sdevkit__write_reg((uint8_t)(SDEVKIT_PPU_BG1SC + i), sdevkit_ppu.bgsc[i]);
        }
        sdevkit__write_reg(SDEVKIT_PPU_BG12NBA, sdevkit_ppu.bgnba[0]);
        sdevkit__write_reg(SDEVKIT_PPU_BG34NBA, sdevkit_ppu.bgnba[1]);
        sdevkit__write_reg(SDEVKIT_PPU_SETINI, sdevkit_ppu.setini);
        sdevkit__write_reg(SDEVKIT_PPU_TM, sdevkit_ppu.tm);
        sdevkit__write_reg(SDEVKIT_PPU_TS, sdevkit_ppu.ts);
        memcpy((uint8_t *)&committed + groups[4].from, (const uint8_t *)&sdevkit_ppu + groups[4].from, groups[4].to - groups[4].from);
    }
    if (marks & SDEVKIT_MARK_WINDOW) {
        sdevkit__write_reg(SDEVKIT_PPU_W12SEL, sdevkit_ppu.w12sel);
        sdevkit__write_reg(SDEVKIT_PPU_W34SEL, sdevkit_ppu.w34sel);
        sdevkit__write_reg(SDEVKIT_PPU_WOBJSEL, sdevkit_ppu.wobjsel);
        for (i = 0; i < 4; i++) {
            sdevkit__write_reg((uint8_t)(SDEVKIT_PPU_WH0 + i), sdevkit_ppu.wh[i]);
        }
        sdevkit__write_reg(SDEVKIT_PPU_WBGLOG, sdevkit_ppu.wbglog);
        sdevkit__write_reg(SDEVKIT_PPU_WOBJLOG, sdevkit_ppu.wobjlog);
        sdevkit__write_reg(SDEVKIT_PPU_TMW, sdevkit_ppu.tmw);
        sdevkit__write_reg(SDEVKIT_PPU_TSW, sdevkit_ppu.tsw);
        memcpy((uint8_t *)&committed + groups[5].from, (const uint8_t *)&sdevkit_ppu + groups[5].from, groups[5].to - groups[5].from);
    }
    if (marks & SDEVKIT_MARK_MODE7) {
        sdevkit__write_reg(SDEVKIT_PPU_M7SEL, sdevkit_ppu.m7sel);
        write_twice(SDEVKIT_PPU_M7A, (uint16_t)sdevkit_ppu.m7a);
        write_twice(SDEVKIT_PPU_M7B, (uint16_t)sdevkit_ppu.m7b);
        write_twice(SDEVKIT_PPU_M7C, (uint16_t)sdevkit_ppu.m7c);
        write_twice(SDEVKIT_PPU_M7D, (uint16_t)sdevkit_ppu.m7d);
        write_twice(SDEVKIT_PPU_M7X, (uint16_t)sdevkit_ppu.m7x);
        write_twice(SDEVKIT_PPU_M7Y, (uint16_t)sdevkit_ppu.m7y);
        memcpy((uint8_t *)&committed + groups[2].from, (const uint8_t *)&sdevkit_ppu + groups[2].from, groups[2].to - groups[2].from);
    }
    if (marks & SDEVKIT_MARK_HDMA) {
        for (i = 1; i < 8; i++) {
            const sdevkit_hdma_channel *h = &sdevkit_hdma[i];
            if (h->table != 0) {
                /* drawn: direct HDMA, CPU to PPU, one register, two, or one twice */
                if ((h->dmap & ~0x07) != 0 || (h->dmap & 7) > 2) {
                    count_undrawn(h->bbad, h->dmap);
                }
                sdevkit__rachael.hdma[i].reg = h->bbad;
                sdevkit__rachael.hdma[i].two = (uint8_t)(h->dmap & 7);
                sdevkit__rachael.hdma[i].table = h->table;
            }
        }
        sdevkit__rachael.hdma_enabled = sdevkit_ppu.hdmaen;
        memcpy(committed_hdma, sdevkit_hdma, sizeof committed_hdma);
        committed.hdmaen = sdevkit_ppu.hdmaen;
    }
}

static void commit_queue(void)
{
    uint8_t k;

    for (k = 0; k < queue.n; k++) {
        port_copy(queue.port[k], queue.dmap[k], queue.vmain[k], queue.dest[k], (const uint8_t *)queue_src[k], queue.bytes[k]);
        sdevkit__rachael.dma_count = (uint16_t)(sdevkit__rachael.dma_count + queue.bytes[k]);
    }
    sdevkit__rachael.refused = (uint16_t)(sdevkit__rachael.refused + queue.refused);
    queue.refused = 0;
    queue_done();
}

/* --- Power on and vertical blank -------------------------------------------------------- */
void sdevkit__reset(void)
{
    memset(&sdevkit__rachael, 0, sizeof sdevkit__rachael);
    shadow_reset();
    committed = sdevkit_ppu;
    memset(committed_hdma, 0, sizeof committed_hdma);
    sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] = 0x80;   /* forced blank until the game turns the screen on */
    sdevkit__rachael.m7[0] = sdevkit__rachael.m7[3] = 0x100;   /* the matrix: the identity, as lib/snes resets it */
    sdevkit__rachael.tick_hash = SDEVKIT__HASH_START;
    sdevkit__sound_reset();
}

/* The commit, as lib/snes/snes.c's sdevkit__commit: a screen to be switched off goes off first;
 * the sprite table, the registers, the queue; then INIDISP. The vertical blank begins with it. */
void sdevkit__vblank(void)
{
    sdevkit__rachael.dma_count = 0;
    sdevkit__rachael.dma_lost = 0;
    sdevkit__rachael.in_vblank = 1;
    if (sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) {
        sdevkit__write_reg(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
    }
    memcpy(sdevkit__rachael.oam, sdevkit_oam, sizeof sdevkit__rachael.oam);
    sdevkit__commit_registers(sdevkit_marks);
    sdevkit_marks = 0;
    commit_queue();
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
    sprites_begin();                     /* as lib/snes/main.c after the commit */
}

/* In game_init: the commit without the sprite table, as on the SNES. In a tick it does nothing. */
void sdevkit_commit_now(void)
{
    if (sdevkit__rachael.in_tick) {
        return;
    }
    if (sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) {
        sdevkit__write_reg(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
    }
    sdevkit__commit_registers(SDEVKIT_MARK_ALL);
    commit_queue();
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
}

void sdevkit__display(void)
{
    sdevkit__rachael.in_vblank = 0;
}

void sdevkit__set_input(uint16_t pad1, uint16_t pad2)
{
    input_set(pad1, pad2);
}

sdevkit__frame_stats sdevkit__stats(void)
{
    sdevkit__frame_stats s = sdevkit__rachael.stats;
    s.dma_bytes = sdevkit__rachael.dma_count;   /* of the current / last vertical blank */
    s.dma_lost = sdevkit__rachael.dma_lost;
    return s;
}

const uint16_t *sdevkit_dbg_vram(void)
{
    return sdevkit__rachael.vram;
}

/* FNV-1a over the bytes the game names during a tick (sdevkit__tick_hash closes it). */
void sdevkit_dbg_hash(const void *data, uint16_t bytes)
{
    const uint8_t *p = (const uint8_t *)data;
    uint32_t h = sdevkit__rachael.tick_hash;
    uint16_t i;

    for (i = 0; i < bytes; i++) {
        h = (h ^ p[i]) * 16777619u;
    }
    sdevkit__rachael.tick_hash = h;
}

/* After game_tick, as lib/snes/main.c: the sprite list closed, then the shadow state hashed after
 * what the game named. */
uint32_t sdevkit__tick_hash(void)
{
    uint32_t h;

    sprites_end();
    shadow_hash();
    h = sdevkit__rachael.tick_hash;
    sdevkit__rachael.tick_hash = SDEVKIT__HASH_START;
    return h;
}

const uint16_t *sdevkit__cgram(void)
{
    return sdevkit__rachael.cgram;
}

const uint8_t *sdevkit__oam(void)
{
    return sdevkit__rachael.oam;
}

/* --- The public functions of the shadow state ------------------------------------------------ */
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large)
{
    sprite_add(x, y, tile, attr, large);
}

uint8_t sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    return queue_add(port, dmap, vmain, dest, src, bytes);
}

uint8_t sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes)
{
    return queue_add(0x18, 0x01, 0x80, a, src, bytes);
}

uint8_t sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes)
{
    return queue_add(0x18, 0x01, 0x81, a, src, bytes);
}

uint8_t sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes)
{
    return queue_add(0x22, 0x00, 0x00, i, src, bytes);
}

uint8_t sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes)
{
    return queue_add(0x18, 0x00, 0x00, e, src, bytes);
}

uint8_t sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes)
{
    return queue_add(0x19, 0x00, 0x80, b, src, bytes);
}

uint8_t sdevkit_queue_color(uint8_t index, uint16_t bgr)
{
    return queue_color(index, bgr);
}

uint16_t sdevkit_queue_room(void)
{
    return queue_room();
}
