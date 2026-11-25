/*
 * Das Festzelt: stage 3, in mode 7, and its boss, das Kettenkarussell.
 *
 * Mode 7 has one background layer, which the PPU turns, scales and moves by a matrix. The tent is
 * seen from above: a picture of 1024 x 1024 pixels (assets/festzelt.png, drawn by
 * dev/art/ertuep.py, converted by superdevkit png's mode7), and the camera flies a loop through its
 * aisles. The ship always flies to the right of the screen; at each corner of the loop the floor
 * turns a quarter under it. Everything else is as in the other stages: objects live in screen
 * space, the waves come by the camera's distance (cam_x), only the picture under them turns.
 *
 * The status line stays on BG3 of mode 1: two HDMA channels switch BGMODE and TM after its 16
 * lines, every frame, from tables in ROM (mode 1 with BG3 and the sprites above, mode 7 with BG1
 * and the sprites below). Mode 7's tiles and map fill VRAM up to 0x4000, below the status line's
 * and the sprites' (game.c); its colours are CGRAM 33 to 127, between the status line's and the
 * sprites'.
 *
 * Beer is the joke of the stage: the more bottles the crate holds, the more the picture sways,
 * by the matrix (a slow turn back and forth, a breathing zoom) and by the lines (game.c's sway,
 * the same HDMA as the brewhouse's, on mode 7's horizontal scroll).
 *
 * The boss is a chain carousel seen from above, a second mode 7 picture (assets/karussell.png)
 * loaded while the screen is dark: the whole picture turns about the carousel's hub, and the
 * chairs on their chains are sprites at the same angle, flying further out the faster it turns.
 * Its crown, on the hub, opens now and then: the only place it can be hurt.
 */
#include "ertuep.h"
#include "festzelt.h"
#include "karussell.h"
#include "sprites_c.h"

#define VRAM_SHEET_C  0x7800               /* sheet B's rows 4 to 7 (the sprites' tiles: game.c) */
#define CENTER_X      128                  /* the screen point the camera is on */
#define CENTER_Y      120
#define BGMODE_HUD    0x39                 /* mode 1, BG3 above the sprites, 16 x 16 tiles on BG1/2 */

/* --- the turn ------------------------------------------------------------------------------------ */
/* A quarter of sin over 256 steps (1,024 a turn), 256 for 1.0. */
static const int16_t QUARTER[257] = {
    0, 2, 3, 5, 6, 8, 9, 11, 13, 14, 16, 17, 19, 20, 22, 24,
    25, 27, 28, 30, 31, 33, 34, 36, 38, 39, 41, 42, 44, 45, 47, 48,
    50, 51, 53, 55, 56, 58, 59, 61, 62, 64, 65, 67, 68, 70, 71, 73,
    74, 76, 77, 79, 80, 82, 83, 85, 86, 88, 89, 91, 92, 94, 95, 97,
    98, 99, 101, 102, 104, 105, 107, 108, 109, 111, 112, 114, 115, 117, 118, 119,
    121, 122, 123, 125, 126, 128, 129, 130, 132, 133, 134, 136, 137, 138, 140, 141,
    142, 144, 145, 146, 147, 149, 150, 151, 152, 154, 155, 156, 157, 159, 160, 161,
    162, 164, 165, 166, 167, 168, 170, 171, 172, 173, 174, 175, 177, 178, 179, 180,
    181, 182, 183, 184, 185, 186, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197,
    198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 207, 208, 209, 210, 211, 212,
    213, 214, 215, 215, 216, 217, 218, 219, 220, 220, 221, 222, 223, 224, 224, 225,
    226, 227, 227, 228, 229, 229, 230, 231, 231, 232, 233, 233, 234, 235, 235, 236,
    237, 237, 238, 238, 239, 239, 240, 241, 241, 242, 242, 243, 243, 244, 244, 245,
    245, 245, 246, 246, 247, 247, 248, 248, 248, 249, 249, 249, 250, 250, 250, 251,
    251, 251, 252, 252, 252, 252, 253, 253, 253, 253, 254, 254, 254, 254, 254, 255,
    255, 255, 255, 255, 255, 255, 256, 256, 256, 256, 256, 256, 256, 256, 256, 256,
    256,
};

/* sin and cos of an angle of 1,024 steps a turn, 256 for 1.0 */
static int16_t sin1024(uint16_t a)
{
    uint16_t q = (uint16_t)(a & 255);

    switch ((a >> 8) & 3) {
    case 0:
        return QUARTER[q];
    case 1:
        return QUARTER[256 - q];
    case 2:
        return (int16_t)-QUARTER[q];
    default:
        return (int16_t)-QUARTER[256 - q];
    }
}

static int16_t cos1024(uint16_t a)
{
    return sin1024((uint16_t)(a + 256));
}

/* v * z / 256, with v up to 256 in size and z below 1024: as |v| * (z / 4) / 64, which fits 16
 * bits unsigned (a 32-bit product would cost dampf_mul32 thousands of cycles); z = 1.0 is v. */
static int16_t scaled(int16_t v, uint16_t z)
{
    uint16_t m;
    int16_t r;

    if (z == 256) {
        return v;
    }
    m = (uint16_t)((uint16_t)(v < 0 ? -v : v) * (uint16_t)(z >> 2));
    r = (int16_t)(m >> 6);
    return v < 0 ? (int16_t)-r : r;
}

/* --- the camera's loop through the tent ------------------------------------------------------------ */
/* Four legs, each a straight of 605 pixels and a quarter turn to the left of 128 (a radius of
 * 81.5): 2,932 pixels a loop, which the stage's 3,328 begin again. Leg k flies east, north, west,
 * south; its start in 12.4 map pixels: */
#define STRAIGHT 605
#define LEG      (STRAIGHT + 128)
static const int16_t LEG_X[4] = { 3352, 14336, 13032, 2048 };
static const int16_t LEG_Y[4] = { 14336, 13032, 2048, 3352 };
static const int8_t FWD_X[4] = { 1, 0, -1, 0 };     /* east, north, west, south */
static const int8_t FWD_Y[4] = { 0, -1, 0, 1 };
/* the turn's arc every 4 pixels of its 128: how far forward and how far to the left, 12.4 */
static const int16_t ARC_F[33] = { 0, 64, 128, 191, 254, 317, 378, 439, 499, 557, 615, 670, 724, 777, 827, 876, 922,
                                   966, 1008, 1047, 1084, 1118, 1150, 1179, 1205, 1228, 1248, 1265, 1279, 1290, 1298, 1302, 1304 };
static const int16_t ARC_L[33] = { 0, 2, 6, 14, 25, 39, 56, 76, 99, 125, 154, 185, 220, 257, 296, 338, 382,
                                   428, 477, 527, 579, 634, 689, 746, 805, 865, 925, 987, 1049, 1112, 1176, 1240, 1304 };

static int16_t view_x, view_y;             /* the map point under CENTER, pixels */
static uint16_t view_angle;                /* the direction of flight, 1,024 a turn (0 east, 768 north) */
static uint16_t view_zoom;                 /* map pixels a screen pixel, 8.8 */
static int16_t m7a, m7b, m7c, m7d;         /* the matrix of this frame */
static uint8_t view_ready;

/* v moved by d in direction dir (1, -1 or 0): an addition, not a multiplication */
static int16_t step(int16_t v, int8_t dir, int16_t d)
{
    return dir > 0 ? (int16_t)(v + d) : dir < 0 ? (int16_t)(v - d) : v;
}

/* Where the camera is at distance px (and sub sixteenths) of the loop. */
static void path_at(uint16_t px, uint8_t sub)
{
    uint8_t k = 0, i;
    int16_t x, y;

    for (i = 0; i < 6 && px >= LEG; i++) {
        px = (uint16_t)(px - LEG);
        k = (uint8_t)((k + 1) & 3);
    }
    x = LEG_X[k];
    y = LEG_Y[k];
    if (px < STRAIGHT) {
        int16_t d = (int16_t)(px * 16 + sub);
        x = step(x, FWD_X[k], d);
        y = step(y, FWD_Y[k], d);
        view_angle = (uint16_t)((1024 - k * 256) & 1023);
    } else {
        uint16_t s = (uint16_t)((px - STRAIGHT) * 16 + sub);   /* 0 .. 2047 */
        uint8_t j = (uint8_t)(s >> 6), f = (uint8_t)(s & 63);
        uint8_t left = (uint8_t)((k + 1) & 3);
        /* the differences are below 64, times f below 64: the product fits 16 bits */
        int16_t fw = (int16_t)(ARC_F[j] + ((int16_t)((ARC_F[j + 1] - ARC_F[j]) * f) >> 6));
        int16_t lf = (int16_t)(ARC_L[j] + ((int16_t)((ARC_L[j + 1] - ARC_L[j]) * f) >> 6));
        x = step(step(x, FWD_X[k], (int16_t)(STRAIGHT * 16 + fw)), FWD_X[left], lf);
        y = step(step(y, FWD_Y[k], (int16_t)(STRAIGHT * 16 + fw)), FWD_Y[left], lf);
        view_angle = (uint16_t)((1024 - k * 256 - (s >> 3)) & 1023);
    }
    view_x = PX(x);
    view_y = PX(y);
}

/* The matrix that turns the screen by angle and scales by zoom: screen (1, 0) is the map's
 * (cos, sin), screen (0, 1) its (-sin, cos). */
static void set_view(uint16_t angle, uint16_t zoom)
{
    int16_t c = cos1024(angle), s = sin1024(angle);

    m7a = scaled(c, zoom);
    m7c = scaled(s, zoom);
    m7b = (int16_t)-m7c;
    m7d = m7a;
    view_ready = 1;
}

/* --- the screen split ---------------------------------------------------------------------------- */
/* HDMA: the first 16 lines mode 1 with BG3 (the status line), the rest mode 7 with BG1. A count
 * byte, then the value; a count of 1 keeps the value to the end of the frame. */
static const uint8_t SPLIT_MODE[5] = { 16, BGMODE_HUD, 1, SDEVKIT_BG_MODE7, 0 };
static const uint8_t SPLIT_TM[5] = { 16, SDEVKIT_BG3_ENABLE | SDEVKIT_OBJ_ENABLE, 1, SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE, 0 };

uint8_t festzelt_on;                       /* VRAM below 0x4000 and sheet B's lower half are stage 3's */
uint8_t oompah;                            /* frames of the tuba's blast: the floor jumps */
static uint8_t which;                      /* 0: the tent is loaded, 1: the carousel */

static void split_on(void)
{
    sdevkit_hdma[4].table = SPLIT_MODE;
    sdevkit_hdma[4].dmap = SDEVKIT_HDMA_ONE;
    sdevkit_hdma[4].bbad = SDEVKIT_PPU_BGMODE;
    sdevkit_hdma[5].table = SPLIT_TM;
    sdevkit_hdma[5].dmap = SDEVKIT_HDMA_ONE;
    sdevkit_hdma[5].bbad = SDEVKIT_PPU_TM;
    sdevkit_marks |= SDEVKIT_MARK_HDMA;
    sdevkit_ppu.hdmaen = SDEVKIT_HDMA_CHANNEL4 | SDEVKIT_HDMA_CHANNEL5;
}

/* With the screen off: the tent's picture, the stage's sprites, the split. */
void festzelt_load(void)
{
    sdevkit_mode7_tiles_now(0, festzelt_tiles, FESTZELT_TILES_SIZE);
    sdevkit_mode7_map_now(0, festzelt_map, FESTZELT_MAP_SIZE);
    sdevkit_cgram_now(FESTZELT_PAL_FIRST, festzelt_pal, FESTZELT_PAL_SIZE);
    sdevkit_vram_now(VRAM_SHEET_C, sprites_c_tiles, SPRITES_C_TILES_SIZE);
    sdevkit_cgram_now(192, sprites_c_pal, SPRITES_C_PAL_SIZE);
    sdevkit_ppu.m7sel = SDEVKIT_M7_WRAP;
    sdevkit_marks |= SDEVKIT_MARK_MODE7;
    sdevkit_color_now(0, SDEVKIT_RGB8(24, 16, 10));
    split_on();
    festzelt_on = 1;
    which = 0;
    oompah = 0;
    view_zoom = 256;
    path_at(cam_x, cam_sub);
    set_view(view_angle, view_zoom);
}

/* How drunk the picture is: 0 to 3, by the bottles in the crate. */
uint8_t festzelt_drunk(void)
{
    uint8_t b = crate_state != C_NONE ? crate_bottles : 0;

    return (uint8_t)(b >= 18 ? 3 : b >= 12 ? 2 : b >= 6 ? 1 : 0);
}

/* The picture of this frame in the tent: the camera on its loop, swaying with the beer. */
void festzelt_view(void)
{
    uint8_t drunk = festzelt_drunk();
    uint16_t angle, zoom = 256;

    path_at(shown_cam, 0);
    angle = view_angle;
    if (drunk > 0) {                           /* a slow turn back and forth, a breathing zoom */
        int16_t k = (int16_t)(drunk * 6);         /* sin up to 256 times 18: the products fit 16 bits */
        angle = (uint16_t)((angle + ((int16_t)(sin1024((uint16_t)(frame_count << 2)) * k) >> 8)) & 1023);
        zoom = (uint16_t)(zoom + ((int16_t)(sin1024((uint16_t)(frame_count * 3)) * k) >> 8));
    }
    if (oompah > 0) {                          /* the tuba blows: the floor jumps toward the eye */
        oompah--;
        zoom = (uint16_t)(zoom - oompah * 4);
    }
    view_zoom = zoom;
    set_view(angle, zoom);
}

/* The horizontal scroll mode 7 is at: the camera's point under CENTER. Also the base of the
 * sway's lines (game.c). */
uint16_t festzelt_hofs(void)
{
    return (uint16_t)((view_x - CENTER_X) & 0x1FFF);
}

/* After the tick: the matrix when the view changed, the centre and the scroll, every frame. */
void festzelt_show(void)
{
    if (view_ready) {
        sdevkit_ppu.m7a = m7a;
        sdevkit_ppu.m7b = m7b;
        sdevkit_ppu.m7c = m7c;
        sdevkit_ppu.m7d = m7d;
        view_ready = 0;
    }
    sdevkit_ppu.m7x = (int16_t)view_x;
    sdevkit_ppu.m7y = (int16_t)view_y;
    sdevkit_marks |= SDEVKIT_MARK_MODE7;
    sdevkit_ppu.hofs[0] = festzelt_hofs();
    sdevkit_ppu.vofs[0] = (uint16_t)((view_y - CENTER_Y) & 0x1FFF);
    sdevkit_marks |= SDEVKIT_MARK_SCROLL;
}

/* Back to mode 1, with the screen off: sheet B's lower half (game.c restores its tiles), no
 * split. The VRAM below 0x4000 is the stage's again once load_stage_gfx has copied its tiles. */
void festzelt_unload(void)
{
    sdevkit_ppu.hdmaen = 0;
    sdevkit_marks |= SDEVKIT_MARK_HDMA;
    festzelt_on = 0;
}

/* --- das Kettenkarussell ------------------------------------------------------------------------- */
/* The picture turns about the carousel's hub, which stays under CENTER: the matrix turns by
 * spin_angle, and a point of the canopy at angle a is on the screen at angle a - spin_angle. The
 * chairs hang from the canopy's rim at eight angles; their chains swing out with the speed. */
#define CHAIRS      8
#define CHAIR_HP    8
#define CROWN_HP    100
#define HUB_X       512
#define HUB_Y       512

static uint16_t spin;                      /* the angle, 1,024 a turn, 6 bits more below */
static int16_t spin_speed;                 /* per frame, in those units; negative turns the other way */
static int16_t spin_target;
static uint8_t chair_hp[CHAIRS];
static int16_t chair_x[CHAIRS], chair_y[CHAIRS];   /* where they are this frame, screen pixels */
static uint8_t crown_hp, crown_open, timer, chairs_left, dying;
static uint16_t phase_time;

void carousel_start(void)
{
    uint8_t i;

    sdevkit_mode7_tiles_now(0, karussell_tiles, KARUSSELL_TILES_SIZE);
    sdevkit_mode7_map_now(0, karussell_map, KARUSSELL_MAP_SIZE);
    sdevkit_cgram_now(KARUSSELL_PAL_FIRST, karussell_pal, KARUSSELL_PAL_SIZE);
    sdevkit_color_now(0, SDEVKIT_RGB8(6, 6, 16));
    which = 1;
    view_x = HUB_X;
    view_y = HUB_Y;
    view_zoom = 960;                           /* far away: a quarter of its size (below 1024: scaled) */
    spin = 0;
    spin_speed = 0;
    spin_target = 96;
    for (i = 0; i < CHAIRS; i++) {
        chair_hp[i] = CHAIR_HP;
        chair_x[i] = -64;
        chair_y[i] = -64;
    }
    chairs_left = CHAIRS;
    crown_hp = CROWN_HP;
    crown_open = 0;
    timer = 0;
    dying = 0;
    phase_time = 0;
    boss_state = BS_ENTER;
    set_view(0, view_zoom);
    hud_message("DAS KETTENKARUSSELL", 150);
}

/* How far out the chairs fly, screen pixels: the canopy's rim, and more the faster it turns. */
static int16_t chair_radius(void)
{
    int16_t v = spin_speed < 0 ? (int16_t)-spin_speed : spin_speed;

    return (int16_t)(62 + (v >> 3));
}

static void place_chairs(void)
{
    uint8_t i;
    int16_t r = chair_radius();
    uint16_t a = (uint16_t)(1024 - (spin >> 6));

    for (i = 0; i < CHAIRS; i++) {
        uint16_t ca = (uint16_t)((a + i * 128) & 1023);
        chair_x[i] = (int16_t)(CENTER_X + ((int16_t)(r * cos1024(ca)) >> 8));   /* r below 128: fits */
        chair_y[i] = (int16_t)(CENTER_Y + ((int16_t)(r * sin1024(ca)) >> 8));
    }
}

/* The crown's notes: the carousel's organ plays a ring of them. */
static void organ(uint8_t offset)
{
    uint8_t d;

    for (d = 0; d < 16; d += 2) {
        bullet_dir(B_NOTE, FX(CENTER_X), FX(CENTER_Y), (uint8_t)(d + offset));
    }
}

void carousel_update(void)
{
    uint8_t i;

    timer++;
    phase_time++;
    if (spin_speed < spin_target) {
        spin_speed++;
    } else if (spin_speed > spin_target) {
        spin_speed--;
    }
    spin = (uint16_t)(spin + (uint16_t)spin_speed);
    switch (boss_state) {
    case BS_ENTER:                                  /* it comes closer, turning */
        if (view_zoom > 256) {
            view_zoom = (uint16_t)(view_zoom - 4);
        } else {
            boss_state = BS_CLOSED;
            phase_time = 0;
        }
        break;
    case BS_CLOSED:                                 /* faster and faster; the riders throw foam */
        spin_target = chairs_left > CHAIRS / 2 ? 160 : 224;
        if ((timer & 31) == 0 && ship_alive) {
            i = (uint8_t)((timer >> 5) & (CHAIRS - 1));
            if (chair_hp[i] > 0) {
                fire_at_ship(B_FOAMBALL, FX(chair_x[i]), FX(chair_y[i]), 0);
            }
        }
        if (phase_time > 240) {
            boss_state = BS_OPEN;
            phase_time = 0;
            crown_open = 1;
            hud_message("DIE KRONE IST OFFEN!", 60);
        }
        break;
    case BS_OPEN:                                   /* it slows, the crown open, the organ plays */
        spin_target = 48;
        if ((timer & 47) == 0) {
            organ((uint8_t)(timer >> 4));
        }
        if (phase_time > 180) {
            crown_open = 0;
            phase_time = 0;
            boss_state = crown_hp < CROWN_HP / 2 || chairs_left == 0 ? BS_BROKEN : BS_CLOSED;
            if (boss_state == BS_BROKEN) {
                hud_message("ES DREHT SICH ANDERSRUM!", 90);
            }
        }
        break;
    case BS_BROKEN:                                 /* the other way round, wilder, the crown open longer */
        spin_target = -256;
        crown_open = (uint8_t)((phase_time & 255) < 140);
        if ((timer & 23) == 0) {
            organ((uint8_t)(timer >> 3));
        }
        if ((timer & 31) == 16 && ship_alive) {
            i = (uint8_t)((timer >> 5) & (CHAIRS - 1));
            if (chair_hp[i] > 0) {
                fire_at_ship(B_BREZEL, FX(chair_x[i]), FX(chair_y[i]), 0);
            }
        }
        break;
    case BS_DYING:                                  /* it runs down; the chairs fall; deposit */
        spin_target = 0;
        crown_open = 1;
        dying++;
        if ((dying & 7) == 0 && dying < 160) {
            item_spawn((dying & 8) ? I_CAN : I_BOTTLE, FX(CENTER_X), FX(CENTER_Y));
            part_spawn(P_PUFF, FX(CENTER_X - 30 + (int16_t)(random16() & 63)), FX(CENTER_Y - 30 + (int16_t)(random16() & 63)), 0, -6);
        }
        if (dying >= 200) {
            boss_state = BS_DONE;
            hud_message("FEIERABEND!", 255);
        }
        break;
    default:
        break;
    }
    place_chairs();
    view_x = HUB_X;
    view_y = HUB_Y;
}

/* The picture of this frame: the turn, the zoom, the beer's sway on top. */
void carousel_view(void)
{
    uint8_t drunk = festzelt_drunk();
    uint16_t angle = (uint16_t)(spin >> 6);
    uint16_t zoom = view_zoom;

    if (drunk > 0) {
        zoom = (uint16_t)(zoom + ((int16_t)(sin1024((uint16_t)(frame_count * 3)) * (int16_t)(drunk * 6)) >> 8));
    }
    set_view(angle, zoom);
}

static uint8_t near(int16_t ax, int16_t ay, int16_t bx, int16_t by, int16_t w, int16_t h)
{
    int16_t dx = (int16_t)(ax - bx), dy = (int16_t)(ay - by);

    return (uint8_t)(dx > -w && dx < w && dy > -h && dy < h);
}

static void crown_damage(uint8_t n)
{
    if (crown_hp <= n) {
        crown_hp = 0;
        boss_state = BS_DYING;
        dying = 0;
        hud_message("KARUSSELL KAPUTT!", 255);
        return;
    }
    crown_hp = (uint8_t)(crown_hp - n);
}

void carousel_collide(void)
{
    uint8_t i, j;
    int16_t px = PX(ship_x), py = PX(ship_y);

    if (boss_state < BS_CLOSED || boss_state > BS_BROKEN) {
        return;
    }
    for (j = 0; j < MAX_SHOTS; j++) {
        Obj *s = &shots[j];
        int16_t sx, sy;
        if (s->kind == S_NONE) {
            continue;
        }
        sx = PX(s->x);
        sy = PX(s->y);
        if (near(sx, sy, CENTER_X, CENTER_Y, 14, 14)) {
            if (crown_open) {
                crown_damage(s->kind == S_CORK ? s->hp : s->kind == S_BOTTLE ? 2 : 1);
                part_spawn(P_SPARK, s->x, s->y, 0, -8);
            } else {
                part_spawn(P_SPARK, s->x, s->y, -12, 0);   /* the closed crown is brass */
            }
            s->kind = S_NONE;
            if (boss_state == BS_DYING) {
                return;
            }
            continue;
        }
        for (i = 0; i < CHAIRS; i++) {
            if (chair_hp[i] == 0 || !near(sx, sy, chair_x[i], chair_y[i], 9, 9)) {
                continue;
            }
            if (chair_hp[i] <= 2) {
                chair_hp[i] = 0;
                chairs_left--;
                part_spawn(P_PUFF, FX(chair_x[i]), FX(chair_y[i]), 0, -6);
                item_spawn(I_BOTTLE, FX(chair_x[i]), FX(chair_y[i]));
            } else {
                chair_hp[i] = (uint8_t)(chair_hp[i] - (s->kind == S_BOTTLE || s->kind == S_CORK ? 2 : 1));
            }
            if (s->kind != S_CORK) {
                s->kind = S_NONE;
            }
            break;
        }
    }
    if (!ship_alive || ship_blink != 0 || demo) {
        return;
    }
    for (i = 0; i < CHAIRS; i++) {
        if (chair_hp[i] > 0 && near(px, py, chair_x[i], chair_y[i], 12, 9)) {
            ship_hit();
            return;
        }
    }
    if (near(px, py, CENTER_X, CENTER_Y, 22, 18)) {
        ship_hit();
    }
}

/* The chairs, then the crown above them (four sprites, sheet C's palette). */
void carousel_draw(void)
{
    uint8_t i;
    uint16_t crown = (uint16_t)(crown_open ? SPRITE_B(32 + 26) : SPRITE_B(32 + 24));

    if (boss_state < BS_CLOSED || boss_state > BS_DYING) {
        return;
    }
    draw_sprite(CENTER_X - 16, CENTER_Y - 16, crown, 4, 1);
    draw_sprite(CENTER_X, CENTER_Y - 16, (uint16_t)(crown + 2), 4, 1);
    draw_sprite(CENTER_X - 16, CENTER_Y, (uint16_t)(crown + 32), 4, 1);
    draw_sprite(CENTER_X, CENTER_Y, (uint16_t)(crown + 34), 4, 1);
    if (boss_state == BS_DYING) {
        return;
    }
    for (i = 0; i < CHAIRS; i++) {
        if (chair_hp[i] > 0) {
            draw_sprite((int16_t)(chair_x[i] - 8), (int16_t)(chair_y[i] - 8), SPRITE_B(32 + 6 + (i & 1)), 4, 1);
        }
    }
}

int16_t carousel_aim_y(void)
{
    return crown_open ? CENTER_Y : -1;
}

/* The state for the checks */
void festzelt_hash(void)
{
    sdevkit_dbg_hash(&view_x, sizeof view_x);
    sdevkit_dbg_hash(&view_y, sizeof view_y);
    sdevkit_dbg_hash(&view_angle, sizeof view_angle);
    sdevkit_dbg_hash(&m7a, sizeof m7a);
    sdevkit_dbg_hash(&m7b, sizeof m7b);
    sdevkit_dbg_hash(&spin, sizeof spin);
    sdevkit_dbg_hash(chair_hp, sizeof chair_hp);
    sdevkit_dbg_hash(&crown_hp, sizeof crown_hp);
}
