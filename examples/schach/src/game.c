/*
 * Schach: the screen, the joypad and the course of a game. The player is white, the console
 * black.
 *
 * Layer 1 is the board in 16 x 16 tiles (a bit of BGMODE): one map entry is one square, a light
 * or dark square with or without a piece on it. The squares of the last move, the squares the
 * piece in the hand can go to and the king in check keep their tiles and get another palette
 * (1 to 3), whose square colours differ. Layer 1 is scrolled by 4 pixels, so that the board's
 * letters and numbers on layer 2 sit in the middle of their squares. Layer 2 is text in 8 x 8
 * tiles: the panel at the right, the moves below. The cursor and the piece in the hand are
 * sprites.
 *
 * It uses the optional layers of sdevkit/easy.h: the registers in sdevkit_ppu, the sprite list,
 * the map copies queued in the tick; game_vblank is one call.
 *
 * VRAM (16-bit words): the board's tiles at 0x0000, the font at 0x1000, the board's map at
 * 0x2000, the text's map at 0x2400, the sprites at 0x4000. CGRAM: palettes 0 to 3 the board,
 * 4 and 5 the text (white, yellow), sprite palette 0 the pieces and the cursor.
 *
 * The console thinks for a node limit, some steps of the search every tick (search_run, schach.h),
 * so the cursor keeps moving while it thinks, and the same move comes on the SNES and on the
 * desktop. A tick of the search takes about two frames on the SNES.
 *
 * Sound (assets/sfx/sfx.json, one voice): a piece put down or taken on every move, "your move" when
 * the console has moved, "check" and "checkmate". They come in the same tick, and with one voice
 * the higher priority wins: the last two over the others, "your move" over the console's piece.
 */
#include "search.h"
#include "book.h"
#include "schach.h"
#include "sdevkit/loop.h"
#include "sdevkit/easy.h"
#include "board_gfx.h"
#include "sprites_gfx.h"
#include "font_gfx.h"
#include "sfx.h"

#define VRAM_BOARD     0x0000
#define VRAM_FONT      0x1000
#define VRAM_BOARD_MAP 0x2000
#define VRAM_TEXT_MAP  0x2400
#define VRAM_SPRITES   0x4000

#define PAL(n)         ((uint16_t)((n) << 10))
#define PAL_LAST       1               /* the board's palettes */
#define PAL_TARGET     2
#define PAL_CHECK      3
#define PAL_TEXT       4
#define PAL_YELLOW     5

#define BOARD_PX       20              /* the board's top left corner on the screen, in pixels */
#define BOARD_SHIFT    4               /* layer 1 is scrolled by this: square (0, 0) is map cell (1, 1) */
#define PANEL          19              /* the panel's first text column */
#define LIST_ROW       21              /* the move list's first text row */
#define LIST_ROWS      7

enum { ST_TITLE, ST_PLAYER, ST_PROMOTE, ST_THINK, ST_OVER };

static uint16_t board_map[16 * 32];
static uint8_t board_dirty;
static uint16_t text_map[28 * 32];
static uint8_t text_from = 0xFF, text_to;

static uint8_t state;
static uint8_t square_pal[128];        /* the palette of each square: 0 or PAL_LAST ... */
static uint8_t last_from = NO_SQ, last_to = NO_SQ;
static uint8_t cur_file = 4, cur_rank = 1;   /* the cursor: e2 */
static uint8_t held = NO_SQ;           /* the square of the piece in the hand */
static uint8_t promo_from, promo_to, promo_choice;
static uint16_t repeat_wait;
static uint16_t timer;
static uint16_t think_frames;
static uint8_t shown_depth;
static uint8_t from_book;              /* the console's move comes from the book */
static uint8_t level = LEVEL_DEFAULT;
static const uint16_t LEVEL_NODES[LEVELS] = { NODES_EASY, NODES_NORMAL, NODES_STRONG };
static const char *const LEVEL_NAME[LEVELS] = { "Easy", "Normal", "Strong" };
static uint16_t seed;

/* The moves of the game as text, the last 32 half moves. */
static char san[32][8];
static uint16_t plies;

/* --- the board ------------------------------------------------------------------------------- */
static uint8_t piece_index(uint8_t p)
{
    if (p == EMPTY) {
        return 0;
    }
    return (uint8_t)(COLOR(p) == WHITE ? TYPE(p) : 6 + TYPE(p));
}

/* The number of a 16 x 16 tile of a sheet with eight elements a row. */
static uint16_t element_tile(uint8_t e)
{
    return (uint16_t)((e >> 3) * 32 + (e & 7) * 2);
}

static void draw_square(uint8_t sq)
{
    uint8_t f = FILE_OF(sq), r = RANK_OF(sq);
    uint8_t p = sq == held ? EMPTY : board[sq];
    uint8_t dark = (uint8_t)(((f + r) & 1) == 0);
    uint8_t e = (uint8_t)(piece_index(p) * 2 + dark);

    board_map[(uint16_t)(8 - r) * 32 + 1 + f] = (uint16_t)(element_tile(e) | PAL(square_pal[sq]));
    board_dirty = 1;
}

static uint8_t is_target(uint8_t from, uint8_t to)
{
    uint16_t i;

    for (i = 0; i < legal_n; i++) {
        if (legal_from[i] == from && legal_to[i] == to) {
            return 1;
        }
    }
    return 0;
}

/* The palettes of all squares (last move, targets of the piece in the hand, the king in check)
 * and the squares drawn again. */
static void draw_board(void)
{
    uint8_t sq, i;

    for (sq = 0; sq < 128; sq++) {
        square_pal[sq] = 0;
    }
    if (last_from != NO_SQ) {
        square_pal[last_from] = PAL_LAST;
        square_pal[last_to] = PAL_LAST;
    }
    if (held != NO_SQ) {
        for (i = 0; i < legal_n; i++) {
            if (legal_from[i] == held) {
                square_pal[legal_to[i]] = PAL_TARGET;
            }
        }
    }
    if (in_check()) {
        square_pal[KING_SQ(side)] = PAL_CHECK;
    }
    for (sq = 0; sq < 128; sq++) {
        if (!OFF(sq)) {
            draw_square(sq);
        }
    }
}

/* --- text ------------------------------------------------------------------------------------ */
static void text_dirty(uint8_t y)
{
    if (text_from == 0xFF || y < text_from) {
        text_from = y;
    }
    if (y > text_to) {
        text_to = y;
    }
}

/* Writes s at column x of row y, then spaces up to width characters (0: no padding). */
static void text_at(uint8_t x, uint8_t y, const char *s, uint8_t width, uint8_t pal)
{
    uint16_t at = (uint16_t)(y * 32 + x);
    uint8_t n = 0;

    while (*s != 0 && x + n < 32) {
        text_map[at + n] = (uint16_t)((uint8_t)((uint8_t)*s - 32) | PAL(pal) | SDEVKIT_BG_TIL_PRIO);
        s++;
        n++;
    }
    while (n < width && x + n < 32) {
        text_map[at + n] = 0;
        n++;
    }
    text_dirty(y);
}

static void panel(uint8_t y, const char *s)
{
    text_at(PANEL, y, s, 13, PAL_TEXT);
}

static void panel_yellow(uint8_t y, const char *s)
{
    text_at(PANEL, y, s, 13, PAL_YELLOW);
}

/* A number of up to three digits, without leading zeros, then spaces to width. */
static void number_text(uint16_t v, char *out)
{
    uint8_t n = 0;

    if (v >= 100) {
        out[n++] = (char)('0' + v / 100);
    }
    if (v >= 10) {
        out[n++] = (char)('0' + v / 10 % 10);
    }
    out[n++] = (char)('0' + v % 10);
    out[n] = 0;
}

/* The last 14 full moves, two columns of seven rows below the board: the number, white's move,
 * black's move, each cut to 6 characters, and a space before the next column. */
static void draw_moves(void)
{
    uint16_t full = (uint16_t)((plies + 1) / 2);
    uint16_t first = full > LIST_ROWS * 2 ? (uint16_t)(full - LIST_ROWS * 2 + 1) : 1;
    uint8_t i, k;

    for (i = 0; i < LIST_ROWS * 2; i++) {
        uint16_t m = (uint16_t)(first + i);
        uint8_t x = (uint8_t)(i < LIST_ROWS ? 0 : 16);
        uint8_t y = (uint8_t)(LIST_ROW + i % LIST_ROWS);
        uint16_t h = (uint16_t)((m - 1) * 2);
        char line[17], num[4];

        for (k = 0; k < 16; k++) {
            line[k] = ' ';
        }
        line[16] = 0;
        if (m <= full) {
            for (k = 0; k < 6 && san[h & 31][k] != 0; k++) {
                line[3 + k] = san[h & 31][k];
            }
            for (k = 0; h + 1 < plies && k < 6 && san[(h + 1) & 31][k] != 0; k++) {
                line[9 + k] = san[(h + 1) & 31][k];
            }
        }
        text_at(x, y, line, 16, PAL_TEXT);
        if (m <= full) {
            number_text(m % 100, num);
            text_at((uint8_t)(x + (m % 100 < 10 ? 1 : 0)), y, num, 0, PAL_YELLOW);
        }
    }
}

static void draw_labels(void)
{
    char s[2];
    uint8_t i;

    s[1] = 0;
    for (i = 0; i < 8; i++) {
        s[0] = (char)('a' + i);
        text_at((uint8_t)(3 + 2 * i), 19, s, 0, PAL_TEXT);
        s[0] = (char)('8' - i);
        text_at(1, (uint8_t)(3 + 2 * i), s, 0, PAL_TEXT);
    }
}

/* --- the sprites ----------------------------------------------------------------------------- */
/* Drawn anew every tick (the sprite list, sdevkit_sprite): the cursor in front, then the piece in
 * the hand, three pixels above its square. The cursor blinks while the console thinks. */
static void draw_sprites(void)
{
    int16_t x = (int16_t)(BOARD_PX + cur_file * 16);
    int16_t y = (int16_t)(BOARD_PX + (7 - cur_rank) * 16);

    if (state == ST_TITLE || state == ST_OVER) {
        return;
    }
    if (!(state == ST_THINK && ((timer >> 4) & 1))) {
        sdevkit_sprite(x, y, element_tile(held != NO_SQ ? 1 : 0), SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
    }
    if (held != NO_SQ) {
        uint8_t p = board[held];
        uint8_t e = (uint8_t)(COLOR(p) == WHITE ? 1 + TYPE(p) : 7 + TYPE(p));
        sdevkit_sprite(x, (int16_t)(y - 3), element_tile(e), SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
    }
}

/* --- the course of the game ------------------------------------------------------------------ */
static const char *const END_TEXT[6][2] = {
    { "", "" },
    { "Checkmate", "" },
    { "Stalemate", "" },
    { "50 moves", "rule" },
    { "Threefold", "repetition" },
    { "Insufficient", "material" },
};

static void player_turn(void)
{
    state = ST_PLAYER;
    held = NO_SQ;
    panel_yellow(3, "Your move");
    panel(4, in_check() ? "Check!" : "");
    draw_board();
}

static void console_turn(void)
{
    state = ST_THINK;
    think_frames = 0;
    shown_depth = 0;
    held = NO_SQ;
    panel_yellow(3, "Thinking");
    panel(4, in_check() ? "Check!" : "");
    draw_board();
    from_book = book_find();
    if (!from_book) {
        search_start(LEVEL_NODES[level], MAX_DEPTH);
    }
}

/* A move of either side: written down, played, shown; then the end of the game or the other side. */
static void move_made(uint8_t from, uint8_t to, uint8_t flag)
{
    uint8_t end;

    sdevkit_sfx_play(board[to] != EMPTY || flag == MF_EP ? SFX_TAKE : SFX_PUT);
    move_text(from, to, flag, san[plies & 31]);
    if (side == BLACK) {
        panel(6, "I played:");
        panel_yellow(7, san[plies & 31]);
    }
    plies++;
    play_move(from, to, flag);
    last_from = from;
    last_to = to;
    draw_moves();
    list_legal();
    end = game_end();
    if (end == END_MATE) {
        sdevkit_sfx_play(SFX_MATE);
    } else if (in_check()) {
        sdevkit_sfx_play(SFX_CHECK);
    } else if (end == END_NONE && side == WHITE) {
        sdevkit_sfx_play(SFX_YOUR_MOVE);
    }
    if (end != END_NONE) {
        state = ST_OVER;
        held = NO_SQ;
        panel_yellow(3, "Game over");
        panel(4, "");
        panel_yellow(9, END_TEXT[end][0]);
        panel(10, END_TEXT[end][1]);
        panel_yellow(11, end != END_MATE ? "Draw" : side == BLACK ? "You win!" : "I win!");
        panel(13, "START: again");
        draw_board();
    } else if (side == WHITE) {
        player_turn();
    } else {
        console_turn();
    }
}

static void new_game(void)
{
    uint8_t y;

    set_start();
    search_clear();
    book_seed(seed);
    plies = 0;
    last_from = NO_SQ;
    last_to = NO_SQ;
    cur_file = 4;
    cur_rank = 1;
    for (y = 3; y < 18; y++) {
        panel(y, "");
    }
    panel(17, LEVEL_NAME[level]);
    draw_moves();
    list_legal();
    player_turn();
}

static void draw_level(void)
{
    char s[13];
    const char *name = LEVEL_NAME[level];
    uint8_t n = 0;

    s[n++] = level > 0 ? '<' : ' ';
    s[n++] = ' ';
    while (*name != 0) {
        s[n++] = *name++;
    }
    s[n++] = ' ';
    s[n++] = level + 1 < LEVELS ? '>' : ' ';
    s[n] = 0;
    panel_yellow(12, s);
}

static void title(void)
{
    uint8_t y;

    state = ST_TITLE;
    set_start();
    held = NO_SQ;
    last_from = NO_SQ;
    for (y = 3; y < 18; y++) {
        panel(y, "");
    }
    panel(7, "You: white");
    panel(8, "Me: black");
    panel(11, "Level:");
    draw_level();
    panel_yellow(15, "Press START");
    draw_board();
}

/* --- the joypad ------------------------------------------------------------------------------ */
/* The cursor: one square per press, held down it repeats after a pause. */
static void move_cursor(uint16_t pad, uint16_t pressed)
{
    uint16_t dirs = (uint16_t)(pad & (SDEVKIT_KEY_UP | SDEVKIT_KEY_DOWN | SDEVKIT_KEY_LEFT | SDEVKIT_KEY_RIGHT));
    uint8_t step = 0;

    if (pressed & dirs) {
        step = 1;
        repeat_wait = 14;
    } else if (dirs != 0) {
        if (--repeat_wait == 0) {
            step = 1;
            repeat_wait = 5;
        }
    }
    if (!step) {
        return;
    }
    if ((pad & SDEVKIT_KEY_UP) && cur_rank < 7) {
        cur_rank++;
    }
    if ((pad & SDEVKIT_KEY_DOWN) && cur_rank > 0) {
        cur_rank--;
    }
    if ((pad & SDEVKIT_KEY_RIGHT) && cur_file < 7) {
        cur_file++;
    }
    if ((pad & SDEVKIT_KEY_LEFT) && cur_file > 0) {
        cur_file--;
    }
}

static uint8_t has_moves(uint8_t from)
{
    uint16_t i;

    for (i = 0; i < legal_n; i++) {
        if (legal_from[i] == from) {
            return 1;
        }
    }
    return 0;
}

static void draw_promotion(void)
{
    static const char *const NAMES[4] = { "  Queen", "  Rook", "  Bishop", "  Knight" };
    uint8_t i;

    panel(12, "Promote to:");
    for (i = 0; i < 4; i++) {
        if (i == promo_choice) {
            panel_yellow((uint8_t)(13 + i), NAMES[i]);
            text_at(PANEL, (uint8_t)(13 + i), ">", 0, PAL_YELLOW);
        } else {
            panel((uint8_t)(13 + i), NAMES[i]);
        }
    }
}

static void clear_promotion(void)
{
    uint16_t y;

    for (y = 12; y < 17; y++) {
        panel(y, "");
    }
}

static void player(uint16_t pad, uint16_t pressed)
{
    uint8_t sq;

    move_cursor(pad, pressed);
    sq = SQ(cur_file, cur_rank);
    if (pressed & SDEVKIT_KEY_B) {
        if (held != NO_SQ) {
            held = NO_SQ;
            draw_board();
        }
        return;
    }
    if (!(pressed & SDEVKIT_KEY_A)) {
        return;
    }
    if (held != NO_SQ && is_target(held, sq)) {
        if (TYPE(board[held]) == PAWN && cur_rank == 7) {
            state = ST_PROMOTE;
            promo_from = held;
            promo_to = sq;
            promo_choice = 0;
            draw_promotion();
            return;
        }
        {
            uint8_t from = held, i, flag = MF_NORMAL;
            for (i = 0; i < legal_n; i++) {
                if (legal_from[i] == from && legal_to[i] == sq) {
                    flag = legal_flag[i];
                }
            }
            held = NO_SQ;
            move_made(from, sq, flag);
        }
        return;
    }
    if (sq == held) {
        held = NO_SQ;                    /* put back */
    } else if (board[sq] != EMPTY && COLOR(board[sq]) == WHITE && has_moves(sq)) {
        held = sq;
    }
    draw_board();
}

static void promote(uint16_t pressed)
{
    static const uint8_t FLAG[4] = { MF_PROMO_Q, MF_PROMO_R, MF_PROMO_B, MF_PROMO_N };

    if (pressed & SDEVKIT_KEY_UP) {
        promo_choice = (uint8_t)((promo_choice + 3) & 3);
        draw_promotion();
    } else if (pressed & SDEVKIT_KEY_DOWN) {
        promo_choice = (uint8_t)((promo_choice + 1) & 3);
        draw_promotion();
    } else if (pressed & SDEVKIT_KEY_B) {
        clear_promotion();
        state = ST_PLAYER;
    } else if (pressed & SDEVKIT_KEY_A) {
        clear_promotion();
        held = NO_SQ;
        move_made(promo_from, promo_to, FLAG[promo_choice]);
    }
}

static void think(void)
{
    static const char *const DOTS[4] = { "Thinking", "Thinking.", "Thinking..", "Thinking..." };
    uint8_t done;

    think_frames++;
    done = from_book ? 1 : search_run(STEPS);
    if ((think_frames & 15) == 0) {
        panel_yellow(3, DOTS[(think_frames >> 4) & 3]);
    }
    if (!from_book && best_depth != shown_depth) {
        char s[10];
        shown_depth = best_depth;
        s[0] = 'D';
        s[1] = 'e';
        s[2] = 'p';
        s[3] = 't';
        s[4] = 'h';
        s[5] = ' ';
        number_text(shown_depth, s + 6);
        panel(5, s);
    }
    if (done && think_frames >= MIN_THINK) {
        if (from_book) {
            panel(5, "From my book");
            move_made(book_from, book_to, book_flag);
        } else {
            panel(5, "");
            move_made(best_from, best_to, best_flag);
        }
    }
}

/* --- the frame rhythm ------------------------------------------------------------------------ */
void game_init(uint16_t init_seed)
{
    uint8_t i;
    uint16_t j;

    seed = init_seed;
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1 | SDEVKIT_BG1_TILES_16;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_OBJ_ENABLE;
    sdevkit_ppu.bgnba[0] = SDEVKIT_BGNBA(VRAM_BOARD, VRAM_FONT);
    sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(VRAM_BOARD_MAP, SDEVKIT_SC_32x32);
    sdevkit_ppu.bgsc[1] = SDEVKIT_BGSC(VRAM_TEXT_MAP, SDEVKIT_SC_32x32);
    sdevkit_ppu.hofs[0] = (uint16_t)(16 - BOARD_PX + 0x400);
    sdevkit_ppu.vofs[0] = (uint16_t)(16 - BOARD_PX - 1 + 0x400);
    sdevkit_ppu.vofs[1] = 0x3FF;
    sdevkit_ppu.obsel = SDEVKIT_OBSEL(VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_vram_copy(VRAM_BOARD, board_gfx_tiles, BOARD_GFX_TILES_SIZE);
    for (i = 0; i < 4; i++) {            /* palettes 0 to 3: the board's; 1 to 3 get other square colours */
        sdevkit_cgram_copy((uint8_t)(i * 16), board_gfx_pal, BOARD_GFX_PAL_SIZE);
    }
    sdevkit_cgram_set(PAL_LAST * 16 + 1, 0x43BE);    /* the last move: yellow */
    sdevkit_cgram_set(PAL_LAST * 16 + 2, 0x26D9);
    sdevkit_cgram_set(PAL_TARGET * 16 + 1, 0x4B76);  /* where the piece in the hand can go: green */
    sdevkit_cgram_set(PAL_TARGET * 16 + 2, 0x328F);
    sdevkit_cgram_set(PAL_CHECK * 16 + 1, 0x3A1E);   /* the king in check: red */
    sdevkit_cgram_set(PAL_CHECK * 16 + 2, 0x2559);
    sdevkit_vram_copy(VRAM_FONT, font_gfx_tiles, FONT_GFX_TILES_SIZE);
    sdevkit_cgram_copy(PAL_TEXT * 16, font_gfx_pal, FONT_GFX_PAL_SIZE);
    sdevkit_cgram_copy(PAL_YELLOW * 16, font_gfx_pal, FONT_GFX_PAL_SIZE);
    sdevkit_cgram_set(PAL_YELLOW * 16 + 1, 0x235F);
    sdevkit_vram_copy(VRAM_SPRITES, sprites_gfx_tiles, SPRITES_GFX_TILES_SIZE);
    sdevkit_cgram_copy(128, sprites_gfx_pal, SPRITES_GFX_PAL_SIZE);
    for (j = 0; j < 16 * 32; j++) {      /* around the board: an empty element, the backdrop */
        board_map[j] = element_tile(31);
    }
    chess_init();
    sdevkit_sfx_init(&sfx_bank);
    book_init();
    draw_labels();
    panel_yellow(1, "Schach");
    title();
    /* the screen is still off: copy the whole maps at once */
    sdevkit_vram_copy(VRAM_BOARD_MAP, board_map, sizeof board_map);
    sdevkit_vram_copy(VRAM_TEXT_MAP, text_map, sizeof text_map);
    board_dirty = 0;
    text_from = 0xFF;
    text_to = 0;
    sdevkit_ppu.inidisp = 15;
}

/* What changed in the maps, queued for the vertical blank; what does not fit stays due. */
static void queue_maps(void)
{
    if (board_dirty                      /* the board's eight rows of map cells */
        && sdevkit_queue_vram(VRAM_BOARD_MAP + 32, &board_map[32], 8 * 32 * 2)) {
        board_dirty = 0;
    }
    if (text_from != 0xFF) {
        uint16_t first = (uint16_t)text_from * 32;
        if (sdevkit_queue_vram((uint16_t)(VRAM_TEXT_MAP + first), &text_map[first],
                               (uint16_t)((text_to - text_from + 1) * 64))) {
            text_from = 0xFF;
            text_to = 0;
        }
    }
}

void game_tick(const uint16_t pads[SDEVKIT_PADS])
{
    uint16_t pad, pressed;

    sdevkit_input_update(pads);
    pad = sdevkit_input.pad[0];
    pressed = sdevkit_input.pressed[0];
    timer++;
    switch (state) {
    case ST_TITLE:
        if ((pressed & SDEVKIT_KEY_LEFT) && level > 0) {
            level--;
            draw_level();
        } else if ((pressed & SDEVKIT_KEY_RIGHT) && level + 1 < LEVELS) {
            level++;
            draw_level();
        }
        if (pressed & SDEVKIT_KEY_START) {
            seed = (uint16_t)(seed + timer);
            new_game();
        }
        break;
    case ST_PLAYER:
        player(pad, pressed);
        break;
    case ST_PROMOTE:
        promote(pressed);
        break;
    case ST_THINK:
        move_cursor(pad, pressed);       /* the cursor moves while the console thinks */
        think();
        break;
    default:                             /* ST_OVER */
        if (pressed & SDEVKIT_KEY_START) {
            seed = (uint16_t)(seed + timer);
            new_game();
        }
        break;
    }
    draw_sprites();
    queue_maps();
    /* the state for the checks, which compare the SNES and the PC after every tick
     * (sdevkit_dbg_hash): the position, its key, and where the game is */
    sdevkit_dbg_hash(board, sizeof board);
    sdevkit_dbg_hash(&hash_lo, sizeof hash_lo);
    sdevkit_dbg_hash(&hash_hi, sizeof hash_hi);
    sdevkit_dbg_hash(&state, sizeof state);
    sdevkit_dbg_hash(&plies, sizeof plies);
    sdevkit_dbg_hash(sdevkit_oam, 8);    /* the cursor and the piece in the hand */
}

void game_vblank(void)
{
    sdevkit_commit();
}

