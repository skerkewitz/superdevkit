# 44. Sound effects come from sfx.json, and Rachael plays the SNES's BRR

**Context.** Record 32 decided that a game plays an effect by its number and that the desktop
plays the effects from their files; the SNES had no sound. The
[proposal](../archive/sound-effects-proposal.md) of 2026-11-19 worked out the rest: where the numbers come
from, how a WAV becomes a sample, what runs on the SPC700, and how the two platforms stay the
same. Playing the WAV files on the desktop would make it sound clear where the console sounds
dull, and the difference would only show on the console.

**Decision.**

- A game lists its effects in `assets/sfx/sfx.json`: per effect an id and a priority, and samples
  to choose from, at most one active, each with its WAV file, rate, treble boost, gain, trim and
  volume; for the file the number of voices. `superdevkit sfx` (Java, in the CLI) converts it
  into `NAME.h` (`SFX_<ID>`, the number is the place in the list) and `NAME.c` (the BRR of the
  active samples and a bank, `NAME_bank`). An effect without an active sample keeps its name and
  plays nothing. The build stops, with the line, on the errors docs/sound.md lists.
- The conversion is integers only, with tables computed once (`dev/sfx_tables.py`), and exists
  twice: in Java for the build, in JavaScript in `tools/brr_lab.html`, which becomes the editor of
  `sfx.json`. `SfxTest` checks that both give the same bytes.
- The API: `sdevkit_sfx_init(&NAME_bank)` once in `game_init`, `sdevkit_sfx_play(id)` where
  something happens. The init call is new against record 32: the library cannot name a game's
  generated bank, and an explicit call keeps a game without sound free of it.
- Both platforms get the same BRR bytes. On the SNES the SPC700's boot code takes the driver, the
  sample directory and the samples; the driver (21 bytes, kept as a commented array in C) does
  one DSP write per exchange with the CPU. Rachael gets a small DSP (`lib/rachael/dsp.c`): BRR
  decoding, pitch, the Gaussian interpolation with the chip's table, GAIN's direct envelope, key
  on and off, the volumes. She counts the writes to what she does not model (ADSR, other GAIN
  modes, echo, noise, pitch modulation, loops), and the desktop's check fails on them.
- The choice of the voice is C shared by both (`lib/sound/sfx_voices.h`): a free voice, else the
  one of the lowest priority not above the new effect's (the older of equal ones), else the
  effect is dropped. A voice is free when the effect's length in frames, written by the build,
  has counted down, so the driver needs no way back.
- The effects of a tick start after `game_vblank`, all with one key on, not in the vertical blank
  as record 32 said: the DSP writes take CPU time that the game's uploads need there, and the
  sound processor can be written at any time.
- The checks compare sound as they compare pictures: dampf's emulator (with the boot code's
  protocol as behaviour and an SPC700 that runs the driver), the desktop and Mesen each write
  every DSP write per tick and a hash of the uploads, and the lists must be equal.

**Alternatives.**

- The desktop plays the WAV files (record 32): simpler, but it hides what the SNES does to the
  sound.
- A driver with the voice logic on the SPC700: the decision would be made where Rachael cannot
  see it, and the driver would need an assembler for the SPC700; the forwarding driver fits in
  bytes written by hand.
- The converter in Python, like `png2snes.py`: the release asks for nothing but Java (record 41).

**Consequences.** Record 32 is amended: sound on the desktop is Rachael's DSP, the queue is sent
after `game_vblank`, and a game calls `sdevkit_sfx_init`. Schach and Steinschlag have sound.
Music, ADSR envelopes and echo are not built. The upload runs at about 350 bytes a frame (Schach's
13.8 KB take 40 frames in Mesen, after the splash screen): the CPU's loop and the boot code's wait
for each other on every byte. A faster loader is left for later.
