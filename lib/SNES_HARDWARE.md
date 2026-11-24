# The SNES hardware for superdevkit games

What the console allows, punishes and rewards, and which of it the kit has. The companion of
[C_STYLE.md](C_STYLE.md), which says what dampf makes of C code; this guide is about the machine,
which has not changed since 1990. Written for whoever writes a game, an AI first: it tests on
Rachael (the desktop) and in Mesen, never on a console, so each rule says how it is checked.

**Three kinds of rule.** *Enforced*: Rachael or the build reports a breach, and the desktop
check fails on it. *Measured*: a check shows it without failing on it: what only the console's
timing shows, in Mesen (`dev/check_example.sh`), or a count of the desktop check that a game may
mean. *Documented*: the kit does not check it; take care yourself.

## What the kit has

A game reaches the hardware only through the console API (`sdevkit/snes.h`), and a feature is
usable only when both platforms have it: one Rachael does not know would make the desktop show
something else than the SNES, and every check on the desktop would be worthless for that game.
Use what is built in both columns. `SnesHardwareGuideTest` checks that each feature built on
both has its functions in `lib/snes` and in `lib/rachael`.

| Feature | SNES (`lib/snes`) | Rachael | Functions |
| --- | --- | --- | --- |
| Mode 1 (BG1 and BG2 with 16 colours, BG3 with 4), 8 x 8 and 16 x 16 tiles, scrolling | built | built | `sdevkit_set_mode`, `sdevkit_bg_init_tile_set`, `sdevkit_bg_set_map_ptr`, `sdevkit_bg_set_scroll` |
| Sprites, with the limits of 32 a line and 34 tiles a line | built | built, and counted | `sdevkit_oam_init_gfx_set`, `sdevkit_oam_set`, `sdevkit_oam_set_ex`, `sdevkit_oam_clear` |
| Windows, main and sub screen, colour math, brightness | built | built | `sdevkit_set_color_effect`, `sdevkit_set_color_intensity`, `sdevkit_bg_set_enable_sub`, `sdevkit_set_brightness`, `sdevkit_reg_write` |
| HDMA in direct mode: one register, two, or one written twice (a scroll register: a wave down the screen) | built | built | `sdevkit_hdma_set`, `sdevkit_hdma_enable` |
| DMA to VRAM and CGRAM, within the vertical blank's budget; down a column of a map | built | built, lost bytes counted | `sdevkit_dma_copy_vram`, `sdevkit_dma_copy_vram_column`, `sdevkit_dma_copy_cgram`, `sdevkit_dma_fits` |
| Modes 0 and 2 to 6 | not built | not built, reported | |
| Mode 7: one layer of 1024 x 1024 pixels, rotated, scaled and mirrored, and what lies outside it; with HDMA on BGMODE and TM, mode 1 above it (a status line on BG3) | built | built | `sdevkit_mode7_load_map`, `sdevkit_mode7_load_tiles`, `sdevkit_mode7_set_matrix`, `sdevkit_mode7_set_center`, `sdevkit_mode7_set_select` |
| Mode 7's second layer (EXTBG), direct colour | not built | not built, reported | |
| Mosaic, high resolution, interlace | not built | not built, reported | |
| Raster interrupts (H/V IRQ), the timers | not built: the game is never interrupted (`sdevkit/loop.h`) | not built | |
| Sound effects: samples (BRR) from a game's `sfx.json`, 1 to 8 voices with priorities, GAIN's direct envelope, volume ([docs/sound.md](../docs/sound.md)) | built: a forwarding driver on the SPC700, the logic in C | built: Rachael's DSP, through raylib's audio stream; the DSP writes compared with the SNES's | `sdevkit_sfx_init`, `sdevkit_sfx_play` |
| Music: tracks from files ([docs/sound.md](../docs/sound.md#music)) | not built: the calls do nothing | built: the file plays through raylib (OGG, MP3, WAV, QOA, XM, MOD) | `sdevkit_music_play`, `sdevkit_music_stop` |
| ADSR envelopes, echo, noise, pitch modulation, looped samples | not built | not built, reported | |
| Save RAM: battery-backed, up to 32 KB, kept while the console is off | built: the cartridge's RAM at `$70:0000`, announced in the header by `dampf --sram` | built: `<game>.srm` next to the program; checks and recordings start with the save RAM they name | `sdevkit_sram` |
| Mouse, multitap, Super Scope | not built | not built | |
| The CPU's multiplier (`$4202`, `$4203`, `$4216`) | used by dampf for every multiplication of two variables: a game that uses it itself must not multiply between its write and its read | not needed: the game is compiled for the PC | |
| The CPU's divider (`$4204`-`$4206`) | not used: dampf's runtime divides in software. A multiplication overwrites the divider's results and a 32-bit one its dividend, so a game that uses the divider itself must not multiply between its write and its read | not needed: the game is compiled for the PC | |
| Mode 7's multiplier (`$2134`-`$2136`) | not used | not needed: the game is compiled for the PC | |

"Reported": `sdevkit_reg_write` can set any PPU register, and Rachael counts every write she
does not draw (a mode other than 1 and 7, mosaic, `SETINI` for interlace, high resolution and
EXTBG, direct colour, and the ports of VRAM, CGRAM and OAM, which go around her memories). The desktop check prints
`undrawn N` and fails on it, as on a lost byte (*enforced*).

What each function costs on the SNES, in CPU cycles, is the last column of the table in
[README.md](README.md#built-and-stub-per-function-and-platform).

## Video memory

- **Write VRAM, CGRAM and OAM only in the vertical blank or with the screen off** (forced
  blank), never during the picture: the PPU drops what comes then. Prepare in `game_tick`,
  upload in `game_vblank` (*enforced*: Rachael counts lost bytes, and the Mesen check of an
  example fails on one).
- **Copy with DMA**, never with a loop of the CPU. A vertical blank takes about 5,700 bytes of
  DMA (37 lines of 1,364 master cycles, 8 a byte, less the sprite table's 544); more is lost
  (*enforced*). `sdevkit_dma_fits(n)` says whether `n` more bytes fit.
- **Keep the sprite table in RAM and upload all of it each frame**: the kit's main loop copies
  `sdevkit_oam_memory` in every vertical blank; a game only writes the copy.
- **Upload what changed, not everything**: a row of the map, a tile of an animation. Animate by
  copying a new frame over a tile that the map names in many places (Steinschlag's README shows
  both).

## Sprites

- **At most 32 sprites and 34 tiles of 8 pixels on one line.** Over 32, the sprites with the
  highest numbers in the sprite table are dropped; over 34 tiles, the sprites are checked from
  the highest number down, so those with the lowest numbers lose their tiles (*measured*: Rachael
  counts both, and the desktop check prints the lines over a limit as `sprites N`; a game may
  mean it, flickering on purpose, so the check does not fail on it).
- **Put the most important sprite at the lowest number**: it is drawn in front, and it is the
  last to be dropped by the 32-sprite limit (but the first to lose tiles over 34).
- **Flicker** by rotating the order in the table from frame to frame when a line needs more
  (*documented*).

## CPU and memory

- **FastROM**: code and constant data in the banks from `$80` are read at 3.58 MHz, WRAM at
  2.68 MHz; dampf builds FastROM and places its function frames in the direct page, whose
  instructions are a byte shorter (*documented*; the cycles are in the timing report).
- **A DMA does not cross a 64 KB bank**: the source address wraps inside its bank. dampf places
  no object across a bank boundary, so a copy of one object is safe; a copy that runs from one
  object into the next is not (*documented*).
- **Read the joypads after the automatic read** at the start of the vertical blank: the kit's
  main loop does, and hands them to `game_tick` (*enforced by the kit*).
- **A tick that takes longer than a frame** makes the game wait for the next vertical blank: it
  then runs at 30 frames a second, never wrong. Rachael does not show it, she has the PC's speed;
  the Mesen check counts frames and ticks (*measured*). Plan the work of a frame with the timing
  report and `DAMPF_BUDGET` (C_STYLE.md, part 6).

## Sound: what the SNES can play

The SNES makes its sound separately from the CPU, in its sound unit: the SPC700, a small
processor of its own at about 1 MHz with 64 KB of RAM (the audio RAM), and the S-DSP (the DSP),
the chip that plays samples from that RAM. The game's CPU reaches neither of them directly. It
talks to the SPC700 through four ports of one byte each, and a program on the SPC700, the sound
player (or driver), does what the CPU tells it: take bytes into the audio RAM, start a sound,
stop one.

The DSP mixes 8 voices, its channels. A voice plays one sample at a time; key on starts a note,
key off ends it. This section explains the hardware. The rules for a game are in the table of
[What the kit has](#what-the-kit-has) and in [docs/sound.md](../docs/sound.md).

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
for it by boosting the highs before it encodes; the kit's does that with `treble` in `sfx.json`
([docs/sound.md](../docs/sound.md)).

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
ROM, measured in Mesen, [docs/sound.md](../docs/sound.md#on-the-snes)), so 30 KB take a second
and a half.

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
sends into the DSP register the CPU names ([docs/sound.md](../docs/sound.md#on-the-snes)). The
logic is C in `lib/snes`, run in the game's tick, so whatever it plays follows the game's
frames. With it a game plays the sound effects of `sfx.json`: one sample each without a loop, at
its own rate, priority and volume, on up to 8 voices the file reserves. The samples are one set,
loaded once in `game_init`. What else is built, on the SNES and on Rachael, is in the table of
[What the kit has](#what-the-kit-has); music plays only on the desktop and the web for now.

## The console is not the emulator

- **Start values**: at power on, a console's registers and video memory hold whatever is there,
  where emulators start with zeros. The kit's start-up on the SNES (`sdevkit__reset_video`)
  clears every PPU register, VRAM, CGRAM and the sprite table before `game_init`, and C zeroes
  the game's static variables, so a game of the kit starts from known values on both (*enforced
  by the kit*). What stays a risk is a local variable read before it is written: compile the PC
  build with `-Wall -Wextra` and fix every "may be used uninitialized".
- **PAL** consoles run at 50 frames a second, NTSC at 60, with a longer vertical blank. One ROM
  runs on both with NTSC's limits; a game that counts in frames runs a sixth slower on PAL
  (*documented*; decided in ADR-6F60Z4AZ,
  [docs/adr/0036](../docs/adr/6F60Z4AZ-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md)).

## Not usable yet

Music on the SNES, raster effects by interrupt, the mouse: each waits for its feature in
both columns of the table. Many raster effects work today with HDMA (a value per line for a
register: scrolling, colours, windows).

## Sources

The rules on the hardware are from the SNESdev wiki (snes.nesdev.org: sprites, DMA registers)
and fullsnes by nocash; the state of the kit from `lib/README.md`, `lib/rachael/` and
`lib/snes/`. A feature added later changes its row in the same commit, on both platforms.
