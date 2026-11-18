# SNES platform layer

Blubber Blasen's console API (`snapshots/blubberblasen/src/snes/snes.h`) on the real SNES, written
in C and compiled by dampf together with the game. With it, `dev/build_game_rom.sh` builds a
ROM you can play (`build/game/blubberblasen.sfc`, for Mesen or real hardware).

| File | What |
| --- | --- |
| `hw.h` | The registers, as pointers to `volatile` memory |
| `snes.c` | The PVSnesLib-shaped functions and the `snesXxx()` extensions of `snes.h`, and what `main.c` needs from the hardware |
| `main.c` | Start-up and the main loop: joypads, `game_tick`, then in the vertical blank the OAM copy and `game_vblank` |

There is no interrupt handler: the main loop waits for the vertical blank itself, because C code
called from an NMI in the middle of a tick would overwrite the frames of the code it interrupted
(dampf keeps them at fixed places). Sound and save RAM are not built.

Checks: `dev/check_game_rom.sh` (a minute of play in Mesen: no byte may reach VRAM, CGRAM or OAM
outside a vertical blank; frames per tick; screenshots), and `ReplayTest` with `-Preplays` and a
Mesen (a recorded game through this layer must give the PC's checksums). More in
[docs/roadmap.md](../../docs/roadmap.md), *The playable ROM*.

This layer is the dev kit's library on the SNES
([ADR record 29](../../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)). In M5 the
console API it implements moves from the game's `snes.h` to `lib/include/snes.h`.
