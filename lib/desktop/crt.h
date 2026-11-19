/* The retro TV filter of the desktop frontend (crt.c, RETRO-EFFECT.md). */
#ifndef SDEVKIT_DESKTOP_CRT_H
#define SDEVKIT_DESKTOP_CRT_H

#include "raylib.h"

/* Compiles the shaders and makes the textures, after InitWindow; 0 when a shader failed (then
 * the window is drawn without the filter, and the log says so). */
int sdevkit__crt_load(void);
void sdevkit__crt_unload(void);
/* Stages 1 and 2 for this frame, from Rachael's 256 x 224 picture: before BeginDrawing. */
void sdevkit__crt_prepare(Texture2D frame, float scale);
/* Stage 3: the prepared picture into dst, scale window pixels per game pixel; between
 * BeginDrawing and EndDrawing. */
void sdevkit__crt_draw(Rectangle dst, float scale);

#endif
