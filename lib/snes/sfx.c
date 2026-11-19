/*
 * Sound effects on the SNES (docs/sound.md): the logic of lib/sound/sfx_voices.h, and how it
 * reaches the SPC700 through the four ports $2140-$2143.
 *
 * The uploads go through the SPC700's boot code (the IPL ROM, as fullsnes documents its
 * protocol): it says $BBAA on ports 0 and 1 when it is ready; a block starts with its address on
 * ports 2 and 3, a non-zero byte on port 1 and a "kick" on port 0 that the boot code echoes; then
 * every byte goes to port 1 with its index (0, 1, 2, ...) on port 0, and the CPU waits for the
 * echo of the index. A block after the first is kicked with the last index + 2 (never 0); the jump
 * to the driver is a block with 0 on port 1.
 *
 * The driver (sfx_voices.h) then takes one DSP write per exchange: the register on port 1, the
 * value on port 2, then a new value on port 0, which it echoes when the write is done.
 */
#include "hw.h"
#include "../sound/sfx_voices.h"

static uint8_t sfx_index;       /* the index of the next byte of the block */
static uint8_t sfx_kick;        /* port 0's last value: the kick, then the counter of the DSP writes */
static uint8_t sfx_started;     /* a block has begun */

static void sfx_dsp(uint8_t reg, uint8_t value)
{
    APUIO1 = reg;
    APUIO2 = value;
    sfx_kick++;
    APUIO0 = sfx_kick;
    while (APUIO0 != sfx_kick) {
    }
}

/* A new block at this address: the first waits for the boot code; the others are kicked with an
 * index past the last one. */
static void sfx_upload_begin(uint16_t address)
{
    if (!sfx_started) {
        while (APUIO0 != 0xAA || APUIO1 != 0xBB) {
        }
        sfx_kick = 0xCC;
        sfx_started = 1;
    } else {
        sfx_kick = (uint8_t)(sfx_index + 2);
        if (sfx_kick == 0) {
            sfx_kick = 1;
        }
    }
    APUIO2 = (uint8_t)address;
    APUIO3 = (uint8_t)(address >> 8);
    APUIO1 = 1;
    APUIO0 = sfx_kick;
    while (APUIO0 != sfx_kick) {
    }
    sfx_index = 0;
}

static void sfx_upload(const uint8_t *data, uint16_t size)
{
    uint16_t k;
    uint8_t i = sfx_index;

    for (k = 0; k < size; k++) {
        APUIO1 = data[k];
        APUIO0 = i;
        while (APUIO0 != i) {
        }
        i++;
    }
    sfx_index = i;
}

/* The jump to the driver; it answers on port 1 when it runs, and keeps port 0 as the counter of
 * the DSP writes, starting from the jump's kick. */
static void sfx_upload_end(void)
{
    sfx_kick = (uint8_t)(sfx_index + 2);
    if (sfx_kick == 0) {
        sfx_kick = 1;
    }
    APUIO2 = (uint8_t)SFX_DRIVER;
    APUIO3 = (uint8_t)(SFX_DRIVER >> 8);
    APUIO1 = 0;
    APUIO0 = sfx_kick;
    while (APUIO1 != SFX_READY) {
    }
}

void sdevkit_sfx_init(const sdevkit_sfx_bank *bank)
{
    sfx_voices_init(bank);
}

void sdevkit_sfx_play(uint8_t id)
{
    sfx_voices_play(id);
}

/* The main loop's part (main.c), after game_vblank. */
void sdevkit__sfx_frame(void)
{
    sfx_voices_frame();
}
