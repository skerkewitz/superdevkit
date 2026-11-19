/*
 * The desktop frontend of superdevkit (Linux and Windows; ADR records 32 and 39): a window with
 * raylib, the keyboard and a gamepad as the SNES joypad, and the frame rhythm of sdevkit/loop.h
 * on Rachael (lib/rachael):
 *
 *   game_tick(pads)     the picture is being drawn: VRAM and CGRAM take nothing now
 *   sdevkit__vblank()   the vertical blank begins: the sprite table is copied
 *   game_vblank()       the game's uploads
 *   sdevkit__render()   the picture of the frame, which ends the vertical blank
 *
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
 * With --check <file> it runs without a window, with the input of the file (the format of the
 * examples' check.txt: "ticks N", "press T1 T2 buttons...", "shot T", "splash N"; on the desktop
 * every frame is one tick, and the splash comes before them, without input), writes the screenshots it
 * asks for as PNG into the folder of --out (default .), the checksum of every tick
 * (sdevkit_dbg_hash: "tick hash" in hex, one a line) into hashes.txt there, and prints "frames N ticks N lost L
 * undrawn U sprites S"; the exit status is 1 when a byte was lost or a register write was one
 * Rachael does not draw (sdevkit__undrawn). S counts the lines over the sprite limits (32 sprites,
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
        tick_count++;
    }
    lost = sdevkit__stats().dma_lost;
    sdevkit__vblank();
    game_vblank();
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
static long splash_shots[64];
static int splash_nshot;

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

static int window(int scale, int no_crt)
{
    Texture2D tex;
    unsigned lost = 0;
    int shots = 0;

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
    splash_texture = tex;
    splash_scale = scale;
    start();
    while (!WindowShouldClose()) {
        uint16_t pad[SDEVKIT_PADS] = { 0, 0 };
        pad[0] = keyboard();
        lost += frame_step(pad);
        show(tex);
        if (IsKeyPressed(KEY_F12)) {
            char name[64];
            snprintf(name, sizeof name, "%s_%d.png", GAME_NAME, shots++);
            save_png(name);
        }
    }
    sdevkit__crt_unload();
    UnloadTexture(tex);
    CloseWindow();
    if (lost > 0) {
        fprintf(stderr, "%s: %u bytes would not have reached the PPU on the SNES\n", GAME_NAME, lost);
    }
    report_undrawn(stderr);
    return 0;
}

/* --- without a window: a check ---------------------------------------------------------------- */
typedef struct {
    long from, to;
    uint16_t buttons;
} Press;

static uint16_t button(const char *name)
{
    static const struct { const char *name; uint16_t bit; } names[] = {
        { "b", 0x8000u }, { "y", 0x4000u }, { "select", 0x2000u }, { "start", 0x1000u },
        { "up", 0x0800u }, { "down", 0x0400u }, { "left", 0x0200u }, { "right", 0x0100u },
        { "a", 0x0080u }, { "x", 0x0040u }, { "l", 0x0020u }, { "r", 0x0010u },
    };
    size_t i;

    for (i = 0; i < sizeof names / sizeof names[0]; i++) {
        if (strcmp(names[i].name, name) == 0) {
            return names[i].bit;
        }
    }
    fprintf(stderr, "check: unknown button '%s'\n", name);
    exit(2);
}

#define CHECK_LINES 4096                /* press and shot lines a check may have, each */

static int check(const char *path, const char *out)
{
    static Press presses[CHECK_LINES];
    static long shots[CHECK_LINES];
    int npress = 0, nshot = 0;
    long frames = 600, f;
    unsigned lost = 0;
    char line[256];
    FILE *in = fopen(path, "r");

    if (in == NULL) {
        fprintf(stderr, "check: no file %s\n", path);
        return 2;
    }
    while (fgets(line, sizeof line, in) != NULL) {
        char *w = strtok(line, " \t\r\n");
        if (w == NULL) {
            continue;
        }
        if (strcmp(w, "ticks") == 0) {
            frames = atol(strtok(NULL, " \t\r\n"));
        } else if ((strcmp(w, "shot") == 0 && nshot == CHECK_LINES) || (strcmp(w, "press") == 0 && npress == CHECK_LINES)) {
            fprintf(stderr, "check: %s has more than %d %s lines\n", path, CHECK_LINES, w);
            fclose(in);
            return 2;
        } else if (strcmp(w, "shot") == 0) {
            shots[nshot++] = atol(strtok(NULL, " \t\r\n"));
        } else if (strcmp(w, "splash") == 0 && splash_nshot < 64) {
            splash_shots[splash_nshot++] = atol(strtok(NULL, " \t\r\n"));
        } else if (strcmp(w, "press") == 0) {
            Press *p = &presses[npress++];
            p->from = atol(strtok(NULL, " \t\r\n"));
            p->to = atol(strtok(NULL, " \t\r\n"));
            p->buttons = 0;
            while ((w = strtok(NULL, " \t\r\n")) != NULL) {
                p->buttons |= button(w);
            }
        }
    }
    fclose(in);
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
    start();
    for (f = 0; f < frames; f++) {
        uint16_t pad[SDEVKIT_PADS] = { 0, 0 };
        unsigned l;
        int i;
        for (i = 0; i < npress; i++) {
            if (f >= presses[i].from && f < presses[i].to) {
                pad[0] |= presses[i].buttons;
            }
        }
        l = frame_step(pad);
        if (l > 0) {
            printf("lost frame %ld: %u bytes\n", f + 1, l);
        }
        lost += l;
        for (i = 0; i < nshot; i++) {
            if (shots[i] == f + 1) {
                char name[512];
                snprintf(name, sizeof name, "%s/example_shot_%ld.png", out, f + 1);
                save_png(name);
            }
        }
    }
    fclose(hashes);
    hashes = NULL;
    report_undrawn(stdout);
    printf("frames %ld ticks %ld lost %u undrawn %u sprites %u\n", frames, frames, lost, sdevkit__rachael.undrawn,
           sprite_lines);
    return lost > 0 || sdevkit__rachael.undrawn > 0 ? 1 : 0;
}

int main(int argc, char **argv)
{
    const char *check_file = NULL;
    const char *out = ".";
    int scale = 3;
    int no_crt = 0;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--check") == 0 && i + 1 < argc) {
            check_file = argv[++i];
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out = argv[++i];
        } else if (strcmp(argv[i], "--scale") == 0 && i + 1 < argc) {
            scale = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--no-crt") == 0) {
            no_crt = 1;
        } else {
            fprintf(stderr, "usage: %s [--scale N] [--no-crt] [--check check.txt [--out dir]]\n", argv[0]);
            return 2;
        }
    }
    if (check_file != NULL) {
        SetTraceLogLevel(LOG_WARNING);
        return check(check_file, out);
    }
    return window(scale < 1 ? 1 : scale, no_crt);
}
