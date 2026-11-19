/*
 * The sound effects' logic, the same on the SNES and on Rachael (docs/sound.md): included by
 * lib/snes/sfx.c and lib/rachael/sfx.c, which define the four functions declared here, how the
 * bytes reach the sound chip:
 *
 *   sfx_dsp(reg, value)        one write to a DSP register
 *   sfx_upload_begin(address)  the bytes that follow go to this address (never an empty block)
 *   sfx_upload(data, size)     bytes of the audio RAM, one after the other
 *   sfx_upload_end()           the uploads are done: the driver starts
 *
 * On the SNES these go through the ports $2140-$2143 to the SPC700 (its boot code for the
 * uploads, the forwarding driver for the DSP writes); on Rachael straight into her audio RAM and
 * her DSP. Everything else is here, so the choice of the voice is the same on both, frame by
 * frame: the length of each effect in frames comes from the build, and a voice is free when it
 * has counted down, without asking the sound CPU.
 *
 * The audio RAM: the driver at $0200, the sample directory at $0300 (4 bytes a sample: start and
 * loop address), the samples from $0400 on, one after the other, up to $FFC0 (superdevkit sfx
 * checks that they fit).
 */
#ifndef SDEVKIT__SFX_VOICES_H
#define SDEVKIT__SFX_VOICES_H

#include "sdevkit/snes.h"

#define SFX_DRIVER    0x0200
#define SFX_DIRECTORY 0x0300
#define SFX_SAMPLES   0x0400
#define SFX_QUEUE     8          /* effects asked for in one tick; more are dropped */
#define SFX_READY     0x5D       /* what the driver answers on port 1 when it runs */

/* DSP registers (voice v: v * 16 + the register) */
#define DSP_VOLL   0x00
#define DSP_VOLR   0x01
#define DSP_PITCHL 0x02
#define DSP_PITCHH 0x03
#define DSP_SRCN   0x04
#define DSP_ADSR1  0x05
#define DSP_GAIN   0x07
#define DSP_MVOLL  0x0C
#define DSP_MVOLR  0x1C
#define DSP_EVOLL  0x2C
#define DSP_EVOLR  0x3C
#define DSP_KON    0x4C
#define DSP_KOF    0x5C
#define DSP_FLG    0x6C
#define DSP_PMON   0x2D
#define DSP_NON    0x3D
#define DSP_EON    0x4D
#define DSP_DIR    0x5D
#define DSP_EDL    0x7D

static void sfx_dsp(uint8_t reg, uint8_t value);
static void sfx_upload_begin(uint16_t address);
static void sfx_upload(const uint8_t *data, uint16_t size);
static void sfx_upload_end(void);

/*
 * The driver, SPC700 code at $0200. It waits until the CPU changes port 0, then writes the value
 * of port 2 into the DSP register named by port 1, and answers with port 0's value: one DSP write
 * per exchange, nothing else. Started by the boot code's jump, it first tells the CPU it runs.
 */
static const uint8_t sfx_driver[21] = {
    0xF8, 0xF4,             /* $0200  mov x,$F4       port 0 as the CPU left it */
    0x8F, SFX_READY, 0xF5,  /* $0202  mov $F5,#$5D    "the driver runs" on port 1 */
    0x3E, 0xF4,             /* $0205  loop: cmp x,$F4 port 0 changed? */
    0xF0, 0xFC,             /* $0207  beq loop */
    0xF8, 0xF4,             /* $0209  mov x,$F4       the new value */
    0xFA, 0xF5, 0xF2,       /* $020B  mov $F2,$F5     the register (port 1) into DSPADDR */
    0xFA, 0xF6, 0xF3,       /* $020E  mov $F3,$F6     the value (port 2) into DSPDATA */
    0xD8, 0xF4,             /* $0211  mov $F4,x       the answer: port 0's value */
    0x2F, 0xF0              /* $0213  bra loop */
};

static const sdevkit_sfx_bank *sfx_bank;
static uint8_t  sfx_directory[64 * 4];   /* built here, then uploaded */
static uint8_t  sfx_queue[SFX_QUEUE];
static uint8_t  sfx_queued;
static uint16_t sfx_left[8];      /* frames until the voice is free */
static uint8_t  sfx_prio[8];      /* the priority of what it plays */
static uint16_t sfx_start[8];     /* the frame it started in */
static uint16_t sfx_clock;        /* frames since sdevkit_sfx_init */

/* The samples into the audio RAM, the DSP set up: the driver's job starts. */
static void sfx_voices_init(const sdevkit_sfx_bank *bank)
{
    uint16_t address;
    uint8_t i, v;

    if (bank == 0 || sfx_bank != 0) {
        return;
    }
    sfx_upload_begin(SFX_DRIVER);
    sfx_upload(sfx_driver, sizeof sfx_driver);
    if (bank->samples != 0 && bank->samples <= 64) {   /* the boot code hangs on an empty block */
        address = SFX_SAMPLES;
        for (i = 0; i < bank->samples; i++) {
            sfx_directory[i * 4] = (uint8_t)address;
            sfx_directory[i * 4 + 1] = (uint8_t)(address >> 8);
            sfx_directory[i * 4 + 2] = (uint8_t)address;      /* the loop address: never used, no loop flag */
            sfx_directory[i * 4 + 3] = (uint8_t)(address >> 8);
            address += bank->brr_size[i];
        }
        sfx_upload_begin(SFX_DIRECTORY);
        sfx_upload(sfx_directory, (uint16_t)(bank->samples * 4));
        sfx_upload_begin(SFX_SAMPLES);
        for (i = 0; i < bank->samples; i++) {
            sfx_upload(bank->brr[i], bank->brr_size[i]);
        }
    }
    sfx_upload_end();
    sfx_dsp(DSP_FLG, 0x20);       /* no reset, no mute, the echo does not write */
    sfx_dsp(DSP_KOF, 0);
    sfx_dsp(DSP_MVOLL, 0x7F);
    sfx_dsp(DSP_MVOLR, 0x7F);
    sfx_dsp(DSP_EVOLL, 0);
    sfx_dsp(DSP_EVOLR, 0);
    sfx_dsp(DSP_EON, 0);
    sfx_dsp(DSP_NON, 0);
    sfx_dsp(DSP_PMON, 0);
    sfx_dsp(DSP_EDL, 0);
    sfx_dsp(DSP_DIR, SFX_DIRECTORY >> 8);
    for (v = 0; v < 8; v++) {
        sfx_dsp((uint8_t)(v << 4 | DSP_ADSR1), 0);      /* the fixed envelope: GAIN, direct, full */
        sfx_dsp((uint8_t)(v << 4 | DSP_GAIN), 0x7F);
        sfx_left[v] = 0;
    }
    sfx_queued = 0;
    sfx_clock = 0;
    sfx_bank = bank;
}

static void sfx_voices_play(uint8_t id)
{
    const sdevkit_sfx_bank *b = sfx_bank;

    if (b == 0 || id >= b->effects || b->sample[id] == SDEVKIT_SFX_OFF || sfx_queued == SFX_QUEUE) {
        return;
    }
    sfx_queue[sfx_queued] = id;
    sfx_queued++;
}

/* The voice for an effect of this priority: the first free one; else the one of the lowest
 * priority not above it, the older of equal ones; else none (0xFF). */
static uint8_t sfx_voice(uint8_t prio)
{
    uint8_t v, n, best;

    n = sfx_bank->voices;
    if (n > 8) {             /* never more (superdevkit sfx checks it); tells dampf the bound */
        n = 8;
    }
    for (v = 0; v < n; v++) {
        if (sfx_left[v] == 0) {
            return v;
        }
    }
    best = 0xFF;
    for (v = 0; v < n; v++) {
        if (sfx_prio[v] > prio) {
            continue;
        }
        if (best == 0xFF || sfx_prio[v] < sfx_prio[best]
            || (sfx_prio[v] == sfx_prio[best] && (uint16_t)(sfx_clock - sfx_start[v]) > (uint16_t)(sfx_clock - sfx_start[best]))) {
            best = v;
        }
    }
    return best;
}

/* After each tick: the voices count down, the effects asked for start, all with one key on. */
static void sfx_voices_frame(void)
{
    const sdevkit_sfx_bank *b = sfx_bank;
    uint8_t i, v, id, base, kon;
    uint16_t pitch;

    if (b == 0) {
        return;
    }
    sfx_clock++;
    for (v = 0; v < 8; v++) {
        if (sfx_left[v] != 0) {
            sfx_left[v]--;
        }
    }
    kon = 0;
    for (i = 0; i < sfx_queued; i++) {
        id = sfx_queue[i];
        v = sfx_voice(b->priority[id]);
        if (v == 0xFF) {
            continue;
        }
        sfx_left[v] = b->frames[id];
        sfx_prio[v] = b->priority[id];
        sfx_start[v] = sfx_clock;
        base = (uint8_t)(v << 4);
        pitch = b->pitch[id];
        sfx_dsp((uint8_t)(base | DSP_SRCN), b->sample[id]);
        sfx_dsp((uint8_t)(base | DSP_PITCHL), (uint8_t)pitch);
        sfx_dsp((uint8_t)(base | DSP_PITCHH), (uint8_t)(pitch >> 8));
        sfx_dsp((uint8_t)(base | DSP_VOLL), b->volume[id]);
        sfx_dsp((uint8_t)(base | DSP_VOLR), b->volume[id]);
        kon |= (uint8_t)(1 << v);
    }
    sfx_queued = 0;
    if (kon != 0) {
        sfx_dsp(DSP_KON, kon);
    }
}

#endif
