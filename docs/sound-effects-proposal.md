# Proposal: sound effects for superdevkit

Status: proposal, 2026-11-19. Nothing is built yet beyond `tools/brr_lab.html`. Music is left out
on purpose; this is about sound effects only.

## What a game sees

A game calls `sdevkit_sfx_play(SFX_JUMP)` where something happens, as ADR record 32 has it. The
numbers come from a file of the game's assets, never from file names:

- `assets/sfx/sfx.json` lists the effects, next to their WAV files, with the settings of the
  conversion and of the playing.
- The build turns it into the samples as C (like `png2snes.py` turns PNG into tiles) and a header
  with one name per effect.

```json
{
  "voices": 2,
  "sounds": [
    { "id": "jump",  "file": "jump.wav",  "rate": 16000, "treble": 6, "gain": 0,
      "trim": true, "volume": 100, "priority": 1 },
    { "id": "death", "file": "death.wav", "rate": 11000, "treble": 3, "gain": -2,
      "trim": true, "volume": 127, "priority": 3 }
  ]
}
```

```c
/* generated from assets/sfx/sfx.json */
#define SFX_JUMP   0
#define SFX_DEATH  1
#define SFX_COUNT  2
```

An effect's number is its place in the list, its name the `id` in capitals with `SFX_` in front.
The names belong to the game, so they do not start with `SDEVKIT_`.

`gain` and `volume` are different things: `gain` scales the sound before it is encoded, so that it
uses the range of BRR well (a quiet effect stored quietly loses bits); `volume` is the DSP's
volume register when it plays. `priority` decides which effect gives way when the voices are
busy. `voices` is how many of the DSP's 8 voices the effects use.

## BRR Lab is the editor of the file

`tools/brr_lab.html` grows from one sound into the editor of `sfx.json`: a list of the effects on
one side, the settings of the chosen one on the other (`id`, rate, treble, gain, trim, volume,
priority), the total of all samples against the audio RAM always in view, and playing with the
voices and priorities of the file, so that two effects can be triggered one after the other and
one hears which cuts which off.

It opens the folder `assets/sfx` and saves the file back into it through the browser's File
System Access API (Chrome and Edge); in Firefox, which lacks it, it loads the file and offers the
changed one as a download.

## What the Lab plays is what the build makes

The Lab is only useful if it plays exactly what goes into the ROM, so the conversion exists
twice, the Lab's in JavaScript and the build's in Java (the kit asks for nothing but Java), and
both must give the same bytes:

- The Lab reads the WAV itself (PCM of 16 and 24 bits, and float). The browser's
  `decodeAudioData` resamples to the rate of the audio device, which differs between machines.
- The resampling uses a fixed table of filter coefficients as integers, made once and checked in,
  instead of `sin` and `cos`: the maths libraries of JavaScript, Java and C do not promise the
  same last bits.
- A test converts a few WAV files with their settings and compares with `.brr` files checked in:
  the Java converter always, the Lab's code under Node when Node is there (skipped otherwise, as
  the tests that need Mesen are).

The BRR encoder of the Lab already tries every filter and shift per block and picks each nibble
against the real decoder; the Java one does the same.

## Rachael plays BRR, not WAV

Record 32 has the desktop play the effects from their files. That would make the desktop sound
clear where the console sounds dull, and the difference would only show on the console. Instead
both targets get the same BRR bytes from the build, and Rachael has a small DSP: BRR decoding, the
pitch, the Gaussian interpolation with the chip's table, the voices' volume, key on and key off.
Envelopes beyond a fixed one, echo, noise and pitch modulation come later; until then Rachael
reports a write to their registers, as she reports a lost byte. The DSP's output is integers
only, so it can later be compared with Mesen's sample for sample, as the pictures are pixel for
pixel. This changes record 32, so it becomes a new record.

## The SNES side: a forwarding driver, the logic in C

The SPC700 runs a driver of a few dozen bytes that does one thing: it waits until the CPU puts a
DSP register and a value on the ports `$2140`-`$2143`, writes the value into that register and
answers. It is small enough to be kept as a commented array of bytes in C; no SPC700 assembler is
needed. At start, while the screen is still off, `lib/snes` loads the driver, the sample
directory (4 bytes per sample) and all samples into the audio RAM through the SPC700's boot
protocol.

Everything else is C in `lib/snes`, the same code on Rachael:

- `sdevkit_sfx_play(id)` puts the effect in a queue; the main loop sends the queue in the vertical
  blank (record 32).
- A free voice takes the effect. If all are busy, the effect replaces the voice with the lowest
  priority below its own, the older one of equal priority; if its priority is lower than every
  playing one, it is dropped.
- Whether a voice is free is known without asking the SPC700: the build writes each effect's
  length in frames into the table, and `lib/snes` counts down each frame. The decision is then the
  same on the SNES and on Rachael, frame by frame, and the driver needs no way back.
- Starting an effect is about eight writes: the source number, the pitch, the two volumes, the
  envelope, key on.

## The first users: the examples

The examples get sound first, Blubber Blasen after them:

1. **Schach**: five effects. "Your move" when the console has made its move (the player need not
   watch the screen while it thinks), two for a move (a piece put down, a piece taken), "check"
   and "checkmate". The shortest complete path, file, converter, header, `sdevkit_sfx_play`,
   driver, Rachael, in a game that is played; and a first small test of the priorities, since a
   move that gives check or mate also puts a piece down or takes one in the same frame, and
   "check" and "checkmate" must win.
2. **Steinschlag**: digging, taking a gem, a boulder landing, a beetle, the miner crushed, the exit
   opening. Much happens at once, so with two voices it shows whether the priorities work: digging
   must not cut off the exit opening.
3. **Blubber Blasen**, through [game-findings.md](game-findings.md), once the kit has it. Its 19
   effects and its 32-bit mask `game_sfx()` become an `sfx.json` and calls of
   `sdevkit_sfx_play`.

The examples' sounds are made like their graphics: a script in `dev/art/` synthesises the WAV files
(square and noise, a pitch sweep, an envelope, in the manner of sfxr), so they are the kit's own,
need no licence and can be made again. Their settings are then chosen in the Lab.

## Steps

1. The format of `sfx.json`; the Lab as its editor, reading WAV itself, with the integer
   resampling.
2. The converter in Java (WAV and `sfx.json` to BRR as C and the header) and the test that the Lab
   and the converter give the same bytes.
3. Rachael's DSP, and the desktop frontend's audio output (raylib's audio stream, 32 kHz).
4. The forwarding driver, the upload at start and the voices in `lib/snes`; the check in Mesen.
5. Schach with its five effects; then Steinschlag with its effects and two voices.
6. The new record (it changes record 32), the rows in `lib/README.md`, the hardware guide's sound
   section, and the finding for Blubber Blasen.

## Open questions

- Is the length in frames exact enough for freeing a voice, given the Gaussian interpolation's
  three samples of delay after key on? A frame of margin is probably enough.
- How the check compares sound: Rachael's DSP output against Mesen's recording, sample for sample,
  or first only "the same effects started in the same frames".
- Whether `sdevkit_sfx_play` takes a volume or a pan as well, or the file's values are enough for
  now.
