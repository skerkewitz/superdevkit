# The console API {#mainpage}

How a game made with superdevkit reaches the SNES. A game is written from these headers alone:
each explains the hardware at the names that reach it.

| Header | What |
| --- | --- |
| loop.h | The frame rhythm: `game_init`, `game_tick`, `game_vblank`, which every game defines, and the joypads. Start here. |
| snes.h | The core: how the SNES makes a picture, the frame and the vertical blank, every PPU register and its bits, the formats of colours, tiles, maps and sprites, copies into VRAM, CGRAM and OAM, HDMA, sound effects, music, save RAM, the debug calls. Everything goes to the hardware at once. |
| shadow.h | Optional: the registers in memory, written into the hardware by one call. |
| sprites.h | Optional: a sprite list drawn anew every tick. |
| queue.h | Optional: copies into VRAM and CGRAM asked for in the tick, made in the vertical blank. |
| input.h | Optional: the buttons pressed since the last tick. |
| easy.h | Optional: all of them, and one commit for a `game_vblank` of one line. |
| publish.h | Not for games yet: the data the tick publishes for interrupt code, which reads it complete. |

A game that wants every cycle uses the core and writes the hardware in `game_vblank`; a game that
wants none of the vertical blank's rules uses the layers (easy.h). Both mix: a layer a game does
not call costs it nothing.

The kit's repository has the rest: what each function costs (lib/README.md), what the hardware
allows and which of it the kit has (lib/SNES_HARDWARE.md), how to write fast C for dampf
(lib/C_STYLE.md), and the examples in examples/.
