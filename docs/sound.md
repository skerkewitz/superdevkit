# Sound effects

How a game gets sound effects, from the WAV files to the voices of the S-DSP. Built from the
[proposal](archive/sound-effects-proposal.md). Music, so far on the desktop and the web only, is at the end
([Music](#music)).

## The file: `assets/sfx/sfx.json`

A game lists its effects in `assets/sfx/sfx.json`, next to their WAV files:

```json
{
  "voices": 2,
  "sounds": [
    { "id": "jump", "priority": 1, "samples": [
        { "file": "jump.wav",  "active": true,
          "rate": 16000, "treble": 6, "gain": 0, "trim": true, "volume": 100 },
        { "file": "jump2.wav", "active": false,
          "rate": 11000, "treble": 9, "gain": 2, "trim": true, "volume": 110 } ] },
    { "id": "hurry", "priority": 2, "samples": [] }
  ]
}
```

| Member | Of | Values | What |
| --- | --- | --- | --- |
| `voices` | the file | 1 to 8, default 1 | how many of the DSP's 8 voices the effects use |
| `id` | an effect | small letters, digits, `_` | its name in the header: `SFX_` and the id in capitals |
| `priority` | an effect | 0 to 255, default 0 | which effect gives way when the voices are busy: the higher wins |
| `samples` | an effect | a list, may be empty | the variants to choose from; at most one is `active` |
| `file` | a sample | a WAV file in the same folder | PCM of 8, 16, 24 or 32 bits or 32-bit float, any channels, 1 to 192 kHz |
| `active` | a sample | `true`, `false` (default) | only the active sample is read, converted and put into the ROM |
| `rate` | a sample | 2000 to 32000 Hz, default 16000 | the rate the DSP plays it at; it sets the pitch register, `rate * 4096 / 32000` |
| `treble` | a sample | 0 to 18 dB in steps of 0.5, default 0 | a boost of the highs before encoding, against the dull Gaussian interpolation |
| `gain` | a sample | -18 to +12 dB in steps of 0.5, default 0 | scales the sound before it is encoded, so that it uses BRR's range (a quiet sound stored quietly loses bits) |
| `trim` | a sample | `true` (default), `false` | cuts what is quieter than -50 dB at the start and the end |
| `volume` | a sample | 0 to 127, default 100 | the voice's volume register when it plays |

An effect's number is its place in the list. An effect without an active sample is disabled: its
name stays in the header (with a comment), so the game builds unchanged; it takes no byte of the
audio RAM, and `sdevkit_sfx_play` returns at once for it. Changing which sample is active swaps
the sound in the next build and keeps the settings of the others.

`superdevkit sfx assets/sfx/sfx.json NAME out_dir` writes `NAME.h` (the names, `SFX_COUNT`, and
`NAME_bank`) and `NAME.c` (the BRR of every active sample and the tables of the effects). It
stops, with the line in the file, when:

- the file is not JSON, or a member is unknown, of the wrong type or out of its range;
- an effect has more than one active sample;
- an active sample's WAV file is missing or cannot be read (an inactive one is not read);
- two effects have the same `id`;
- a sample is larger than 32 KB of BRR (no object of dampf is larger than a ROM bank);
- there are more than 64 active samples (the sample directory is one page);
- the active samples do not fit into the audio RAM next to the driver (64,448 bytes, from `$0400`
  to `$FFC0`); the message lists the largest.

A game that uses an effect the file does not list stops at the C compiler: the name is not in the
header.

## The conversion

The conversion exists twice: in Java (`superdevkit sfx`, `cli/src/main/java/superdevkit/sfx`) for
the build, which asks for nothing but Java, and in JavaScript in the Lab, which plays what the
build makes. Both give the same bytes (`SfxTest` runs the Lab's code under Node, and compares
both with BRR files checked in). To make that possible, every step uses integers only; what needs
sin, cos or pow is in tables that [`dev/sfx_tables.py`](../dev/sfx_tables.py) computed once
(`cli/src/main/resources/superdevkit/sfx/tables.txt`, and the same numbers in the Lab).

1. **WAV.** Read by the converter itself, not by the browser's `decodeAudioData`, which resamples
   to the rate of the audio device. Every sample becomes 24 bits: 8 bits unsigned (`(b - 128) <<
   16`), 16 bits shifted up by 8, 24 bits as they are, 32 bits shifted down by 8, a float `f` as
   `floor(f * 8388608 + 0.5)`, clamped. The channels are added and divided by their number,
   rounding towards minus infinity.
2. **Pitch.** `round(rate * 4096 / 32000)`, 1 to `$3FFF`; the DSP plays at `pitch * 32000 /
   4096` Hz, which the resampling aims at exactly.
3. **Resampling.** Windowed sinc (Blackman, 32 zero crossings a side, 128 steps per crossing),
   the cut-off at 0.95 of the lower of the two Nyquist frequencies, so nothing folds back.
   Positions are in units of 2^-20 input samples; each output is the weighted sum divided by the
   sum of the weights it used. Every value stays below 2^53, where JavaScript's numbers are exact.
4. **Treble.** A high shelf at a quarter of the rate (RBJ cookbook); there cos(w) is 0, so its
   coefficients depend on the boost alone: a table per half dB, in units of 1/16384.
5. **Trim.** Drops the samples below -50 dB (26,527 in 24 bits) at both ends.
6. **Gain.** A table per half dB in units of 1/65536; the result is 16 bits, clipped, and the
   clipped samples are counted (the Lab shows them, the build prints them).
7. **BRR.** For each block of 16 samples every filter and shift is tried, each nibble chosen
   against the real decoder (so the 15-bit wrap is never hit by accident), and the pair with the
   smallest squared error kept; the first block uses filter 0, the last carries the end flag.

The length an effect plays, in frames, is what the library counts down to know when its voice is
free: the 32 kHz samples it plays (`blocks * 16 * 4096 / pitch`, rounded up) in NTSC frames
(532.46 samples each), rounded up, plus one frame for the DSP's delay after key on.

## BRR Lab, the editor of the file

[`tools/brr_lab.html`](../tools/brr_lab.html) opens the folder `assets/sfx` and saves `sfx.json`
back into it through the browser's File System Access API (Chrome and Edge); other browsers load
the folder's files and offer the changed `sfx.json` as a download. With a folder open, WAV files
loaded or dropped from elsewhere are copied into it, so that the build finds them (a different
file of the same name is replaced only after asking). A folder without `sfx.json` starts with one
effect per WAV file (the id from the file's name, in the order of the names), and WAV files that
no effect names are listed below the effects, to add one by one or all at once.

The effects are cards: the number, the id, the priority, Up, Down and Remove, the active sample's
file, the sizes of the WAV and the BRR, the BRR's size in percent of the WAV's, and the length in
ms. They sort by number, id, file name, WAV size or BRR size, either way; a sorted view changes no
number until "Use this order" makes it the file's order. One row of buttons sets the rate of all
samples at once (8 to 32 kHz), the start after a first import. The chosen effect's samples are on
the other side: per sample the file, which one is active, rate, treble, gain, trim and volume. Each sample plays as the
original or as the SNES plays it (the S-DSP's BRR decoding, the Gaussian interpolation with the
chip's table, the fixed envelope and the voice's volume); the variants of an effect play one
after the other. The total of the active samples against the audio RAM is always in view, with
the errors the build would stop on, and a row of buttons plays the effects with the voices and
priorities of the file, so one hears which cuts which off.

The Lab's tests run it in a browser with Playwright ([tools/lab_test](../tools/lab_test/README.md)),
and the last one compares what it plays and saves with what `superdevkit sfx` builds.

## What a game writes

```c
#include "sfx.h"                       /* written by superdevkit sfx into build/gen */

void game_init(uint16_t seed)
{
    ...
    sdevkit_sfx_init(&sfx_bank);       /* once, last: see "On the SNES" */
}

    sdevkit_sfx_play(SFX_JUMP);        /* where it happens, in game_tick */
```

A project names its file in `superdevkit.toml` (`[sound] sfx = "assets/sfx/sfx.json"`), and
`superdevkit build` converts it into `build/gen/sfx.c` and `sfx.h` before it compiles (Schach and
Steinschlag do). An effect asked for in a tick starts after the tick, when the main
loop has run `game_vblank`: a free voice takes it; if all of the file's voices are busy, it
replaces the voice of the lowest priority not above its own, the older of equal ones; if every
playing effect has a higher priority, it is dropped. A voice is free when the effect's length in
frames has counted down. All effects of a tick start with one key on. More than 8 requests in a
tick are dropped. This logic is C shared by both platforms, `lib/sound/sfx_voices.h`, so the
choice is the same on the SNES and on Rachael, frame by frame.

## On the SNES

`sdevkit_sfx_init` waits for the SPC700's boot code and sends it, by its transfer protocol
(fullsnes, "SNES APU Main CPU Communication Port"), three blocks: the driver at `$0200`, the
sample directory at `$0300` (4 bytes a sample: start and loop address) and the samples from
`$0400` on, then jumps to the driver. The driver is 21 bytes of SPC700 code, kept as a commented
array in `sfx_voices.h`: it waits until the CPU changes port 0, writes port 2's value into the
DSP register port 1 names, and answers with port 0's value. Each DSP write is one exchange.

At start the driver's job is set up through the same exchanges: FLG (no reset, no mute, no echo
writes), the main volumes, echo, noise and pitch modulation off, the directory page, and for
every voice GAIN in direct mode at full. An effect then takes five writes (sample, pitch low and
high, volume left and right) and its share of the tick's key on.

The upload is slow: about 350 bytes a frame, as Mesen measures it (Schach's 13.8 KB take 40
frames after the splash screen). The CPU's loop needs about 100 cycles a byte as dampf compiles
it, the boot code about 25 of the SPC700's, and they wait for each other on every byte. A loader
of our own that the boot code uploads first, and that takes three bytes per exchange, would be
about three times faster; it is not built.

What the player sees during the upload is the game's choice, since only the game knows which
samples it loads and when. The PPU keeps showing its picture while the CPU sends the samples, so
a game that draws its title first and switches the screen on before the upload shows the title
instead of a black screen:

```c
void game_init(uint16_t seed)
{
    draw_title();                      /* everything for the PPU first */
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);   /* the screen on */
    sdevkit_sfx_init(&sfx_bank);       /* the title stays on screen meanwhile */
}
```

The CPU does nothing else until the upload ends, so the picture stands still and the pads are not
read. On a copy of Steinschlag in Mesen, this order took the 48 frames of black screen to none,
with no byte lost and the same checksums and sound over 1,700 ticks.

## On Rachael

`lib/rachael/sfx.c` puts the same bytes into Rachael's audio RAM, and the DSP writes go to her
DSP, `lib/rachael/dsp.c`. Per voice it decodes BRR from the audio RAM as the S-DSP does, steps
by the pitch, interpolates with the chip's Gaussian table, applies GAIN's direct envelope (key
off: the release, 8 steps a sample) and the volumes, then the main volume and the mute of FLG.
Integers only. A voice of the Lab is a voice of hers at the main volume `$7F`, sample for sample
(`RachaelSoundTest` runs both).

Not modelled: ADSR, the other modes of GAIN, echo, noise, pitch modulation, looped samples, and
the five samples of delay after key on. A write that switches one of them on is counted
(`sdevkit__sound.unheard`, the first with its value), as a register write she does not draw is,
and the desktop's check fails on it.

The desktop's frontend makes a frame's samples after every tick: in the window 32000 / 60 of them
into raylib's audio stream (the window runs at 60 frames a second), in a check 32000 / 60.0988,
the NTSC frame's, and with `--wav file` it writes them as a WAV file.

## The checks

The sound is compared as the picture is. dampf's emulator (`dampf.emu.Apu`: the boot code's
protocol as behaviour, since Nintendo's 64 bytes are not part of the kit, and an SPC700 that runs
the opcodes of the kit's driver and stops on any other), the desktop's check and Mesen's
(`dev/example_check.lua`) each write the sound of a run as `sound.txt`: every write the DSP gets,
`init` or the tick after which it came, the register and the value; after `game_init`'s writes
the line `aram H`, the FNV-1a of the bytes uploaded from `$0200` on, each with its address.
`ExamplesTest` compares the emulator's with the desktop's for every example, and
`dev/compare_example.sh` Mesen's with the desktop's. So dampf's compiled library, the upload
protocol and the driver's bytes are checked against the real SPC700 (Mesen) and against Rachael.
What the DSP makes of the writes is not compared with Mesen yet.

## Music

A project lists its tracks in `superdevkit.toml`:

```toml
[sound]
music = ["assets/music/title.ogg", { file = "assets/music/boss-2.xm", name = "boss" }]
```

The build writes `build/gen/music.h` with a number per track, its place in the list
(`MUSIC_TITLE 0`, `MUSIC_BOSS 1`, `MUSIC_COUNT 2`; the name is the file's without its extension,
unless `name` gives one), and the game calls `sdevkit_music_play(MUSIC_TITLE)` and
`sdevkit_music_stop()` (ADR-6F3CK03Z: numbers, never file names). On the desktop the track plays
from its file, over and over, until another one or the stop; the same track again goes on
playing, and the music pauses with the run. A desktop build copies the files into
`build/<target>/music/` and compiles their list (`music_files.c`) with the frontend, so the game's
code holds no file name; the web build packs that folder into the page's `index.data`, and the
track plays there the same way. Any format raylib reads: OGG, MP3, WAV, QOA, and the trackers' XM and
MOD. CatchMe plays a chase loop (`dev/art/music.py` made it) from a level's start to the game's
end.

On the SNES the calls do nothing yet (and dampf removes them, `DebugCallsTest`): music there
needs a driver of its own on the SPC700, which plays a sequence (notes of samples, as a tracker
does) beside the effects, not a file. The checks play no music on the desktop either, so the
sound they compare is the effects', which both have.

## Not built

Music on the SNES; ADSR envelopes; echo; a volume or a pan per call of `sdevkit_sfx_play` (the file's
volume is the one); a jingle with the splash screen; comparing Rachael's DSP output with Mesen's
recording sample for sample; a faster upload.
