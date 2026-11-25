/*
 * The maze and the background map.
 *
 * The maze is 16 x 13 cells of 16 x 16 pixels, written as text below. On screen every cell is
 * 2 x 2 background tiles of 8 x 8 pixels: a wall tile is chosen by which of its four neighbours
 * are walls too (the tile sheet has one for each of the 16 cases), so the walls get their bevels
 * and round corners by themselves.
 *
 * The background map lives in RAM (bg_map, 32 x 32 entries) and is queued for VRAM whenever
 * something changed (game_tick in game.c): 2 KB, well within what one vertical blank can copy.
 */
#include "catchme.h"

/* '#' wall, '.' crumb, 'o' chili, '-' the door of the cats' house, 'H' inside the house. */
static const char *const LAYOUT[MAZE_H] = {
    "################",
    "#o.....##.....o#",
    "#.##.#.##.#.##.#",
    "#..............#",
    "#.#..#....#..#.#",
    "#.#..##--##..#.#",
    "......#HH#......",
    "#.#.#.####.#.#.#",
    "#..............#",
    "#.##.##..##.##.#",
    "#o....#..#....o#",
    "#.....#..#.....#",
    "################",
};

/* The tiles of the background sheet (assets/maze.png). */
#define TILE_WALL   1          /* + the mask of wall neighbours: 1 up, 2 right, 4 down, 8 left */
#define TILE_CRUMB  17
#define TILE_DOOR   18
#define TILE_CHILI  20         /* four tiles: top left, top right, bottom left, bottom right */
#define TILE_TEXT   32         /* the font, in the order of FONT below */
#define TILE_YELLOW 80         /* the same font in yellow */

static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";

uint8_t maze[MAZE_H][MAZE_W];
uint8_t maze_crumbs;
uint16_t bg_map[32 * 32];
uint8_t bg_map_dirty;

void maze_reset(void)
{
    uint8_t x, y;

    maze_crumbs = 0;
    for (y = 0; y < MAZE_H; y++) {
        for (x = 0; x < MAZE_W; x++) {
            char c = LAYOUT[y][x];
            uint8_t cell = CELL_EMPTY;
            if (c == '#') {
                cell = CELL_WALL;
            } else if (c == '.') {
                cell = CELL_CRUMB;
            } else if (c == 'o') {
                cell = CELL_CHILI;
            } else if (c == '-') {
                cell = CELL_DOOR;
            } else if (c == 'H') {
                cell = CELL_HOUSE;
            }
            if (cell == CELL_CRUMB || cell == CELL_CHILI) {
                maze_crumbs++;
            }
            maze[y][x] = cell;
        }
    }
}

uint8_t maze_cell(int8_t x, int8_t y)
{
    if (y < 0 || y >= MAZE_H) {
        return CELL_WALL;
    }
    if (x < 0 || x >= MAZE_W) {
        return y == TUNNEL_ROW ? CELL_EMPTY : CELL_WALL;
    }
    return maze[y][x];
}

/* Whether the background tile at (tx, ty) of the screen belongs to a wall. */
static uint8_t wall_tile(int8_t tx, int8_t ty)
{
    if (ty < MAZE_TOP / 8 || ty >= MAZE_TOP / 8 + MAZE_H * 2 || tx < 0 || tx >= MAZE_W * 2) {
        return 0;
    }
    return maze[(ty - MAZE_TOP / 8) >> 1][tx >> 1] == CELL_WALL;
}

static void set_tile(uint8_t tx, uint8_t ty, uint16_t tile)
{
    bg_map[(uint16_t)ty * 32 + tx] = tile;
}

/* The four tiles of one cell, from what the cell holds. */
static void draw_cell(uint8_t x, uint8_t y)
{
    uint8_t tx = (uint8_t)(x * 2);
    uint8_t ty = (uint8_t)(MAZE_TOP / 8 + y * 2);
    uint8_t cell = maze[y][x];
    uint8_t i;

    if (cell == CELL_WALL) {
        for (i = 0; i < 4; i++) {
            int8_t px = (int8_t)(tx + (i & 1));
            int8_t py = (int8_t)(ty + (i >> 1));
            uint8_t mask = (uint8_t)(wall_tile(px, (int8_t)(py - 1)) | (wall_tile((int8_t)(px + 1), py) << 1)
                                     | (wall_tile(px, (int8_t)(py + 1)) << 2) | (wall_tile((int8_t)(px - 1), py) << 3));
            set_tile((uint8_t)px, (uint8_t)py, (uint16_t)(TILE_WALL + mask));
        }
        return;
    }
    for (i = 0; i < 4; i++) {
        set_tile((uint8_t)(tx + (i & 1)), (uint8_t)(ty + (i >> 1)), 0);
    }
    if (cell == CELL_CRUMB) {
        set_tile(tx, ty, TILE_CRUMB);
    } else if (cell == CELL_CHILI) {
        for (i = 0; i < 4; i++) {
            set_tile((uint8_t)(tx + (i & 1)), (uint8_t)(ty + (i >> 1)), (uint16_t)(TILE_CHILI + i));
        }
    } else if (cell == CELL_DOOR) {
        set_tile(tx, ty, TILE_DOOR);
        set_tile((uint8_t)(tx + 1), ty, TILE_DOOR);
    }
}

void maze_draw(void)
{
    uint8_t x, y;

    for (y = 0; y < MAZE_H; y++) {
        for (x = 0; x < MAZE_W; x++) {
            draw_cell(x, y);
        }
    }
    bg_map_dirty = 1;
}

void maze_clear_cell(uint8_t x, uint8_t y)
{
    maze[y][x] = CELL_EMPTY;
    maze_crumbs--;
    draw_cell(x, y);
    bg_map_dirty = 1;
}

/* The wall colours of palette 0: dark to bright, and all white while blinking. */
static const uint16_t WALL_COLOURS[5] = {
    SDEVKIT_RGB8(22, 18, 64), SDEVKIT_RGB8(44, 38, 128), SDEVKIT_RGB8(70, 68, 190),
    SDEVKIT_RGB8(112, 120, 236), SDEVKIT_RGB8(184, 196, 255),
};

static const uint16_t WALL_WHITE[5] = {
    SDEVKIT_RGB8(250, 250, 250), SDEVKIT_RGB8(250, 250, 250), SDEVKIT_RGB8(250, 250, 250),
    SDEVKIT_RGB8(250, 250, 250), SDEVKIT_RGB8(250, 250, 250),
};

uint8_t maze_blink(uint8_t on)
{
    return sdevkit_queue_cgram(1, on ? WALL_WHITE : WALL_COLOURS, sizeof WALL_COLOURS);
}

static uint16_t glyph(char c, uint8_t yellow)
{
    uint8_t i = 0;

    while (FONT[i] != 0 && FONT[i] != c) {
        i++;
    }
    if (FONT[i] == 0) {
        i = 0;
    }
    return (uint16_t)((yellow ? TILE_YELLOW : TILE_TEXT) + i);
}

/* Text on the screen at tile (x, y); the background map is copied in the next vertical blank. */
void text(uint8_t x, uint8_t y, const char *s, uint8_t yellow)
{
    while (*s != 0) {
        set_tile(x, y, glyph(*s, yellow));
        x++;
        s++;
    }
    bg_map_dirty = 1;
}

void number(uint8_t x, uint8_t y, uint32_t value, uint8_t digits)
{
    uint8_t i;

    for (i = digits; i > 0; i--) {
        set_tile((uint8_t)(x + i - 1), y, glyph((char)('0' + value % 10), 0));
        value /= 10;
    }
    bg_map_dirty = 1;
}
