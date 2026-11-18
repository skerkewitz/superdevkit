# 32. Desktop and web are for prototyping, with sound, save RAM and a debug view the game registers into

**Context.** Besides the console API, Blubber Blasen's PC frontend plays sound (it reads a bit
mask of effects, `game_sfx()`, and a music track, `game_music()`, from the game), saves 128 bytes
of save RAM (`game_sram()`), and shows a debug view written in C++ with Dear ImGui that calls about
20 `game_debug_*()` functions. For the game to drop its own frontend (record 29), the kit has to
offer these, and the SNES side has neither sound nor save RAM yet
([brainstorm](../brainstorm/superdevkit-targets_2026-11-18.md)).

**Decision.**

- The targets are `snes` (the product), `win64`, `linux` and `web` (for rapid prototyping and for
  sharing a game for feedback: a link to the web build needs no install); macOS later, since it
  cannot be tested now.
- Sound: the game calls `sfxPlay(id)`, `musicPlay(track)` and `musicStop()` where things happen.
  The numbers come from the assets listed in the project file, never file names. Built on the
  desktop and the web, from files; a stub on the SNES until it has sound, which then is a queue the
  main loop sends to the sound processor in the vertical blank, the simplest way to drive an SPC700
  sound driver.
- Save RAM is a block of memory, as on a cartridge: `snesSram()` with a size from the project file.
  The game reads and writes it; the kit saves it when it changed and on exit (a file on the
  desktop, IndexedDB in the browser, later a cartridge's battery RAM).
- The debug view exists on the desktop and the web only. It has the kit's standard views (pause,
  step, slow motion, collision boxes, the SNES's VRAM, palettes and sprites); a game registers what
  it wants to change (`debugToggle`, `debugRange`, `debugColor`, `debugAction`) and draws boxes per
  frame (`debugBox`). On the SNES these functions are empty, so the game code needs no `#ifdef`.

**Alternatives.**

- Sound and save RAM pulled from the game after each tick, as Blubber Blasen does now: no change
  for the game, but every new game would collect its effects as a bit mask (at most 32) and define
  functions even without sound.
- Calls named after PVSnesLib's: being PVSnesLib's form is no reason by itself; the numbered calls
  are as simple for a game and the simplest to drive a sound driver with.
- A save function (`sramWrite`) instead of memory: one more call to forget, and not how the SNES
  works.
- The game's own debug view as an ImGui callback: Blubber Blasen's view would move almost
  unchanged, but every game would need C++ and ImGui, and a fully customisable view is more than
  is needed.

**Consequences.** Blubber Blasen changes about 20 places from setting sound bits to calls, its save
RAM to the kit's block, and its debug view to registrations; its CPU-partner view becomes boxes and
switches and loses its text panel; its recordings and autoplay stay its own, reached through
`debugAction`. The calls of the debug registrations stay in the SNES build as empty functions;
whether dampf removes them entirely is checked by a test when the API is built.
