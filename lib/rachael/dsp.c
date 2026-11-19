/*
 * Rachael's S-DSP (docs/sound.md): the audio RAM and the part of the sound chip the kit's effects
 * use. Integers only, so its output can be compared with an emulator's sample for sample.
 *
 * Per voice: the sample's start from the directory (DIR, SRCN) at key on, BRR decoded block by
 * block as the S-DSP decodes it (the four filters, the clamp to 16 bits and the clip to 15), the
 * pitch (14 bits, 0x1000 = one sample a sample), the Gaussian interpolation over four samples
 * with the chip's table, the envelope of GAIN in direct mode, key off (the release: 8 steps a
 * sample), the two volumes; then the main volume and the mute of FLG. The voice of the BRR Lab
 * (voicePlay in tools/brr_lab.html) is this one at full main volume.
 *
 * Not modelled, and counted in sdevkit__sound.unheard when a write switches it on (the SNES would
 * sound different, so the desktop's check fails on it as on a lost byte): ADSR, the other modes
 * of GAIN, echo, noise, pitch modulation, a sample's loop. The five samples of delay after key on
 * are not modelled either.
 */
#include "rachael.h"

#include <string.h>

/* The S-DSP's Gaussian interpolation table (512 entries, read from the chip). */
static const int16_t GAUSS[512] = {
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2,
    2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10,
    11, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15, 15, 16, 16, 17, 17, 18, 19, 19, 20, 20, 21, 21, 22, 23, 23, 24, 24, 25, 26, 27, 27,
    28, 29, 29, 30, 31, 32, 32, 33, 34, 35, 36, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56,
    58, 59, 60, 61, 62, 64, 65, 66, 67, 69, 70, 71, 73, 74, 76, 77, 78, 80, 81, 83, 84, 86, 87, 89, 90, 92, 94, 95, 97, 99, 100, 102,
    104, 106, 107, 109, 111, 113, 115, 117, 118, 120, 122, 124, 126, 128, 130, 132, 134, 137, 139, 141, 143, 145, 147, 150, 152, 154, 156, 159, 161, 163, 166, 168,
    171, 173, 175, 178, 180, 183, 186, 188, 191, 193, 196, 199, 201, 204, 207, 210, 212, 215, 218, 221, 224, 227, 230, 233, 236, 239, 242, 245, 248, 251, 254, 257,
    260, 263, 267, 270, 273, 276, 280, 283, 286, 290, 293, 297, 300, 304, 307, 311, 314, 318, 321, 325, 328, 332, 336, 339, 343, 347, 351, 354, 358, 362, 366, 370,
    374, 378, 381, 385, 389, 393, 397, 401, 405, 410, 414, 418, 422, 426, 430, 434, 439, 443, 447, 451, 456, 460, 464, 469, 473, 477, 482, 486, 491, 495, 499, 504,
    508, 513, 517, 522, 527, 531, 536, 540, 545, 550, 554, 559, 563, 568, 573, 577, 582, 587, 592, 596, 601, 606, 611, 615, 620, 625, 630, 635, 640, 644, 649, 654,
    659, 664, 669, 674, 678, 683, 688, 693, 698, 703, 708, 713, 718, 723, 728, 732, 737, 742, 747, 752, 757, 762, 767, 772, 777, 782, 787, 792, 797, 802, 806, 811,
    816, 821, 826, 831, 836, 841, 846, 851, 855, 860, 865, 870, 875, 880, 884, 889, 894, 899, 904, 908, 913, 918, 923, 927, 932, 937, 941, 946, 951, 955, 960, 965,
    969, 974, 978, 983, 988, 992, 997, 1001, 1005, 1010, 1014, 1019, 1023, 1027, 1032, 1036, 1040, 1045, 1049, 1053, 1057, 1061, 1066, 1070, 1074, 1078, 1082, 1086, 1090, 1094, 1098, 1102,
    1106, 1109, 1113, 1117, 1121, 1125, 1128, 1132, 1136, 1139, 1143, 1146, 1150, 1153, 1157, 1160, 1164, 1167, 1170, 1174, 1177, 1180, 1183, 1186, 1190, 1193, 1196, 1199, 1202, 1205, 1207, 1210,
    1213, 1216, 1219, 1221, 1224, 1227, 1229, 1232, 1234, 1237, 1239, 1241, 1244, 1246, 1248, 1251, 1253, 1255, 1257, 1259, 1261, 1263, 1265, 1267, 1269, 1270, 1272, 1274, 1275, 1277, 1279, 1280,
    1282, 1283, 1284, 1286, 1287, 1288, 1290, 1291, 1292, 1293, 1294, 1295, 1296, 1297, 1297, 1298, 1299, 1300, 1300, 1301, 1302, 1302, 1303, 1303, 1303, 1304, 1304, 1304, 1304, 1304, 1305, 1305
};

#define RING 32   /* decoded samples kept per voice: the four taps and a block ahead */

typedef struct {
    uint8_t  on;          /* playing (or releasing) */
    uint8_t  release;     /* keyed off: the envelope falls */
    uint8_t  end;         /* the block with the end flag is decoded */
    uint16_t brr;         /* the next block to decode */
    int32_t  decoded;     /* samples decoded so far */
    int16_t  o1, o2;      /* BRR's history: the last two outputs as stored */
    int16_t  ring[RING];  /* sample i at ring[i % RING] */
    uint32_t pos;         /* in units of 1/4096 sample */
    int32_t  env;         /* 0..0x7FF */
} voice;

sdevkit__sound_state sdevkit__sound;
static voice voices[8];

static int32_t clamp16(int32_t v)
{
    return v < -32768 ? -32768 : v > 32767 ? 32767 : v;
}

static int32_t wrap16(int32_t v)
{
    return (int16_t)(uint16_t)(v & 0xFFFF);
}

static void unheard(uint8_t reg, uint8_t value)
{
    if (sdevkit__sound.unheard == 0) {
        sdevkit__sound.unheard_reg = reg;
        sdevkit__sound.unheard_value = value;
    }
    sdevkit__sound.unheard++;
}

void sdevkit__sound_reset(void)
{
    void (*log)(uint8_t, uint8_t) = sdevkit__sound.log;   /* the frontend's, kept */

    memset(&sdevkit__sound, 0, sizeof sdevkit__sound);
    sdevkit__sound.log = log;
    sdevkit__sound.upload_hash = SDEVKIT__HASH_START;
    memset(voices, 0, sizeof voices);
    sdevkit__sound.regs[0x6C] = 0xE0;   /* FLG at power on: reset, mute, no echo writes */
}

static int32_t predict(int filter, int32_t out1, int32_t out2)
{
    int32_t p1 = out1 >> 1, p2 = out2 >> 1;

    switch (filter) {
    case 1: return p1 + ((-p1) >> 4);
    case 2: return (p1 << 1) + ((-((p1 << 1) + p1)) >> 5) - p2 + (p2 >> 4);
    case 3: return (p1 << 1) + ((-(p1 + (p1 << 2) + (p1 << 3))) >> 6) - p2 + (((p2 << 1) + p2) >> 4);
    }
    return 0;
}

/* The next block of a voice: 16 samples into its ring. */
static void decode_block(voice *v)
{
    const uint8_t *b = &sdevkit__sound.aram[v->brr];
    uint8_t h = b[0];
    int shift = h >> 4, filter = (h >> 2) & 3, i;

    if (h & 2) {
        unheard(0xFF, h);   /* a loop: the kit's samples have none */
    }
    for (i = 0; i < 16; i++) {
        uint8_t byte = sdevkit__sound.aram[(uint16_t)(v->brr + 1 + (i >> 1))];
        int32_t n = (((i & 1) ? byte & 15 : byte >> 4) ^ 8) - 8;
        int32_t s = (int32_t)((uint32_t)n << shift) >> 1;
        int32_t out;
        if (shift >= 13) {
            s = n < 0 ? -0x800 : 0;
        }
        out = wrap16(clamp16(s + predict(filter, v->o1, v->o2)) * 2);
        v->ring[v->decoded % RING] = (int16_t)out;
        v->decoded++;
        v->o2 = v->o1;
        v->o1 = (int16_t)out;
    }
    v->brr = (uint16_t)(v->brr + 9);
    if (h & 1) {
        v->end = 1;
    }
}

/* Sample j of the interpolation's input: three zeros, then the decoded samples. */
static int32_t tap(const voice *v, int32_t j)
{
    return j < 3 ? 0 : v->ring[(j - 3) % RING];
}

static int32_t gain_env(int n)
{
    uint8_t adsr1 = sdevkit__sound.regs[n << 4 | 0x05], gain = sdevkit__sound.regs[n << 4 | 0x07];

    if ((adsr1 & 0x80) || (gain & 0x80)) {
        return 0x7F0;   /* not modelled; the write was counted */
    }
    return (int32_t)(gain & 0x7F) << 4;
}

static void key_on(int n)
{
    voice *v = &voices[n];
    uint16_t entry = (uint16_t)(sdevkit__sound.regs[0x5D] << 8 | sdevkit__sound.regs[n << 4 | 0x04] << 2);

    memset(v, 0, sizeof *v);
    v->brr = (uint16_t)(sdevkit__sound.aram[entry] | sdevkit__sound.aram[(uint16_t)(entry + 1)] << 8);
    v->env = gain_env(n);
    v->on = 1;
}

void sdevkit__dsp_write(uint8_t reg, uint8_t value)
{
    int n;
    uint8_t low = reg & 0x0F;

    reg &= 0x7F;
    if (sdevkit__sound.log != NULL) {
        sdevkit__sound.log(reg, value);
    }
    sdevkit__sound.regs[reg] = value;
    if (low == 0x05 && reg < 0x80 && (value & 0x80)) {
        unheard(reg, value);                            /* ADSR */
    } else if (low == 0x07 && (value & 0x80) && !(sdevkit__sound.regs[(reg & 0x70) | 0x05] & 0x80)) {
        unheard(reg, value);                            /* GAIN: a mode other than direct */
    }
    switch (reg) {
    case 0x4C:                                          /* KON */
        for (n = 0; n < 8; n++) {
            if (value & (1 << n)) {
                key_on(n);
                if (sdevkit__sound.regs[0x5C] & (1 << n)) {
                    voices[n].release = 1;
                }
            }
        }
        break;
    case 0x5C:                                          /* KOF */
        for (n = 0; n < 8; n++) {
            if (value & (1 << n)) {
                voices[n].release = 1;
            }
        }
        break;
    case 0x6C:                                          /* FLG */
        if (value & 0x80) {
            for (n = 0; n < 8; n++) {
                voices[n].on = 0;
            }
        }
        if (!(value & 0x20)) {
            unheard(reg, value);                        /* the echo writes */
        }
        break;
    case 0x2C: case 0x3C: case 0x2D: case 0x3D: case 0x4D:   /* EVOL, PMON, NON, EON */
        if (value != 0) {
            unheard(reg, value);
        }
        break;
    default:
        break;
    }
}

/* One voice's sample at 32 kHz, before its volumes; advances it. */
static int32_t voice_sample(int n)
{
    voice *v = &voices[n];
    int32_t k, off, s;
    uint16_t pitch;

    if (!v->on) {
        return 0;
    }
    k = (int32_t)(v->pos >> 12);
    while (v->decoded <= k && !v->end) {
        decode_block(v);
    }
    if (k >= v->decoded) {
        v->on = 0;      /* the end of the sample without a loop: silent */
        return 0;
    }
    off = (int32_t)(v->pos >> 4) & 0xFF;
    s = wrap16(((GAUSS[255 - off] * tap(v, k)) >> 11) + ((GAUSS[511 - off] * tap(v, k + 1)) >> 11)
               + ((GAUSS[256 + off] * tap(v, k + 2)) >> 11));
    s += (GAUSS[off] * tap(v, k + 3)) >> 11;
    s = clamp16(s) & ~1;
    s = (s * v->env) >> 11;
    pitch = (uint16_t)((sdevkit__sound.regs[n << 4 | 0x03] & 0x3F) << 8 | sdevkit__sound.regs[n << 4 | 0x02]);
    v->pos += pitch;
    if (v->release) {
        v->env -= 8;
        if (v->env <= 0) {
            v->env = 0;
            v->on = 0;
        }
    } else {
        v->env = gain_env(n);
    }
    return s;
}

void sdevkit__dsp_render(int16_t *stereo, unsigned frames)
{
    unsigned i;
    int n;
    const uint8_t *r = sdevkit__sound.regs;

    for (i = 0; i < frames; i++) {
        int32_t l = 0, rr = 0;
        for (n = 0; n < 8; n++) {
            int32_t s = voice_sample(n);
            l += (s * (int8_t)r[n << 4 | 0x00]) >> 7;
            rr += (s * (int8_t)r[n << 4 | 0x01]) >> 7;
        }
        l = (clamp16(l) * (int8_t)r[0x0C]) >> 7;
        rr = (clamp16(rr) * (int8_t)r[0x1C]) >> 7;
        if (r[0x6C] & 0x40) {
            l = rr = 0;
        }
        stereo[2 * i] = (int16_t)clamp16(l);
        stereo[2 * i + 1] = (int16_t)clamp16(rr);
    }
}
