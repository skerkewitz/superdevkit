/*
 * The retro TV filter of the desktop frontend: the picture as a SNES showed it on a consumer CRT
 * over composite video. Taken over from Blubber Blasen's frontend (src/platform/platform_raylib.c),
 * shaders and constants unchanged; RETRO-EFFECT.md beside this file explains each step.
 * Only the window is filtered: Rachael's picture, F12 and --check screenshots stay clean.
 */
#include "crt.h"

#include <stddef.h>

#include "rlgl.h"       /* rlGetShaderIdDefault: detect a failed shader compile */

#include "rachael.h"   /* SDEVKIT__W, SDEVKIT__H: the picture's size */

#define SCREEN_W SDEVKIT__W
#define SCREEN_H SDEVKIT__H

/* --- Retro TV look ------------------------------------------------------------
 * A post-processing chain applied only when the 256x224 framebuffer is scaled
 * into the window; the framebuffer itself (and --shot) stays clean.
 * It imitates a SNES on a consumer CRT over composite video, in the stages a
 * real set has (docs/RETRO-EFFECT.md explains each step and its constants):
 *   1. Signal (signal_fs, into `signal`, 1024x224, fixed size): 15-bit colour
 *      (5 bits per channel, like SNES CGRAM) and the composite blur: luma
 *      soft, chroma bleeds wider (YIQ, horizontal only). 4x oversampled
 *      horizontally so the final pass can read it smoothly between pixels;
 *      stored gamma-encoded so dark tones don't band in 8 bits.
 *   2. Glow (glow_fs, into glow_a then glow_b, 256x224): the light the glass
 *      scatters (halation), blurred from the signal in two passes; it also
 *      carries part of the light the mask clips off bright colours.
 *   3. Screen (crt_fs, at window resolution), everything finer than a game
 *      pixel:
 *      - curved glass: barrel distortion that bows the picture's edges
 *        outward (CURVE). The middle of each edge stays on the window edge
 *        and the rest curves inward, so no game pixel is ever cut off; the
 *        HUD reaches the edges, so there are no rounded corners either
 *      - a round beam spot: each pixel takes the line above, its own and the
 *        line below, each as a bell curve that widens on bright pixels; the
 *        curve keeps its energy, so bright lines fill the gaps (scanlines)
 *      - towards the edges the beam goes out of focus (DEFOCUS, DEFOCUS_H)
 *        and the red and blue beams drift apart from green (CONVERGE), so
 *        each colour channel is drawn by its own beam
 *      - DISTANCE (0 = at the desk, 1 = from the sofa) softens everything
 *        and fades scanlines and mask, the way the eye did from far away
 *      - an aperture-grille mask: R, G, B phosphor stripes with dark gaps.
 *        From MASK_ON_TUBE_FROM (6x) up they follow the curved picture
 *        (TRIADS per game pixel), each stripe box-filtered over the screen
 *        pixel. Below that they are straight, one screen pixel per stripe:
 *        1-pixel stripes on the curve drift against the pixel grid and
 *        show moire bands.
 *      - the glow added on top, then a light vignette.
 * Scanlines, mask and the clipped-light glow fade out below 3x scale, where
 * they would only alias. Tab toggles it; --no-crt starts with it off. */

#define CRT_SIGNAL_W (SCREEN_W * 4)

/* Shared by the screen and glow shaders: the glow pass needs the mask's gain
 * to work out how much light the mask clips. */
#define CRT_MASK_CONSTS \
    "const float MASK_FILL = 0.75;\n" \
    "const float MASK_DARK = 0.1;\n" \
    "const float MASK_GAIN = 1.0 / (MASK_DARK + (1.0 - MASK_DARK) * MASK_FILL / 3.0);\n"

/* Stage 1. texture0 is the framebuffer; fragTexCoord runs over the whole
 * picture, so x * 256 is the position in game pixels. */
#define SIGNAL_SHADER_BODY \
    "uniform sampler2D texture0;\n" \
    "uniform vec4 colDiffuse;\n" \
    "uniform vec2 sourceSize;\n" \
    "const float LUMA_SHARP = 1.4;\n" \
    "const float CHROMA_SHARP = 0.45;\n" \
    "const mat3 toYIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);\n" \
    "const mat3 toRGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);\n" \
    "vec3 fetch(vec2 texel)\n" \
    "{\n" \
    "    vec3 c = TEX(texture0, texel / sourceSize).rgb;\n" \
    "    c = floor(c * 31.0 + 0.5) / 31.0;\n" \
    "    return toYIQ * pow(c, vec3(2.2));\n" \
    "}\n" \
    "void main()\n" \
    "{\n" \
    "    vec2 p = fragTexCoord * sourceSize;\n" \
    "    float row = floor(p.y) + 0.5;\n" \
    "    float cx = floor(p.x) + 0.5;\n" \
    "    vec3 yiq = vec3(0.0);\n" \
    "    vec3 wsum = vec3(0.0);\n" \
    "    for (int i = -2; i <= 2; i++) {\n" \
    "        float tx = cx + float(i);\n" \
    "        float d = tx - p.x;\n" \
    "        vec3 w = vec3(exp(-LUMA_SHARP * d * d), vec2(exp(-CHROMA_SHARP * d * d)));\n" \
    "        yiq += w * fetch(vec2(tx, row));\n" \
    "        wsum += w;\n" \
    "    }\n" \
    "    vec3 rgb = max(toRGB * (yiq / wsum), 0.0);\n" \
    "    OUT = vec4(pow(rgb, vec3(1.0 / 2.2)), 1.0) * colDiffuse * fragColor;\n" \
    "}\n"

/* Stage 2, one blur direction. The first pass (horizontal, reading the
 * signal) turns each pixel into glow light: a faint share of its colour
 * (GLOW) plus a part (OVERFLOW) of what the mask will clip off it, scaled
 * by `overflow`, which follows the mask's fade at small scales. The second
 * pass blurs that vertically. Output is linear light. */
#define GLOW_SHADER_BODY \
    "uniform sampler2D texture0;\n" \
    "uniform vec4 colDiffuse;\n" \
    "uniform vec2 sourceSize;\n" \
    "uniform vec2 dir;\n" \
    "uniform float first;\n" \
    "uniform float overflow;\n" \
    "const float GLOW = 0.03;\n" \
    "const float GLOW_SIZE = 2.0;\n" \
    "const float OVERFLOW = 0.1;\n" \
    CRT_MASK_CONSTS \
    "vec3 src(vec2 uv)\n" \
    "{\n" \
    "    vec3 c = TEX(texture0, uv).rgb;\n" \
    "    if (first > 0.5) {\n" \
    "        c = pow(c, vec3(2.2));\n" \
    "        c = GLOW * c + OVERFLOW * overflow * MASK_FILL / 3.0 * max(c * MASK_GAIN - 1.0, 0.0);\n" \
    "    }\n" \
    "    return c;\n" \
    "}\n" \
    "void main()\n" \
    "{\n" \
    "    vec3 sum = vec3(0.0);\n" \
    "    float wsum = 0.0;\n" \
    "    for (int i = -6; i <= 6; i++) {\n" \
    "        float w = exp(-float(i * i) / (2.0 * GLOW_SIZE * GLOW_SIZE));\n" \
    "        sum += w * src(fragTexCoord + dir * float(i) / sourceSize);\n" \
    "        wsum += w;\n" \
    "    }\n" \
    "    OUT = vec4(sum / wsum, 1.0) * colDiffuse * fragColor;\n" \
    "}\n"

/* Stage 3. texture0 is the signal; sourceSize is still the game picture
 * (256x224), since all positions are in game pixels. */
#define CRT_SHADER_BODY \
    "uniform sampler2D texture0;\n" \
    "uniform sampler2D glowTex;\n" \
    "uniform vec4 colDiffuse;\n" \
    "uniform vec2 sourceSize;\n" \
    "uniform float scale;\n" \
    "const float CURVE = 0.12;\n" \
    "const float SCANLINES = 0.85;\n" \
    "const float BEAM_DARK = 0.22;\n" \
    "const float BEAM_BRIGHT = 0.36;\n" \
    "const float TRIADS = 1.0;\n" \
    "const float MASK_ON_TUBE_FROM = 6.0;\n" \
    "const float DEFOCUS = 0.5;\n" \
    "const float DEFOCUS_H = 0.4;\n" \
    "const float CONVERGE = 0.3;\n" \
    "const float DISTANCE = 0.0;\n" \
    CRT_MASK_CONSTS \
    "vec3 line(float row, float x)\n" \
    "{\n" \
    "    return pow(TEX(texture0, vec2(x, row) / sourceSize).rgb, vec3(2.2));\n" \
    "}\n" \
    "vec3 wide(float row, float x, float h)\n" \
    "{\n" \
    "    return 0.5 * (line(row, x - h) + line(row, x + h));\n" \
    "}\n" \
    "vec3 row3(float row, float x, float ox, float h)\n" \
    "{\n" \
    "    return vec3(wide(row, x + ox, h).r, wide(row, x, h).g, wide(row, x - ox, h).b);\n" \
    "}\n" \
    "vec3 spot(vec3 col, vec3 dy, float grow)\n" \
    "{\n" \
    "    float sigma = mix(BEAM_DARK, BEAM_BRIGHT, sqrt(dot(col, vec3(0.299, 0.587, 0.114)))) * grow;\n" \
    "    return col * exp(-dy * dy / (2.0 * sigma * sigma)) / (sigma * 2.5066);\n" \
    "}\n" \
    "float lit(float a, float b, float s)\n" \
    "{\n" \
    "    float d = MASK_FILL / 3.0;\n" \
    "    float fa = floor(a - s) * d + min(fract(a - s), d);\n" \
    "    float fb = floor(b - s) * d + min(fract(b - s), d);\n" \
    "    return (fb - fa) / (b - a);\n" \
    "}\n" \
    "void main()\n" \
    "{\n" \
    "    vec2 c = fragTexCoord - 0.5;\n" \
    "    c *= 1.0 + CURVE * c.yx * c.yx;\n" \
    "    vec2 e = (0.5 - abs(c)) * sourceSize * scale;\n" \
    "    float inside = clamp(min(e.x, e.y) + 0.5, 0.0, 1.0);\n" \
    "    vec2 p = (c + 0.5) * sourceSize;\n" \
    "    float e2 = 2.0 * dot(c, c);\n" \
    "    float grow = (1.0 + DEFOCUS * e2) * (1.0 + DISTANCE);\n" \
    "    float h = DEFOCUS_H * e2 + 0.5 * DISTANCE;\n" \
    "    vec2 o = CONVERGE * 2.0 * c;\n" \
    "    float row = floor(p.y) + 0.5;\n" \
    "    vec3 dy = p.y - row + vec3(o.y, 0.0, -o.y);\n" \
    "    vec3 mid = row3(row, p.x, o.x, h);\n" \
    "    vec3 beam = spot(row3(row - 1.0, p.x, o.x, h), dy + 1.0, grow) + spot(mid, dy, grow) + spot(row3(row + 1.0, p.x, o.x, h), dy - 1.0, grow);\n" \
    "    float fx = clamp((scale - 1.5) / 1.5, 0.0, 1.0) * (1.0 - DISTANCE);\n" \
    "    vec3 rgb = mix(mid, beam, fx * SCANLINES);\n" \
    "    float t = p.x * TRIADS;\n" \
    "    float hw = 0.5 * TRIADS / scale;\n" \
    "    if (scale < MASK_ON_TUBE_FROM) {\n" \
    "        t = gl_FragCoord.x / 3.0;\n" \
    "        hw = 0.5 / 3.0;\n" \
    "    }\n" \
    "    t -= floor(t);\n" \
    "    float s0 = (1.0 - MASK_FILL) / 6.0;\n" \
    "    vec3 cover = vec3(lit(t - hw, t + hw, s0), lit(t - hw, t + hw, s0 + 1.0 / 3.0), lit(t - hw, t + hw, s0 + 2.0 / 3.0));\n" \
    "    vec3 mask = mix(vec3(MASK_DARK), vec3(1.0), cover) * MASK_GAIN;\n" \
    "    rgb *= mix(vec3(1.0), mask, fx);\n" \
    "    rgb += TEX(glowTex, c + 0.5).rgb;\n" \
    "    rgb *= (1.0 - 0.35 * dot(c, c)) * inside;\n" \
    "    OUT = vec4(pow(rgb, vec3(1.0 / 2.2)), 1.0) * colDiffuse * fragColor;\n" \
    "}\n"

#ifdef __EMSCRIPTEN__
#define CRT_FS_HEADER \
    "#version 100\n" \
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" \
    "varying vec2 fragTexCoord;\n" \
    "varying vec4 fragColor;\n" \
    "#define TEX texture2D\n" \
    "#define OUT gl_FragColor\n"
#else
#define CRT_FS_HEADER \
    "#version 330\n" \
    "in vec2 fragTexCoord;\n" \
    "in vec4 fragColor;\n" \
    "out vec4 finalColor;\n" \
    "#define TEX texture\n" \
    "#define OUT finalColor\n"
#endif
static const char signal_fs[] = CRT_FS_HEADER SIGNAL_SHADER_BODY;
static const char glow_fs[]   = CRT_FS_HEADER GLOW_SHADER_BODY;
static const char crt_fs[]    = CRT_FS_HEADER CRT_SHADER_BODY;

static Shader          signal_shader, glow_shader, crt_shader;
static RenderTexture2D signal;             /* stage 1, CRT_SIGNAL_W x 224 */
static RenderTexture2D glow_a, glow_b;     /* stage 2: horizontal, then vertical blur */
static int    crt_ready;
static int    crt_loc_scale, crt_loc_glow;
static int    glow_loc_dir, glow_loc_first, glow_loc_overflow;

static int shader_ok(Shader sh)
{
    return IsShaderValid(sh) && sh.id != rlGetShaderIdDefault();
}

int sdevkit__crt_load(void)
{
    /* All three use raylib's default vertex shader. */
    signal_shader = LoadShaderFromMemory(NULL, signal_fs);
    glow_shader   = LoadShaderFromMemory(NULL, glow_fs);
    crt_shader    = LoadShaderFromMemory(NULL, crt_fs);
    crt_ready = shader_ok(signal_shader) && shader_ok(glow_shader) && shader_ok(crt_shader);
    if (crt_ready) {
        Vector2 size = { (float)SCREEN_W, (float)SCREEN_H };
        SetShaderValue(signal_shader, GetShaderLocation(signal_shader, "sourceSize"), &size, SHADER_UNIFORM_VEC2);
        SetShaderValue(glow_shader, GetShaderLocation(glow_shader, "sourceSize"), &size, SHADER_UNIFORM_VEC2);
        SetShaderValue(crt_shader, GetShaderLocation(crt_shader, "sourceSize"), &size, SHADER_UNIFORM_VEC2);
        crt_loc_scale     = GetShaderLocation(crt_shader, "scale");
        crt_loc_glow      = GetShaderLocation(crt_shader, "glowTex");
        glow_loc_dir      = GetShaderLocation(glow_shader, "dir");
        glow_loc_first    = GetShaderLocation(glow_shader, "first");
        glow_loc_overflow = GetShaderLocation(glow_shader, "overflow");
        signal = LoadRenderTexture(CRT_SIGNAL_W, SCREEN_H);
        glow_a = LoadRenderTexture(SCREEN_W, SCREEN_H);
        glow_b = LoadRenderTexture(SCREEN_W, SCREEN_H);
        SetTextureFilter(signal.texture, TEXTURE_FILTER_BILINEAR);
        SetTextureFilter(glow_a.texture, TEXTURE_FILTER_POINT);
        SetTextureFilter(glow_b.texture, TEXTURE_FILTER_BILINEAR);
    } else {
        TraceLog(LOG_WARNING, "CRT: shader failed, drawing without it");
    }
    return crt_ready;
}

void sdevkit__crt_unload(void)
{
    UnloadShader(signal_shader);
    UnloadShader(glow_shader);
    UnloadShader(crt_shader);
    if (crt_ready) {
        UnloadRenderTexture(signal);
        UnloadRenderTexture(glow_a);
        UnloadRenderTexture(glow_b);
    }
    crt_ready = 0;
}

/* Draw all of `src` into `dst` through `sh`. Render textures are stored flipped; drawing them
 * with a negative source height keeps every stage in the same orientation as the picture, so
 * later stages read them all with the same coordinates. `upright`: src is a plain texture
 * (Rachael's picture), stored the right way up. */
static void crt_pass(Shader sh, Texture2D src, int upright, RenderTexture2D dst)
{
    float h = upright ? (float)src.height : (float)-src.height;

    BeginTextureMode(dst);
    ClearBackground(BLACK);
    BeginShaderMode(sh);
    DrawTexturePro(src, (Rectangle){ 0.0f, 0.0f, (float)src.width, h },
                   (Rectangle){ 0.0f, 0.0f, (float)dst.texture.width, (float)dst.texture.height },
                   (Vector2){ 0.0f, 0.0f }, 0.0f, WHITE);
    EndShaderMode();
    EndTextureMode();
}

static void glow_pass(Texture2D src, RenderTexture2D dst, float dx, float dy, float first)
{
    Vector2 dir = { dx, dy };
    SetShaderValue(glow_shader, glow_loc_dir, &dir, SHADER_UNIFORM_VEC2);
    SetShaderValue(glow_shader, glow_loc_first, &first, SHADER_UNIFORM_FLOAT);
    crt_pass(glow_shader, src, 0, dst);
}

void sdevkit__crt_prepare(Texture2D frame, float scale)
{
    float overflow = (scale - 1.5f) / 1.5f;
    if (overflow < 0.0f) overflow = 0.0f;
    if (overflow > 1.0f) overflow = 1.0f;
    crt_pass(signal_shader, frame, 1, signal);
    SetShaderValue(glow_shader, glow_loc_overflow, &overflow, SHADER_UNIFORM_FLOAT);
    glow_pass(signal.texture, glow_a, 1.0f, 0.0f, 1.0f);
    glow_pass(glow_a.texture, glow_b, 0.0f, 1.0f, 0.0f);
}

void sdevkit__crt_draw(Rectangle dst, float scale)
{
    BeginShaderMode(crt_shader);
    SetShaderValue(crt_shader, crt_loc_scale, &scale, SHADER_UNIFORM_FLOAT);
    SetShaderValueTexture(crt_shader, crt_loc_glow, glow_b.texture);
    DrawTexturePro(signal.texture, (Rectangle){ 0.0f, 0.0f, (float)CRT_SIGNAL_W, (float)-SCREEN_H },
                   dst, (Vector2){ 0.0f, 0.0f }, 0.0f, WHITE);
    EndShaderMode();
}
