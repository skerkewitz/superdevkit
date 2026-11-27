---
title: The console API
sources:
  - lib/include/sdevkit/snes.h
  - lib/include/sdevkit/loop.h
  - lib/include/sdevkit/shadow.h
  - lib/include/sdevkit/sprites.h
  - lib/include/sdevkit/queue.h
  - lib/include/sdevkit/input.h
  - lib/include/sdevkit/easy.h
  - lib/include/sdevkit/irq.h
  - lib/include/sdevkit/publish.h
  - lib/layers/layers.h
  - lib/README.md
  - lib/snes/README.md
  - lib/snes/main.c
  - lib/snes/snes.c
  - lib/snes/layers.c
  - lib/snes/sfx.c
  - lib/snes/irq.c
  - lib/sound/sfx_voices.h
  - lib/rachael/rachael.h
  - lib/rachael/ppu.c
  - lib/rachael/layers.c
  - lib/rachael/sfx.c
  - lib/rachael/music.c
  - lib/rachael/sram.c
  - lib/rachael/debug.c
  - lib/rachael/irq.c
  - lib/splash/splash.h
  - docs/sound.md
  - docs/adr/6FNPPCSX-the-console-api-is-the-hardware-at-once-with.md
  - docs/adr/6FNWEJAA-dampf-leaves-out-the-functions-a-program-cannot.md
  - docs/adr/6FRDS030-interrupt-code-reads-the-copy-of-the-tick-whose.md
  - docs/adr/6FRX1GYC-the-game-s-irq-runs-at-a-picture-row-and-writes.md
  - docs/adr/6FS0T4WB-the-kit-s-nmi-runs-the-vertical-blank-of-a-game.md
  - docs/adr/6FSHCP06-rachael-draws-modes-0-to-4-mosaic-and-extbg-the.md
  - cli/src/main/java/superdevkit/project/Project.java
  - cli/src/main/java/superdevkit/project/Build.java
  - compiler/src/test/java/dampf/lib/LibApiTest.java
  - compiler/src/test/java/dampf/lib/LibCostTest.java
  - compiler/src/test/java/dampf/lib/LibNamesTest.java
  - examples/hello/src/*.c
  - examples/mode7/src/*.c
  - dev/irq_check/src/*.c
  - examples/catchme/src/game.c
  - examples/catchme/src/actors.c
audience: Game programmers who write a game with the dev kit and look up what a function of the kit takes, does and costs
---

## Purpose

The reference of everything a game may call or define: the frame rhythm of `sdevkit/loop.h`, the
core of `sdevkit/snes.h`, which reaches the hardware at once, the optional layers above it
(`sdevkit/shadow.h`, `sprites.h`, `queue.h`, `input.h`, `easy.h`), and the interrupts
(`sdevkit/publish.h`, `sdevkit/irq.h`), with what each function takes,
what it does on the SNES and on Rachael, and what it costs. A reader who knows a function's name
finds its entry; a reader who knows what they want done finds the group it is in. The headers stay
the source of truth; the page says so in one sentence and names them.

## Sections

The page opens, before the first H2, with what holds for all of it: a game reaches the console
only through this API, which exists on every platform (`#include "sdevkit/snes.h"`, and
`"sdevkit/easy.h"` or one of the layers' headers for a layer; `superdevkit build` passes
`-I <home>/lib/include` and compiles the library of the target with the game). Then the API's
shape in a few sentences: the core writes the hardware at once and the kit does nothing in the
vertical blank a game did not ask for; the layers are optional, a game uses them by calling them,
and a layer it does not call costs it nothing, since dampf leaves out what a program does not
reach (ADR-6FNWEJAA). It also says once what a cost range in the entries is: the best and the worst
path the timing report finds through the function, from a program that calls it with zeros, not a
cost per argument; that a call with a constant is often cheaper, since dampf inlines the small
functions (a register write with a constant register is one store); and that a game's own timing
report can differ a little, since dampf compiles the whole program at once.

1. **The frame rhythm** – `game_init`, `game_tick(pad)`, `game_vblank`: when each runs, what
   belongs in it, what the main loop does between them (the wait for the vertical blank, the
   joypads, the sound effects the tick asked for, after `game_vblank`; no copy and no register of
   its own), what happens when a tick takes longer than a frame, and the splash screen before
   `game_init`. When a write may happen, as a short table: VRAM, CGRAM and OAM only in
   `game_vblank` or with the screen off, a register likewise, switching the screen off at any
   time; and that the desktop's check fails on a lost byte or a register written while the
   picture is drawn. The pad bits `SDEVKIT_KEY_*` as a table. Two complete minimal games side by
   side: one on the core (from `examples/mode7`, cut down) and one on the layers (from
   `examples/hello`, cut down), each with all three functions. One sentence that a game which
   publishes its data defines `game_tick` and `game_vblank` through a macro instead, and gets
   its vertical blank run by the kit's NMI (section 14).
2. **Registers** – `sdevkit_reg_write` and `sdevkit_reg_write_twice`, the `SDEVKIT_PPU_*` names as
   a table (register, address, what it does, written twice or not), and the macros that build
   values: `SDEVKIT_FORCED_BLANK`, `SDEVKIT_BG_MODE1` and `_MODE7`, the `_TILES_16` bits,
   `SDEVKIT_BG1_ENABLE` to `SDEVKIT_OBJ_ENABLE`, `SDEVKIT_BGSC` with `SDEVKIT_SC_*`,
   `SDEVKIT_BGNBA`, `SDEVKIT_OBSEL` with `SDEVKIT_OBJ_SIZE*`, the map entry macros
   `SDEVKIT_BG_TIL_*`, `SDEVKIT_RGB5` and `SDEVKIT_RGB8`. An example sets up mode 1 with one layer
   and scrolls it in `game_vblank`.
3. **Colour math and mode 7** – the `SDEVKIT_CM_*` constants of CGWSEL, CGADSUB and COLDATA, and
   mode 7: how the matrix, the centre and the scroll map a screen pixel (the formula of the
   header), `SDEVKIT_M7_*`, and which registers hold them.
4. **Copies** – `sdevkit_dma`, `sdevkit_vram_copy`, `sdevkit_vram_column_copy`,
   `sdevkit_vram_fill`, `sdevkit_cgram_copy`, `sdevkit_cgram_set`, `sdevkit_oam_copy`,
   `sdevkit_mode7_map_copy`, `sdevkit_mode7_tiles_copy`, `sdevkit_dma_fits`: the units of the
   addresses (VRAM words, CGRAM colours, OAM bytes, sizes in bytes), what a vertical blank takes
   (the bytes of `lib/README.md`), and that `sdevkit_dma_fits` may answer differently on the SNES
   and the desktop while the queue (section 8) decides the same on both.
5. **Sprites** – the sprite table in the hardware's format (`SDEVKIT_OAM_BYTES`,
   `SDEVKIT_OAM_ATTR`, `SDEVKIT_OAM_HIDE_Y`, the high table's two bits), that the SNES draws a
   sprite one line lower than its y, and how a game copies only the entries in use with
   `sdevkit_oam_copy`. An example with a table of a few sprites.
6. **HDMA** – `sdevkit_hdma_set`, `sdevkit_hdma_enable`, `SDEVKIT_HDMA_ONE`, `_TWO_REGS`,
   `_TWICE` and `SDEVKIT_HDMA_CHANNELn`, the table format: the channel number of
   `sdevkit_hdma_set` against the mask of `sdevkit_hdma_enable`, why channel 0 is not for HDMA,
   and why a table must stay valid while the frame is drawn. The example draws something the
   effect can be seen on.
7. **The register shadow** (`sdevkit/shadow.h`) – `sdevkit_ppu`, its fields grouped by their
   marks as in the header, `sdevkit_hdma`, `sdevkit_marks` and `SDEVKIT_MARK_*`,
   `sdevkit_shadow_commit`: that every group starts marked, that a group changed without its mark
   is not committed and the desktop's check fails on it ("unmarked"), that INIDISP is written by
   every commit, and that the shadow and `sdevkit_reg_write` mix.
8. **The sprite list and the queue** (`sdevkit/sprites.h`, `sdevkit/queue.h`) – `sdevkit_oam`,
   `sdevkit_sprite`, `sdevkit_sprites_commit`; the `sdevkit_queue_*` functions,
   `SDEVKIT_QUEUE_BYTES`, `_COST` and `_ENTRIES`, `sdevkit_queue_commit`: what a refused copy
   means (it returns 0, ask again in the next tick), that the source must still hold its data at
   the commit, and that the budget holds when the commit starts `game_vblank`.
9. **The buttons pressed and the one commit** (`sdevkit/input.h`, `sdevkit/easy.h`) –
   `sdevkit_input`, `sdevkit_input_update`, `sdevkit_commit`: the order it commits in, that it may
   also run in `game_init` with the screen off, and how a game mixes it with the core (CatchMe
   copies its own sprite slots beside it).
10. **Sound effects** – `sdevkit_sfx_init`, `sdevkit_sfx_play`, the bank `sdevkit_sfx_bank` that
    the build writes (a game only passes it on) and `SDEVKIT_SFX_OFF`: when an effect starts,
    which one gives way when the voices are busy, and what is dropped.
11. **Music** – `sdevkit_music_play` and `sdevkit_music_stop`, the track numbers the build writes,
    and that the calls do nothing on the SNES yet.
12. **Save RAM** – `sdevkit_sram` and `SDEVKIT_SRAM_SIZE`: how a game asks for it (`sram` in its
    project file), where it is kept on each platform, and what it holds the first time, so that a
    game marks its data as valid. A short example that keeps a high score.
13. **Debug calls** – `sdevkit_dbg_vram` and `sdevkit_dbg_hash`: what each does on each platform,
    what data `sdevkit_dbg_hash` may be given, and that the kit names nothing itself, so a game
    names its sprite table and what its picture depends on; then the registrations of the debug
    view (`sdevkit_dbg_toggle`, `_range`, `_color`, `_action`, `_text`, `_value`) and
    `sdevkit_dbg_box`, `_fill`, `_arrow`, which are empty on the SNES.

14. **Interrupts** (`sdevkit/publish.h`, `sdevkit/irq.h`) – why interrupt code reads only
    published data; `SDEVKIT_PUBLISH`, `SDEVKIT_PUBLISH_DECLARE` and `SDEVKIT_PUBLISHED`: what the
    macro defines, which tick's data interrupt code gets, what the copy costs; the kit's NMI that
    runs a publishing game's `vblank` in every vertical blank, also while a tick overruns, the
    rule that `vblank` writes depend only on the published data, and the desktop check's
    `repeated`; the game's IRQ: `game_irq` with `DAMPF_INTERRUPT(irq)`, `sdevkit_irq_line`,
    `sdevkit_irq_off`, `sdevkit_irq_wait_hblank`, which row the handler runs before, the time
    before the blank and the cycles for its writes after the wait (with HDMA), several splits a
    frame, and the desktop check of a write before the wait. One complete example: a status bar
    under a scrolling area, cut down from `dev/irq_check`.

In sections 2 to 14, each function is an entry of its own (H3 with its name): its declaration as
in the header, what it does in one to three sentences, its parameters where the header's names
do not say it, the registers it writes where that matters, and its cost on the SNES from the
table in `lib/README.md` (CPU cycles, and for a copy the bytes of the vertical blank it uses).
Constants and fields are tables, not entries.

## Pay attention to

- Declarations exactly as in the headers, including the parameter names.
- The cost of every function exactly as in the table of `lib/README.md`, and where the cost
  depends on the call ("135 to 2,143" for `sdevkit_shadow_commit`), why, when the source says it.
  The layers' commits with the numbers of "The commit's time" there.
- Each entry says whether the function writes the hardware at once (the core) or in a later
  commit (the layers), and where a call belongs (`game_init`, `game_tick`, `game_vblank`), as the
  headers say it.
- What is different on Rachael (no time; the ports modelled; `sdevkit_dbg_vram` a single zero
  word on the SNES; music and the debug view's calls empty on the SNES, where dampf removes
  them), said where it applies, not collected at the end.
- Constants whose names read against their meaning (the `SCBLK` and `MATWIN` ones of colour
  math, PVSnesLib's names) get one sentence on how to read the name.
- The core for a game that wants every cycle, the layers for one that wants no rule of the
  vertical blank: say it once, in the opening, without ranking one above the other.

## Out of scope

- What the hardware allows and punishes, and which features the kit has:
  [The SNES hardware](/guide/snes-hardware); each entry links there where a rule applies.
- How to write the C of a game fast: [Writing fast C](/guide/writing-fast-c).
- How the effects and the music are made and converted (`sfx.json`, the BRR Lab,
  `superdevkit sfx`): [Graphics and sound](/guide/graphics-and-sound); how the debug view is used:
  [The desktop](/guide/the-desktop).
- Why the API has this shape (the shadow state tried first and measured): the decision records,
  linked once.
- Rachael's interface to the frontends (`rachael.h`, `sdevkit__*`): not for games.
