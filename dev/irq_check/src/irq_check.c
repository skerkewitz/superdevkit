/* The game's IRQ (sdevkit/irq.h) on the kit's frame rhythm: the screen split twice.
 *
 * BG1 is a field of stripes, each pixel column of a tile a colour and each tile a palette by its
 * column and row, so a scroll shows to the pixel, across and down. game_vblank scrolls the top; the IRQ before row SPLIT1 scrolls
 * the middle by another value and sets the IRQ before row SPLIT2, which moves the bottom part
 * up and sets SPLIT1 again, for the next frame. All three values change every tick, and the tick
 * publishes them (sdevkit/publish.h), so each split shows the tick the rest of the picture was
 * made from. The handler reads its value first, then waits for the horizontal blank
 * (sdevkit_irq_wait_hblank) and writes. An HDMA channel writes the brightness on every line, the
 * IRQ's lines too. With A held the tick is long: on the SNES it then ends below both splits, while it
 * ends above them otherwise; the desktop draws the same either way. The pictures of check.txt's
 * shots are compared with Mesen's (dev/compare_example.sh dev/irq_check), the checksums of the
 * ticks by IrqCheckTest. */
#include <stdint.h>
#include <dampf.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "sdevkit/irq.h"
#include "sdevkit/publish.h"

#define SPLIT1 72
#define SPLIT2 150
#define VRAM_TILES 0x0000
#define VRAM_MAP 0x1000
#define LONG_WORK 830           /* the long tick's rounds: it ends below SPLIT2 on the SNES */

struct split {
    uint16_t top_x;             /* rows 0 to SPLIT1 - 1 */
    uint16_t mid_x;             /* SPLIT1 to SPLIT2 - 1 */
    uint16_t low_y;             /* SPLIT2 on: the vertical scroll there */
    uint16_t seq;
};

SDEVKIT_PUBLISH(struct split, split, tick, vblank)

static uint8_t tile[32];
static uint16_t map[32 * 32];
static uint16_t palettes[8 * 16];
static uint8_t brightness[2 * (1 + 112) + 1];   /* HDMA: INIDISP on every line, a dimmer band */
uint16_t work;
uint16_t irq_split;             /* the handler's own: which split comes next */

void game_init(uint16_t seed)
{
    uint16_t i;
    (void)seed;
    /* a tile whose pixel x has colour x + 1 (colours 1 to 8; 0 would be transparent) */
    for (i = 0; i < 8; i++) {
        tile[i * 2] = 0xAA;             /* plane 0: x even (colour x + 1 odd) */
        tile[i * 2 + 1] = 0x66;         /* plane 1: x + 1 & 2 */
        tile[16 + i * 2] = 0x1E;        /* plane 2: x + 1 & 4 */
        tile[16 + i * 2 + 1] = 0x01;    /* plane 3: x + 1 & 8 */
    }
    for (i = 0; i < 32 * 32; i++) {
        map[i] = (uint16_t)(1u | (uint16_t)((((i & 31u) + (i >> 5)) & 7u) << 10));
    }
    for (i = 0; i < 8 * 16; i++) {
        palettes[i] = (uint16_t)((i * 0x0C63u + (i >> 4) * 0x1111u) & 0x7FFFu);
    }
    sdevkit_vram_copy((uint16_t)(VRAM_TILES + 16), tile, sizeof tile);
    sdevkit_vram_copy(VRAM_MAP, map, sizeof map);
    sdevkit_cgram_copy(0, palettes, sizeof palettes);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(VRAM_MAP, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(VRAM_TILES, VRAM_TILES));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, 0x3FF);
    /* HDMA on every line, also on the IRQ's: it takes its time at the start of the horizontal
     * blank, which the IRQ's writes share */
    brightness[0] = 128 + 112;
    for (i = 0; i < 112; i++) {
        brightness[1 + i] = 15;
        brightness[114 + i] = (uint8_t)(i >= 60 && i < 80 ? 9 : 15);
    }
    brightness[113] = 128 + 112;
    brightness[226] = 0;
    sdevkit_hdma_set(1, SDEVKIT_PPU_INIDISP, SDEVKIT_HDMA_ONE, brightness);
    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL1);
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
}

static void tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t i, rounds;
    split.seq++;
    split.top_x = (uint16_t)(split.top_x + 1u);
    split.mid_x = (uint16_t)(split.mid_x + ((pad[0] & SDEVKIT_KEY_RIGHT) ? 5u : 3u));
    split.low_y = (uint16_t)((split.low_y + 2u) & 0x3FFu);
    rounds = (pad[0] & SDEVKIT_KEY_A) ? LONG_WORK : 10;
    for (i = 0; i < rounds; i++) {
        work = (uint16_t)(work * 5u + i + split.seq);
    }
    sdevkit_dbg_hash(&split, sizeof split);
    sdevkit_dbg_hash(&work, sizeof work);
}

static void vblank(void)
{
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, split.top_x);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, 0x3FF);
    sdevkit_irq_line(SPLIT1);
}

DAMPF_INTERRUPT(irq) void game_irq(void)
{
    const struct split *s = SDEVKIT_PUBLISHED(split);
    if (irq_split == 0) {
        uint16_t x = s->mid_x;
        sdevkit_irq_wait_hblank();
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, x);
        sdevkit_irq_line(SPLIT2);
        irq_split = 1;
    } else {
        uint16_t y = s->low_y;
        sdevkit_irq_wait_hblank();
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, y);
        sdevkit_irq_line(SPLIT1);
        irq_split = 0;
    }
}
