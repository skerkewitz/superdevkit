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
 * play through raylib's audio stream; a check writes them to a WAV file with --wav file.
 * Before game_init, as on the SNES, the splash screen (lib/splash/splash.h): its frames are drawn
 * and shown like the game's, then the console is reset again.
 *
 * Rachael counts every byte that would not arrive on the console (written outside the
 * vertical blank, or more than one vertical blank can copy), so the desktop finds what the SNES
 * would lose.
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
 * goes on, F6 steps one tick while paused, F7 runs eight ticks a frame. With --check,
 * --record file writes the recording of the check's run.
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
} rec;

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
} Input;

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
    char line[512];
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
static void show(Texture2D tex);

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
        show(splash_texture);
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

/* Power on: the splash, the console reset again, then the game. */
static void start(void)
{
    sdevkit__reset();
    sdevkit__splash();
    sdevkit__reset();
    game_init(0);
}

/* --- the window ------------------------------------------------------------------------------ */
static void show(Texture2D tex)
{
    /* The picture fills the window as far as its shape allows, centred; the rest stays black. */
    float w = (float)GetScreenWidth(), h = (float)GetScreenHeight();
    float s = w / SDEVKIT__W < h / SDEVKIT__H ? w / SDEVKIT__W : h / SDEVKIT__H;
    float dw = SDEVKIT__W * s, dh = SDEVKIT__H * s;
    Rectangle dst = { (w - dw) / 2, (h - dh) / 2, dw, dh };

    UpdateTexture(tex, rgba);
    if (crt_on) {
        sdevkit__crt_prepare(tex, s);
    }
    BeginDrawing();
    ClearBackground(BLACK);
    if (crt_on) {
        sdevkit__crt_draw(dst, s);
    } else {
        DrawTexturePro(tex, (Rectangle){ 0, 0, SDEVKIT__W, SDEVKIT__H }, dst, (Vector2){ 0, 0 }, 0, WHITE);
    }
    EndDrawing();
    if (IsKeyPressed(KEY_TAB) && crt_ok) {
        crt_on = !crt_on;
    }
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
static void title(int paused, int fast, int replaying)
{
    char t[160];

    snprintf(t, sizeof t, "%s (superdevkit) - tick %ld%s%s%s", GAME_NAME, tick_count,
             replaying ? " - replay" : "", paused ? " - paused (F5 on, F6 a tick)" : "", fast ? " - fast" : "");
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
    int replaying = 0, paused = 0, fast = 0, reported = 0, next_mark = 0;
    long shown_tick = -1;

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
    start();
    while (!WindowShouldClose()) {
        int steps = paused ? (IsKeyPressed(KEY_F6) ? 1 : 0) : fast ? 8 : 1;
        int k;
        if (IsKeyPressed(KEY_F5)) {
            paused = !paused;
        }
        if (IsKeyPressed(KEY_F7)) {
            fast = !fast;
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
                paused = 1;
            }
            if (replaying && next_mark < replay.nmarks && tick_count == replay.marks[next_mark]) {
                fprintf(stderr, "%s: the mark at tick %ld\n", GAME_NAME, tick_count);
                next_mark++;
                paused = 1;
            }
            if (paused) {
                break;
            }
        }
        audio_feed();
        show(tex);
        if (tick_count != shown_tick || IsKeyPressed(KEY_F5) || IsKeyPressed(KEY_F7)) {
            if ((tick_count & 15) == 0 || paused || fast || tick_count - shown_tick != 1) {
                title(paused, fast, replaying);
            }
            shown_tick = tick_count;
        }
        if (IsKeyPressed(KEY_F12)) {
            char name[64];
            snprintf(name, sizeof name, "%s_%d.png", GAME_NAME, shots++);
            save_png(name);
        }
    }
    sdevkit__crt_unload();
    UnloadTexture(tex);
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
    start();
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
    const char *out = ".";
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
        } else if (strcmp(argv[i], "--no-crt") == 0) {
            no_crt = 1;
        } else {
            fprintf(stderr, "usage: %s [--scale N] [--no-crt] [--replay recording.txt]\n"
                            "       %s --check check.txt [--out dir] [--record recording.txt] [--wav sound.wav]\n", argv[0], argv[0]);
            return 2;
        }
    }
    if (check_file != NULL) {
        SetTraceLogLevel(LOG_WARNING);
        return check(check_file, out, record_file, wav_file);
    }
    return window(scale < 1 ? 1 : scale, no_crt, replay_file);
}
