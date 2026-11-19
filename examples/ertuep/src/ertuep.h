/*
 * Er-Tüp: a side-scrolling shooter in a German pub. The ship is a flying beer tap, its companion a
 * crate of beer that docks at the front or the back and can be launched; caps and bottles are the
 * shots, the cork of a bottle of sparkling wine the charged shot, and the empties the enemies drop
 * are the score, as deposit (Pfand) in cents. docs/er-tuep-proposal.md has the whole idea.
 *
 * What the files share. Positions and speeds are 12.4 fixed point (16 is one pixel) in screen
 * space: x 0 is the left edge of the picture, y 0 its top. The world scrolls under the objects;
 * whatever stands on the ground moves left with the camera.
 */
#ifndef ERTUEP_H
#define ERTUEP_H

#include <stdint.h>
#include <string.h>
#include <dampf.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define FX(px)    ((int16_t)((px) * 16))   /* pixels to 12.4 */
#define PX(v)     ((int16_t)((v) >> 4))    /* 12.4 to whole pixels, rounded down */

/* --- the screen ------------------------------------------------------------------------------ */
#define SCREEN_W     256
#define SCREEN_H     224
#define PLAY_TOP     16                    /* the status line covers the first 16 lines */
#define ROWS         14                    /* the stage is 14 cells of 16 pixels high */

/* --- one object of a table: shots, enemies, bullets, items, particles ----------------------- */
typedef struct {
    int16_t x, y;                          /* the centre, 12.4 */
    int16_t vx, vy;                        /* 12.4 per frame; some kinds keep other things here */
    uint8_t kind;                          /* 0: the slot is free */
    uint8_t state;                         /* where its state machine is */
    uint8_t timer;
    uint8_t hp;
    uint8_t anim;
    uint8_t flags;
} Obj;
/* 14 bytes on the SNES and on the PC (no padding), so the checks can hash the tables. */
typedef char obj_has_no_padding[sizeof(Obj) == 14 ? 1 : -1];

#define F_FLASH   0x0F                     /* frames left of the white flash after a hit */
#define F_DROP    0x70                     /* the item it drops when destroyed (>> 4), 0: none */
#define F_CEILING 0x80                     /* stands on the ceiling (a dice cup upside down) */

#define MAX_SHOTS   10                     /* a full crate fans out three bottles a volley */
#define MAX_ENEMIES 16
#define MAX_BULLETS 16
#define MAX_ITEMS   10
#define MAX_PARTS   12                     /* each a sprite: in the densest waves the frame decides it */

extern Obj shots[MAX_SHOTS], enemies[MAX_ENEMIES], bullets[MAX_BULLETS], items[MAX_ITEMS], parts[MAX_PARTS];

/* the kinds, one list per table */
enum { S_NONE, S_CAP, S_BOTTLE, S_CORK, S_FOAM };
enum { E_NONE, E_BEERMAT, E_PRETZEL, E_DICECUP, E_BARREL, E_FLY, E_DARTBOARD, E_STEIN, E_SLOT, E_METTIGEL, E_HOP,
       E_HENDL, E_HEART, E_TUBA, E_KINDS };
enum { B_NONE, B_PEANUT, B_DART, B_FOAMBALL, B_COIN, B_BOTTLE, B_NOTE, B_BREZEL };
enum { I_NONE, I_SIXPACK, I_SEKT, I_ESPRESSO, I_RUCKEL, I_BOTTLE, I_CAN };
enum { P_NONE, P_PUFF, P_FOAM, P_SHARD, P_EXHAUST, P_SPARK };

/* --- the stage (stage.c) --------------------------------------------------------------------- */
#define STAGE_COLS   224                   /* cells of 16 pixels: 3,584 pixels */
#define STAGE_END    ((STAGE_COLS - 16) * 16)   /* the camera stops here */
enum { ROOM_BAR, ROOM_CELLAR, ROOM_KITCHEN, ROOM_BREW };

extern uint16_t cam_x;                     /* the camera's left edge in the stage, pixels */
extern uint8_t cam_sub;                    /* and sixteenths of a pixel */
extern uint8_t scroll_step;                /* sixteenths of a pixel per frame; 0 stops it */
extern uint8_t scrolled;                   /* sixteenths the camera moved this frame */

#define STAGES       3
extern uint8_t stage_no;                   /* 0: die Kneipe, 1: das Sudhaus, 2: das Festzelt */
void stage_select(uint8_t n);
void stage_start(uint16_t at);             /* the camera at pixel at, the maps drawn whole (screen off) */
void stage_scroll(void);                   /* one frame of camera; columns coming into view prepared */
void stage_vblank(void);                   /* their upload, the scroll registers */
uint8_t solid_at(int16_t sx, int16_t sy);  /* is the stage solid at this screen pixel? */
uint8_t room_at(uint16_t col);
void waves_spawn(void);                    /* the waves whose column came into view */
uint16_t checkpoint_before(uint16_t px);   /* the last checkpoint at or before this camera position */
uint8_t checkpoint_reached(uint16_t px);   /* 1 when px is exactly a checkpoint */
extern uint16_t shown_cam;                 /* the camera the scroll registers show (the stutter holds it) */
extern uint8_t walls_off;                  /* layer 2 belongs to the boss: no wall columns, no wall scroll */

/* --- stage 3 in mode 7, and its boss (festzelt.c) ------------------------------------------------ */
extern uint8_t festzelt_on;                /* VRAM below 0x4000 and sheet B's lower half are stage 3's */
extern uint8_t oompah;                     /* frames of the tuba's blast */
void festzelt_load(void);                  /* with the screen off */
void festzelt_unload(void);
void festzelt_view(void);                  /* the picture of this frame */
void festzelt_vblank(void);
uint16_t festzelt_hofs(void);              /* mode 7's horizontal scroll: the base of the sway */
uint8_t festzelt_drunk(void);              /* 0..3, by the bottles in the crate */
void festzelt_hash(void);
void carousel_start(void);                 /* with the screen off */
void carousel_update(void);
void carousel_view(void);
void carousel_collide(void);
void carousel_draw(void);
int16_t carousel_aim_y(void);

/* --- the player (player.c) ------------------------------------------------------------------- */
enum { C_NONE, C_FRONT, C_BACK, C_FLY, C_FLOAT, C_RETURN };

extern int16_t ship_x, ship_y;
extern uint8_t ship_alive, ship_tilt, ship_speed, ship_blink;
extern int16_t crate_x, crate_y;
extern uint8_t crate_state, crate_bottles;
extern uint8_t has_sekt;
extern uint8_t charge;                     /* frames the fire button has been held, with the Sekt */

void player_start(void);
void player_update(uint16_t pad, uint16_t pressed, uint16_t released);
void shots_update(void);
void ship_hit(void);                       /* the ship is destroyed */
void crate_grow(uint8_t bottles);
uint16_t autopilot(void);                  /* the demo's joypad */
extern uint8_t demo;                       /* the title's demo is playing: bullets do not hit */

/* --- everything else that moves (objects.c) -------------------------------------------------- */
void objects_clear(void);
Obj *enemy_spawn(uint8_t kind, int16_t x, int16_t y, uint8_t param);
void enemies_update(void);
void bullets_update(void);
void items_update(void);
void parts_update(void);
void collide(void);
uint8_t slot_reel(const Obj *e, uint8_t i);   /* the symbol a reel of the slot machine shows */
void part_spawn(uint8_t kind, int16_t x, int16_t y, int16_t vx, int16_t vy);
void item_spawn(uint8_t kind, int16_t x, int16_t y);
uint16_t random16(void);
extern uint16_t rnd;
int8_t sine(uint8_t a);                    /* 64 steps a turn, -127..127 */
void fire_at_ship(uint8_t kind, int16_t x, int16_t y, int8_t turn);
void bullet_dir(uint8_t kind, int16_t x, int16_t y, uint8_t dir);   /* one of 16 directions */
#define SINE_AT(a) sine(a)

/* --- the boss of stage 1 (boss.c) ------------------------------------------------------------ */
enum { BS_NONE, BS_ENTER, BS_CLOSED, BS_OPEN, BS_BROKEN, BS_DYING, BS_DONE };
extern uint8_t boss_state;
void boss_start(void);                     /* with the screen off */
void boss_update(void);
void boss_collide(void);
void boss_vblank(void);
int16_t boss_aim_y(void);                  /* the intake's height for the demo's pilot, or -1 */

/* --- the sprites (draw.c) -------------------------------------------------------------------- */
void draw_all(uint8_t flicker);
void draw_title_logo(void);
/* one sprite with its upper left corner at (x, y), palette 0 (sheet A) .. 3, 16 x 16 when large */
void draw_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t pal, uint8_t large);
#define SPRITE_A(k) ((uint16_t)(((k) >> 3) * 32 + ((k) & 7) * 2))
#define SPRITE_B(k) ((uint16_t)(256 + ((k) >> 3) * 32 + ((k) & 7) * 2))
void boss_draw(void);                      /* the boss's sprites, if it has any */
void sprites_hide_all(void);

/* --- the course of the game, the status line (game.c) ---------------------------------------- */
extern uint16_t pfand;                     /* deposit collected, in cents */
extern uint8_t lives;
extern uint16_t frame_count;
extern uint8_t ruckel;                     /* frames left of the stutter */
void add_pfand(uint8_t cents);
void hud_crate(void);
void hud_sekt(void);
void hud_message(const char *text, uint8_t frames);
void lose_life(void);
void start_ruckel(void);

#endif
