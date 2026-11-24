/*
 * The stage: its map, the camera, the columns that come into view, the waves and checkpoints.
 *
 * The stage is written below as text, 14 rows of 224 cells of 16 x 16 pixels, one character a
 * cell, so it can be read and changed as a picture: the pub's bar room, then the cellar, then the
 * kitchen, and at the end the corner where the deposit machine stands. A character names a thing
 * (CELLS1 and CELLS2 below), and a solid thing gets a top, middle or bottom tile by its neighbours above and
 * below, so a block of any height looks right without a tile per position in the text.
 *
 * Layer 1 (BG1) shows the stage in 16 x 16 tiles: its map of 64 x 32 entries is 1024 pixels wide,
 * and the column about to come into view is built in RAM and copied down the map in the next
 * vertical blank (sdevkit_dma_copy_vram_column), one column every 32 frames at the usual speed.
 * Layer 2 (BG2) is the wall behind, scrolled at half the speed: a picture of 16 x 14 cells per
 * room (walls.c, drawn by dev/art/ertuep.py) that repeats every 256 pixels.
 *
 * Stage 3, das Festzelt, has no text: it is a mode 7 picture flown over from above (festzelt.c),
 * with nothing solid in it. It shares the camera, the waves and the checkpoints.
 */
#include "ertuep.h"

#define VRAM_BG1_MAP 0x4000         /* 64 x 32: the left half, the right 0x400 words on */
#define VRAM_BG2_MAP 0x4800
#define PAL(n)       ((uint16_t)((n) << 10))

static const char STAGE1[ROWS][STAGE_COLS + 1] = {
    "################################################################################XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW                  ",
    "################################################################################XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW",
    "      !       !               !             PP    !             RRRRRRRR  ######XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX    EEEE        p   p   p JJJJJJJJ  EEEE  FF      p   p                         ",
    "      L       L               L             PP    L             RRRRRRRR     ###XXXX  u      w  XXXX |  u     w       | XXXXu   XXXX      u XXXX    EEEE                  JJJJJJJJ  EEEE  FF                                    ",
    "                                            PP                                                  XXXX |                | XXXX    XXXX                EEEE                            EEEE  FF                                    ",
    "                                            PP                                                  XXXX  ------V---------  XXXX                                      FF                EEEE                                        ",
    "                                                                                                 XX                     XXXX                                      FF                EEEE                                        ",
    "                                                            PP    _______               OOOO                                        OOOO                          FF                                                            ",
    "                   Y    Y                                   PP                          OOOO            KKKK              XXXXXX    OOOO                   HH     FF                                                            ",
    "                BBBBBBBBBBBB      T     T               T   PP                  SSS     OOOO            KKKK    OO        XXXXXX    OOOO                CCCCCCCC  FF        NNNN          FF                                    ",
    "                BBBBBBBBBBBB      i     i               i   PP              ====SSS     OOOO            KKKK    OO        XXXXXX    OOOO     SSS        CCCCCCCC  FF        NNNN          FF  CCCCCCCCCC                        ",
    "                BBBBBBBBBBBB      f     f               f   PP              ====SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS        CCCCCCCC  FF        NNNN          FF  CCCCCCCCCC                        ",
    "================================================================================SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW",
    "================================================================================SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW",
};

/* Stage 2, das Sudhaus: copper kettles, pipes and catwalks, then the Mett, which grows through
 * the pipes and over the walls and narrows the way; at the end the brew kettle. */
static const char STAGE2[ROWS][STAGE_COLS + 1] = {
    "################################################################################################################################################################################################################################",
    "##############################################################################################################################################################################################################                  ",
    "                        g     |         w     SSSS                |                       MMMMMM          MMMMMM          MMMM  MMMMMM  MMMMMM  MMMMMM  h             MMMMMMM                   MMMMMM                          ",
    "                              |               SSSS                |                       MMMMMM          MMMMMM          MMMM  MMMMMM  MMMMMM  MMMMMM                MMMMMMM                   MMMMMM                          ",
    "          ----------o----------               SSSS                |                       MMMMMM          MMMMMM                MMMMMM          MMMMMM                MMMMMMM                                                   ",
    "                                              SSSS                |                         d             MMMMMM                MMMMMM          MMMMMM                   d              _______                                 ",
    "                                                                  ---------o---------                       d                   MMMMMM          MMMMMM                                                                          ",
    "                                    ______             v                            |               MMMM                                                                                                                        ",
    "              KK                                      KK                            |               MMMM                                MMMMMM                KK                                                                ",
    "              KK                                      KK    BBBB                    |               MMMM          MMMMMM                MMMMMM                KK                BBBBBB                                          ",
    "              KK                                      KK    BBBB                    |     MMMMMM    MMMM          MMMMMM  MMMM          MMMMMM                KK                BBBBBB          MMMMMM                          ",
    "              KK                                      KK    BBBB                    |     MMMMMM    MMMM          MMMMMM  MMMM  MMMMMM  MMMMMM  MMMMMM      h KK                BBBBBB          MMMMMM                          ",
    "================================================================================================================================================================================================================================",
    "================================================================================================================================================================================================================================",
};

/* The stage being played: a pointer to its rows. */
static const char (*stage)[STAGE_COLS + 1] = STAGE1;
uint8_t stage_no;

/* What a character of the stage is: its room's sheet, the cells of its top, middle and bottom
 * (16 x 16 cells of the sheet, see dev/art/ertuep.py) and whether it is solid. */
typedef struct {
    char ch;
    uint8_t sheet, top, mid, bottom, solid;
} CellDef;

static const CellDef CELLS1[] = {
    /* the bar room (stage_bar.png) */
    { '=', 0, 1, 2, 2, 1 },      /* oak floor */
    { '#', 0, 2, 2, 3, 1 },      /* the ceiling's beams */
    { 'B', 0, 4, 5, 6, 1 },      /* the bar counter */
    { 'T', 0, 7, 7, 7, 1 },      /* a table top */
    { 'i', 0, 8, 8, 8, 0 },      /* its post */
    { 'f', 0, 9, 9, 9, 0 },      /* its foot */
    { 'Y', 0, 10, 10, 10, 0 },   /* a beer tap tower */
    { 'L', 0, 11, 11, 11, 0 },   /* a lamp */
    { '!', 0, 12, 12, 12, 0 },   /* its cord */
    { 'P', 0, 13, 14, 15, 1 },   /* a pillar */
    { '_', 0, 16, 16, 16, 1 },   /* a brass rail */
    { 'R', 0, 17, 18, 19, 1 },   /* a shelf of bottles */
    /* the cellar (stage_cellar.png) */
    { 'X', 1, 1, 2, 3, 1 },      /* brick */
    { 'O', 1, 4, 5, 6, 1 },      /* barrels on a rack */
    { 'S', 1, 7, 8, 17, 1 },     /* stone */
    { '-', 1, 9, 9, 9, 1 },      /* a copper pipe */
    { '|', 1, 10, 10, 10, 1 },
    { 'V', 1, 11, 11, 11, 1 },   /* its valve */
    { 'K', 1, 12, 13, 14, 1 },   /* crates */
    { 'w', 1, 15, 15, 15, 0 },   /* a cobweb */
    { 'u', 1, 16, 16, 16, 0 },   /* a bulb */
    /* the kitchen (stage_kitchen.png) */
    { 'W', 2, 1, 2, 3, 1 },      /* tiles */
    { 'C', 2, 4, 5, 6, 1 },      /* a worktop and its cabinets */
    { 'H', 2, 7, 7, 7, 1 },      /* a stove */
    { 'N', 2, 8, 8, 8, 1 },      /* an oven */
    { 'E', 2, 10, 10, 9, 1 },    /* an extractor hood */
    { 'p', 2, 11, 11, 11, 0 },   /* pans */
    { 'J', 2, 12, 12, 12, 1 },   /* a shelf of jars */
    { 'F', 2, 13, 14, 15, 1 },   /* a fridge */
};

static const CellDef CELLS2[] = {
    /* the brewhouse (stage_brew.png) */
    { '=', 0, 1, 2, 2, 1 },      /* tiled floor */
    { '#', 0, 17, 18, 3, 1 },    /* steel girders */
    { 'S', 0, 17, 18, 19, 1 },   /* a steel wall */
    { 'K', 0, 7, 8, 9, 1 },      /* a copper kettle */
    { '-', 0, 4, 4, 4, 1 },      /* a copper pipe */
    { '|', 0, 5, 5, 5, 1 },
    { 'o', 0, 6, 6, 6, 1 },      /* its flange */
    { '_', 0, 13, 13, 13, 1 },   /* a catwalk */
    { 'B', 0, 10, 11, 12, 1 },   /* malt sacks */
    { 'w', 0, 14, 14, 14, 0 },   /* a valve wheel */
    { 'g', 0, 15, 15, 15, 0 },   /* a gauge */
    { 'v', 0, 16, 16, 16, 0 },   /* a steam vent */
    /* the Mett (stage_mett.png): its cells 1 to 3 pulse (game.c copies the frames) */
    { 'M', 1, 1, 2, 3, 1 },
    { 'h', 1, 4, 4, 4, 0 },      /* Mett through a hole */
    { 'd', 1, 6, 6, 6, 0 },      /* a drip */
};

/* The same, by character (from ' '), made by stage_init: map entries and solidity. */
static uint16_t cell_top[96], cell_mid[96], cell_bottom[96];
static uint8_t cell_solid[96];

/* The map entry of cell k of a sheet whose first tile is base: a 16 x 16 tile is the 8 x 8 tiles
 * n, n + 1, n + 16, n + 17 of a sheet 128 pixels wide. */
static uint16_t cell_entry(uint16_t base, uint8_t k)
{
    return (uint16_t)(base + (uint16_t)(k >> 3) * 32 + (uint16_t)(k & 7) * 2);
}

static void stage_init(void)
{
    const CellDef *cells = stage_no == 0 ? CELLS1 : CELLS2;
    uint8_t n = stage_no == 0 ? (uint8_t)(sizeof CELLS1 / sizeof CELLS1[0]) : (uint8_t)(sizeof CELLS2 / sizeof CELLS2[0]);
    uint8_t i;

    memset(cell_solid, 0, sizeof cell_solid);
    memset(cell_top, 0, sizeof cell_top);
    memset(cell_mid, 0, sizeof cell_mid);
    memset(cell_bottom, 0, sizeof cell_bottom);
    for (i = 0; i < n; i++) {
        const CellDef *d = &cells[i];
        uint8_t c = (uint8_t)((uint8_t)d->ch - ' ');
        uint16_t base = (uint16_t)(d->sheet * 128);
        uint16_t pal = PAL(1 + d->sheet);
        cell_top[c] = (uint16_t)(cell_entry(base, d->top) | pal);
        cell_mid[c] = (uint16_t)(cell_entry(base, d->mid) | pal);
        cell_bottom[c] = (uint16_t)(cell_entry(base, d->bottom) | pal);
        cell_solid[c] = d->solid;
    }
}

/* --- the walls behind (BG2) ------------------------------------------------------------------- */
extern const uint8_t WALL_BAR[14][16], WALL_CELLAR[14][16], WALL_KITCHEN[14][16], WALL_BREW[14][16];

uint8_t room_at(uint16_t col)
{
    if (stage_no == 1) {
        return ROOM_BREW;
    }
    if (col < 80) {
        return ROOM_BAR;
    }
    return col < 144 ? ROOM_CELLAR : ROOM_KITCHEN;
}

/* --- the camera and the columns ----------------------------------------------------------------- */
uint16_t cam_x;
uint8_t cam_sub;
uint8_t scroll_step = 8;
uint8_t scrolled;
uint16_t shown_cam;
uint8_t walls_off;

static uint8_t wave_next;
static void waves_from(uint16_t at);

static uint16_t next_col;                  /* the next stage column to build for BG1 */
static uint16_t next_wall;                 /* the next wall column for BG2 */
static uint16_t col_buf[ROWS], wall_buf[ROWS];
static uint16_t col_vram, wall_vram;       /* where they go; 0: nothing to copy */
static uint8_t col_half;                   /* the upper half of next_col is built */

/* Rows from to to - 1 of a column into col_buf. A column is built in two halves on two frames (it
 * comes every 32 frames, and a whole one costs a twelfth of a frame on top of the busiest). */
static void build_rows(uint16_t col, uint8_t from, uint8_t to)
{
    uint8_t r;
    uint8_t above = (uint8_t)((uint8_t)stage[from > 0 ? from - 1 : 0][col] - ' ');

    for (r = from; r < to; r++) {
        uint8_t c = (uint8_t)((uint8_t)stage[r][col] - ' ');
        uint8_t below = r + 1 < ROWS ? (uint8_t)((uint8_t)stage[r + 1][col] - ' ') : c;
        if (c == 0) {
            col_buf[r] = 0;
        } else if (above != c) {
            col_buf[r] = cell_top[c];
        } else if (below != c) {
            col_buf[r] = cell_bottom[c];
        } else {
            col_buf[r] = cell_mid[c];
        }
        above = c;
    }
}

static void build_column(uint16_t col)
{
    build_rows(col, 0, ROWS);
    col_vram = (uint16_t)(VRAM_BG1_MAP + ((col & 32) ? 0x400 : 0) + (col & 31));
}

static void build_wall(uint16_t col)
{
    uint8_t room = room_at((uint16_t)(col * 2));
    const uint8_t (*wall)[16] = room == ROOM_BAR ? WALL_BAR : room == ROOM_CELLAR ? WALL_CELLAR
                                : room == ROOM_KITCHEN ? WALL_KITCHEN : WALL_BREW;
    uint16_t base = room == ROOM_BREW ? 0 : (uint16_t)(room * 128);   /* the brewhouse's wall where the bar's was */
    uint16_t pal = room == ROOM_BREW ? PAL(4) : PAL(4 + room);
    uint8_t r;

    for (r = 0; r < ROWS; r++) {
        uint8_t k = wall[r][col & 15];
        uint16_t e = (uint16_t)(cell_entry(base, (uint8_t)(k & 0x7F)) | pal);
        wall_buf[r] = (k & 0x80) ? (uint16_t)(e | SDEVKIT_BG_TIL_FLIPX) : e;
    }
    wall_vram = (uint16_t)(VRAM_BG2_MAP + ((col & 32) ? 0x400 : 0) + (col & 31));
}

/* With the screen off: the camera at pixel at and all of both maps that it shows. */
void stage_start(uint16_t at)
{
    uint8_t i;

    stage_init();
    walls_off = 0;
    cam_x = at;
    cam_sub = 0;
    shown_cam = at;
    scrolled = 0;
    next_col = (uint16_t)(at >> 4);
    next_wall = (uint16_t)(at >> 5);
    for (i = 0; i < 18 && stage_no < 2; i++) {
        build_column(next_col);
        sdevkit_dma_copy_vram_column((const uint8_t *)col_buf, col_vram, sizeof col_buf);
        next_col++;
        build_wall(next_wall);
        sdevkit_dma_copy_vram_column((const uint8_t *)wall_buf, wall_vram, sizeof wall_buf);
        next_wall++;
    }
    col_vram = 0;
    wall_vram = 0;
    col_half = 0;
    waves_from(at);
}

/* One frame of the camera. The column that will come into view next is built now and copied in
 * the vertical blank; at most one of each layer a frame. */
void stage_scroll(void)
{
    uint16_t s = (uint16_t)(cam_sub + scroll_step);

    scrolled = 0;
    if (cam_x < STAGE_END) {
        cam_x = (uint16_t)(cam_x + (s >> 4));
        cam_sub = (uint8_t)(s & 15);
        scrolled = scroll_step;
    }
    if (stage_no == 2) {
        return;                                     /* das Festzelt: no columns, no walls */
    }
    if (col_vram == 0 && next_col < STAGE_COLS && next_col <= (uint16_t)((cam_x >> 4) + 17)) {
        if (!col_half) {
            build_rows(next_col, 0, ROWS / 2);
            col_half = 1;
        } else {
            build_rows(next_col, ROWS / 2, ROWS);
            col_vram = (uint16_t)(VRAM_BG1_MAP + ((next_col & 32) ? 0x400 : 0) + (next_col & 31));
            col_half = 0;
            next_col++;
        }
    }
    if (!walls_off && wall_vram == 0 && next_wall <= (uint16_t)((cam_x >> 5) + 17)) {
        build_wall(next_wall);
        next_wall++;
    }
}

void stage_vblank(void)
{
    if (stage_no == 2) {
        festzelt_vblank();
        return;
    }
    if (col_vram != 0) {
        sdevkit_dma_copy_vram_column((const uint8_t *)col_buf, col_vram, sizeof col_buf);
        col_vram = 0;
    }
    if (wall_vram != 0) {
        sdevkit_dma_copy_vram_column((const uint8_t *)wall_buf, wall_vram, sizeof wall_buf);
        wall_vram = 0;
    }
    sdevkit_bg_set_scroll(0, (uint16_t)(shown_cam & 0x3FF), 0x3FF);
    if (walls_off) {
        boss_vblank();
    } else {
        sdevkit_bg_set_scroll(1, (uint16_t)((shown_cam >> 1) & 0x3FF), 0x3FF);
    }
}

/* Is the stage solid at this pixel of the screen? Above the stage is open, below it solid. */
uint8_t solid_at(int16_t sx, int16_t sy)
{
    uint16_t col;
    uint8_t row;

    if (sy < 0) {
        return 0;
    }
    if (sy >= SCREEN_H) {
        return 1;
    }
    if (stage_no == 2) {
        return 0;                                   /* the tent is flown over */
    }
    col = (uint16_t)((uint16_t)(cam_x + sx) >> 4);
    if (sx < 0 || col >= STAGE_COLS) {
        return 0;
    }
    row = (uint8_t)((uint16_t)sy >> 4);
    return cell_solid[(uint8_t)((uint8_t)stage[row][col] - ' ')];
}

/* --- the waves -------------------------------------------------------------------------------- */
/* Each wave comes in when its column reaches the right edge of the screen: count enemies of a kind
 * in a row, 20 pixels apart (flies 12), the first one carrying an item. param is the kind's:
 * for beer mats the height of their wave, for dice cups 1 on the ceiling. */
typedef struct {
    uint8_t col, kind, row, count, param, drop;
} Wave;

static const Wave WAVES1[] = {
    /* the bar room: beer mats, pretzels, the first six-pack, dice cups, the Sekt */
    { 18, E_BEERMAT, 5, 5, 1, I_NONE },
    { 26, E_BEERMAT, 8, 5, 1, I_NONE },
    { 31, E_PRETZEL, 4, 1, 0, I_SIXPACK },
    { 38, E_PRETZEL, 3, 4, 0, I_NONE },
    { 44, E_DICECUP, 11, 1, 0, I_NONE },
    { 47, E_DICECUP, 2, 1, 1, I_NONE },
    { 50, E_BEERMAT, 6, 6, 2, I_NONE },
    { 55, E_FLY, 5, 6, 0, I_NONE },
    { 58, E_PRETZEL, 9, 3, 0, I_SEKT },
    { 63, E_DICECUP, 11, 1, 0, I_NONE },
    { 64, E_BEERMAT, 5, 4, 1, I_NONE },
    { 69, E_PRETZEL, 9, 2, 0, I_NONE },
    { 72, E_DARTBOARD, 6, 1, 0, I_SIXPACK },
    /* the cellar: flies, barrels, the stutter beside a real six-pack, the narrow passage */
    { 84, E_FLY, 6, 6, 0, I_NONE },
    { 94, E_BARREL, 10, 2, 0, I_NONE },
    { 100, E_BEERMAT, 7, 1, 1, I_SIXPACK },
    { 103, E_BEERMAT, 4, 1, 1, I_RUCKEL },
    { 109, E_DICECUP, 10, 1, 0, I_NONE },
    { 114, E_PRETZEL, 8, 4, 0, I_NONE },
    { 118, E_FLY, 4, 1, 0, I_ESPRESSO },
    { 124, E_FLY, 7, 8, 0, I_NONE },
    { 130, E_BEERMAT, 6, 4, 2, I_NONE },
    { 138, E_BARREL, 10, 3, 0, I_NONE },
    { 139, E_SLOT, 6, 1, 0, I_SIXPACK },   /* the mid-boss: the stage waits for it */
    /* the kitchen: everything at once */
    { 148, E_BEERMAT, 7, 6, 2, I_NONE },
    { 157, E_DICECUP, 8, 1, 0, I_NONE },
    { 160, E_FLY, 6, 6, 0, I_NONE },
    { 166, E_PRETZEL, 4, 4, 0, I_NONE },
    { 167, E_PRETZEL, 9, 4, 0, I_NONE },
    { 170, E_DARTBOARD, 7, 1, 0, I_ESPRESSO },
    { 176, E_BEERMAT, 8, 6, 1, I_NONE },
    { 177, E_BEERMAT, 4, 6, 2, I_NONE },
    { 182, E_BARREL, 11, 3, 0, I_NONE },
    { 186, E_STEIN, 6, 1, 0, I_SIXPACK },
    { 192, E_FLY, 5, 10, 0, I_NONE },      /* the busiest wave: the benchmark of stage 1 */
    { 193, E_PRETZEL, 8, 4, 0, I_NONE },
    { 194, E_BEERMAT, 10, 5, 2, I_NONE },
    { 200, E_PRETZEL, 6, 1, 0, I_SIXPACK },
    { 206, E_STEIN, 4, 1, 0, I_NONE },
    { 207, E_STEIN, 9, 1, 0, I_NONE },
};

/* Stage 2: the densest waves, the busiest at the end of the Mett: the benchmark of the game. */
static const Wave WAVES2[] = {
    { 18, E_HOP, 6, 6, 1, I_NONE },
    { 24, E_FLY, 8, 8, 0, I_NONE },
    { 30, E_HOP, 4, 6, 2, I_SIXPACK },
    { 34, E_METTIGEL, 11, 2, 0, I_NONE },
    { 38, E_PRETZEL, 9, 5, 0, I_NONE },
    { 44, E_DICECUP, 11, 1, 0, I_NONE },
    { 48, E_HOP, 8, 6, 2, I_NONE },
    { 52, E_BEERMAT, 4, 6, 1, I_SEKT },
    { 58, E_METTIGEL, 11, 3, 0, I_NONE },
    { 62, E_FLY, 4, 10, 0, I_NONE },
    { 66, E_STEIN, 9, 1, 0, I_ESPRESSO },
    { 72, E_HOP, 9, 6, 1, I_NONE },
    { 76, E_BARREL, 11, 3, 0, I_NONE },
    { 80, E_DARTBOARD, 5, 1, 0, I_SIXPACK },
    { 88, E_FLY, 7, 10, 0, I_NONE },
    { 92, E_HOP, 6, 6, 2, I_NONE },
    { 97, E_METTIGEL, 9, 2, 1, I_NONE },
    { 104, E_PRETZEL, 6, 5, 0, I_RUCKEL },
    { 105, E_PRETZEL, 9, 1, 0, I_SIXPACK },
    { 112, E_HOP, 7, 8, 1, I_NONE },
    { 118, E_FLY, 6, 10, 0, I_NONE },
    { 126, E_HOP, 8, 6, 2, I_NONE },
    { 134, E_FLY, 6, 10, 0, I_NONE },
    { 140, E_HOP, 5, 8, 1, I_SIXPACK },
    { 150, E_STEIN, 6, 1, 0, I_NONE },
    { 156, E_METTIGEL, 11, 3, 0, I_NONE },
    { 160, E_FLY, 4, 8, 0, I_NONE },       /* the densest second of the game */
    { 161, E_HOP, 9, 8, 2, I_NONE },
    { 162, E_BEERMAT, 6, 6, 2, I_NONE },
    { 170, E_DARTBOARD, 7, 1, 0, I_ESPRESSO },
    { 178, E_HOP, 4, 8, 1, I_NONE },
    { 184, E_FLY, 8, 8, 0, I_NONE },
    { 190, E_STEIN, 7, 1, 0, I_SIXPACK },
    { 198, E_HOP, 6, 8, 2, I_NONE },
    { 204, E_PRETZEL, 4, 6, 0, I_NONE },
};

/* Stage 3: roast chickens, gingerbread hearts, wasps and the fair's booths; the tuba in the middle. */
static const Wave WAVES3[] = {
    { 18, E_BEERMAT, 6, 5, 1, I_NONE },
    { 24, E_HENDL, 4, 4, 0, I_NONE },
    { 28, E_HENDL, 10, 4, 0, I_SIXPACK },
    { 34, E_HEART, 7, 3, 0, I_NONE },
    { 40, E_FLY, 5, 8, 0, I_NONE },
    { 44, E_PRETZEL, 9, 4, 0, I_NONE },
    { 48, E_STEIN, 6, 1, 0, I_SEKT },
    { 54, E_HENDL, 7, 6, 0, I_NONE },
    { 58, E_HEART, 4, 4, 0, I_NONE },
    { 62, E_BEERMAT, 9, 6, 2, I_ESPRESSO },
    { 68, E_FLY, 8, 10, 0, I_NONE },
    { 72, E_DARTBOARD, 6, 1, 0, I_SIXPACK },
    { 80, E_HENDL, 3, 5, 0, I_NONE },
    { 81, E_HENDL, 11, 5, 0, I_NONE },
    { 86, E_HEART, 8, 4, 0, I_RUCKEL },
    { 90, E_PRETZEL, 5, 5, 0, I_NONE },
    { 94, E_STEIN, 9, 1, 0, I_SIXPACK },
    { 100, E_TUBA, 7, 1, 0, I_SIXPACK },   /* the mid-boss: the stage waits for it */
    { 108, E_FLY, 6, 10, 0, I_NONE },
    { 112, E_HEART, 5, 5, 0, I_NONE },
    { 116, E_HENDL, 9, 6, 0, I_NONE },
    { 122, E_BEERMAT, 4, 6, 1, I_NONE },
    { 123, E_BEERMAT, 10, 6, 2, I_NONE },
    { 130, E_DARTBOARD, 7, 1, 0, I_ESPRESSO },
    { 138, E_HENDL, 5, 8, 0, I_NONE },
    { 142, E_HEART, 9, 5, 0, I_NONE },
    { 148, E_STEIN, 5, 1, 0, I_SIXPACK },
    { 149, E_STEIN, 10, 1, 0, I_NONE },
    { 156, E_FLY, 7, 8, 0, I_NONE },
    { 160, E_HENDL, 4, 6, 0, I_NONE },
    { 161, E_HEART, 10, 4, 0, I_NONE },
    { 166, E_BEERMAT, 6, 6, 2, I_SIXPACK },
    { 172, E_PRETZEL, 8, 6, 0, I_NONE },
    { 176, E_DARTBOARD, 5, 1, 0, I_NONE },
    { 182, E_HENDL, 8, 6, 0, I_NONE },
    { 186, E_FLY, 4, 8, 0, I_NONE },       /* the busiest wave of the tent */
    { 187, E_HEART, 9, 3, 0, I_NONE },
    { 192, E_STEIN, 7, 1, 0, I_SIXPACK },
    { 198, E_HENDL, 5, 6, 0, I_NONE },
    { 204, E_PRETZEL, 9, 6, 0, I_NONE },
};

static const Wave *waves = WAVES1;
static uint8_t wave_count = sizeof WAVES1 / sizeof WAVES1[0];

/* The waves after a camera at pixel at: those already on the screen there are left out. */
static void waves_from(uint16_t at)
{
    uint8_t i;

    for (i = 0; i < wave_count; i++) {
        if ((uint16_t)(waves[i].col * 16) > (uint16_t)(at + SCREEN_W + 8)) {
            break;
        }
    }
    wave_next = i;
}

/* At most one wave a frame: the camera moves a column in 32 frames, and no two waves share one. */
void waves_spawn(void)
{
    if (wave_next < wave_count && (uint16_t)(waves[wave_next].col * 16) <= (uint16_t)(cam_x + SCREEN_W + 8)) {
        const Wave *w = &waves[wave_next];
        int16_t x = (int16_t)(w->col * 16 - cam_x + 8);
        int16_t y = (int16_t)(w->row * 16 + 8);
        uint8_t gap = w->kind == E_FLY ? 12 : 20;
        uint8_t count = w->count;
        uint8_t i;

        DAMPF_RANGE(count, 1, 10);
        for (i = 0; i < count; i++) {
            Obj *e = enemy_spawn(w->kind, FX(x), FX(y), w->param);
            if (e == 0) {
                break;                              /* the table is full: so it stays this frame */
            }
            e->anim = (uint8_t)(i * 8);             /* a row of beer mats is a wave */
            if (i == 0) {
                e->flags = (uint8_t)(e->flags | (w->drop << 4));
            }
            x = (int16_t)(x + gap);
        }
        wave_next++;
    }
}

/* --- checkpoints -------------------------------------------------------------------------------- */
static const uint16_t CHECKPOINTS1[] = { 0, 960, 1280, 2304, 2944 };
static const uint16_t CHECKPOINTS2[] = { 0, 1120, 1856, 2624, 2944 };
static const uint16_t CHECKPOINTS3[] = { 0, 736, 1472, 2200, 2944 };   /* the tent: one a leg of the loop */
#define CHECKPOINT_COUNT 5

static const uint16_t *checkpoints = CHECKPOINTS1;

/* The stage to play: its map, cells, waves and checkpoints (before stage_start). */
void stage_select(uint8_t n)
{
    stage_no = n;
    stage = n == 0 ? STAGE1 : STAGE2;              /* stage 3 has none: solid_at knows */
    if (n == 0) {
        waves = WAVES1;
        wave_count = (uint8_t)(sizeof WAVES1 / sizeof WAVES1[0]);
        checkpoints = CHECKPOINTS1;
    } else if (n == 1) {
        waves = WAVES2;
        wave_count = (uint8_t)(sizeof WAVES2 / sizeof WAVES2[0]);
        checkpoints = CHECKPOINTS2;
    } else {
        waves = WAVES3;
        wave_count = (uint8_t)(sizeof WAVES3 / sizeof WAVES3[0]);
        checkpoints = CHECKPOINTS3;
    }
}

uint16_t checkpoint_before(uint16_t px)
{
    uint8_t i = CHECKPOINT_COUNT;

    while (i > 1 && checkpoints[i - 1] > px) {
        i--;
    }
    return checkpoints[i - 1];
}

uint8_t checkpoint_reached(uint16_t px)
{
    uint8_t i;

    for (i = 1; i < CHECKPOINT_COUNT; i++) {
        if (checkpoints[i] == px) {
            return 1;
        }
    }
    return 0;
}
