/*
 * The desktop frontend of superdevkit (Linux now, Windows next; ADR record 32): a window with
 * raylib, the keyboard and a gamepad as the SNES joypad, and the frame rhythm of snes/loop.h on
 * the pseudo SNES (lib/pseudo):
 *
 *   game_tick(pads)     the picture is being drawn: VRAM and CGRAM take nothing now
 *   snes_vblank()       the vertical blank begins: the sprite table is copied
 *   game_vblank()       the game's uploads
 *   snes_render()       the picture of the frame, which ends the vertical blank
 *
 * The pseudo SNES counts every byte that would not arrive on the console (written outside the
 * vertical blank, or more than one vertical blank can copy), so the desktop finds what the SNES
 * would lose.
 *
 * With --check <file> it runs without a window, with the input of the file (the format of the
 * examples' check.txt: "ticks N", "press T1 T2 buttons...", "shot T"; on the desktop every frame is
 * one tick), writes the screenshots it
 * asks for as PNG into the folder of --out (default .), and prints "frames N ticks N lost L";
 * the exit status is 1 when a byte was lost.
 *
 * This file is host code for the desktop's C compiler, not for dampf.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "snes/loop.h"
#include "snes/snes.h"
/* raylib names its keyboard keys KEY_UP, KEY_A ...: the joypad bits of loop.h go, this file
 * writes them as numbers */
#undef KEY_B
#undef KEY_Y
#undef KEY_SELECT
#undef KEY_START
#undef KEY_UP
#undef KEY_DOWN
#undef KEY_LEFT
#undef KEY_RIGHT
#undef KEY_A
#undef KEY_X
#undef KEY_L
#undef KEY_R
#include "raylib.h"

#ifndef GAME_NAME
#define GAME_NAME "superdevkit"
#endif

static unsigned char rgba[SNES_W * SNES_H * 4];

/* The picture of the pseudo SNES (BGR 5:5:5) as RGBA. */
static void picture(void)
{
    static u16 frame[SNES_W * SNES_H];
    int i;

    snes_render(frame);
    for (i = 0; i < SNES_W * SNES_H; i++) {
        u16 c = frame[i];
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        rgba[i * 4 + 0] = (unsigned char)((r << 3) | (r >> 2));
        rgba[i * 4 + 1] = (unsigned char)((g << 3) | (g >> 2));
        rgba[i * 4 + 2] = (unsigned char)((b << 3) | (b >> 2));
        rgba[i * 4 + 3] = 255;
    }
}

/* One frame of the console; returns the bytes lost in it. */
static unsigned frame_step(const uint16_t pad[SNES_PADS])
{
    SnesStats s;

    game_tick(pad);
    snes_vblank();
    game_vblank();
    s = snes_stats();
    picture();
    return s.dma_lost;
}

static void save_png(const char *path)
{
    Image img = { rgba, SNES_W, SNES_H, 1, PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 };
    ExportImage(img, path);
}

/* --- the window ------------------------------------------------------------------------------ */
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

static int window(int scale)
{
    Texture2D tex;
    unsigned lost = 0;
    int shots = 0;

    SetTraceLogLevel(LOG_WARNING);
    InitWindow(SNES_W * scale, SNES_H * scale, GAME_NAME " (superdevkit)");
    SetTargetFPS(60);
    {
        Image img = { rgba, SNES_W, SNES_H, 1, PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 };
        tex = LoadTextureFromImage(img);
    }
    while (!WindowShouldClose()) {
        uint16_t pad[SNES_PADS] = { 0, 0 };
        pad[0] = keyboard();
        lost += frame_step(pad);
        UpdateTexture(tex, rgba);
        BeginDrawing();
        ClearBackground(BLACK);
        DrawTexturePro(tex, (Rectangle){ 0, 0, SNES_W, SNES_H },
                       (Rectangle){ 0, 0, (float)(SNES_W * scale), (float)(SNES_H * scale) }, (Vector2){ 0, 0 }, 0, WHITE);
        EndDrawing();
        if (IsKeyPressed(KEY_F12)) {
            char name[64];
            snprintf(name, sizeof name, "%s_%d.png", GAME_NAME, shots++);
            save_png(name);
        }
    }
    UnloadTexture(tex);
    CloseWindow();
    if (lost > 0) {
        fprintf(stderr, "%s: %u bytes would not have reached the PPU on the SNES\n", GAME_NAME, lost);
    }
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

static int check(const char *path, const char *out)
{
    static Press presses[256];
    static long shots[256];
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
        } else if (strcmp(w, "shot") == 0 && nshot < 256) {
            shots[nshot++] = atol(strtok(NULL, " \t\r\n"));
        } else if (strcmp(w, "press") == 0 && npress < 256) {
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
    for (f = 0; f < frames; f++) {
        uint16_t pad[SNES_PADS] = { 0, 0 };
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
    printf("frames %ld ticks %ld lost %u\n", frames, frames, lost);
    return lost > 0 ? 1 : 0;
}

int main(int argc, char **argv)
{
    const char *check_file = NULL;
    const char *out = ".";
    int scale = 3;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--check") == 0 && i + 1 < argc) {
            check_file = argv[++i];
        } else if (strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out = argv[++i];
        } else if (strcmp(argv[i], "--scale") == 0 && i + 1 < argc) {
            scale = atoi(argv[++i]);
        } else {
            fprintf(stderr, "usage: %s [--scale N] [--check check.txt [--out dir]]\n", argv[0]);
            return 2;
        }
    }
    snes_reset();
    game_init(0);
    if (check_file != NULL) {
        SetTraceLogLevel(LOG_WARNING);
        return check(check_file, out);
    }
    return window(scale < 1 ? 1 : scale);
}
