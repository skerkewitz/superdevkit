/*
 * The shadow state's logic, the same on both platforms (sdevkit/snes.h, "Shadow state"): included
 * once by lib/snes/snes.c and once by lib/rachael/ppu.c, as lib/sound/sfx_voices.h is by the two
 * sfx.c. What the game writes (the PPU's registers, the HDMA channels, the sprite table, the
 * marks), the upload queue and what fits into a vertical blank, the sprite list, the joypads, the
 * order in which the checks hash it all after a tick. Each platform adds the commit: how the state
 * reaches its hardware (lib/snes: the registers and DMA; Rachael: her memories and registers).
 *
 * The queue decides what fits when the game asks, in bytes, so the SNES and Rachael take and
 * refuse the same copies. The bytes are what one vertical blank can copy after everything else
 * the commit does there; lib/README.md, "The commit's time", has the numbers they come from.
 */
#ifndef SDEVKIT__SHADOW_H
#define SDEVKIT__SHADOW_H

#include "sdevkit/snes.h"
#include "sdevkit/loop.h"

sdevkit_ppu_regs sdevkit_ppu;
sdevkit_hdma_channel sdevkit_hdma[8];
uint8_t sdevkit_marks;
uint8_t sdevkit_oam[128 * 4 + 8 * 4];
sdevkit_input_state sdevkit_input;

/* The queue: what the checks hash (no pointers, no padding), and the sources beside it. */
typedef struct {
    uint16_t dest[SDEVKIT_QUEUE_ENTRIES];    /* a VRAM word address or a CGRAM colour */
    uint16_t bytes[SDEVKIT_QUEUE_ENTRIES];
    uint16_t value[SDEVKIT_QUEUE_ENTRIES];   /* sdevkit_queue_color's colour, the source of its copy */
    uint16_t used;                           /* bytes counted for the next vertical blank */
    uint8_t  port[SDEVKIT_QUEUE_ENTRIES];    /* the low byte of $21xx */
    uint8_t  dmap[SDEVKIT_QUEUE_ENTRIES];
    uint8_t  vmain[SDEVKIT_QUEUE_ENTRIES];
    uint8_t  n;                              /* copies queued */
    uint8_t  refused;                        /* copies refused since the last commit (for the checks) */
} sdevkit__queue_state;

static sdevkit__queue_state queue;
#ifdef SDEVKIT__QUEUE_SOURCES
static const void *queue_src[SDEVKIT_QUEUE_ENTRIES];   /* Rachael copies from them; lib/snes keeps the address split */
#endif

static uint8_t sprites_n;      /* entries the sprite list filled in this tick */
static uint8_t sprites_last;   /* and in the tick before */

static uint8_t queue_add(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
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
#ifdef SDEVKIT__QUEUE_SOURCES
    queue_src[n] = src;
#else
    (void)src;
#endif
    queue.used = (uint16_t)(queue.used + cost);
    queue.n = (uint8_t)(n + 1);
    return 1;
}

static uint8_t queue_color(uint8_t index, uint16_t bgr)
{
    uint8_t n = queue.n;

    if (!queue_add(0x22, 0x00, 0x00, index, &queue.value[n < SDEVKIT_QUEUE_ENTRIES ? n : 0], 2)) {
        return 0;
    }
    queue.value[n] = bgr;
    return 1;
}

static uint16_t queue_room(void)
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

/* One sprite of the list, where a background scrolled by (0, 0) has its pixel (x, y): the SNES
 * draws a sprite one line lower than its entry, hence y - 1. Its two bits in the high table (bit 8
 * of x, large) are ORed into their byte: sprites_begin cleared the bytes the list used, and the
 * bits after its last entry are zero (sprites_end), so no loop runs here; out of the picture (for
 * sprites up to 64 pixels) it is left out. */
static void sprite_add(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large)
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
    if (bits) {
        sdevkit_oam[512 + (n >> 2)] = (uint8_t)(sdevkit_oam[512 + (n >> 2)] | bits);
    }
    sprites_n = (uint8_t)(n + 1);
}

/* After the commit has copied the sprite table: the high table's bytes of the entries the list
 * filled are cleared for the next tick's ORs. Here, not in sdevkit_sprite, so the game's call has
 * no loop, and not in the vertical blank. */
static void sprites_begin(void)
{
    uint8_t i, used = (uint8_t)((sprites_last + 3) >> 2);

    for (i = 0; i < 32 && i < used; i++) {
        sdevkit_oam[512 + i] = 0;
    }
}

/* After game_tick: the entries the list filled the tick before and not in this one are hidden,
 * and their bits in the high table cleared (a large sprite hidden below the picture would reach
 * into its top). */
static void sprites_end(void)
{
    uint8_t n = sprites_n;
    uint8_t last = sprites_last;

    if ((uint8_t)(n | last) == 0) {          /* the list is not used */
        return;
    }
    if (n < last) {
        uint16_t o;
        uint8_t i = (uint8_t)(n >> 2);
        for (o = (uint16_t)((uint16_t)n * 4); o < 512 && o < (uint16_t)((uint16_t)last * 4); o += 4) {
            sdevkit_oam[o + 1] = SDEVKIT_OAM_HIDE_Y;
        }
        if (n & 3) {                         /* the byte shared with entries still shown */
            sdevkit_oam[512 + i] = (uint8_t)(sdevkit_oam[512 + i] & (uint8_t)~(0xFF << ((n & 3) * 2)));
            i++;
        }
        for (; i < 32 && i < (uint8_t)((last + 3) >> 2); i++) {
            sdevkit_oam[512 + i] = 0;
        }
    }
    sprites_last = n;
    sprites_n = 0;
}

/* The joypads of the coming tick, and the buttons pressed since the last. */
static void input_set(uint16_t pad1, uint16_t pad2)
{
    sdevkit_input.pressed[0] = (uint16_t)(pad1 & ~sdevkit_input.pad[0]);
    sdevkit_input.pressed[1] = (uint16_t)(pad2 & ~sdevkit_input.pad[1]);
    sdevkit_input.pad[0] = pad1;
    sdevkit_input.pad[1] = pad2;
}

/* What the checks hash after each tick, after what the game named: the sprite table, the PPU's
 * registers, and the queue when it has copies. Each part costs the SNES five port writes a tick,
 * so the HDMA channels (whose tables are pointers) and the marks are left out: Rachael compares
 * both at the commit (unmarked), and the pictures show the rest. */
static void shadow_hash(void)
{
    sdevkit_dbg_hash(sdevkit_oam, sizeof sdevkit_oam);
    sdevkit_dbg_hash(&sdevkit_ppu, sizeof sdevkit_ppu);
    if (queue.n != 0) {
        sdevkit_dbg_hash(&queue, sizeof queue);
    }
}

/* The state of power-on and after the splash: the screen off, mode 1 (the kit's; the hardware's
 * mode 0 is one Rachael does not draw), the mode 7 matrix the identity, every sprite hidden,
 * nothing queued, the joypads released. */
static void shadow_reset(void)
{
    uint16_t i;
    uint8_t *p = (uint8_t *)&sdevkit_ppu;

    for (i = 0; i < sizeof sdevkit_ppu; i++) {
        p[i] = 0;
    }
    sdevkit_ppu.inidisp = SDEVKIT_FORCED_BLANK;
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
    sdevkit_ppu.m7a = 0x100;
    sdevkit_ppu.m7d = 0x100;
    for (i = 0; i < 8; i++) {
        sdevkit_hdma[i].table = 0;
        sdevkit_hdma[i].dmap = 0;
        sdevkit_hdma[i].bbad = 0;
        sdevkit_hdma[i].dasb = 0;
    }
    for (i = 0; i < 512; i += 4) {
        sdevkit_oam[i] = 0;
        sdevkit_oam[i + 1] = SDEVKIT_OAM_HIDE_Y;
        sdevkit_oam[i + 2] = 0;
        sdevkit_oam[i + 3] = 0;
    }
    for (i = 512; i < sizeof sdevkit_oam; i++) {
        sdevkit_oam[i] = 0;
    }
    sdevkit_marks = 0;
    queue.n = 0;
    queue.used = 0;
    queue.refused = 0;
    sprites_n = 0;
    sprites_last = 0;
    sdevkit_input.pad[0] = sdevkit_input.pad[1] = 0;
    sdevkit_input.pressed[0] = sdevkit_input.pressed[1] = 0;
}

#endif
