/* The commit's worst case in every frame, for Mesen: all groups marked, HDMA channels 1 to 7 set,
 * and the queue filled to its budget, in turn with as many copies as it takes (the setup's worst)
 * and with one copy of all its bytes. */
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

static uint8_t data[SDEVKIT_QUEUE_BYTES];
static const uint8_t TABLE[] = { 0x7F, 0, 0, 0x7F, 0, 0, 0 };
static uint16_t tick;
uint16_t refused;

void game_init(uint16_t seed)
{
    uint8_t ch;
    (void)seed;
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE;
    for (ch = 1; ch < 8; ch++) {
        sdevkit_hdma[ch].table = TABLE;
        sdevkit_hdma[ch].dmap = SDEVKIT_HDMA_TWICE;
        sdevkit_hdma[ch].bbad = SDEVKIT_PPU_BG1HOFS + 6;   /* BG4HOFS: not shown in mode 1 */
    }
    sdevkit_ppu.hdmaen = 0xFE;
    sdevkit_ppu.inidisp = 15;
}

void game_tick(void)
{
    uint8_t k;
    tick++;
    sdevkit_marks = SDEVKIT_MARK_ALL;
    sdevkit_ppu.hofs[0] = tick;
    sdevkit_ppu.m7a = (int16_t)tick;
    data[0] = (uint8_t)tick;
    if (tick & 1) {
        for (k = 0; k < SDEVKIT_QUEUE_ENTRIES - 1; k++) {
            if (!sdevkit_queue_vram((uint16_t)(0x6000 + k * 16), data, 2)) {
                refused++;
            }
        }
        if (!sdevkit_queue_vram(0x7000, data, sdevkit_queue_room())) {
            refused++;
        }
    } else if (!sdevkit_queue_vram(0x6000, data, sdevkit_queue_room())) {
        refused++;
    }
    sdevkit_dbg_hash(&tick, sizeof tick);
}
