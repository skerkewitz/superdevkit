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

The SNES makes its sound apart from the CPU, in its sound unit: the SPC700, a small processor
of its own at about 1 MHz with 64 KB of RAM (the audio RAM), and the S-DSP, the chip that plays
samples from that RAM. The game's CPU reaches neither the audio RAM nor the DSP. It talks to the
SPC700 through four ports of one byte each, and a program on the SPC700, the sound player (or
driver), does what it is told: upload bytes, start a sound, stop one. So what a game can play is
what the DSP can do, as far as the player makes use of it.

### Samples: BRR

The DSP plays samples, only in BRR, its compressed format. BRR stores 16 values in a block of 9
bytes: a header byte and 16 values of 4 bits. The header gives the block a shift (how loud its
16 values are) and one of four filters, which predict each value from the two before it, so that
the 4 bits only hold the error of the prediction. A tone that changes smoothly keeps its
quality; a block with a loud click followed by something quiet loses the quiet part, since all
16 values share one shift. Two flags in the header end a sample: at its end a voice stops, or,
with the loop flag, jumps back to the sample's loop point and plays on while the note is held.

A sample takes its rate times 9/16 bytes a second:

| Rate | Bytes a second | Seconds in the kit's 64,448 bytes for samples |
| --- | --- | --- |
| 8,000 Hz | 4,500 | 14.3 |
| 16,000 Hz | 9,000 | 7.2 |
| 32,000 Hz | 18,000 | 3.6 |

A recorded piece of music therefore does not fit: three minutes at 8 kHz are about 800 KB. SNES
music is played from notes, as MIDI or a tracker plays it (see the player below).

Every voice comes out at 32 kHz. The DSP steps through the sample by the voice's pitch register:
4096 plays one value of the sample per value out, 2048 half as fast (an octave lower), up to
16383, just under four times as fast (two octaves higher). A sample recorded at 16 kHz plays at
its own pitch with 2048 and can go three octaves up. One sample is all the notes of an
instrument: a piano is one recorded note played higher or lower. Close to the recorded note it
sounds natural; far from it, thin or muffled, which is why a player may use a second sample for
the top or the bottom of a range, if the audio RAM has room.

A long note needs no long sample. The sample holds the attack, the first fraction of a second in
which an instrument sounds like itself, and then a short loop that repeats while the note is
held. A loop is a whole number of blocks (a multiple of 16 values). One wave of a square, saw or
triangle tone fits in a block or two, 9 or 18 bytes: the sounds of the 8-bit consoles cost almost
nothing.

On the way out the DSP interpolates between the values along a Gaussian curve, which rounds off
the highs. That is the slightly dull sound of the SNES; a converter makes up for it by boosting
the highs before it encodes (`treble` in `sfx.json`, [docs/sound.md](../docs/sound.md)).

### The eight voices

The DSP has 8 voices, and that is the limit: at most 8 sounds at a time, music and effects
together. Each voice has

- a sample, chosen from a directory of up to 256 (a start and a loop address each);
- a pitch, which the player may change at any time: vibrato, slides and arpeggios are the player
  changing it many times a second;
- a volume for the left and one for the right, -128 to 127 each, which is also the pan; a
  negative volume turns the wave over, which some games used for a "surround" sound;
- an envelope, its volume over time: either ADSR, run by the DSP (an attack from about 4
  seconds to none, a decay, a sustain level in eighths, a slow fall while the note is held), or
  GAIN, which the player sets directly or lets rise or fall at one of 32 rates. On key off the
  voice fades out in about 8 ms;
- noise instead of its sample, for wind, surf, explosions and cymbals. There is one noise
  generator, so all voices on noise share its pitch (32 steps);
- pitch modulation by the voice before it (voices 1 to 7), which makes bells and other metallic
  sounds;
- echo on or off.

Key on and key off, a bit per voice, start and end the notes. After the voices come a main volume
and one echo for all voices that have it on: a delay of up to 240 ms in steps of 16 ms, a
feedback and an 8-tap filter that colours it (a hall, a dull cave, a clear echo). The echo's
buffer lies in the audio RAM, 2 KB per 16 ms, so the longest echo takes 30 KB, nearly half of it.

What the DSP does not have: a filter per voice (no low pass on one instrument), two echo settings
at a time, stereo samples (a stereo sound takes two voices), and more than 8 voices.

### The audio RAM

All that plays has to be in the 64 KB when it plays: the player, the sample directory, the
samples, the notes of the music and the echo buffer. The ROM can hold any number of samples, but
a sample in the ROM is not playable until it is copied into the audio RAM, and that copy goes
through the four ports: with the SPC700's boot code about 350 bytes a frame, as the kit measures
it ([docs/sound.md](../docs/sound.md#on-the-snes)), so 30 KB take a second and a half. Games
therefore load a set of samples per level or per song during a transition (the short silence
between the title and a level in many SNES games) and keep a common set (jumping, the menu)
loaded throughout. One way to split the RAM, as an example: 32 KB of instruments, 8 KB of effects,
4 KB of songs, 8 KB of echo (64 ms), and the player in what is left.

### The sound player

The DSP plays what its registers say at this moment. Everything that happens over time is the
player writing registers at the right time: the notes of a song in their rhythm, the pitch of a
vibrato, a fade out, the choice of the voice an effect takes when all are busy. Games ran their
player on the SPC700, with the music's notes in the audio RAM and the SPC700's timers (8 and 64
kHz) for the tempo. The music then plays on when the game's CPU is late with a frame, and the
game sends only short commands: play effect 3, play song 2, fade out.

What a game can do with sound is what its player offers: instruments (a sample with its
envelope, tuning and volume), songs as notes per voice with tempo, repeats, volume and pan,
vibrato, slides and arpeggios, echo set per song, an effect that borrows a voice of the music and
gives it back, a new set of samples between levels. Playing a long sample from the ROM while it
streams through the ports is possible too, but the CPU pays for it in every frame.

### What the kit has today

The kit's player is a forwarder: 21 bytes on the SPC700 that write each value the CPU sends into
the DSP register the CPU names ([docs/sound.md](../docs/sound.md#on-the-snes)). The logic is C in
`lib/snes`, run in the game's tick, so whatever it plays follows the game's frames. With it a
game has sound effects from `sfx.json`: one sample each, without a loop, at a rate of its own,
with a priority and a volume, on up to 8 voices the file reserves, with GAIN's direct envelope;
and one set of samples, loaded once in `game_init`.

Not built, on the SNES and on Rachael: loops, ADSR and GAIN's other modes, echo, noise, pitch
modulation, a pan or a volume per call, a second set of samples, and music, which needs a player
that plays notes on the SPC700. A DSP write that switches on what Rachael does not play is
counted, and the desktop check fails on it (`unheard`, *enforced*). Music plays on the desktop
and the web only, from files.

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
