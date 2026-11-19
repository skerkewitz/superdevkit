/*
 * The desktop frontend of superdevkit (Linux and Windows; ADR records 32 and 39): a window with
 * raylib, the keyboard and a gamepad as the SNES joypad, and the frame rhythm of sdevkit/loop.h
 * on Rachael (lib/rachael):
 *
 *   game_tick(pads)     the picture is being drawn: VRAM and CGRAM take nothing now
 *   sdevkit__vblank()   the vertical blank begins: the sprite table is copied
 *   game_vblank()       the game's uploads
 *   sdevkit__sfx_frame() the sound effects the tick asked for start (lib/sound/sfx_voices.h)
 *   sdevkit__render()   the picture of the frame, which ends the vertical blank
 *
 * Each tick also makes a frame's samples of Rachael's DSP (32 kHz, stereo): in the window they
 * play through raylib's audio stream; a check writes them to a WAV file with --wav file. The music
 * the game asks for (sdevkit_music_play) plays in the window from its file, music/<file> next to
 * the program (sdevkit__music_files, which the build writes), over and over; it pauses with the
 * run. A check plays no music.
 * Before game_init, as on the SNES, the splash screen (lib/splash/splash.h): its frames are drawn
 * and shown like the game's, then the console is reset again.
 *
 * Rachael counts every byte that would not arrive on the console (written outside the
 * vertical blank, or more than one vertical blank can copy), so the desktop finds what the SNES
 * would lose.
 *
 * Save RAM (sdevkit_sram, built with -DSDEVKIT_SRAM_SIZE): in the window it is the file <game>.srm
 * next to the program, read before the game starts, written when it changed (looked at every
 * half second) and when the window closes. A check, a replay and a recording start with the save
 * RAM their file names ("sram H": its bytes in hex, the rest zero), or zeros; they never touch
 * the .srm file. A recording names the save RAM of its start.
 *
 * The window shows the picture through the retro TV filter (crt.c); Tab turns it off and on,
 * --no-crt starts without it. Screenshots (F12, --check) are Rachael's picture, unfiltered.
 *
 * Every session in the window is recorded: the joypad and the checksum of every tick. F11 flags
 * the moment (a mark) and saves the session so far as <game>_rec_<n>.txt, a file in the format of
 * check.txt ("ticks", "press", and "mark T" and "hash T H"), so the same file plays on the SNES
 * too (dev/replay.sh <rom> <recording>, in dampf's emulator, which compares the hashes and skips
 * the marks). --replay file plays one in the window: it pauses at each mark, and at the first
 * tick whose checksum is not the recorded one; after its last tick the keyboard takes over (and the recording goes on). F5 pauses and
 * goes on, F6 steps one tick while paused, F7 runs eight ticks a frame, F8 a tick every four
 * frames (slow motion). With --check, --record file writes the recording of the check's run.
 *
 * F1 opens the debug view beside the picture (view.c): the run, what the game registered with
 * sdevkit_dbg_toggle, _range, _color and _action, its boxes (sdevkit_dbg_box) over the picture,
 * CGRAM, VRAM's tiles and the sprites. A change made there is not part of the recording: the
 * window says from which tick a replay plays another game, and the recording notes it.
 * --view-shot file.png (with --check) draws the view once after the check into a PNG, in a
 * hidden window (--view-tab 0..3 picks the tab), for the tests and the docs.
 *
 * With --check <file> it runs without a window, with the input of the file (the format of the
 * examples' check.txt: "ticks N", "press T1 T2 buttons...", "shot T", "splash N"; on the desktop
 * every frame is one tick, and the splash comes before them, without input), writes the screenshots it
 * asks for as PNG into the folder of --out (default .), the checksum of every tick
 * (sdevkit_dbg_hash: "tick hash" in hex, one a line) into hashes.txt there, and prints "frames N ticks N lost L
 * undrawn U unheard H sprites S"; the exit status is 1 when a byte was lost, a register write was one
 * Rachael does not draw (sdevkit__undrawn), a DSP write one she does not play (sdevkit__sound.unheard),
 * or the file is a recording and a tick's checksum is not the recorded one. sound.txt there has
 * the sound as dampf's emulator also writes it (dampf.emu.ExampleCheck): every DSP write, "init"
 * (in game_init) or the tick after which it was made, the register and the value in hex; after
 * game_init's writes the line "aram H": the FNV-1a of the bytes written into the audio RAM from $0200 on (each its address, low and high byte, then the value, in the order written). S counts the lines over the sprite limits (32 sprites,
 * 34 tiles); a game may mean that (flicker), so it does not fail the check.
 *
 * This file is host code for the desktop's C compiler, not for dampf.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "rachael.h"
#include "raylib.h"
#include "crt.h"
#include "view.h"

#ifndef GAME_NAME
#define GAME_NAME "superdevkit"
#endif

static unsigned char rgba[SDEVKIT__W * SDEVKIT__H * 4];

/* The picture of Rachael (BGR 5:5:5) as RGBA. */
static void picture(void)
{
    static uint16_t frame[SDEVKIT__W * SDEVKIT__H];
    int i;

    sdevkit__render(frame);
    for (i = 0; i < SDEVKIT__W * SDEVKIT__H; i++) {
        uint16_t c = frame[i];
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        rgba[i * 4 + 0] = (unsigned char)((r << 3) | (r >> 2));
        rgba[i * 4 + 1] = (unsigned char)((g << 3) | (g >> 2));
        rgba[i * 4 + 2] = (unsigned char)((b << 3) | (b >> 2));
        rgba[i * 4 + 3] = 255;
    }
}

/* Lines of the frames so far with more sprites or sprite tiles than the SNES draws. */
static unsigned sprite_lines;

/* The checksum of each tick (sdevkit_dbg_hash), written by a check to hashes.txt. */
static FILE *hashes;
static long tick_count;
static uint32_t last_hash;                 /* the checksum of the last tick */

/* --- the sound ------------------------------------------------------------------------------- */
/* What a tick makes of the DSP's output: on the SNES 32000 / 60.0988 samples (NTSC), in the window
 * 32000 / 60, the window's frame rate, so that the stream neither runs dry nor fills up. */
static long sound_ticks;                   /* ticks whose samples were made */
static long sound_rate = 600988;           /* frames per 10,000 seconds */
static FILE *sound_log;                    /* a check's sound.txt */
static long sound_tick = -1;               /* the tick a DSP write belongs to; -1 is game_init */
static FILE *wav;                          /* --wav: the DSP's output */
static unsigned long wav_frames;

static void log_dsp(uint8_t reg, uint8_t value)
{
    if (sound_tick < 0) {
        fprintf(sound_log, "init %02x %02x\n", reg, value);
    } else {
        fprintf(sound_log, "%ld %02x %02x\n", sound_tick, reg, value);
    }
}

#define AUDIO_CHUNK 1024                    /* frames the stream asks for at a time */
#define FIFO_FRAMES (AUDIO_CHUNK * 8)
static AudioStream stream;
static int audio_on;
static int16_t fifo[FIFO_FRAMES * 2];
static unsigned fifo_n;

static void le(FILE *f, unsigned long v, int bytes)
{
    int i;

    for (i = 0; i < bytes; i++) {
        fputc((int)(v >> (8 * i)) & 0xFF, f);
    }
}

static void wav_header(FILE *f, unsigned long frames)
{
    fwrite("RIFF", 1, 4, f);
    le(f, 36 + frames * 4, 4);
    fwrite("WAVEfmt ", 1, 8, f);
    le(f, 16, 4);
    le(f, 1, 2);                            /* PCM */
    le(f, 2, 2);                            /* stereo */
    le(f, SDEVKIT__DSP_RATE, 4);
    le(f, SDEVKIT__DSP_RATE * 4, 4);
    le(f, 4, 2);
    le(f, 16, 2);
    fwrite("data", 1, 4, f);
    le(f, frames * 4, 4);
}

/* The samples of one tick: into the window's stream, or the check's WAV file. */
static void sound_step(void)
{
    static int16_t buf[1200 * 2];
    long long a = (long long)sound_ticks * SDEVKIT__DSP_RATE * 10000 / sound_rate;
    long long b = (long long)(sound_ticks + 1) * SDEVKIT__DSP_RATE * 10000 / sound_rate;
    unsigned n = (unsigned)(b - a), i;

    sound_ticks++;
    sdevkit__dsp_render(buf, n);
    if (wav != NULL) {
        for (i = 0; i < n * 2; i++) {
            le(wav, (unsigned long)(uint16_t)buf[i], 2);
        }
        wav_frames += n;
    }
    if (audio_on) {
        if (fifo_n + n > FIFO_FRAMES) {     /* fast forward: the oldest go */
            unsigned drop = fifo_n + n - FIFO_FRAMES;
            memmove(fifo, fifo + drop * 2, (fifo_n - drop) * 2 * sizeof fifo[0]);
            fifo_n -= drop;
        }
        memcpy(fifo + fifo_n * 2, buf, n * 2 * sizeof buf[0]);
        fifo_n += n;
    }
}

/* The window's stream asks for its next chunk: from the samples made, silence where there are none. */
static void audio_feed(void)
{
    static int16_t chunk[AUDIO_CHUNK * 2];

    while (audio_on && IsAudioStreamProcessed(stream)) {
        unsigned n = fifo_n < AUDIO_CHUNK ? fifo_n : AUDIO_CHUNK;
        memcpy(chunk, fifo, n * 2 * sizeof fifo[0]);
        memset(chunk + n * 2, 0, (AUDIO_CHUNK - n) * 2 * sizeof chunk[0]);
        memmove(fifo, fifo + n * 2, (fifo_n - n) * 2 * sizeof fifo[0]);
        fifo_n -= n;
        UpdateAudioStream(stream, chunk, AUDIO_CHUNK);
    }
}


/* --- the music ------------------------------------------------------------------------------- */
static Music music;
static int music_loaded;

/* The track the game asked for: the old one stops, the new one starts from its file. */
static void music_step(int paused)
{
    if (sdevkit__music.changed) {
        sdevkit__music.changed = 0;
        if (music_loaded) {
            StopMusicStream(music);
            UnloadMusicStream(music);
            music_loaded = 0;
        }
        if (audio_on && sdevkit__music.track >= 0) {
            if (sdevkit__music.track >= sdevkit__music_count) {
                fprintf(stderr, "%s: music track %d, but the project lists %d\n", GAME_NAME, sdevkit__music.track,
                        sdevkit__music_count);
            } else {
                char path[1024];
                snprintf(path, sizeof path, "%s%s", GetApplicationDirectory(), sdevkit__music_files[sdevkit__music.track]);
                music = LoadMusicStream(path);
                if (IsMusicValid(music)) {
                    music.looping = true;
                    PlayMusicStream(music);
                    music_loaded = 1;
                } else {
                    fprintf(stderr, "%s: cannot play %s\n", GAME_NAME, path);
                }
            }
        }
    }
    if (music_loaded) {
        if (paused && IsMusicStreamPlaying(music)) {
            PauseMusicStream(music);
        } else if (!paused && !IsMusicStreamPlaying(music)) {
            ResumeMusicStream(music);
        }
        UpdateMusicStream(music);
    }
}

/* --- recordings ------------------------------------------------------------------------------- */
static const struct { const char *name; uint16_t bit; } BUTTONS[] = {
    { "b", 0x8000u }, { "y", 0x4000u }, { "select", 0x2000u }, { "start", 0x1000u },
    { "up", 0x0800u }, { "down", 0x0400u }, { "left", 0x0200u }, { "right", 0x0100u },
    { "a", 0x0080u }, { "x", 0x0040u }, { "l", 0x0020u }, { "r", 0x0010u },
};
#define NBUTTONS (sizeof BUTTONS / sizeof BUTTONS[0])
#define MAX_MARKS 64

/* The session being recorded: pad 1 and the checksum of every tick, and the marks. */
static struct {
    int on;
    uint16_t *pad;
    uint32_t *hash;
    long n, cap;
    long marks[MAX_MARKS];
    int nmarks;
    uint8_t sram[SDEVKIT_SRAM_SIZE > 0 ? SDEVKIT_SRAM_SIZE : 1];   /* the save RAM when it began */
    long debug_tick;                   /* the first tick the debug view changed the game before; -1 none */
    const char *debug_name;
} rec = { .debug_tick = -1 };

/* The save RAM in hex, up to its last byte that is not zero; nothing when all are zero. */
static void write_sram_line(FILE *f, const uint8_t *sram)
{
    long n = SDEVKIT_SRAM_SIZE, i;

    while (n > 0 && sram[n - 1] == 0) {
        n--;
    }
    if (n == 0) {
        return;
    }
    fprintf(f, "sram ");
    for (i = 0; i < n; i++) {
        fprintf(f, "%02x", sram[i]);
    }
    fprintf(f, "\n");
}

static void *grow(void *p, size_t size)
{
    void *q = realloc(p, size);
    if (q == NULL) {
        fprintf(stderr, "%s: out of memory\n", GAME_NAME);
        exit(2);
    }
    return q;
}

static void rec_tick(uint16_t pad, uint32_t hash)
{
    if (rec.n == rec.cap) {
        rec.cap = rec.cap ? rec.cap * 2 : 4096;
        rec.pad = grow(rec.pad, (size_t)rec.cap * sizeof rec.pad[0]);
        rec.hash = grow(rec.hash, (size_t)rec.cap * sizeof rec.hash[0]);
    }
    rec.pad[rec.n] = pad;
    rec.hash[rec.n] = hash;
    rec.n++;
}

/* The session as a check.txt: a press line for every run of ticks with the same buttons held,
 * the marks, and the checksum of every tick. */
static int rec_save(const char *path)
{
    FILE *f = fopen(path, "wb");
    long t, start;
    int i;

    if (f == NULL) {
        fprintf(stderr, "%s: cannot write %s\n", GAME_NAME, path);
        return 0;
    }
    fprintf(f, "# a recording of %s (lib/desktop): pad 1 and the checksum of every tick\n", GAME_NAME);
    fprintf(f, "ticks %ld\n", rec.n);
    if (rec.debug_tick >= 0) {
        fprintf(f, "# the debug view changed %s before tick %ld: from there a replay plays another game\n",
                rec.debug_name, rec.debug_tick);
    }
    write_sram_line(f, rec.sram);
    for (start = 0; start < rec.n; start = t) {
        uint16_t p = rec.pad[start];
        for (t = start + 1; t < rec.n && rec.pad[t] == p; t++) {
        }
        if (p != 0) {
            size_t b;
            fprintf(f, "press %ld %ld", start, t);
            for (b = 0; b < NBUTTONS; b++) {
                if (p & BUTTONS[b].bit) {
                    fprintf(f, " %s", BUTTONS[b].name);
                }
            }
            fprintf(f, "\n");
        }
    }
    for (i = 0; i < rec.nmarks; i++) {
        fprintf(f, "mark %ld\n", rec.marks[i]);
    }
    for (t = 0; t < rec.n; t++) {
        fprintf(f, "hash %ld %08x\n", t, (unsigned)rec.hash[t]);
    }
    fclose(f);
    return 1;
}

/* A check.txt or a recording, read: pad 1 for every tick, the recorded checksums, the marks and
 * the screenshots asked for. */
typedef struct {
    long ticks;
    uint16_t *pad;
    uint32_t *hash;
    uint8_t *has_hash;
    long *shots;
    int nshots;
    long marks[MAX_MARKS];
    int nmarks;
    uint8_t sram[SDEVKIT_SRAM_SIZE > 0 ? SDEVKIT_SRAM_SIZE : 1];   /* the save RAM at power-on */
} Input;

/* A line of a check or a recording; an "sram" line has two hex digits per byte of save RAM. */
static char line[2 * 32768 + 64];

static int hex_digit(char c)
{
    return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
}

static uint16_t button(const char *name);
static long splash_shots[64];
static int splash_nshot;

static long number(const char *path, const char *w)
{
    if (w == NULL) {
        fprintf(stderr, "%s: a line ends too early\n", path);
        exit(2);
    }
    return atol(w);
}

static int load_input(const char *path, Input *in, long default_ticks)
{
    long size;
    FILE *f = fopen(path, "r");

    memset(in, 0, sizeof *in);
    in->ticks = default_ticks;
    if (f == NULL) {
        fprintf(stderr, "%s: no file %s\n", GAME_NAME, path);
        return 0;
    }
    size = 0;                                       /* first: how many ticks the file speaks of */
    while (fgets(line, sizeof line, f) != NULL) {
        char *w = strtok(line, " \t\r\n");
        if (w != NULL && strcmp(w, "ticks") == 0) {
            in->ticks = number(path, strtok(NULL, " \t\r\n"));
        } else if (w != NULL && strcmp(w, "press") == 0) {
            long to;
            number(path, strtok(NULL, " \t\r\n"));
            to = number(path, strtok(NULL, " \t\r\n"));
            if (to > size) {
                size = to;
            }
        }
    }
    if (in->ticks > size) {
        size = in->ticks;
    }
    in->pad = calloc((size_t)size + 1, sizeof in->pad[0]);
    in->hash = calloc((size_t)size + 1, sizeof in->hash[0]);
    in->has_hash = calloc((size_t)size + 1, 1);
    in->shots = calloc((size_t)size + 1, sizeof in->shots[0]);
    if (in->pad == NULL || in->hash == NULL || in->has_hash == NULL || in->shots == NULL) {
        fprintf(stderr, "%s: out of memory\n", GAME_NAME);
        exit(2);
    }
    rewind(f);
    while (fgets(line, sizeof line, f) != NULL) {
        char *w = strtok(line, " \t\r\n");
        if (w == NULL || w[0] == '#') {
            continue;
        }
        if (strcmp(w, "press") == 0) {
            long from = number(path, strtok(NULL, " \t\r\n")), to = number(path, strtok(NULL, " \t\r\n")), t;
            uint16_t bits = 0;
            while ((w = strtok(NULL, " \t\r\n")) != NULL) {
                bits |= button(w);
            }
            for (t = from < 0 ? 0 : from; t < to; t++) {
                in->pad[t] |= bits;
            }
        } else if (strcmp(w, "hash") == 0) {
            long t = number(path, strtok(NULL, " \t\r\n"));
            const char *h = strtok(NULL, " \t\r\n");
            if (t >= 0 && t < size && h != NULL) {
                in->hash[t] = (uint32_t)strtoul(h, NULL, 16);
                in->has_hash[t] = 1;
            }
        } else if (strcmp(w, "mark") == 0 && in->nmarks < MAX_MARKS) {
            in->marks[in->nmarks++] = number(path, strtok(NULL, " \t\r\n"));
        } else if (strcmp(w, "shot") == 0 && in->nshots < size) {
            in->shots[in->nshots++] = number(path, strtok(NULL, " \t\r\n"));
        } else if (strcmp(w, "splash") == 0 && splash_nshot < 64) {
            splash_shots[splash_nshot++] = number(path, strtok(NULL, " \t\r\n"));
        } else if (strcmp(w, "sram") == 0) {
            const char *h = strtok(NULL, " \t\r\n");
            long i, n = h != NULL ? (long)strlen(h) / 2 : 0;
            if (h == NULL || (long)strlen(h) % 2 != 0 || n > SDEVKIT_SRAM_SIZE) {
                fprintf(stderr, "%s: the sram line of %s does not fit the game's %d bytes of save RAM\n",
                        GAME_NAME, path, SDEVKIT_SRAM_SIZE);
                exit(2);
            }
            for (i = 0; i < n; i++) {
                int hi = hex_digit(h[2 * i]), lo = hex_digit(h[2 * i + 1]);
                if (hi < 0 || lo < 0) {
                    fprintf(stderr, "%s: the sram line of %s is not hex\n", GAME_NAME, path);
                    exit(2);
                }
                in->sram[i] = (uint8_t)(hi << 4 | lo);
            }
        }
    }
    fclose(f);
    return 1;
}

/* One frame of the console; returns the bytes lost in it: those written during the picture
 * (in game_tick, which the vertical blank would otherwise forget) and those over the vertical
 * blank's budget (in game_vblank). */
static unsigned frame_step(const uint16_t pad[SDEVKIT_PADS])
{
    sdevkit__frame_stats s;
    unsigned lost;

    sdevkit__dbg_nboxes = 0;
    game_tick(pad);
    {
        uint32_t h = sdevkit__tick_hash();
        if (hashes != NULL) {
            fprintf(hashes, "%ld %08x\n", tick_count, (unsigned)h);
        }
        if (rec.on) {
            rec_tick(pad[0], h);
        }
        last_hash = h;
        tick_count++;
    }
    lost = sdevkit__stats().dma_lost;
    sdevkit__vblank();
    game_vblank();
    sound_tick = tick_count - 1;
    sdevkit__sfx_frame();
    sound_step();
    s = sdevkit__stats();
    picture();
    lost += s.dma_lost;
    s = sdevkit__stats();
    sprite_lines += (unsigned)s.range_over_lines + s.time_over_lines;
    return lost;
}

static void save_png(const char *path)
{
    Image img = { rgba, SDEVKIT__W, SDEVKIT__H, 1, PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 };
    ExportImage(img, path);
}

/* --- the splash screen ------------------------------------------------------------------------ */
static int crt_ok, crt_on;              /* the filter compiled; it is shown */
static int splash_scale;                /* the window's first scale; 0: a check, no window */
static Texture2D splash_texture;
static long splash_count;               /* frames of the splash so far */
static const char *splash_out = ".";

static uint16_t keyboard(void);
static const char *show(Texture2D tex, sdevkit__run_state *run);

/* One frame of the splash (lib/splash/splash.h): the brightness written in the vertical blank,
 * the picture drawn; in the window shown, with the keyboard as pad 1; in a check, the screenshots
 * of the splash it asks for. */
static uint16_t sdevkit__splash_frame(uint8_t brightness)
{
    int i;

    sdevkit__vblank();
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, brightness);
    picture();
    splash_count++;
    if (splash_scale > 0) {
        if (WindowShouldClose()) {
            CloseWindow();
            exit(0);
        }
        show(splash_texture, NULL);
        return keyboard();
    }
    for (i = 0; i < splash_nshot; i++) {
        if (splash_shots[i] == splash_count) {
            char name[512];
            snprintf(name, sizeof name, "%s/example_splash_%ld.png", splash_out, splash_count);
            save_png(name);
        }
    }
    return 0;
}

/* The splash's sprites (lib/splash/splash.h): into the sprite table's copy, which Rachael's vertical
 * blank shows; the reset after the splash clears it again. */
static void sdevkit__splash_sprites(const uint8_t *low, uint16_t bytes, const uint8_t *high)
{
    memcpy(sdevkit_oam_memory, low, bytes);
    memcpy(sdevkit_oam_memory + 512, high, 32);
}

#include "../splash/splash.h"

/* Power on with this save RAM (null: as it is): the splash, the console reset again, then the
 * game. The recording begins with the save RAM of this moment. */
static void start(const uint8_t *sram)
{
#if SDEVKIT_SRAM_SIZE > 0
    if (sram != NULL) {
        memcpy(sdevkit_sram(), sram, SDEVKIT_SRAM_SIZE);
    }
    memcpy(rec.sram, sdevkit_sram(), SDEVKIT_SRAM_SIZE);
#else
    (void)sram;
#endif
    sdevkit__reset();
    sdevkit__splash();
    sdevkit__reset();
    sdevkit__dbg_reset();
    sdevkit__music_reset();
    game_init(0);
}

/* --- the save RAM file ------------------------------------------------------------------------ */
#if SDEVKIT_SRAM_SIZE > 0
static char sram_path[1024];                /* <game>.srm next to the program; empty: not kept */
static uint8_t sram_saved[SDEVKIT_SRAM_SIZE];   /* what the file holds */

/* Reads the file into the save RAM: what it has, the rest zero. */
static void sram_load(void)
{
    FILE *f;

    snprintf(sram_path, sizeof sram_path, "%s%s.srm", GetApplicationDirectory(), GAME_NAME);
    memset(sdevkit_sram(), 0, SDEVKIT_SRAM_SIZE);
    f = fopen(sram_path, "rb");
    if (f != NULL) {
        size_t n = fread(sdevkit_sram(), 1, SDEVKIT_SRAM_SIZE, f);
        (void)n;
        fclose(f);
    }
    memcpy(sram_saved, sdevkit_sram(), SDEVKIT_SRAM_SIZE);
}

/* Writes the save RAM into the file when it is not what the file holds. */
static void sram_store(void)
{
    FILE *f;

    if (sram_path[0] == 0 || memcmp(sram_saved, sdevkit_sram(), SDEVKIT_SRAM_SIZE) == 0) {
        return;
    }
    f = fopen(sram_path, "wb");
    if (f == NULL || fwrite(sdevkit_sram(), 1, SDEVKIT_SRAM_SIZE, f) != SDEVKIT_SRAM_SIZE) {
        fprintf(stderr, "%s: cannot write the save RAM to %s\n", GAME_NAME, sram_path);
        sram_path[0] = 0;
    } else {
        memcpy(sram_saved, sdevkit_sram(), SDEVKIT_SRAM_SIZE);
    }
    if (f != NULL) {
        fclose(f);
    }
}
#endif

/* --- the window ------------------------------------------------------------------------------ */
/* The picture, with the filter or without, and the debug view when it is open, into a window (or a
 * texture) of w x h: the picture fills what the panel leaves as far as its shape allows, centred;
 * the rest stays black. Returns what the debug view changed. */
static const char *draw_frame(Texture2D tex, float w, float h, int filter, sdevkit__run_state *run)
{
    static sdevkit__run_state none;
    float pw = sdevkit__debug_is_open() ? w - SDEVKIT__PANEL_W : w;
    float s = pw / SDEVKIT__W < h / SDEVKIT__H ? pw / SDEVKIT__W : h / SDEVKIT__H;
    float dw = SDEVKIT__W * s, dh = SDEVKIT__H * s;
    Rectangle dst = { (pw - dw) / 2, (h - dh) / 2, dw, dh };

    ClearBackground(BLACK);
    if (filter) {
        sdevkit__crt_draw(dst, s);
    } else {
        DrawTexturePro(tex, (Rectangle){ 0, 0, SDEVKIT__W, SDEVKIT__H }, dst, (Vector2){ 0, 0 }, 0, WHITE);
    }
    return sdevkit__debug_draw(dst, s, w, h, run != NULL ? run : &none);
}

static const char *show(Texture2D tex, sdevkit__run_state *run)
{
    float w = (float)GetScreenWidth(), h = (float)GetScreenHeight();
    float pw = sdevkit__debug_is_open() ? w - SDEVKIT__PANEL_W : w;
    float s = pw / SDEVKIT__W < h / SDEVKIT__H ? pw / SDEVKIT__W : h / SDEVKIT__H;
    const char *changed;

    UpdateTexture(tex, rgba);
    if (crt_on) {
        sdevkit__crt_prepare(tex, s);
    }
    BeginDrawing();
    changed = draw_frame(tex, w, h, crt_on, run);
    EndDrawing();
    if (IsKeyPressed(KEY_TAB) && crt_ok) {
        crt_on = !crt_on;
    }
    sdevkit__debug_keys();
    return changed;
}

static uint16_t keyboard(void)
{
    uint16_t p = 0;

    if (IsKeyDown(KEY_UP)) p |= 0x0800u;
    if (IsKeyDown(KEY_DOWN)) p |= 0x0400u;
    if (IsKeyDown(KEY_LEFT)) p |= 0x0200u;
    if (IsKeyDown(KEY_RIGHT)) p |= 0x0100u;
    if (IsKeyDown(KEY_Z)) p |= 0x8000u;           /* B */
    if (IsKeyDown(KEY_X)) p |= 0x0080u;           /* A */
    if (IsKeyDown(KEY_A)) p |= 0x4000u;           /* Y */
    if (IsKeyDown(KEY_S)) p |= 0x0040u;           /* X */
    if (IsKeyDown(KEY_Q)) p |= 0x0020u;           /* L */
    if (IsKeyDown(KEY_W)) p |= 0x0010u;           /* R */
    if (IsKeyDown(KEY_ENTER)) p |= 0x1000u;       /* Start */
    if (IsKeyDown(KEY_RIGHT_SHIFT) || IsKeyDown(KEY_BACKSPACE)) p |= 0x2000u;   /* Select */
    if (IsGamepadAvailable(0)) {
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_FACE_UP)) p |= 0x0800u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_FACE_DOWN)) p |= 0x0400u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_FACE_LEFT)) p |= 0x0200u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_FACE_RIGHT)) p |= 0x0100u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_RIGHT_FACE_DOWN)) p |= 0x8000u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_RIGHT_FACE_RIGHT)) p |= 0x0080u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_RIGHT_FACE_LEFT)) p |= 0x4000u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_RIGHT_FACE_UP)) p |= 0x0040u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_TRIGGER_1)) p |= 0x0020u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_RIGHT_TRIGGER_1)) p |= 0x0010u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_MIDDLE_RIGHT)) p |= 0x1000u;
        if (IsGamepadButtonDown(0, GAMEPAD_BUTTON_MIDDLE_LEFT)) p |= 0x2000u;
    }
    return p;
}

/* Register writes Rachael does not draw (sdevkit__undrawn): the SNES would show another picture. */
static void report_undrawn(FILE *out)
{
    if (sdevkit__rachael.undrawn > 0) {
        fprintf(out, "%s: %u register writes Rachael does not draw, the first $21%02X = $%02X "
                     "(the SNES would show something else)\n",
                GAME_NAME, sdevkit__rachael.undrawn, sdevkit__rachael.undrawn_reg, sdevkit__rachael.undrawn_value);
    }
}

/* DSP writes Rachael does not play (sdevkit__sound.unheard): the SNES would sound different. */
static void report_unheard(FILE *out)
{
    if (sdevkit__sound.unheard > 0) {
        fprintf(out, "%s: %u DSP writes Rachael does not play, the first $%02X = $%02X "
                     "(docs/sound.md: what Rachael's DSP plays)\n",
                GAME_NAME, sdevkit__sound.unheard, sdevkit__sound.unheard_reg, sdevkit__sound.unheard_value);
    }
}

/* The window's title: the game, the tick, and what the debugger does. */
static void title(const sdevkit__run_state *run, int replaying)
{
    char t[200];

    snprintf(t, sizeof t, "%s (superdevkit) - tick %ld%s%s%s%s%s", GAME_NAME, tick_count,
             replaying ? " - replay" : "", run->paused ? " - paused (F5 on, F6 a tick)" : "",
             run->fast ? " - fast" : "", run->slow ? " - slow" : "",
             rec.debug_tick >= 0 ? " - changed in the debug view" : "");
    SetWindowTitle(t);
}

/* F11: a mark at this tick, and the session so far into the next free <game>_rec_<n>.txt. */
static void flag(void)
{
    char name[64];
    int n;
    FILE *f;

    if (rec.nmarks < MAX_MARKS) {
        rec.marks[rec.nmarks++] = tick_count;
    }
    for (n = 0; n < 1000; n++) {
        snprintf(name, sizeof name, "%s_rec_%d.txt", GAME_NAME, n);
        f = fopen(name, "r");
        if (f == NULL) {
            break;
        }
        fclose(f);
    }
    if (rec_save(name)) {
        fprintf(stderr, "%s: tick %ld flagged, the recording is %s\n", GAME_NAME, tick_count, name);
    }
}

static int window(int scale, int no_crt, const char *replay_file)
{
    Texture2D tex;
    unsigned lost = 0;
    int shots = 0;
    Input replay;
    int replaying = 0, reported = 0, next_mark = 0;
    long shown_tick = -1, frames = 0;
    sdevkit__run_state run = { 0 };

    if (replay_file != NULL) {
        int m;
        if (!load_input(replay_file, &replay, 0)) {
            return 2;
        }
        replaying = 1;
        for (m = 0; m < replay.nmarks && rec.nmarks < MAX_MARKS; m++) {
            rec.marks[rec.nmarks++] = replay.marks[m];   /* a new recording keeps the old marks */
        }
    }
    SetTraceLogLevel(LOG_WARNING);
    SetConfigFlags(FLAG_WINDOW_RESIZABLE);
    InitWindow(SDEVKIT__W * scale, SDEVKIT__H * scale, GAME_NAME " (superdevkit)");
    SetTargetFPS(60);
    {
        Image img = { rgba, SDEVKIT__W, SDEVKIT__H, 1, PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 };
        tex = LoadTextureFromImage(img);
    }
    crt_ok = sdevkit__crt_load();
    crt_on = crt_ok && !no_crt;
    sound_rate = 600000;                    /* the window's 60 frames a second */
    InitAudioDevice();
    if (IsAudioDeviceReady()) {
        SetAudioStreamBufferSizeDefault(AUDIO_CHUNK);
        stream = LoadAudioStream(SDEVKIT__DSP_RATE, 16, 2);
        PlayAudioStream(stream);
        audio_on = 1;
    }
    splash_texture = tex;
    splash_scale = scale;
    rec.on = 1;
#if SDEVKIT_SRAM_SIZE > 0
    if (!replaying) {
        sram_load();                        /* a replay starts with its own and keeps no file */
    }
#endif
    start(replaying ? replay.sram : NULL);
    while (!WindowShouldClose()) {
        int steps;
        int k;
        const char *changed;
        if (IsKeyPressed(KEY_F6)) {
            run.step = 1;
        }
        steps = run.paused ? (run.step ? 1 : 0) : run.fast ? 8 : run.slow ? (frames % 4 == 0) : 1;
        run.step = 0;
        frames++;
        if (IsKeyPressed(KEY_F5)) {
            run.paused = !run.paused;
        }
        if (IsKeyPressed(KEY_F7)) {
            run.fast = !run.fast;
            run.slow = 0;
        }
        if (IsKeyPressed(KEY_F8)) {
            run.slow = !run.slow;
            run.fast = 0;
        }
        if (IsKeyPressed(KEY_F11)) {
            flag();
        }
        for (k = 0; k < steps; k++) {
            uint16_t pad[SDEVKIT_PADS] = { 0, 0 };
            long t = tick_count;
            if (replaying && t >= replay.ticks) {
                replaying = 0;                      /* the recording is over: the keyboard plays on */
                fprintf(stderr, "%s: the replay ended at tick %ld\n", GAME_NAME, t);
            }
            pad[0] = replaying ? replay.pad[t] : keyboard();
            lost += frame_step(pad);
            if (replaying && replay.has_hash[t] && replay.hash[t] != last_hash && !reported) {
                fprintf(stderr, "%s: tick %ld has checksum %08x, the recording %08x: from here it plays "
                                "another game\n", GAME_NAME, t, (unsigned)last_hash, (unsigned)replay.hash[t]);
                reported = 1;
                run.paused = 1;
            }
            if (replaying && next_mark < replay.nmarks && tick_count == replay.marks[next_mark]) {
                fprintf(stderr, "%s: the mark at tick %ld\n", GAME_NAME, tick_count);
                next_mark++;
                run.paused = 1;
            }
            if (run.paused) {
                break;
            }
        }
        audio_feed();
        music_step(run.paused);
        run.tick = tick_count;
        run.lost = lost;
        changed = show(tex, &run);
        if (changed != NULL && rec.debug_tick < 0) {
            rec.debug_tick = tick_count;
            rec.debug_name = changed;
            fprintf(stderr, "%s: the debug view changed %s before tick %ld: a replay of this session plays "
                            "another game from there\n", GAME_NAME, changed, tick_count);
            shown_tick = -1;
        }
        if (tick_count != shown_tick || IsKeyPressed(KEY_F5) || IsKeyPressed(KEY_F7) || IsKeyPressed(KEY_F8)
            || run.step) {
            if ((tick_count & 15) == 0 || run.paused || run.fast || run.slow || tick_count - shown_tick != 1) {
                title(&run, replaying);
            }
            shown_tick = tick_count;
        }
        if (IsKeyPressed(KEY_F12)) {
            char name[64];
            snprintf(name, sizeof name, "%s_%d.png", GAME_NAME, shots++);
            save_png(name);
        }
#if SDEVKIT_SRAM_SIZE > 0
        if (tick_count % 30 == 0) {
            sram_store();
        }
#endif
    }
#if SDEVKIT_SRAM_SIZE > 0
    sram_store();
#endif
    sdevkit__debug_unload();
    sdevkit__crt_unload();
    UnloadTexture(tex);
    if (music_loaded) {
        UnloadMusicStream(music);
    }
    if (audio_on) {
        UnloadAudioStream(stream);
        CloseAudioDevice();
    }
    CloseWindow();
    if (lost > 0) {
        fprintf(stderr, "%s: %u bytes would not have reached the PPU on the SNES\n", GAME_NAME, lost);
    }
    report_undrawn(stderr);
    report_unheard(stderr);
    return 0;
}

/* --- without a window: a check ---------------------------------------------------------------- */
static uint16_t button(const char *name)
{
    size_t i;

    for (i = 0; i < NBUTTONS; i++) {
        if (strcmp(BUTTONS[i].name, name) == 0) {
            return BUTTONS[i].bit;
        }
    }
    fprintf(stderr, "check: unknown button '%s'\n", name);
    exit(2);
}

/* The debug view after a check, into a PNG: a hidden window (the drawing needs one), the picture
 * and the panel of the tab drawn into a texture of the window's size at scale 3. */
static int view_shot(const char *path, int tab)
{
    const int w = SDEVKIT__W * 3 + SDEVKIT__PANEL_W, h = SDEVKIT__H * 3;
    Texture2D tex;
    RenderTexture2D target;
    Image shot;
    sdevkit__run_state run = { 0 };
    int ok;

    SetConfigFlags(FLAG_WINDOW_HIDDEN);
    InitWindow(w, h, GAME_NAME " (superdevkit) - view shot");
    {
        Image img = { rgba, SDEVKIT__W, SDEVKIT__H, 1, PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 };
        tex = LoadTextureFromImage(img);
    }
    target = LoadRenderTexture(w, h);
    sdevkit__debug_set_open(1, tab);
    run.tick = tick_count;
    run.paused = 1;
    BeginTextureMode(target);
    draw_frame(tex, (float)w, (float)h, 0, &run);
    EndTextureMode();
    BeginTextureMode(target);                /* the tiles' texture is filled while drawing: once more */
    draw_frame(tex, (float)w, (float)h, 0, &run);
    EndTextureMode();
    shot = LoadImageFromTexture(target.texture);
    ImageFlipVertical(&shot);
    ok = ExportImage(shot, path);
    UnloadImage(shot);
    sdevkit__debug_unload();
    UnloadRenderTexture(target);
    UnloadTexture(tex);
    CloseWindow();
    if (!ok) {
        fprintf(stderr, "check: cannot write %s\n", path);
        return 2;
    }
    return 0;
}

/* Plays the input of a check.txt (or a recording) without a window. With a recording's
 * checksums, a tick that differs is reported, and the exit status is 1. */
static int check(const char *path, const char *out, const char *record, const char *wav_file)
{
    Input in;
    long f, differs = -1;
    unsigned lost = 0;

    if (!load_input(path, &in, 600)) {
        return 2;
    }
    splash_out = out;
    {
        char name[512];
        snprintf(name, sizeof name, "%s/hashes.txt", out);
        hashes = fopen(name, "wb");   /* "b": the same bytes on Windows, without CR */
        if (hashes == NULL) {
            fprintf(stderr, "check: cannot write %s\n", name);
            return 2;
        }
    }
    {
        char name[512];
        snprintf(name, sizeof name, "%s/sound.txt", out);
        sound_log = fopen(name, "wb");
        if (sound_log == NULL) {
            fprintf(stderr, "check: cannot write %s\n", name);
            return 2;
        }
        sdevkit__sound.log = log_dsp;
    }
    if (wav_file != NULL) {
        wav = fopen(wav_file, "wb");
        if (wav == NULL) {
            fprintf(stderr, "check: cannot write %s\n", wav_file);
            return 2;
        }
        wav_header(wav, 0);
    }
    rec.on = record != NULL;
    sound_tick = -1;
    start(in.sram);
    fprintf(sound_log, "aram %08x\n", (unsigned)sdevkit__sound.upload_hash);
    for (f = 0; f < in.ticks; f++) {
        uint16_t pad[SDEVKIT_PADS] = { 0, 0 };
        unsigned l;
        int i;
        pad[0] = in.pad[f];
        l = frame_step(pad);
        if (l > 0) {
            printf("lost frame %ld: %u bytes\n", f + 1, l);
        }
        lost += l;
        if (differs < 0 && in.has_hash[f] && in.hash[f] != last_hash) {
            differs = f;
            printf("tick %ld has checksum %08x, the recording %08x\n", f, (unsigned)last_hash, (unsigned)in.hash[f]);
        }
        for (i = 0; i < in.nshots; i++) {
            if (in.shots[i] == f + 1) {
                char name[512];
                snprintf(name, sizeof name, "%s/example_shot_%ld.png", out, f + 1);
                save_png(name);
            }
        }
    }
    fclose(hashes);
    hashes = NULL;
    sdevkit__sound.log = NULL;
    fclose(sound_log);
    sound_log = NULL;
    if (wav != NULL) {
        fseek(wav, 0, SEEK_SET);
        wav_header(wav, wav_frames);
        fclose(wav);
        wav = NULL;
    }
    if (record != NULL) {
        int i;
        for (i = 0; i < in.nmarks && rec.nmarks < MAX_MARKS; i++) {
            rec.marks[rec.nmarks++] = in.marks[i];
        }
        if (!rec_save(record)) {
            return 2;
        }
    }
    report_undrawn(stdout);
    report_unheard(stdout);
    printf("frames %ld ticks %ld lost %u undrawn %u unheard %u sprites %u\n", in.ticks, in.ticks, lost,
           sdevkit__rachael.undrawn, sdevkit__sound.unheard, sprite_lines);
    return lost > 0 || sdevkit__rachael.undrawn > 0 || sdevkit__sound.unheard > 0 || differs >= 0 ? 1 : 0;
}

int main(int argc, char **argv)
{
    const char *check_file = NULL;
    const char *replay_file = NULL;
    const char *record_file = NULL;
    const char *wav_file = NULL;
    const char *view_file = NULL;
    const char *out = ".";
    int view_tab = 0;
    int scale = 3;
    int no_crt = 0;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--check") == 0 && i + 1 < argc) {
            check_file = argv[++i];
        } else if (strcmp(argv[i], "--replay") == 0 && i + 1 < argc) {
            replay_file = argv[++i];
        } else if (strcmp(argv[i], "--record") == 0 && i + 1 < argc) {
            record_file = argv[++i];
        } else if (strcmp(argv[i], "--wav") == 0 && i + 1 < argc) {
            wav_file = argv[++i];
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out = argv[++i];
        } else if (strcmp(argv[i], "--scale") == 0 && i + 1 < argc) {
            scale = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--view-shot") == 0 && i + 1 < argc) {
            view_file = argv[++i];
        } else if (strcmp(argv[i], "--view-tab") == 0 && i + 1 < argc) {
            view_tab = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--no-crt") == 0) {
            no_crt = 1;
        } else {
            fprintf(stderr, "usage: %s [--scale N] [--no-crt] [--replay recording.txt]\n"
                            "       %s --check check.txt [--out dir] [--record recording.txt] [--wav sound.wav]\n"
                            "                 [--view-shot view.png [--view-tab 0..3]]\n", argv[0], argv[0]);
            return 2;
        }
    }
    if (check_file != NULL) {
        int status;
        SetTraceLogLevel(LOG_WARNING);
        status = check(check_file, out, record_file, wav_file);
        if (view_file != NULL) {
            int v = view_shot(view_file, view_tab);
            if (v != 0) {
                status = v;
            }
        }
        return status;
    }
    return window(scale < 1 ? 1 : scale, no_crt, replay_file);
}
