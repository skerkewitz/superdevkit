# lib/snes: the library on the SNES

The dev kit's console API (`lib/include/sdevkit/snes.h`, see [lib/README.md](../README.md)) on the
real SNES, written in C and compiled by dampf together with the game: `superdevkit build snes`
links it into a ROM you can play (for Mesen or real hardware).

| File | What |
| --- | --- |
| `hw.h` | The registers, as pointers to `volatile` memory |
| `snes.c` | The core of `sdevkit/snes.h`: each function the register writes it names and nothing else, which dampf inlines (a register write with a constant register is one store); and what `main.c` needs from the hardware (`sdevkit__reset_ppu`, `sdevkit__wait_vblank`, `sdevkit__read_pads`) |
| `layers.c` | The optional layers (`sdevkit/shadow.h`, `sprites.h`, `queue.h`, `input.h`, `easy.h`): their logic from `../layers/layers.h`, and the queue's commit written against the registers, since the vertical blank pays for every cycle of its setup ([lib/README.md](../README.md#the-commits-time) has the commit's time) |
| `main.c` | Start-up, the splash screen (`../splash/splash.h`, ADR-6F60Z5JJ), and the main loop of `sdevkit/loop.h`: the joypads, `game_tick`, the mark where the tick ends, then in the vertical blank `game_vblank`, then the sound effects of the tick. The kit writes nothing in the vertical blank itself |
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
([ADR-6F3CJX8T](../../docs/adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md)). Its API is
the hardware at once, with optional layers above it
([ADR-6FNPPCSX](../../docs/adr/6FNPPCSX-the-console-api-is-the-hardware-at-once-with.md)); it
replaced PVSnesLib's functions, which the kit had taken over from Blubber Blasen's
`src/snes/snes.h` and renamed
([ADR-6F3CK2AR](../../docs/adr/6F3CK2AR-every-name-of-the-console-api-starts-with.md)).
