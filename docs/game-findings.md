# Findings for Blubber Blasen

Written by the dev kit (superdevkit) for the AI working on the game. Each finding says what the
kit now offers or found, and what the game would change to use it.

## 1. Sound effects: the kit plays them on the SNES and the desktop (2026-11-19)

The kit has sound effects on both platforms (its `docs/sound.md`, ADR record 44). The game's 19
effects and its 32-bit mask `game_sfx()` can move to it:

1. Make `assets/sfx/sfx.json` with one entry per effect, in the order of the mask's bits if the
   numbers should stay: `id`, `priority`, and one sample per effect (`file`, `active: true`,
   `rate`, `treble`, `gain`, `trim`, `volume`). Effects without a WAV yet get `"samples": []`:
   their names exist and they play nothing. `voices` is how many of the DSP's 8 voices the
   effects may use; 2 or 3 leaves the others for music later.
2. Open the folder in the kit's `tools/brr_lab.html` and choose rate, treble and gain per sample
   by ear; the Lab shows the bytes against the audio RAM (64,448 bytes for samples) and the
   clipped samples, and plays what the SNES will play. Lower `gain` where it reports clipping.
3. In the build, before dampf: `superdevkit sfx assets/sfx/sfx.json sfx build/gen`. It writes
   `sfx.h` (`SFX_<ID>`, `SFX_COUNT`, `sfx_bank`) and `sfx.c`; add `build/gen/sfx.c` to the
   sources. It stops with the line in the file on errors.
4. In `game_init`, with the screen off: `sdevkit_sfx_init(&sfx_bank);`. It uploads the samples,
   about 350 bytes a frame (12 KB take about 35 frames of black screen after the splash).
5. Where the game sets a bit of the mask, call `sdevkit_sfx_play(SFX_<ID>)`, then remove
   `game_sfx()` and the PC frontend's playing of the WAV files. The desktop build plays the same
   BRR through Rachael, so it sounds as dull as the console does, and the checks compare every
   DSP write between the SNES and the desktop.

Priorities decide which effect gives way when the voices are busy: the higher wins, and an
effect of the same priority replaces the older one. The effects of a tick start after
`game_vblank`.
