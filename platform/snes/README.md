# SNES platform layer

Blubber Blasen's console API (`examples/blubberblasen/src/snes/snes.h`) on the real SNES, written
in C and compiled by dampf together with the game. With it, `tools/build_game_rom.sh` builds a
ROM you can play (`build/game/blubberblasen.sfc`, for Mesen or real hardware).

| File | What |
| --- | --- |
| `hw.h` | The registers, as pointers to `volatile` memory |
| `snes.c` | The PVSnesLib-shaped functions and the `snesXxx()` extensions of `snes.h`, and what `main.c` needs from the hardware |
| `main.c` | Start-up and the main loop: joypads, `game_tick`, then in the vertical blank the OAM copy and `game_vblank` |

There is no interrupt handler: the main loop waits for the vertical blank itself, because C code
called from an NMI in the middle of a tick would overwrite the frames of the code it interrupted
(dampf keeps them at fixed places). Sound and save RAM are not built.

Checks: `tools/check_game_rom.sh` (a minute of play in Mesen: no byte may reach VRAM, CGRAM or OAM
outside a vertical blank; frames per tick; screenshots), and `ReplayTest` with `-Preplays` and a
Mesen (a recorded game through this layer must give the PC's checksums). More in
[docs/roadmap.md](../../docs/roadmap.md), *After M2: a playable ROM*.

This layer is specific to Blubber Blasen, like the snapshot in `examples/blubberblasen`, and leaves
the repository with it when dampf goes public (milestone M5).
