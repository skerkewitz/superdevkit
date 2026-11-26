<!-- GENERATED from doc-specs/guide/snes-hardware.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The SNES hardware

A game of the [dev kit](/glossary#dev-kit) reaches the console only through its
[console API](/glossary#console-api) (`sdevkit/snes.h`), and it runs on two platforms: on the
SNES, compiled by dampf, and on the PC, on the desktop or in the browser, compiled by a C
compiler for the PC against [Rachael](/glossary#rachael), the SNES rebuilt in portable C. A
feature is usable only when both platforms have it. If Rachael did not know a feature, the
desktop would show a different picture than the SNES, and every check on the desktop would be
worthless for that game. This page is made from `lib/SNES_HARDWARE.md`, the guide to the
hardware that ships with the kit.

You test on the desktop, on the web and in [Mesen](/glossary#mesen), not on a console, so each
rule below says how it is checked, in one of three words:

- **Enforced**: Rachael or the build reports a breach, and the desktop check fails on it.
- **Measured**: a check shows it without failing on it: what only the console's timing shows,
  in Mesen (`dev/check_example.sh`, the check of the kit's examples), or a count of the desktop
  check that a game may mean.
- **Documented**: the kit does not check it; you take care yourself.

## What the dev kit has

Use what is built in both columns of this table. The last column names how a game reaches a
feature: first with the core of the console API, `sdevkit/snes.h`, which writes the hardware at
once (a register by `sdevkit_reg_write` and its name `SDEVKIT_PPU_*`, a copy, a macro that builds
a value), then, after the semicolon, with the optional layers above it where one helps (a field
of the register shadow `sdevkit_ppu`, the sprite list, the upload queue).

| Feature | SNES (`lib/snes`) | Rachael | Reached through |
| --- | --- | --- | --- |
| Mode 1 (BG1 and BG2 with 16 colours, BG3 with 4), 8 x 8 and 16 x 16 tiles, scrolling | built | built | [`sdevkit_reg_write`](/reference/console-api#sdevkit-reg-write), [`sdevkit_reg_write_twice`](/reference/console-api#sdevkit-reg-write-twice), `SDEVKIT_PPU_BGMODE`, `SDEVKIT_BGSC`, `SDEVKIT_BGNBA`, `SDEVKIT_PPU_BG1HOFS`; `sdevkit_ppu.bgmode`, `sdevkit_ppu.hofs` |
| Sprites, with the limits of 32 a line and 34 tiles a line | built | built, and counted | [`sdevkit_oam_copy`](/reference/console-api#sdevkit-oam-copy), `SDEVKIT_OBSEL`, `SDEVKIT_OAM_ATTR`, `SDEVKIT_OAM_HIDE_Y`; [`sdevkit_sprite`](/reference/console-api#sdevkit-sprite), [`sdevkit_sprites_commit`](/reference/console-api#sdevkit-sprites-commit) |
| Windows, main and sub screen, colour math, brightness | built | built | [`sdevkit_reg_write`](/reference/console-api#sdevkit-reg-write), `SDEVKIT_PPU_WH0`, `SDEVKIT_PPU_TS`, `SDEVKIT_PPU_CGWSEL`, `SDEVKIT_PPU_COLDATA`, `SDEVKIT_PPU_INIDISP`; `sdevkit_ppu.wh`, `sdevkit_ppu.coldata`, `sdevkit_ppu.inidisp` |
| HDMA in direct mode: one register, two, or one written twice (a scroll register: a wave down the screen) | built | built | [`sdevkit_hdma_set`](/reference/console-api#sdevkit-hdma-set), [`sdevkit_hdma_enable`](/reference/console-api#sdevkit-hdma-enable); `sdevkit_hdma[n]`, `sdevkit_ppu.hdmaen` |
| DMA to VRAM, CGRAM and OAM, within the budget of the [vertical blank](/glossary#vertical-blank); down a column of a map; to any register of the [PPU](/glossary#ppu) | built | built, lost bytes counted | [`sdevkit_vram_copy`](/reference/console-api#sdevkit-vram-copy), [`sdevkit_vram_column_copy`](/reference/console-api#sdevkit-vram-column-copy), [`sdevkit_cgram_copy`](/reference/console-api#sdevkit-cgram-copy), [`sdevkit_oam_copy`](/reference/console-api#sdevkit-oam-copy), [`sdevkit_dma`](/reference/console-api#sdevkit-dma), [`sdevkit_dma_fits`](/reference/console-api#sdevkit-dma-fits); [`sdevkit_queue_vram`](/reference/console-api#sdevkit-queue-vram), [`sdevkit_queue_room`](/reference/console-api#sdevkit-queue-room) |
| Modes 0 and 2 to 6 | not built | not built, reported | |
| Mode 7: one layer of 1024 x 1024 pixels, rotated, scaled and mirrored, and what lies outside it; with HDMA on BGMODE and TM, mode 1 above it (a status line on BG3) | built | built | [`sdevkit_mode7_map_copy`](/reference/console-api#sdevkit-mode7-map-copy), [`sdevkit_mode7_tiles_copy`](/reference/console-api#sdevkit-mode7-tiles-copy), [`sdevkit_reg_write_twice`](/reference/console-api#sdevkit-reg-write-twice), `SDEVKIT_PPU_M7A`, `SDEVKIT_PPU_M7SEL`, `SDEVKIT_M7_WRAP`; `sdevkit_ppu.m7a` |
| Mode 7's second layer (EXTBG), direct colour | not built | not built, reported | |
| Mosaic, high resolution, interlace | not built | not built, reported | |
| The game's IRQ at a row of the picture (the timer's H/V IRQ, timed by the kit): a split screen, a status bar, a second scroll; its register writes come after a wait for the horizontal blank, about 25 CPU cycles of them | built: the CPU's timer, the handler in C in a context of its own; a write too late shows a row torn, in Mesen (*measured*) | built: called before the row; a register written before the wait counts as written while the picture is drawn (*enforced*) | [`sdevkit_irq_line`](/reference/console-api#sdevkit-irq-line), [`sdevkit_irq_off`](/reference/console-api#sdevkit-irq-off), [`sdevkit_irq_wait_hblank`](/reference/console-api#sdevkit-irq-wait-hblank), `game_irq`, `SDEVKIT_PUBLISHED` |
| The timers at any dot, an IRQ in the middle of a row | not built: the kit owns the timer | not built | |
| The game's vertical blank in the kit's NMI, in every vertical blank, also while a tick overruns (a game that publishes its data) | built: the NMI's handler that the macro defines, 118 to 140 CPU cycles beyond the game's code a frame | built: `game_vblank` runs it once a tick; the desktop's check runs it twice and counts what the second run changes (*enforced*) | `SDEVKIT_PUBLISH`, `SDEVKIT_PUBLISHED` |
| Sound effects: samples (BRR) from a game's `sfx.json`, 1 to 8 voices with priorities, GAIN's direct envelope, volume | built: a forwarding driver on the SPC700, the logic in C | built: Rachael's DSP, through raylib's audio stream; the DSP writes compared with the SNES's | [`sdevkit_sfx_init`](/guide/graphics-and-sound#sound-effects), [`sdevkit_sfx_play`](/guide/graphics-and-sound#sound-effects) |
| Music: tracks from files | not built: the calls do nothing | built: the file plays through raylib (OGG, MP3, WAV, QOA, XM, MOD) | [`sdevkit_music_play`](/guide/graphics-and-sound#music), [`sdevkit_music_stop`](/guide/graphics-and-sound#music) |
| ADSR envelopes, echo, noise, pitch modulation, looped samples | not built | not built, reported | |
| Save RAM: battery-backed, up to 32 KB, kept while the console is off | built: the cartridge's RAM at `$70:0000`, announced in the header by `dampf --sram` | built: `<game>.srm` next to the program; checks and recordings start with the save RAM they name | [`sdevkit_sram`](/reference/superdevkit#sram) |
| Mouse, multitap, Super Scope | not built | not built | |
| The CPU's multiplier (`$4202`, `$4203`, `$4216`) | used by dampf for every multiplication of two variables: a game that uses it itself must not multiply between its write and its read | not needed: the game is compiled for the PC | |
| The CPU's divider (`$4204`-`$4206`) | not used: dampf's runtime divides in software. A multiplication overwrites the divider's results and a 32-bit one its dividend, so a game that uses the divider itself must not multiply between its write and its read | not needed: the game is compiled for the PC | |
| Mode 7's multiplier (`$2134`-`$2136`) | not used | not needed: the game is compiled for the PC | |

DMA (direct memory access) copies a block of memory without the CPU; HDMA writes a value to a
register at the start of every line of the picture. VRAM holds the tiles and maps, CGRAM the
colours, OAM the sprite table. The IRQ and the NMI are interrupts: code that runs in the middle
of the game's tick, the IRQ at a row of the picture the game chooses, the NMI at the start of
every vertical blank. The SPC700 is the SNES's sound processor; it drives the DSP, the chip that
plays samples in BRR, the SNES's compressed sample format (see
[Sound: what the SNES can play](#sound-what-the-snes-can-play)). `SnesHardwareGuideTest` checks
that each feature built on both platforms names only what the public headers have, and that its
functions are in `lib/snes` and in `lib/rachael`.

"Reported" covers the features a game could reach anyway: `sdevkit_reg_write` sets any register
of the PPU, the SNES's picture processing unit. Rachael counts every write she does not draw: a
mode other than 1 and 7, mosaic, `SETINI` (`$2133`, interlace, high resolution and EXTBG),
direct colour, `VMAIN`'s address remapping, a DMA from the PPU to memory, and HDMA not in direct
mode. Her DSP counts in the same way every write that switches on what she does not play: ADSR,
the other modes of GAIN, echo, noise, pitch modulation and a sample with a loop. The SNES would
show or sound something else there, so the desktop check fails on such a write as it fails on a
lost byte (*enforced*). The check runs the game without a window, with the input of a
`check.txt`; for `examples/hello`, in its folder:

```sh
superdevkit build linux
mkdir -p build/check
build/linux/hello --check check.txt --out build/check
```

It ends with a line that counts the frames and the ticks, the lost bytes, the undrawn and the
unheard writes, the lines over the sprite limits, the registers written while the picture is
drawn, the register shadow's groups changed without their mark, the copies the upload queue
refused and the bytes a second run of the vertical blank changed:

```
frames 400 ticks 400 lost 0 undrawn 0 unheard 0 sprites 0 drawing 0 unmarked 0 refused 0 repeated 0
```

It exits with 1 when one of `lost`, `undrawn`, `unheard`, `drawing`, `unmarked` and `repeated`
is not zero; `sprites` and `refused` do not fail it. A game called `mode3` that calls
`sdevkit_reg_write(SDEVKIT_PPU_BGMODE, 3)` gets, before that line:

```
mode3: 1 register writes Rachael does not draw, the first $2105 = $03 (the SNES would show something else)
frames 3 ticks 3 lost 0 undrawn 1 unheard 0 sprites 0 drawing 0 unmarked 0 refused 0 repeated 0
```

A DSP write she does not play gives a line of the same kind, which starts
`mode3: 1 DSP writes Rachael does not play, the first $RR = $VV` with the DSP's register and its
value in hex. The file format and the other options of the check are on
[The desktop](/guide/the-desktop).

Rachael counts the same wherever she runs. The web runs the desktop's frontend, and the kit's
tests play every example's `check.txt` in its web build in a headless browser, with the same
`--check` and the same exit status ([The web](/guide/the-web)). In the window, the debug view
(F1) shows the lost bytes and the undrawn and unheard writes while the game runs, and the
program prints them to the error output when you close it.

Every function of the console API also has a cost on the SNES, in CPU cycles from best to worst
case, calls included. The table *Built and stub, per function and platform* in `lib/README.md`
lists them; `LibCostTest` fails when they are not what dampf gives today. `sdevkit_reg_write`
costs 25 cycles, `sdevkit_vram_copy` 116, and `sdevkit_shadow_commit` of the optional register
shadow 135 to 2,143, the most when it writes every group of registers and seven HDMA channels.
A call with constants is often less: dampf inlines the small functions, and a register write
with a constant register is one store. A DMA function also uses as many bytes of the vertical
blank's budget as it copies.

## Video memory

- **Write VRAM, CGRAM and OAM only in the vertical blank or with the screen off.** The vertical
  blank is the pause between two pictures; with the screen off (forced blank) the PPU accepts
  writes at any time. During the picture the PPU drops what comes, and so does Rachael. Prepare
  the data in `game_tick` and upload it in `game_vblank`; `game_init` runs with the screen off.
  Rachael counts the bytes written during the picture as lost, and the desktop check and the
  Mesen check of an example fail on a lost byte (*enforced*). The optional upload queue
  (`sdevkit/queue.h`) does it for a game: it queues in the tick, and `sdevkit_commit` copies in
  the vertical blank.
- **Write the registers in the vertical blank too.** A scroll or a brightness written while the
  picture is drawn takes effect from that line on, and the picture tears. Only switching the
  screen off may come at any time. Rachael counts every register or HDMA channel written while
  the picture is drawn, and the desktop check fails on it (*enforced*). A game called `scroll`
  that writes BG1's scroll with `sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, scroll_x)` in
  `game_tick` instead of `game_vblank` gets, in a check of three ticks:

  ```
  scroll: 6 register writes while the picture is drawn, the first $210D = $01 (the SNES would change the picture from the line being drawn on: write it in game_vblank)
  frames 3 ticks 3 lost 0 undrawn 0 unheard 0 sprites 0 drawing 6 unmarked 0 refused 0 repeated 0
  ```

  The optional register shadow (`sdevkit/shadow.h`) keeps the registers in memory for the tick
  and writes the groups marked in `sdevkit_marks` in the vertical blank; a group changed without
  its mark is counted as `unmarked`, and the check fails on it too (*enforced*).
- **Copy with DMA, never with a loop of the CPU**, but for a few bytes, which the CPU writes
  cheaper than a DMA's setup (one colour, one sprite). A vertical blank takes about 6,300 bytes
  of DMA when the game does nothing else in it: 37 lines of 1,364
  [master clocks](/glossary#master-clock), 8 a byte. Rachael counts every byte over that as
  lost, and the desktop check prints `lost frame N: B bytes` for each frame that loses some
  (*enforced*). Ask `sdevkit_dma_fits(n)` whether `n` more bytes fit; the upload queue decides
  it in the tick, the same on both platforms.
- **Keep the sprite table in RAM and copy only what changed**: the entries in use and the high
  table's bytes for them, not all 544 bytes, with `sdevkit_oam_copy` from any even byte. The kit
  copies nothing itself. The optional sprite list (`sdevkit/sprites.h`) copies the entries it
  filled and hides those of the tick before (*documented*).
- **Upload what changed, not everything**: a row of the map, a tile of an animation. Animate by
  copying a new picture over a tile that the map names in many places (*documented*).

## Sprites

- **At most 32 sprites and 34 tiles of 8 pixels on one line.** Over 32, the sprites with the
  highest numbers in the sprite table are dropped. Over 34 tiles, the sprites are fetched from
  the highest number down, so those with the lowest numbers lose their tiles. Rachael drops the
  same sprites and tiles as the SNES and counts the lines over each limit; the desktop check
  adds both counts up over all frames and prints the sum as `sprites N`. A game may mean it,
  when it flickers on purpose, so the check does not fail on it (*measured*).
- **Put the most important sprite at the lowest number.** It is drawn in front, and it is the
  last to be dropped by the 32-sprite limit, but the first to lose tiles over 34
  (*documented*).
- **Flicker** by rotating the order in the table from frame to frame when a line needs more
  (*documented*).

## CPU and memory

- **FastROM**: code and constant data in the [banks](/glossary#bank) from `$80` are read at
  3.58 MHz, WRAM at 2.68 MHz. dampf builds [FastROM](/glossary#fastrom-and-slowrom) and places
  the frames of functions in the [direct page](/glossary#direct-page), whose instructions are a
  byte shorter (*documented*; the cycles are in the [timing report](/glossary#timing-report)).
- **A DMA does not cross a 64 KB bank**: the source address wraps inside its bank. dampf places
  no object across a bank boundary, so a copy of one object is safe; a copy that runs from one
  object into the next is not (*documented*).
- **Read the [joypads](/glossary#joypad) after the automatic read** at the start of the vertical
  blank. The kit's main loop does it and hands the pads to `game_tick` (*enforced by the kit*).
- **The game's IRQ** (`sdevkit/irq.h`) runs in the middle of the tick, at any instruction. It
  reads the game's data only through the copy the tick publishes (`SDEVKIT_PUBLISHED`,
  `sdevkit/publish.h`), and writes no variable the main code writes too: one of the two writes
  could be lost. It reads and decides first, calls `sdevkit_irq_wait_hblank()` and then writes
  registers: the PPU takes a register for a row only in the horizontal blank before it. Measured
  in Mesen, the handler has about 53 CPU cycles before the wait and about 25 after it, less about
  6 for each HDMA channel of a byte on the line. The desktop check fails on a register written
  before the wait (*enforced*); a write after the blank shows a torn row, which only the Mesen
  check sees (*measured*).
- **The game's vertical blank in the kit's NMI.** A game that publishes its data
  (`SDEVKIT_PUBLISH`, `sdevkit/publish.h`) gets its vertical blank run by the kit's NMI, at the
  start of every vertical blank. It runs in the middle of the tick too, so the same holds: it
  reads the game's data only through the published copy, writes no variable the main code
  writes, and uses none of the optional layers. What it writes depends only on the published
  data: the NMI runs it again in every vertical blank while a tick overruns. The desktop check
  runs it twice after each tick and fails on what the second run changes, `repeated`
  (*enforced*).
- **A tick that takes longer than a frame** makes the game wait for the next vertical blank. A
  [tick](/glossary#tick) is one step of the game logic, a [frame](/glossary#frame) one picture;
  the game then runs at 30 frames a second, slower but never wrong. A game whose vertical blank
  the kit's NMI runs keeps its scroll, its splits and its uploads in every frame meanwhile; any
  other keeps the registers as the last writes left them, an IRQ's too. Rachael does not show
  it, because she runs at the PC's speed; the Mesen check counts frames and ticks (*measured*).
  Plan the work of a frame with the timing report and the [hint](/glossary#hint)
  `DAMPF_BUDGET`; [Writing fast C](/guide/writing-fast-c) says what costs time in C.

## Sound: what the SNES can play

The SNES makes its sound separately from the CPU, in its sound unit: the SPC700, a small
processor of its own at about 1 MHz with 64 KB of RAM (the audio RAM), and the S-DSP (the DSP),
the chip that plays samples from that RAM. The game's CPU reaches neither of them directly. It
talks to the SPC700 through four ports of one byte each, and a program on the SPC700, the sound
player (or driver), does what the CPU tells it: take bytes into the audio RAM, start a sound,
stop one.

The DSP mixes 8 voices, its channels. A voice plays one sample at a time; key on starts a note,
key off ends it. This section explains the hardware; what a game can use of it is in the table
of [What the dev kit has](#what-the-dev-kit-has), and how a game gets its sound effects is in
[Graphics and sound](/guide/graphics-and-sound#sound-effects).

### BRR samples

The DSP plays only samples, and only in BRR (bit rate reduction), its own compressed format.
Here a sample is a whole recorded sound, and the numbers it is made of are its values.

BRR packs 16 values, 32 bytes as 16-bit PCM, into a block of 9 bytes: a header byte and sixteen
4-bit numbers. A second of sound therefore takes its rate times 9/16 bytes:

| Rate | Bytes a second | Seconds in 64,448 bytes |
| --- | --- | --- |
| 8 kHz | 4,500 | 14.3 |
| 16 kHz | 9,000 | 7.2 |
| 32 kHz | 18,000 | 3.6 |

64,448 bytes is what the audio RAM leaves for samples next to the kit's driver today; a game
with music and echo has less (see the audio RAM below). A lower rate saves room but holds
nothing above half the rate: an 8 kHz sample has nothing above 4 kHz, which is fine for an
explosion and dull for a cymbal.

The header of a block sets its shift, a scale for its sixteen numbers, and one of four filters.
A filter predicts each value from the two before it (filter 0 predicts nothing), and the 4 bits
hold only the difference from the prediction. A smooth tone is easy to predict and keeps its
quality. A sharp click is not: all 16 values of a block share one shift, so a quiet sound right
after a loud click in the same block (1 ms at 16 kHz) comes out coarse or is lost.

The last block of a sample has the end flag set, and there the voice stops. If the loop flag is
set too, the voice jumps back to the sample's loop point instead and plays on until key off.

A voice puts out 32,000 values a second. How fast it moves through its sample is set by its
pitch register. At 4,096 (`$1000`) it takes one value of the sample for each value it puts out;
at 2,048 half as many, an octave lower; at the highest setting, 16,383, just under four times as
many, two octaves up. A sample recorded at 16 kHz plays at its own pitch with 2,048, so it can
go three octaves up; one recorded at 32 kHz only two.

One sample gives every note of an instrument: an SNES piano is one recorded note, played higher
or lower. Close to the recorded note it sounds natural. Far above it, it sounds thin; far below
it, muffled. A sound player can use a second sample for the top or the bottom of the range, if
the audio RAM has room.

A long note needs no long sample. The sample holds the attack, the first fraction of a second in
which an instrument sounds like itself, and then a short loop that repeats until key off. A loop
is a whole number of blocks, so the wave in it has to fit 16, 32, 48 or more values exactly, or
the loop clicks at its seam; a converter resamples the sound so that it fits, and the pitch
register then tunes the note. One wave of a square, saw or triangle tone fits in a block or two,
9 or 18 bytes, so the tones of an NES or a Game Boy cost almost nothing.

So SNES music is notes played on short sampled instruments, the way a MOD or XM file from a
tracker works on the PC. Three minutes recorded at 8 kHz would be about 800 KB; a song of notes
takes a few KB (see the sound player below).

On the way out the DSP interpolates between the values along a Gaussian curve, which rounds off
the highs. That is why the SNES sounds slightly dull. A converter from WAV to BRR can make up
for it by boosting the highs before it encodes; the kit's does that with `treble` in `sfx.json`.

### The eight voices

Each voice has:

- **A sample**, chosen from a directory of up to 256 entries, each a start and a loop address.
- **A pitch**, which the sound player may change at any time. Vibrato, slides and arpeggios are
  the sound player changing it many times a second.
- **Volume and pan**: a volume for the left and one for the right, -128 to 127 each. A negative
  volume turns the wave over.
- **An envelope**, the voice's volume over time. Either ADSR (attack, decay, sustain, release),
  which the DSP runs itself: an attack from instant to about 4 seconds, a decay to a sustain
  level in eighths, and a slow fall while the note is held. Or GAIN, which the sound player sets
  directly or lets rise or fall at one of 32 rates. On key off the voice fades out in about 8
  ms.
- **Noise** instead of its sample, for wind, surf, explosions and cymbals. There is one noise
  generator, so all voices on noise share its pitch (32 steps).
- **Pitch modulation** by the voice before it (voices 1 to 7), which makes bells and other
  metallic sounds.
- **Echo**, on or off.

The voices are mixed into one stereo output with a main volume, and the voices with echo on
share one echo: a delay of up to 240 ms in steps of 16 ms, a feedback, and an 8-tap filter that
colours it (with a long feedback a hall, with the highs filtered off a cave). The echo's buffer
lies in the audio RAM, 2 KB per 16 ms, so the longest echo takes 30 KB, nearly half the audio
RAM.

What the DSP does not have: a filter per voice (no low pass on one instrument), two echo
settings at a time, stereo samples (a stereo sound takes two voices), and more than 8 voices.

### The audio RAM

Everything that plays has to be in the 64 KB while it plays: the sound player, the sample
directory, the samples, the notes of the music and the echo buffer. The ROM can hold any number
of samples, but a sample in the ROM cannot play until it is copied into the audio RAM. The copy
goes through the four ports, about 350 bytes a frame with the SPC700's built-in loader (its boot
ROM, measured in Mesen), so 30 KB take a second and a half.

The games of the time therefore loaded a set of samples per level or per song during a
transition, the short silence between the title and a level in many SNES games, and kept a
common set (jumping, the menu) loaded throughout. One way to split the RAM: 32 KB of
instruments, 8 KB of effects, 4 KB of songs, 8 KB of echo (64 ms), and the sound player in what
is left.

### The sound player

The DSP plays what its registers say at this moment. Everything that happens over time is the
sound player writing registers at the right time: the notes of a song in their rhythm, the pitch
of a vibrato, a fade out, the choice of the voice an effect takes when all are busy. The games
of the time ran their sound player on the SPC700, with the music's notes in the audio RAM and
the SPC700's timers (8 and 64 kHz) for the tempo. The music then plays on when the game's CPU is
late with a frame, and the game sends only short commands: play effect 3, play song 2, fade out.

What a game can do with sound is what its sound player offers. A sound player for music has
instruments (a sample with its envelope, tuning and volume) and songs (notes per voice, with
tempo, repeats, volume and pan). It may add vibrato, slides and arpeggios, an echo set per song,
effects that borrow a voice of the music and give it back, and a new set of samples between
levels.

Streaming a long sample from the ROM while it plays is possible too: 350 bytes a frame are about
21,000 bytes a second, enough for one stream at up to 37 kHz. The CPU spends time on it in every
frame.

### What the kit has today

The kit's sound player is a forwarder: 21 bytes on the SPC700 that write each value the CPU
sends into the DSP register the CPU names. The logic is C in `lib/snes`, run in the game's tick,
so whatever it plays follows the game's frames. With it a game plays the sound effects of its
`sfx.json`: one sample each without a loop, at its own rate, priority and volume, on up to 8
voices the file reserves. The samples are one set, loaded once in `game_init`. What else is
built, on the SNES and on Rachael, is in the table of
[What the dev kit has](#what-the-dev-kit-has); music plays only on the desktop and the web for
now.

## The console is not the emulator

At power on, a console's registers and video memory hold whatever happens to be there, while
emulators start with zeros. The kit's start-up on the SNES (`sdevkit__reset_ppu`) clears every
PPU register, VRAM and CGRAM and hides every sprite before `game_init`, and C zeroes the game's
static variables, so a game of the kit starts from known values on both (*enforced by the
kit*). The splash screen every game starts with does not change that: the main loop resets the
console again after it, so `game_init` finds what it found before the splash existed. What stays
a risk is a local variable read before it is written: compile the [PC build](/glossary#pc-build)
with `-Wall -Wextra` and fix every "may be used uninitialized".

Save RAM starts unknown as well. The first time, a cartridge's RAM holds whatever it held, while
the desktop and the checks start with zeros. Mark the game's data in it as valid, with a magic
number or a checksum, and clear the block when the mark is missing (*documented*).

PAL consoles run at 50 frames a second, NTSC consoles at 60, and their vertical blank is
longer. One ROM runs on both with NTSC's limits, so a game that counts in frames runs a sixth
slower on PAL (*documented*).

## Not usable yet

Music on the SNES and the mouse each wait until their feature is built in both columns of the
table; [Limits](/reference/limits) says what is planned. A game can call the music functions
already: they do nothing on the SNES, and dampf removes the calls. Raster effects work today
with HDMA, which writes a value per line to a register (scrolling, colours and windows that
change down the screen), and with the game's IRQ at a few rows of the picture; an effect in the
middle of a row does not.
