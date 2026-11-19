/*
 * Sound effects on Rachael (docs/sound.md): the logic of lib/sound/sfx_voices.h, the same as on
 * the SNES, with the bytes going straight into her audio RAM and the writes into her DSP
 * (dsp.c), where the SNES sends them through the ports to the SPC700. The audio RAM ends up with
 * the same bytes from $0200 on: the driver (which Rachael does not run), the directory and the
 * samples.
 */
#include "rachael.h"
#include "../sound/sfx_voices.h"

static uint16_t sfx_at;   /* where the next uploaded byte goes */

static void sfx_dsp(uint8_t reg, uint8_t value)
{
    sdevkit__dsp_write(reg, value);
}

static void sfx_upload_begin(uint16_t address)
{
    sfx_at = address;
}

static void sfx_upload(const uint8_t *data, uint16_t size)
{
    uint16_t k;

    for (k = 0; k < size; k++) {
        uint32_t h = sdevkit__sound.upload_hash;
        sdevkit__sound.aram[sfx_at] = data[k];
        h = (h ^ (uint8_t)sfx_at) * 16777619u;
        h = (h ^ (uint8_t)(sfx_at >> 8)) * 16777619u;
        sdevkit__sound.upload_hash = (h ^ data[k]) * 16777619u;
        sfx_at++;
    }
}

static void sfx_upload_end(void)
{
}

void sdevkit_sfx_init(const sdevkit_sfx_bank *bank)
{
    sfx_voices_init(bank);
}

void sdevkit_sfx_play(uint8_t id)
{
    sfx_voices_play(id);
}

void sdevkit__sfx_frame(void)
{
    sfx_voices_frame();
}
