# Brainstorm: a new console API with shadow state

2026-11-24. The console API (`lib/include/sdevkit/snes.h`) is mostly PVSnesLib's functions,
renamed. The session asked whether that shape is good for speed and for the people who use it,
and what a new API would look like. Nothing in the repository was changed during the session.
The facts about the code were read from it, and two measurements were made in a scratch folder
with dampf 0.9.2 (develop at 505f107).

## Problem

On the hot path the console API costs more cycles than the work needs, and its immediate
register writes leave the timing of the vertical blank to the game. The hot path is sprites,
scrolling, palette, brightness and colour math, and uploads to VRAM and CGRAM during play.

The evidence:

- Er-Tüp does not call `sdevkit_oam_set`. It writes `sdevkit_oam_memory` itself
  ([draw.c](../../examples/ertuep/src/draw.c)), because the call costs 293 to 405 cycles and a busy
  frame has eighty sprites.
- `sdevkit_bg_set_scroll`, `sdevkit_set_palette_color` and the DMA calls write the hardware at
  once, so a game has to know that they belong in `game_vblank`. Code that breaks this rule is
  wrong only on the console.
- The setup functions are hard to read: `sdevkit_oam_set` and `sdevkit_bg_init_tile_set` take
  eight positional parameters each, sprite ids are byte offsets (sprite n is `n * 4`), addresses
  count words while sizes count bytes, and `sdevkit_set_mode` also switches on BG1 to BG3 and the
  sprites.

Goals, in this order: the fastest API dampf can compile, then a clear data model. A friendlier
layer for human beginners, or one that looks like PVSnesLib, may come later and is optional.
Setup is in scope.

Constraints:

- The kit is in alpha. Anything may break, and the examples migrate.
- A game reaches the hardware only through the kit, so that Rachael sees every write
  (ADR-6F3CK2AR).
- A feature is usable only when the SNES and Rachael both have it, as "a hardware feature is
  usable only when the SNES and Rachael both draw it" (ADR-6FKTVDE8) says.
- Every result matches the PC bit for bit, and the checksum after every tick stays comparable
  between the SNES, the desktop and the web.
- The API must fit the interrupt design decided the same day
  ([interrupts-and-asm_2026-11-24.md](interrupts-and-asm_2026-11-24.md)): the kit owns the
  NMI, the game owns an IRQ in C, and interrupt code reads only what the tick published.
- Stable means the core's layout and meaning never change once it exists, and it only grows
  by additions. In C, a new struct field, macro or helper breaks no existing game. A rename, a
  new meaning or a changed signature does. Features that are not built yet (listed below) must
  arrive as additions.

The features still missing, from [SNES_HARDWARE.md](../../lib/SNES_HARDWARE.md), and what each
needs from an API:

| Missing feature | What it needs |
| --- | --- |
| Modes 0 and 2 to 6, offset-per-tile | per layer: colour depth, tile base, map base, size; BG3 as an offset table |
| Mosaic, high resolution, interlace, EXTBG, direct colour | register bits (`MOSAIC`, `SETINI`, bit 0 of `CGWSEL`) |
| H/V IRQ and timers | register writes in the middle of the picture, at once |
| Indirect HDMA, HDMA to CGRAM, tables that change while HDMA reads them | all eight transfer modes and the indirect bank; today's `sdevkit_hdma_set(ch, reg, twoRegs, table)` cannot say this |
| Intrinsics for DMA | DMA with the screen off and in the IRQ, beside any queue |
| Music on the SNES, ADSR, echo, noise, looped samples | commands to the SPC700 driver per frame; most of it is build data. `sdevkit_sfx_play` already works as a queue ("starts after this tick") |
| Mouse, multitap, Super Scope | other input data. `game_tick(const uint16_t pad[SDEVKIT_PADS])` would change its signature: the one place where every game breaks |

## Who is affected

- **An AI writing game code.** The user expects more and more game code to be written this way,
  which makes the AI the API's main user. It does not mind writing four bytes by hand. It knows
  the SNES's own formats from many public documents (fullsnes, the wikis, homebrew), and an
  invented abstraction only from our documentation. Hidden rules hurt it most: a write that
  works only in the vertical blank, or a call that also hides a sprite, is wrong only on the
  console, where it cannot look. It needs costs written down as numbers.
- **A human beginner** wants `sprite.x = 40` to work and never wants to hear about the vertical
  blank. Raw register bits are hard for them. They are served by a layer, later.
- **An experienced SNES developer**, from assembly or PVSnesLib, wants the raw table and the
  registers with nothing in between. Today they go around the API, as Er-Tüp does.
- **Someone porting from PVSnesLib** knows `oamSet` with eight parameters. For them a new model
  is a cost. Blubber Blasen started this way; it has to migrate whatever is chosen, and that is
  the game project's work.
- **The checks** hash the sprite table after every tick. Scroll, layers, brightness and colour
  math are not hashed, because they go to the hardware during the vertical blank. State kept in
  memory would be hashed too.
- **Rachael** copies the sprite table at her vertical blank (`sdevkit__vblank` in
  [ppu.c](../../lib/rachael/ppu.c)) and handles every other call at once.

## Background: how a frame runs today

On the SNES, the loop in [main.c](../../lib/snes/main.c):

1. reads the pads;
2. calls `game_tick`;
3. waits for the vertical blank;
4. copies the 544 bytes of `sdevkit_oam_memory` into OAM with one DMA;
5. calls `game_vblank`, where the game's register and DMA calls go to the hardware at once;
6. starts the sound effects.

So the sprite table already works as shadow state and nothing else does.

PPU registers cannot be read back, and the scroll and mode 7 registers are written twice each,
low byte then high byte. For that reason `snes.c` already keeps shadows of a few registers
(`tm_shadow`, `nba_shadow`).

In dampf, a write to a fixed global array is an absolute store, the cheapest thing it compiles.
A write through a pointer costs more. dampf inlines small functions (about 60 MIR instructions,
or 150 when there is only one caller, [Inline.java](../../compiler/src/main/java/dampf/mir/Inline.java)),
so a tiny helper in the library can cost nothing. `sdevkit_oam_set` is too big to be inlined.

## Ideas

**1. One frame struct, named by meaning.** All per-frame state lives in one global struct that
the tick writes and the kit commits in the vertical blank. `sdevkit_frame` holds the sprite
table, the x and y scroll of each layer, brightness, the main and sub screen layers, colour math,
the mode 7 matrix and centre, and the HDMA channels. The game writes plain fields in `game_tick`
(`sdevkit_frame.bg[0].x = cam_x`). After the tick a commit routine writes the hardware in the
vertical blank, Rachael reads the same struct at hers, and the checksum hashes it. *Why:* every
write on the hot path becomes one absolute store, the vertical blank rule goes away, and the
checks start comparing scroll, layers and brightness.

**2. Commit everything, every frame.** The commit writes every shadowed register every frame,
changed or not. The game keeps no flags, and the cost is the same fixed sequence of stores each
frame. The alternative is a dirty bit per group. *Why:* constant, predictable cost and nothing
to forget.

**3. An upload queue for VRAM and CGRAM.** The tick queues uploads, and the commit runs them in
the vertical blank as far as the budget allows. `sdevkit_queue_vram(src, dst, bytes)` appends an
entry to a fixed array. The commit runs DMAs while `sdevkit_dma_fits` says they fit, and the rest
waits for the next frame, in order. Rachael runs the same queue against the same budget, so an
upload that does not fit is late on both. *Why:* neslib (`set_vram_update`) and SGDK
(`DMA_queueDma`) work this way, two C kits for consoles where calls cost as much as here. With
idea 1, `game_vblank` has nothing left to do.

**4. Sprites as parallel arrays, packed by the kit.** The game writes `spr_x[i]`, `spr_y[i]`,
`spr_tile[i]`, `spr_attr[i]`, with x a signed 16-bit value, and the kit packs them into the OAM
format, high table included. One array per field is dampf's fastest layout, and bit 8 of x and
the size bit are handled once, in one loop of the kit, which can be hand-written assembly: a C
function replaced by assembly that is checked against its C body (`DAMPF_ASM`, ADR-6FMATG2V,
merged after the session). *Why:* the game never touches the high table, and the packing loop is
optimised once for every game.

**5. The hardware's own formats, raw, with folding macros.** The shadow has the hardware's
layout: the OAM bytes, and register shadows named after the registers. Macros such as
`SDEVKIT_OAM_ATTR(pal, prio, hflip, vflip)` and `SDEVKIT_MAP_ENTRY(tile, pal, prio, flip)` build
attribute bytes, and dampf folds them to one number when the arguments are constants. *Why:*
nothing is faster, and an AI already knows the format.

**6. A sprite list rebuilt every frame.** Each frame the game appends the sprites it draws, and
the kit hides the rest. `sdevkit_spr_push(x, y, tile, attr)` is small enough to be inlined. It
writes at the kit's counter and steps it. At the commit, the kit hides the slots used in the last
frame and not in this one, as Er-Tüp's `spr()` with `n_spr` and `last_n` does. A sprite made of
several pieces becomes a constant table generated by the asset build and drawn with one call.
*Why:* games think "draw these things this frame", not "sprite 17 is slot 68". The order of the
calls is the priority, and neslib's `oam_spr` and `oam_meta_spr` work this way.

**7. Setup as a declaration, VRAM laid out by the build.** The game describes its screen once,
as data: the mode, the layers with their tile sets and map sizes, the sprite sizes and sheets.
This could live in `superdevkit.toml` or in a const struct the build generates. The build gives
each part its VRAM address and writes the addresses as constants, with
`sdevkit_screen_apply(&SCREEN_TITLE)` to set the registers and run the uploads with the screen
off. Without the build part, it is still one call with a struct instead of eight calls with
eight parameters. *Why:* word addresses, 4K-word steps and overlapping tile sets are the usual
setup bugs, and a machine computes them correctly. The build already writes headers for sound
effects and music.

**8. Wild: a commit routine generated for each game.** The build, or dampf, sees which parts of
the shadow a game ever writes and generates a commit for only those. A game without mode 7
commits no mode 7 registers. *Why:* the generality of idea 1 at no cost for features a game does
not use, which dampf can do because it sees the whole program.

**9. Wild: no `game_vblank`.** The game has no code in the vertical blank. The tick writes the
shadow and queues uploads, and the kit does the rest. Work at a line goes through HDMA tables or
the game's IRQ. With the NMI, the kit's commit is the only code of the vertical blank. *Why:*
work that runs at the wrong moment, or does not fit the vertical blank, can no longer be written.
The NMI never runs game code.

**10. Changing the requirement: keep the calls, make them cheaper.** No new model.
`sdevkit_oam_set` takes the sprite number and one attribute byte instead of four flags, the high
table uses lookup tables as in Er-Tüp, the hot calls shrink below the inlining limit, and later
arguments in registers (M12) make every call cheaper. `game_vblank` stays. *Why:* the smallest
change, the familiar shape, and dampf's own progress lowers the cost over time.

**11. Two layers from the start.** The raw shadow is the core, and a thin layer of inline helpers
sits on top of it from day one: `sdevkit_sprite(x, y, tile, flags)`, `sdevkit_scroll(bg, x, y)`,
`sdevkit_fade_to(level)`, each small enough to become stores into the shadow. *Why:* the beginner
layer exists early without slowing anyone down, and building it shows whether the core is
complete.

**12. Mirror the whole machine, not its features.** The core is the hardware's own state, as
data:

- the PPU's register file, about 60 bytes (registers written twice take two bytes; the data
  ports are left to the queue);
- the registers of DMA channels 1 to 7 (`DMAP`, `BBAD`, source, bank, indirect bank);
- the sprite table as today.

Every HDMA transfer mode, indirect HDMA and HDMA to CGRAM are then bytes in the channel shadow.
An HDMA table can be double buffered by pointing the shadow at the other table, and the commit
switches in the vertical blank. Ideas 4, 6, 7 and 11 become optional layers on top. *Why:* the
SNES has not changed since 1990, so a shadow with its layout never needs a field it lacks. Every
PPU feature that is not built yet already has its bytes. This is idea 5 carried to its end.

**13. Queue entries as raw DMA descriptors.** An entry of the upload queue (3) holds what the
hardware needs: the port setup (`VMAIN` and `VMADD`, or `CGADD`, or `OAMADD`) and the channel's
`DMAP`, `BBAD`, source and size. `sdevkit_queue_vram` and a column variant are one-line helpers
that fill an entry. *Why:* any later kind of upload is a new helper, not a new queue format, and
the commit is one tight loop.

**14. Input as a shadow that the game reads.** The kit fills `sdevkit_input` before the tick, and
`game_tick(void)` reads it. Today it holds two pads; the multitap's pads, the mouse's movement
and the Super Scope's position arrive later as new fields. *Why:* it removes the one signature
that would break when a device is added.

**15. Two kinds of write, named in the API.** Frame state goes into the shadow and is committed
in the vertical blank. Immediate writes (`sdevkit_reg_write`, the DMA intrinsics) are for the
IRQ, for forced blank and for setup. The desktop check can enforce the rule, because Rachael
knows whether she is inside a tick and whether the screen is off. A register written at once
during a tick with the screen on is reported, since it would tear on the SNES. *Why:* the IRQ
will write registers in the middle of the picture. If the model has no place for that now, it
has to change when the IRQ arrives.

## Measurements

Both were made in a scratch folder, outside the repository, with dampf 0.9.2 built from develop
at 505f107. They link `lib/snes` as a game does. The numbers are worst cases from dampf's timing
report, in CPU cycles.

**A: committing the full mirror (ideas 2 and 12).** A straight-line commit of the PPU register
file and HDMA channels 1 to 7:

| Part | Cycles |
| --- | ---: |
| PPU registers: 27 written once, 8 scroll and 6 mode 7 registers written twice, `COLDATA` three times (58 writes) | 542 |
| HDMA channels 1 to 7 (five registers each) and `HDMAEN` | 407 |
| Both, with the calls | 971 |

Each byte is a load of 4 cycles and a long store of 5. The data bank is `$7E`, so the registers
in bank 0 need long addresses. In the vertical blank, 971 CPU cycles take roughly 750 to 950
bytes of the 5,700 that DMA has after the sprite table, about 15 %. This is an estimate: a CPU
cycle is 6 to 8 master cycles, and a DMA byte is 8. A game today that calls
`sdevkit_bg_set_scroll` twice and `sdevkit_set_brightness` once in `game_vblank` pays about 176
cycles, so committing everything costs about five times as much.

Two things follow from the numbers. The HDMA channels do not need a commit every frame: the
hardware reloads each channel from `A1T` at the start of every frame, and the channel registers
change only when an effect changes. The registers that really change from frame to frame are
brightness, scroll, the layers, colour math, the window edges and mosaic. They make about 30
writes, about 270 cycles. The rest is setup. Hand-written assembly with the direct page at
`$2100` might save another 15 % (estimate, not measured).

**B: packing sprites (idea 4) against writing OAM directly (idea 6, Er-Tüp's `spr()`).** 128
sprites, the game's own objects as input to both:

| Way | Cycles | Per sprite |
| --- | ---: | ---: |
| Idea 4: the game fills the parallel arrays | 10,900 | 85 |
| Idea 4: the kit packs them | 37,455 | 293 |
| Idea 4, together | 48,355 | 378 |
| Er-Tüp's `spr()`, writing OAM directly | 35,536 | 278 |

In C, packing costs 36 % more, about 12,800 cycles for 128 sprites, a quarter of a frame. Both
ways are slow, though: the direct way spends about 70 % of a frame (45,000 to 55,000 CPU cycles,
[timing.md](../timing.md)) on 128 sprites. The generated code shows why. For
`sdevkit_oam_memory[o + 1] = ...` dampf computes `o + 1` into Y each time (`clc`, `lda`,
`adc #1`, `tay`: 11 cycles) instead of writing `sta _sdevkit_oam_memory+1,y`, which costs
nothing extra. The optimisation log mentions an index plus a constant only for pointers, where Y
has no room for the constant ([optimizations-log.md](../optimizations-log.md), "an index plus a
constant (a field of `p[i]`) still computes the sum"). For a global array the constant fits into
the instruction's address. This is a dampf optimisation, independent of the API, and it would
help every game that writes tables.

Hand-written, packing one sprite should take on the order of 60 cycles (estimate). With a packer
in assembly, idea 4 might become the fastest way instead of the slowest: cheap indexed stores in
the game and one optimised loop in the kit. That is not measured.

## Ratings

After the measurements.

| Idea | Realistic? | Cost (time) | Fragility | Fully solves it? | Works for others? | New problems | Reversible? | Fits the plans? |
|---|---|---|---|---|---|---|---|---|
| 1. Frame struct named by meaning | Yes: `sdevkit_oam_memory` already works this way on both sides. | 1 to 2 weeks with the migration. | Medium: we design every field, and a future feature may not fit one. | Hot path and timing, yes. Future features only as far as we foresee them. | AI and beginners: readable. Veterans: one more layer between them and the registers. | Overlaps 12. | Hard once games use it. | Yes: it is the NMI's published copy. Superseded by 12. |
| 2. Commit everything every frame | Yes. | Hours on top of 1 or 12. | Low. | As a commit strategy, yes. | Everyone. | Measured: 971 cycles, about 15 % of the vertical blank's DMA budget, five times a light game's register calls today. | Easy: groups can come later. | Replaced by a commit in groups (see the recommendation). |
| 3. Upload queue | Yes: `sdevkit_dma_fits` is its core. | Days. | Medium: a source in RAM must stay valid until the commit. That holds while the loop waits for the vertical blank, and needs care once the NMI overlaps the next tick. | Uploads, yes. | AI and beginners. Veterans who stream data to an exact frame control only the order, and an entry moved to the next frame is invisible unless the debug view counts it. | A silent delay of a frame when the queue is too full. | Medium. | Yes. Pairs with the NMI. |
| 4. Parallel arrays, packed by the kit | Yes. | Days; an assembly packer later. | Low. | Usability yes. Speed: in C, measured 36 % slower than direct writes; with an assembly packer possibly the fastest (not measured). | Beginners. Veterans and AI know OAM, not our arrays. | An extra pass every frame. | Easy, as a layer. | Only as a layer, and only once an assembly packer is measured. |
| 5. Raw hardware formats with macros | Yes: dampf folds constant expressions. | Days. | Low: the hardware does not change. | Speed, for registers and OAM. | AI and veterans: best. Beginners: poor without a layer. | A wrong bit shows the same wrong picture on both platforms, and no check catches it. | Hard: it is the interface. | Yes. Part of 12. |
| 6. Sprite list rebuilt every frame | Yes: Er-Tüp's `spr()` is it. | Days; metasprites from the build another week. | Low. | Sprites, for speed and use. | Everyone. A veteran who wants fixed slots can still write the raw table. | Mixing the list with raw writes needs a rule. | Easy, as a layer. | Yes. Metasprites fit `superdevkit gen`. |
| 7. Setup as a declaration | The struct: yes. The VRAM layout by the build: yes, but real work. | Struct: days. Layout by the build: 2 weeks or more. | The automatic layout is the weak part: shared tiles, layouts that change per level and streamed tiles fight it. | Setup, yes. | AI and beginners: much easier. Veterans with layouts of their own are blocked unless a manual layout stays possible. | The format must describe every mode from the start, or it breaks the rule of additions only. | Struct: easy. Layout: medium. | Fits `superdevkit gen`. |
| 8. Generated commit per game | Through the toml: yes. Derived by dampf: needs an analysis of globals that are never written, not checked to exist. | 1 to 2 weeks. | High through the toml: a field written but not declared is silently not committed. | Removes the cost of 2. | Invisible to users if dampf derives it. | More build logic. | Easy: fall back to a full commit. | Later, if at all: commit groups get most of the gain. |
| 9. No `game_vblank` | Yes, after 12 and 3. | Small; the migration of 7 examples. | Low. | Removes the vertical blank rule completely. | Everyone, until a game needs work in every vertical blank that the shadow cannot express. | Changes part of the interrupt decision of the same day (its idea 6: `game_vblank` becomes NMI code), and makes it simpler: the NMI needs no game C context, only the IRQ does. Needs a new record. | Easy: adding it back later is an addition. | Changes M10's interrupt work, toward less work. |
| 10. Keep calls, make them cheaper | Yes. | Days. | Low. | No: the vertical blank rule stays, and each future feature needs new calls. | Ports from PVSnesLib. | None. | Easy. | Does not meet the rule of additions only. |
| 11. Two layers from the start | Yes. | About 30 % on top of the core. | Medium: two layers to keep in step and document. | Usability, early. | Beginners. | A larger surface before the core has proved itself. | Easy. | The user wants the beginner layer later. Only the helpers the examples need. |
| 12. Mirror the whole machine | Yes: Rachael models every register she draws and counts the rest as undrawn. | 2 to 3 weeks, the largest: commit, Rachael, 7 examples, `LibApiTest`, `LibCostTest`, `SnesHardwareGuideTest`, the README tables. The book goes stale at the release. | Lowest of all for the layout, which the hardware fixed. Traps: the registers written twice, and DMA channel 0 kept for the queue. | Yes, including the PPU and HDMA features not built yet. | AI and veterans: best. Beginners need layers. PVSnesLib ports meet a new model. | Raw bits, as in 5. About 116 bytes of RAM. | Hard: it becomes the interface, which is the point. | Yes: it is the published copy of the interrupt design. ADR-6F3CK2AR's naming rule stays. |
| 13. Queue entries as raw DMA descriptors | Yes. | Days on top of 3. | Low. | Makes 3 final. | Everyone, through the helpers. | Rachael must interpret port setups she does not support yet (the remapping modes of `VMAIN`), counted as undrawn as today. | Medium. | Yes. |
| 14. Input as a shadow | Yes. | Hours to days. Recordings stay as they are until a device arrives. | Low. | Removes the one signature that would break. | Everyone. | A global instead of a parameter (taste). | Easy. | Yes. |
| 15. Two kinds of write | Yes: Rachael knows whether she is in a tick and whether the screen is off. | Days, mostly the check. | Low. | Makes the IRQ fit without changing the model. | Everyone; the check catches what would otherwise show only on the console. | The rule: immediate writes only with the screen off or in the IRQ. A level change goes through forced blank, which is allowed. | Easy. | Fits the IRQ decision. |

## Recommendation

A new core from 12, 13, 14 and 15 with a commit in groups, without `game_vblank` (9), and
everything friendlier as optional layers on top.

The core, stable from then on and growing only by additions:

1. The machine's state as data (12): the PPU register file (`sdevkit_ppu.bg1hofs`, ...), HDMA
   channels 1 to 7 and the sprite table, named after the hardware, so that any SNES document
   applies.
2. A commit in groups, from measurement A. The per-frame group (brightness, scroll, layers,
   colour math, window edges, mosaic: about 270 cycles) is committed every frame. The rare
   groups (setup registers, mode 7, HDMA channels) are committed only when the game marks them,
   one flag per group. Rachael compares the rare groups at every commit with what she committed
   last, and a change without its mark fails the desktop check. A forgotten mark becomes a
   failed check, not a write that is silently lost.
3. An upload queue of raw DMA descriptors (13), with one-line helpers for VRAM, a column and
   CGRAM.
4. Input as a shadow that the game reads (14): `game_tick(void)`.
5. Two kinds of write, named and checked (15).
6. No `game_vblank` (9). With the shadow and the queue it has nothing left to do, and adding it
   back later would be an addition.

Layers, built when an example needs them: the sprite list with metasprites (6), setup as one
struct without the layout by the build (7), and the attribute and map macros (5). Not now: 4
until an assembly packer is measured, 8, 10, and 11 as a whole.

Independent of the API: dampf should fold a constant index offset into the address of a global
array (`sta oam+1,y`). Measurement B suggests it is the largest single gain for sprite code, and
it helps every game.

Why: the hot path becomes absolute stores, and the vertical blank pays about 270 cycles plus
what the game uploads. The core has the hardware's layout, which does not change, so the
missing features arrive as constants, helpers and fields. The vertical blank rule goes away, and
the checks start comparing scroll, layers and colour math between the SNES and the PC.

The trade-off: raw bits. A wrong bit gives the same wrong picture on both platforms, and no
check sees it. This suits an AI and experienced developers. Beginners and PVSnesLib ports wait
for layers. It is also the largest migration of all the ideas: 7 examples, three tests, the
README tables, the book at the next release, and the game, whose migration is the game project's
work and needs an entry in `docs/game-findings.md`. It changes part of the interrupt decision of
the same day: without `game_vblank` the NMI runs only the kit's commit and needs no C context,
only the IRQ does. That needs a new record amending the interrupt decision, and the M10 work on
interrupt contexts, in progress on its own branch, should take it into account before it builds
the NMI side.

Order: the dampf offset fold, whenever; the mirror with groups on the SNES and Rachael, input,
and the checks, with the examples migrated; the queue, and `game_vblank` removed; the layers as
examples need them.

## Decision

Taken as recommended (2026-11-25), to be built on the branch `new_snes_api`, ending in a
comparison of the old API and the new one with measurements.

Revised the same day, after the comparison (ADR-6FNPPCSX): built, the shadow state cost every game
a fixed amount of work a tick, and a game that already did its writes in `game_vblank` and kept its
own sprite table, as Blubber Blasen does, gained nothing from it. The user chose a core that is the
hardware at once, for the experienced programmer and with nothing forced, and the shadow state,
the sprite list, the queue and the buttons pressed as optional layers above it.

## Open questions

- Once the NMI overlaps the next tick, the shadow and the RAM sources of queued uploads need a
  double buffer, or the loop waits for the commit. Waiting behaves as today and can come first.
  A double buffer of the sprite table costs no cycles (a DMA source is a pointer), but the game
  would then write through a pointer, which costs cycles on every write.
- Which registers belong to which group, measured on the examples: which registers each of them
  changes per frame.
- What an assembly packer for parallel arrays (4) costs per sprite. `DAMPF_ASM` is in develop
  now, so this can be measured.
- Whether a commit in assembly with the direct page at `$2100` saves enough to be worth it.
- How long the upload queue is, and what happens when it is full (an error in the desktop check,
  a counter in the debug view).
- Whether the setup struct (7) can describe every mode, offset-per-tile and high resolution from
  the start, so that it only grows by additions.
- What the recordings store once input is a struct, and whether the recorded checks of the
  examples stay valid.
- How the new record words the change to the interrupt decision, and what it means for the
  interrupt contexts being built.
