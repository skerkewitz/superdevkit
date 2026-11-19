# lib/snes: the library on the SNES

The dev kit's console API (`lib/include/sdevkit/snes.h`, see [lib/README.md](../README.md)) on the
real SNES, written in C and compiled by dampf together with the game: every example's `build.sh`
links it into a ROM you can play (for Mesen or real hardware).

| File | What |
| --- | --- |
| `hw.h` | The registers, as pointers to `volatile` memory |
| `snes.c` | The functions of `sdevkit/snes.h`, and what `main.c` needs from the hardware (`sdevkit__oam_upload`, `sdevkit__wait_vblank`, ...) |
| `main.c` | Start-up, the splash screen (`../splash/splash.h`, ADR record 37), and the main loop of `sdevkit/loop.h`: joypads, `game_tick`, then in the vertical blank the OAM copy and `game_vblank`, then the sound effects of the tick |
| `sfx.c` | The sound effects ([docs/sound.md](../../docs/sound.md)): the logic of `../sound/sfx_voices.h` through the ports `$2140`-`$2143`, the uploads by the SPC700's boot code, then one DSP write per exchange with the kit's driver |

There is no interrupt handler: the main loop waits for the vertical blank itself, because C code
called from an NMI in the middle of a tick would overwrite the frames of the code it interrupted
(dampf keeps them at fixed places). Save RAM is the cartridge's battery RAM at `$70:0000`
(`sdevkit_sram`): `dampf --sram BYTES` writes it into the header (ROM, RAM and battery; the size
rounded up to 2 to 32 KB) and defines `SDEVKIT_SRAM_SIZE`.

Checks: `ExamplesTest` plays every example's `check.txt` through this layer in dampf's emulator,
with a checksum per tick against the desktop, and `dev/check_example.sh` in Mesen (no byte may
reach VRAM, CGRAM or OAM outside a vertical blank; screenshots). More in
[docs/testing.md](../../docs/testing.md).

This is the dev kit's library on the SNES
([ADR record 29](../../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)). The API it
implements was taken over from Blubber Blasen's `src/snes/snes.h` and renamed
([ADR record 34](../../docs/adr/0034-every-name-of-the-console-api-starts-with.md)); through
the rename table (`tools/sdevkit_rename.py`) its declarations were those of the game's copy when
they were last compared, before the game's code left this repository (ADR record 40).
