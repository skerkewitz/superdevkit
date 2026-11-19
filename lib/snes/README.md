# lib/snes: the library on the SNES

The dev kit's console API (`lib/include/sdevkit/snes.h`, see [lib/README.md](../README.md)) on the
real SNES, written in C and compiled by dampf together with the game. With it, `dev/build_game_rom.sh` builds a
ROM you can play (`build/game/blubberblasen.sfc`, for Mesen or real hardware).

| File | What |
| --- | --- |
| `hw.h` | The registers, as pointers to `volatile` memory |
| `snes.c` | The functions of `sdevkit/snes.h`, and what `main.c` needs from the hardware (`sdevkit__oam_upload`, `sdevkit__wait_vblank`, ...) |
| `main.c` | Start-up, the splash screen (`../splash/splash.h`, ADR record 37), and the main loop of `sdevkit/loop.h`: joypads, `game_tick`, then in the vertical blank the OAM copy and `game_vblank` |

There is no interrupt handler: the main loop waits for the vertical blank itself, because C code
called from an NMI in the middle of a tick would overwrite the frames of the code it interrupted
(dampf keeps them at fixed places). Sound and save RAM are not built.

Checks: `dev/check_game_rom.sh` (a minute of play in Mesen: no byte may reach VRAM, CGRAM or OAM
outside a vertical blank; frames per tick; screenshots), and `ReplayTest` with `-Preplays` and a
Mesen (a recorded game through this layer must give the PC's checksums). More in
[docs/roadmap.md](../../docs/roadmap.md), *The playable ROM*.

This is the dev kit's library on the SNES
([ADR record 29](../../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)). The API it
implements was taken over from Blubber Blasen's `src/snes/snes.h` and renamed
([ADR record 34](../../docs/adr/0034-every-name-of-the-console-api-starts-with.md)); through
the rename table its declarations are still those of the game's copy
(`dev/update_game_snapshot.sh` warns when they differ).
