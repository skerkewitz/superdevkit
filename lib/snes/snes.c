/*
 * The console API of superdevkit (lib/include/sdevkit/snes.h, ADR-6F3CK2AR) on the real SNES, for
 * dampf: the commit of the shadow state into the hardware in the vertical blank, the writes now
 * (with the screen off), and what main.c needs from the hardware. The shadow state's logic, the
 * same as Rachael's, is lib/shadow/shadow.h.
 *
 * General DMA uses channel 0; HDMA the channels the game fills (1 to 7). The registers are written
 * with long addresses (the data bank is $7E): 9 cycles a byte from the shadow.
 */
#include <dampf.h>
#include "sdevkit/snes.h"
#include "hw.h"
#include "../shadow/shadow.h"

static const uint16_t zero_word = 0;   /* the source of the VRAM clear */

uint8_t sdevkit__ticking;              /* main.c: game_tick runs (sdevkit_commit_now does nothing then) */

void sdevkit__wait_vblank(void);
void sdevkit__oam_upload(void);

/* A source address for a DMA channel: its 16-bit address and its bank. The bank is byte 2 of the
 * SNES address, read as a byte: dampf shifts a 32-bit value by 16 in a loop today (about 600
 * cycles), which the vertical blank cannot spare. */
static void dma_source(uint8_t channel, const void *source)
{
    uint32_t a = (uint32_t)source;
    A1T(channel) = (uint16_t)a;
    A1B(channel) = ((const uint8_t *)&a)[2];
}

/* One copy on channel 0: the port's address first (CGRAM's colour, or VRAM's word and how it
 * steps), then the channel. */
static void dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    if (port == 0x22) {
        CGADD = (uint8_t)dest;
    } else {
        VMAIN = vmain;
        VMADD = dest;
    }
    DMAP(0) = dmap;
    BBAD(0) = port;
    dma_source(0, src);
    DAS(0) = bytes;
    MDMAEN = 1;
}

/* --- The commit ---------------------------------------------------------------------------- */
/* BGnHOFS/VOFS take two writes each, low byte then high byte. */
static void commit_scroll(void)
{
    REG8(0x210D) = (uint8_t)sdevkit_ppu.hofs[0];
    REG8(0x210D) = (uint8_t)(sdevkit_ppu.hofs[0] >> 8);
    REG8(0x210E) = (uint8_t)sdevkit_ppu.vofs[0];
    REG8(0x210E) = (uint8_t)(sdevkit_ppu.vofs[0] >> 8);
    REG8(0x210F) = (uint8_t)sdevkit_ppu.hofs[1];
    REG8(0x210F) = (uint8_t)(sdevkit_ppu.hofs[1] >> 8);
    REG8(0x2110) = (uint8_t)sdevkit_ppu.vofs[1];
    REG8(0x2110) = (uint8_t)(sdevkit_ppu.vofs[1] >> 8);
    REG8(0x2111) = (uint8_t)sdevkit_ppu.hofs[2];
    REG8(0x2111) = (uint8_t)(sdevkit_ppu.hofs[2] >> 8);
    REG8(0x2112) = (uint8_t)sdevkit_ppu.vofs[2];
    REG8(0x2112) = (uint8_t)(sdevkit_ppu.vofs[2] >> 8);
    REG8(0x2113) = (uint8_t)sdevkit_ppu.hofs[3];
    REG8(0x2113) = (uint8_t)(sdevkit_ppu.hofs[3] >> 8);
    REG8(0x2114) = (uint8_t)sdevkit_ppu.vofs[3];
    REG8(0x2114) = (uint8_t)(sdevkit_ppu.vofs[3] >> 8);
}

/* COLDATA takes one write per colour channel, with its bit. */
static void commit_color(void)
{
    uint16_t c = sdevkit_ppu.coldata;

    REG8(0x2130) = sdevkit_ppu.cgwsel;
    REG8(0x2131) = sdevkit_ppu.cgadsub;
    REG8(0x2132) = (uint8_t)(0x20 | (c & 0x1F));
    REG8(0x2132) = (uint8_t)(0x40 | ((c >> 5) & 0x1F));
    REG8(0x2132) = (uint8_t)(0x80 | ((c >> 10) & 0x1F));
}

static void commit_setup(void)
{
    OBSEL = sdevkit_ppu.obsel;
    BGMODE = sdevkit_ppu.bgmode;
    REG8(0x2106) = sdevkit_ppu.mosaic;
    REG8(0x2107) = sdevkit_ppu.bgsc[0];
    REG8(0x2108) = sdevkit_ppu.bgsc[1];
    REG8(0x2109) = sdevkit_ppu.bgsc[2];
    REG8(0x210A) = sdevkit_ppu.bgsc[3];
    BG12NBA = sdevkit_ppu.bgnba[0];
    BG34NBA = sdevkit_ppu.bgnba[1];
    REG8(0x2133) = sdevkit_ppu.setini;
    TM = sdevkit_ppu.tm;
    TS = sdevkit_ppu.ts;
}

static void commit_window(void)
{
    REG8(0x2123) = sdevkit_ppu.w12sel;
    REG8(0x2124) = sdevkit_ppu.w34sel;
    REG8(0x2125) = sdevkit_ppu.wobjsel;
    REG8(0x2126) = sdevkit_ppu.wh[0];
    REG8(0x2127) = sdevkit_ppu.wh[1];
    REG8(0x2128) = sdevkit_ppu.wh[2];
    REG8(0x2129) = sdevkit_ppu.wh[3];
    REG8(0x212A) = sdevkit_ppu.wbglog;
    REG8(0x212B) = sdevkit_ppu.wobjlog;
    REG8(0x212E) = sdevkit_ppu.tmw;
    REG8(0x212F) = sdevkit_ppu.tsw;
}

static void commit_mode7(void)
{
    M7SEL = sdevkit_ppu.m7sel;
    M7A = (uint8_t)sdevkit_ppu.m7a;
    M7A = (uint8_t)((uint16_t)sdevkit_ppu.m7a >> 8);
    M7B = (uint8_t)sdevkit_ppu.m7b;
    M7B = (uint8_t)((uint16_t)sdevkit_ppu.m7b >> 8);
    M7C = (uint8_t)sdevkit_ppu.m7c;
    M7C = (uint8_t)((uint16_t)sdevkit_ppu.m7c >> 8);
    M7D = (uint8_t)sdevkit_ppu.m7d;
    M7D = (uint8_t)((uint16_t)sdevkit_ppu.m7d >> 8);
    M7X = (uint8_t)sdevkit_ppu.m7x;
    M7X = (uint8_t)((uint16_t)sdevkit_ppu.m7x >> 8);
    M7Y = (uint8_t)sdevkit_ppu.m7y;
    M7Y = (uint8_t)((uint16_t)sdevkit_ppu.m7y >> 8);
}

/* The channels HDMAEN switches on, and HDMAEN: the hardware reads A1T again at the start of every
 * frame, so they are written only when the game marks them (switching a channel on is a change
 * of hdmaen, so marked too). The table's address is read as the bytes of the pointer (address,
 * bank: subset decision 3), not converted, which costs dampf a copy through its frame. */
static void commit_hdma(void)
{
    uint8_t on = sdevkit_ppu.hdmaen;
    uint8_t ch;

    for (ch = 1; ch < 8; ch++) {
        on = (uint8_t)(on >> 1);
        if (on & 1) {
            const uint8_t *t = (const uint8_t *)&sdevkit_hdma[ch].table;
            uint16_t r = (uint16_t)(ch << 4);
            REG8(0x4300 + r) = sdevkit_hdma[ch].dmap;
            REG8(0x4301 + r) = sdevkit_hdma[ch].bbad;
            REG8(0x4302 + r) = t[0];
            REG8(0x4303 + r) = t[1];
            REG8(0x4304 + r) = t[2];
            REG8(0x4307 + r) = sdevkit_hdma[ch].dasb;
        }
    }
    HDMAEN = sdevkit_ppu.hdmaen;
}

/* The registers of the groups marked. INIDISP is the caller's. */
void sdevkit__commit_registers(uint8_t marks)
{
    if (marks & SDEVKIT_MARK_SCROLL) {
        commit_scroll();
    }
    if (marks & SDEVKIT_MARK_COLOR) {
        commit_color();
    }
    if (marks & SDEVKIT_MARK_SETUP) {
        commit_setup();
    }
    if (marks & SDEVKIT_MARK_WINDOW) {
        commit_window();
    }
    if (marks & SDEVKIT_MARK_MODE7) {
        commit_mode7();
    }
    if (marks & SDEVKIT_MARK_HDMA) {
        commit_hdma();
    }
}

/* The queued sources as channel 0 takes them, split when they are queued (in the tick), so the
 * vertical blank does not pay for it. */
static uint16_t queue_a1t[SDEVKIT_QUEUE_ENTRIES];
static uint8_t queue_a1b[SDEVKIT_QUEUE_ENTRIES];

/* A copy into the queue (shadow.h decides whether it fits), with its source split. */
static uint8_t enqueue(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    uint8_t n = queue.n;
    uint32_t a = (uint32_t)src;

    if (!queue_add(port, dmap, vmain, dest, src, bytes)) {
        return 0;
    }
    queue_a1t[n] = (uint16_t)a;
    queue_a1b[n] = ((const uint8_t *)&a)[2];
    return 1;
}

/* The queue, in order, on channel 0: it fits by its count (shadow.h). The registers are written
 * here, not through dma(): the vertical blank pays for every cycle of the setup. */
static void commit_queue(void)
{
    uint8_t k;
    uint8_t n = queue.n;

    DAMPF_RANGE(n, 0, SDEVKIT_QUEUE_ENTRIES);
    for (k = 0; k < n; k++) {
        uint8_t port = queue.port[k];
        if (port == 0x22) {
            CGADD = (uint8_t)queue.dest[k];
        } else {
            VMAIN = queue.vmain[k];
            VMADD = queue.dest[k];
        }
        DMAP(0) = queue.dmap[k];
        BBAD(0) = port;
        A1T(0) = queue_a1t[k];
        A1B(0) = queue_a1b[k];
        DAS(0) = queue.bytes[k];
        MDMAEN = 1;
    }
    queue_done();
}

/* In the vertical blank after a tick (main.c): a screen to be switched off goes off first; the
 * sprite table, the registers of the groups marked, the queue; then INIDISP, which may switch the
 * screen on. */
void sdevkit__commit(void)
{
    if (sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) {
        INIDISP = sdevkit_ppu.inidisp;
    }
    sdevkit__oam_upload();
    sdevkit__commit_registers(sdevkit_marks);
    sdevkit_marks = 0;
    commit_queue();
    INIDISP = sdevkit_ppu.inidisp;
}

/* --- The public functions ---------------------------------------------------------------------- */
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large)
{
    sprite_add(x, y, tile, attr, large);
}

uint8_t sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    return enqueue(port, dmap, vmain, dest, src, bytes);
}

uint8_t sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes)
{
    return enqueue(0x18, 0x01, 0x80, a, src, bytes);
}

uint8_t sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes)
{
    return enqueue(0x18, 0x01, 0x81, a, src, bytes);
}

uint8_t sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes)
{
    return enqueue(0x22, 0x00, 0x00, i, src, bytes);
}

uint8_t sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes)
{
    return enqueue(0x18, 0x00, 0x00, e, src, bytes);
}

uint8_t sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes)
{
    return enqueue(0x19, 0x00, 0x80, b, src, bytes);
}

uint8_t sdevkit_queue_color(uint8_t index, uint16_t bgr)
{
    uint8_t n = queue.n;
    if (!queue_color(index, bgr)) {
        return 0;
    }
    {
        uint32_t a = (uint32_t)&queue.value[n];
        queue_a1t[n] = (uint16_t)a;
        queue_a1b[n] = ((const uint8_t *)&a)[2];
    }
    return 1;
}

uint16_t sdevkit_queue_room(void)
{
    return queue_room();
}

void sdevkit_dma_now(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes)
{
    if (bytes != 0) {
        dma(port, dmap, vmain, dest, src, bytes);
    }
}

void sdevkit_color_now(uint8_t index, uint16_t bgr)
{
    CGADD = index;
    CGDATA = (uint8_t)bgr;
    CGDATA = (uint8_t)(bgr >> 8);
}

void sdevkit_screen_off_now(void)
{
    sdevkit_ppu.inidisp = (uint8_t)(sdevkit_ppu.inidisp | SDEVKIT_FORCED_BLANK);
    INIDISP = sdevkit_ppu.inidisp;
}

/* Everything but the sprite table: the checks count ticks by its copies (dev/example_check.lua).
 * In a tick it does nothing, as on Rachael. */
void sdevkit_commit_now(void)
{
    if (sdevkit__ticking) {
        return;
    }
    sdevkit__wait_vblank();
    if (sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) {
        INIDISP = sdevkit_ppu.inidisp;
    }
    sdevkit__commit_registers(SDEVKIT_MARK_ALL);
    commit_queue();
    INIDISP = sdevkit_ppu.inidisp;
}

/* --- for main.c ------------------------------------------------------------------------------ */
/* The sprite table into OAM: 544 bytes, in a vertical blank. */
void sdevkit__oam_upload(void)
{
    OAMADD = 0;
    DMAP(0) = 0x00;
    BBAD(0) = 0x04;
    dma_source(0, sdevkit_oam);
    DAS(0) = sizeof sdevkit_oam;
    MDMAEN = 1;
}

/* After game_tick: the sprite list closed, then the checksum: what the game named, then the
 * shadow state (shadow.h, the same order as Rachael's sdevkit__tick_hash). */
void sdevkit__end_tick(void)
{
    sprites_end();
    shadow_hash();
}

/* After the commit: the sprite list's high table made ready for the next tick (shadow.h). */
void sdevkit__begin_tick(void)
{
    sprites_begin();
}

/* The PPU as at power-on, the screen off: registers, VRAM, CGRAM, and the shadow state. The
 * sprite table in OAM is left alone, so this also serves after the splash, where a copy of it
 * would count as a tick in the checks. */
void sdevkit__reset_ppu(void)
{
    uint8_t r;
    INIDISP = 0x80;
    for (r = 0x01; r <= 0x0C; r++) {
        REG8(0x2100 + r) = 0;
    }
    for (r = 0x0D; r <= 0x14; r++) {   /* scroll: written twice */
        REG8(0x2100 + r) = 0;
        REG8(0x2100 + r) = 0;
    }
    for (r = 0x15; r <= 0x33; r++) {
        REG8(0x2100 + r) = 0;
    }
    REG8(0x211B) = 0;                  /* mode 7 matrix: identity (written twice) */
    REG8(0x211B) = 1;
    REG8(0x211E) = 0;
    REG8(0x211E) = 1;
    HDMAEN = 0;
    VMAIN = 0x80;
    VMADD = 0;
    DMAP(0) = 0x09;                    /* a fixed source: zeros; size 0 is 64 KB */
    BBAD(0) = 0x18;
    dma_source(0, &zero_word);
    DAS(0) = 0;
    MDMAEN = 1;
    CGADD = 0;
    for (r = 0; r < 255; r++) {
        CGDATA = 0;
        CGDATA = 0;
    }
    CGDATA = 0;
    CGDATA = 0;
    shadow_reset();
}

/* The PPU and the sprite table as at power-on. */
void sdevkit__reset_video(void)
{
    sdevkit__reset_ppu();
    sdevkit__oam_upload();
}

void sdevkit__wait_vblank(void)
{
    /* the start of a vertical blank: a tick that ended inside one waits for the next, so the
     * commit always gets the whole of it */
    while (HVBJOY & 0x80) {
    }
    while (!(HVBJOY & 0x80)) {
    }
}

/* Both joypads, SNES bit layout. The automatic read runs in the first lines of the vertical blank. */
void sdevkit__read_pads(uint16_t *pad1, uint16_t *pad2)
{
    while (HVBJOY & 1) {
    }
    *pad1 = JOY1;
    *pad2 = JOY2;
}

/* The joypads of the coming tick into sdevkit_input. */
void sdevkit__read_input(void)
{
    uint16_t pad1, pad2;
    sdevkit__read_pads(&pad1, &pad2);
    input_set(pad1, pad2);
}

/* Only Rachael can read VRAM like memory (a debug check of the desktop); the SNES
 * cannot, so here it is a single zero word. */
const uint16_t *sdevkit_dbg_vram(void)
{
    static const uint16_t none[1];
    return none;
}

/* The address and the size go to port $21FC, five bytes (address low, high, bank, size low,
 * high); the checker (dev/example_check.lua, dampf's emulator) reads and hashes the memory. */
void sdevkit_dbg_hash(const void *data, uint16_t bytes)
{
    uint32_t a = (uint32_t)data;
    const uint8_t *b = (const uint8_t *)&a;
    DBG_HASH = b[0];
    DBG_HASH = b[1];
    DBG_HASH = b[2];
    DBG_HASH = (uint8_t)bytes;
    DBG_HASH = (uint8_t)(bytes >> 8);
}

/* Music is not built on the SNES yet (docs/sound.md): the calls do nothing, and dampf inlines them
 * away, so a game that plays music on the desktop builds unchanged. */
void sdevkit_music_play(uint8_t track)
{
    (void)track;
}

void sdevkit_music_stop(void)
{
}

/* The debug view exists on the desktop only: here the registrations and the boxes do nothing, and
 * dampf inlines the empty functions away (DebugCallsTest). */
void sdevkit_dbg_toggle(const char *name, uint8_t *flag)
{
    (void)name;
    (void)flag;
}

void sdevkit_dbg_range(const char *name, int16_t *value, int16_t lo, int16_t hi)
{
    (void)name;
    (void)value;
    (void)lo;
    (void)hi;
}

void sdevkit_dbg_color(const char *name, uint16_t *colour)
{
    (void)name;
    (void)colour;
}

void sdevkit_dbg_action(const char *name, uint8_t *pressed)
{
    (void)name;
    (void)pressed;
}

void sdevkit_dbg_text(const char *name, const char *text)
{
    (void)name;
    (void)text;
}

void sdevkit_dbg_value(const char *name, const uint16_t *value)
{
    (void)name;
    (void)value;
}

void sdevkit_dbg_box(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    (void)kind;
}

void sdevkit_dbg_fill(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    (void)kind;
}

void sdevkit_dbg_arrow(int16_t x, int16_t y, int8_t dx, int8_t dy, uint8_t kind)
{
    (void)x;
    (void)y;
    (void)dx;
    (void)dy;
    (void)kind;
}

/* The cartridge's battery-backed RAM: LoROM maps it at $70:0000, and dampf --sram writes into the
 * header that the cartridge has it (ROM, RAM and battery; the size). */
uint8_t *sdevkit_sram(void)
{
#if SDEVKIT_SRAM_SIZE > 0
    return (uint8_t *)0x700000;
#else
    return 0;
#endif
}
