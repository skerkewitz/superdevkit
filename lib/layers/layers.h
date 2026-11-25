/*
 * The optional layers above the core (sdevkit/shadow.h, sprites.h, queue.h, input.h, easy.h;
 * ADR-6FNPPCSX), the same on both platforms: included once by lib/snes/layers.c and once by
 * lib/rachael/layers.c, as lib/sound/sfx_voices.h is by the two sfx.c. Everything here reaches
 * the hardware through the core (sdevkit_reg_write, sdevkit_oam_copy, ...), which dampf inlines on
 * the SNES, so a commit costs what the same writes in the game would. Each platform defines first
 *
 *   static void queue_source(uint8_t n, const void *src);   the n-th queued copy's source
 *   static void shadow_check(uint8_t marks);                 before a commit (Rachael: the
 *                                                            groups changed without their mark)
 *
 * and after the include sdevkit_queue_commit, the one part the SNES writes by hand: the vertical
 * blank pays for every cycle of its setup.
 *
 * The queue decides what fits when the game asks, in bytes, so the SNES and Rachael take and
 * refuse the same copies. lib/README.md ("The commit's time") has where the numbers come from.
 */
#ifndef SDEVKIT__LAYERS_H
#define SDEVKIT__LAYERS_H

#include "sdevkit/easy.h"

/* --- The register shadow (sdevkit/shadow.h) --------------------------------------------------- */
/* As the console after the kit's reset, but mode 1 (the hardware's mode 0 is one Rachael does not
 * draw) and every group marked, so the first commit writes them all. */
sdevkit_ppu_regs sdevkit_ppu = {
    { 0, 0, 0, 0 }, { 0, 0, 0, 0 },    /* hofs, vofs */
    0,                                 /* coldata */
    0x100, 0, 0, 0x100, 0, 0,          /* m7a..m7d: the identity; m7x, m7y */
    0, 0, 0,                           /* m7sel, cgwsel, cgadsub */
    SDEVKIT_BG_MODE1, 0, 0,            /* bgmode, obsel, mosaic */
    { 0, 0, 0, 0 }, { 0, 0 }, 0, 0, 0, /* bgsc, bgnba, setini, tm, ts */
    0, 0, 0, { 0, 0, 0, 0 }, 0, 0, 0, 0,   /* the windows */
    0,                                 /* hdmaen */
    SDEVKIT_FORCED_BLANK               /* inidisp */
};
sdevkit_hdma_channel sdevkit_hdma[8];
uint8_t sdevkit_marks = SDEVKIT_MARK_ALL;

/* The registers of the groups marked, by the core: dampf makes each a store. INIDISP is the
 * caller's. */
static void commit_registers(uint8_t marks)
{
    if (marks & SDEVKIT_MARK_SCROLL) {
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, sdevkit_ppu.hofs[0]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, sdevkit_ppu.vofs[0]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG2HOFS, sdevkit_ppu.hofs[1]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG2VOFS, sdevkit_ppu.vofs[1]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG3HOFS, sdevkit_ppu.hofs[2]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG3VOFS, sdevkit_ppu.vofs[2]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG4HOFS, sdevkit_ppu.hofs[3]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG4VOFS, sdevkit_ppu.vofs[3]);
    }
    if (marks & SDEVKIT_MARK_COLOR) {
        uint16_t c = sdevkit_ppu.coldata;
        sdevkit_reg_write(SDEVKIT_PPU_CGWSEL, sdevkit_ppu.cgwsel);
        sdevkit_reg_write(SDEVKIT_PPU_CGADSUB, sdevkit_ppu.cgadsub);
        /* COLDATA takes one write per colour channel, with its bit */
        sdevkit_reg_write(SDEVKIT_PPU_COLDATA, (uint8_t)(SDEVKIT_CM_APPLY_RED | (c & 0x1F)));
        sdevkit_reg_write(SDEVKIT_PPU_COLDATA, (uint8_t)(SDEVKIT_CM_APPLY_GREEN | ((c >> 5) & 0x1F)));
        sdevkit_reg_write(SDEVKIT_PPU_COLDATA, (uint8_t)(SDEVKIT_CM_APPLY_BLUE | ((c >> 10) & 0x1F)));
    }
    if (marks & SDEVKIT_MARK_SETUP) {
        sdevkit_reg_write(SDEVKIT_PPU_OBSEL, sdevkit_ppu.obsel);
        sdevkit_reg_write(SDEVKIT_PPU_BGMODE, sdevkit_ppu.bgmode);
        sdevkit_reg_write(SDEVKIT_PPU_MOSAIC, sdevkit_ppu.mosaic);
        sdevkit_reg_write(SDEVKIT_PPU_BG1SC, sdevkit_ppu.bgsc[0]);
        sdevkit_reg_write(SDEVKIT_PPU_BG2SC, sdevkit_ppu.bgsc[1]);
        sdevkit_reg_write(SDEVKIT_PPU_BG3SC, sdevkit_ppu.bgsc[2]);
        sdevkit_reg_write(SDEVKIT_PPU_BG4SC, sdevkit_ppu.bgsc[3]);
        sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, sdevkit_ppu.bgnba[0]);
        sdevkit_reg_write(SDEVKIT_PPU_BG34NBA, sdevkit_ppu.bgnba[1]);
        sdevkit_reg_write(SDEVKIT_PPU_SETINI, sdevkit_ppu.setini);
        sdevkit_reg_write(SDEVKIT_PPU_TM, sdevkit_ppu.tm);
        sdevkit_reg_write(SDEVKIT_PPU_TS, sdevkit_ppu.ts);
    }
    if (marks & SDEVKIT_MARK_WINDOW) {
        sdevkit_reg_write(SDEVKIT_PPU_W12SEL, sdevkit_ppu.w12sel);
        sdevkit_reg_write(SDEVKIT_PPU_W34SEL, sdevkit_ppu.w34sel);
        sdevkit_reg_write(SDEVKIT_PPU_WOBJSEL, sdevkit_ppu.wobjsel);
        sdevkit_reg_write(SDEVKIT_PPU_WH0, sdevkit_ppu.wh[0]);
        sdevkit_reg_write(SDEVKIT_PPU_WH1, sdevkit_ppu.wh[1]);
        sdevkit_reg_write(SDEVKIT_PPU_WH2, sdevkit_ppu.wh[2]);
        sdevkit_reg_write(SDEVKIT_PPU_WH3, sdevkit_ppu.wh[3]);
        sdevkit_reg_write(SDEVKIT_PPU_WBGLOG, sdevkit_ppu.wbglog);
        sdevkit_reg_write(SDEVKIT_PPU_WOBJLOG, sdevkit_ppu.wobjlog);
        sdevkit_reg_write(SDEVKIT_PPU_TMW, sdevkit_ppu.tmw);
        sdevkit_reg_write(SDEVKIT_PPU_TSW, sdevkit_ppu.tsw);
    }
    if (marks & SDEVKIT_MARK_MODE7) {
        sdevkit_reg_write(SDEVKIT_PPU_M7SEL, sdevkit_ppu.m7sel);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, (uint16_t)sdevkit_ppu.m7a);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7B, (uint16_t)sdevkit_ppu.m7b);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7C, (uint16_t)sdevkit_ppu.m7c);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, (uint16_t)sdevkit_ppu.m7d);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7X, (uint16_t)sdevkit_ppu.m7x);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7Y, (uint16_t)sdevkit_ppu.m7y);
    }
    if (marks & SDEVKIT_MARK_HDMA) {
        /* the channels hdmaen switches on; the hardware reads their tables again every frame */
        uint8_t on = sdevkit_ppu.hdmaen;
        uint8_t ch;
        for (ch = 1; ch < 8; ch++) {
            on = (uint8_t)(on >> 1);
            if (on & 1) {
                sdevkit_hdma_set(ch, sdevkit_hdma[ch].bbad, sdevkit_hdma[ch].dmap, sdevkit_hdma[ch].table);
            }
        }
        sdevkit_hdma_enable(sdevkit_ppu.hdmaen);
    }
}

void sdevkit_shadow_commit(void)
{
    uint8_t marks = sdevkit_marks;

    shadow_check(marks);
    if (sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) {
        sdevkit_reg_write(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
    }
    commit_registers(marks);
    sdevkit_marks = 0;
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
}

/* --- The sprite list (sdevkit/sprites.h) ------------------------------------------------------ */
uint8_t sdevkit_oam[SDEVKIT_OAM_BYTES];

static uint8_t sprites_n;      /* entries the list filled in this tick */
static uint8_t sprites_last;   /* and in the tick before, as OAM has them */

/* 128 hidden entries, the source of the commit's copy that hides */
#define SDEVKIT__HIDE1  0, SDEVKIT_OAM_HIDE_Y, 0, 0
#define SDEVKIT__HIDE4  SDEVKIT__HIDE1, SDEVKIT__HIDE1, SDEVKIT__HIDE1, SDEVKIT__HIDE1
#define SDEVKIT__HIDE16 SDEVKIT__HIDE4, SDEVKIT__HIDE4, SDEVKIT__HIDE4, SDEVKIT__HIDE4
#define SDEVKIT__HIDE64 SDEVKIT__HIDE16, SDEVKIT__HIDE16, SDEVKIT__HIDE16, SDEVKIT__HIDE16
static const uint8_t sprites_hidden[512] = { SDEVKIT__HIDE64, SDEVKIT__HIDE64 };
static const uint8_t sprites_zero[32];   /* the high table's bytes of hidden entries */

/* Its two bits in the high table (bit 8 of x, large) go into the byte it shares with three
 * others: the first of the four writes the byte, the others or into it, so no byte needs clearing
 * in the tick and no loop runs here. */
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large)
{
    static const uint8_t x8[4] = { 0x01, 0x04, 0x10, 0x40 };
    static const uint8_t big[4] = { 0x02, 0x08, 0x20, 0x80 };
    uint8_t n = sprites_n;
    uint16_t o;
    uint8_t bits;

    if (n >= 128 || (uint16_t)(x + 64) >= 256 + 64 || (uint16_t)(y + 64) >= 224 + 64) {
        return;
    }
    o = (uint16_t)((uint16_t)n * 4);
    sdevkit_oam[o] = (uint8_t)x;
    sdevkit_oam[o + 1] = (uint8_t)(y - 1);
    sdevkit_oam[o + 2] = (uint8_t)tile;
    sdevkit_oam[o + 3] = (uint8_t)(attr | (uint8_t)((tile >> 8) & 1));
    bits = large ? big[n & 3] : 0;
    if (x < 0) {
        bits = (uint8_t)(bits | x8[n & 3]);
    }
    if ((n & 3) == 0) {
        sdevkit_oam[512 + (n >> 2)] = bits;
    } else if (bits) {
        sdevkit_oam[512 + (n >> 2)] = (uint8_t)(sdevkit_oam[512 + (n >> 2)] | bits);
    }
    sprites_n = (uint8_t)(n + 1);
}

/* The entries filled and their bytes of the high table; then the ones filled the tick before and
 * not now, hidden (from sprites_hidden), and their bytes of the high table zero (a large sprite
 * hidden below the picture would reach into its top). All by DMA, no loop in the vertical blank:
 * up to four copies, 544 bytes at most. */
void sdevkit_sprites_commit(void)
{
    uint8_t n = sprites_n;
    uint8_t last = sprites_last;
    uint8_t high = (uint8_t)((n + 3) >> 2);

    if ((uint8_t)(n | last) == 0) {          /* the list is not used */
        return;
    }
    if (n != 0) {
        sdevkit_oam_copy(0, sdevkit_oam, (uint16_t)((uint16_t)n * 4));
        sdevkit_oam_copy(512, sdevkit_oam + 512, high);
    }
    if (n < last) {
        uint16_t o = (uint16_t)((uint16_t)n * 4);
        uint8_t stale = (uint8_t)(((last + 3) >> 2) - high);
        sdevkit_oam_copy(o, sprites_hidden + o, (uint16_t)((uint16_t)(last - n) * 4));
        if (stale != 0) {
            sdevkit_oam_copy((uint16_t)(512 + high), sprites_zero, stale);
        }
    }
    sprites_last = n;
    sprites_n = 0;
}

/* --- The upload queue (sdevkit/queue.h) ------------------------------------------------------- */
/* What the checks may hash (no pointers, no padding); the sources are the platform's. */
typedef struct {
    uint16_t dest[SDEVKIT_QUEUE_ENTRIES];    /* a VRAM word address or a CGRAM colour */
    uint16_t bytes[SDEVKIT_QUEUE_ENTRIES];
    uint16_t value[SDEVKIT_QUEUE_ENTRIES];   /* sdevkit_queue_color's colour, the source of its copy */
    uint16_t used;                           /* bytes counted for the next vertical blank */
    uint8_t  port[SDEVKIT_QUEUE_ENTRIES];    /* the low byte of $21xx */
    uint8_t  dmap[SDEVKIT_QUEUE_ENTRIES];
    uint8_t  vmain[SDEVKIT_QUEUE_ENTRIES];
    uint8_t  n;                              /* copies queued */
    uint8_t  refused;                        /* copies refused since power on (the desktop reports it) */
} sdevkit__queue_state;

static sdevkit__queue_state queue;

uint8_t sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    uint16_t cost = (uint16_t)(bytes + SDEVKIT_QUEUE_COST);
    uint8_t n = queue.n;

    if (bytes == 0 || n >= SDEVKIT_QUEUE_ENTRIES || bytes > SDEVKIT_QUEUE_BYTES
        || cost > (uint16_t)(SDEVKIT_QUEUE_BYTES - queue.used)) {
        queue.refused++;
        return 0;
    }
    queue.port[n] = port;
    queue.dmap[n] = dmap;
    queue.vmain[n] = vmain;
    queue.dest[n] = dest;
    queue.bytes[n] = bytes;
    queue_source(n, src);
    queue.used = (uint16_t)(queue.used + cost);
    queue.n = (uint8_t)(n + 1);
    return 1;
}

uint8_t sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes)
{
    return sdevkit_queue_dma(0x18, 0x01, 0x80, a, src, bytes);
}

uint8_t sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes)
{
    return sdevkit_queue_dma(0x18, 0x01, 0x81, a, src, bytes);
}

uint8_t sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes)
{
    return sdevkit_queue_dma(0x22, 0x00, 0x00, i, src, bytes);
}

uint8_t sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes)
{
    return sdevkit_queue_dma(0x18, 0x00, 0x00, e, src, bytes);
}

uint8_t sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes)
{
    return sdevkit_queue_dma(0x19, 0x00, 0x80, b, src, bytes);
}

uint8_t sdevkit_queue_color(uint8_t i, uint16_t bgr)
{
    uint8_t n = queue.n;

    if (!sdevkit_queue_dma(0x22, 0x00, 0x00, i, &queue.value[n < SDEVKIT_QUEUE_ENTRIES ? n : 0], 2)) {
        return 0;
    }
    queue.value[n] = bgr;
    return 1;
}

uint16_t sdevkit_queue_room(void)
{
    uint16_t left = (uint16_t)(SDEVKIT_QUEUE_BYTES - queue.used);
    return queue.n >= SDEVKIT_QUEUE_ENTRIES || left <= SDEVKIT_QUEUE_COST ? 0 : (uint16_t)(left - SDEVKIT_QUEUE_COST);
}

/* After the commit has run the queue. */
static void queue_done(void)
{
    queue.n = 0;
    queue.used = 0;
}

/* --- The buttons pressed (sdevkit/input.h) ---------------------------------------------------- */
sdevkit_input_state sdevkit_input;

void sdevkit_input_update(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t p0 = pad[0], p1 = pad[1];

    sdevkit_input.pressed[0] = (uint16_t)(p0 & ~sdevkit_input.pad[0]);
    sdevkit_input.pressed[1] = (uint16_t)(p1 & ~sdevkit_input.pad[1]);
    sdevkit_input.pad[0] = p0;
    sdevkit_input.pad[1] = p1;
}

/* --- All of them (sdevkit/easy.h) ------------------------------------------------------------- */
void sdevkit_commit(void)
{
    uint8_t marks = sdevkit_marks;

    shadow_check(marks);
    if (sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) {
        sdevkit_reg_write(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
    }
    sdevkit_sprites_commit();
    commit_registers(marks);
    sdevkit_marks = 0;
    sdevkit_queue_commit();
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, sdevkit_ppu.inidisp);
}

#endif
