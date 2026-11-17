# Memory map

How dampf lays out a program in the SNES address space, and why. This describes the naive code
generator; optimizations may refine it (for example optimization A puts frames into the direct
page, J keeps hot code in FastROM).

## RAM: everything in bank $7E, DBR = $7E

The data bank register (DBR) is set to `$7E` at start-up and never changes. All variables live in
bank `$7E`, so 16-bit absolute addresses (`lda abs`, `lda abs,x`) reach all 64 KB of it. Blubber
Blasen needs about 25 KB of RAM, which does not fit into the 8 KB that bank 0 mirrors.

The first 8 KB of bank `$7E` are the same memory as `$0000-$1FFF` in bank 0. The direct page
(`D = 0`) and the stack live there, because both are always addressed through bank 0.

| Address (bank $7E) | Use |
| --- | --- |
| `$0000-$00FF` | direct page: the runtime's scratch registers (`dampf_regs`) |
| `$0100-$1DFF` | free; meant for static frames in the direct page and other hot data later |
| `$1E00-$1FFF` | stack |
| `$2000-$FFFF` | variables: globals, static locals, the static frames of functions |

Bank `$7F` (another 64 KB) is not used yet.

The linker sees RAM addresses without the bank (`$2000`, not `$7E2000`), so 16-bit address fixups
fit. Code that needs a full 24-bit RAM address (a pointer) adds the bank itself.

## I/O registers

The PPU and CPU registers (`$2100-$21FF`, `$4200-$43FF`) are in bank 0, which DBR does not point
to. Code writes them with 24-bit long addresses (`sta f:$002100`). Only the runtime and the
hardware intrinsics touch them.

## ROM

For now a 32 KB LoROM: code and constant data in bank 0, `$8000-$FFFF`. Generated code reads ROM
data with 24-bit long addresses, because DBR points to RAM. Larger ROMs (more banks, code that
calls across banks) come with the rest of milestone M2.

## Alternatives considered

- **DBR = 0 and only 8 KB of RAM with 16-bit addresses**, everything else through long addresses:
  the 65816 has no long addressing for `inc`, `dec`, shifts, `stz`, `ldx`, `ldy`, `stx`, `sty` or
  `bit`, so most variables would need extra instructions.
- **Switching DBR between RAM and ROM per access**: cheaper ROM table reads, but every switch costs
  instructions and makes the code harder to follow. If the measurements show that ROM tables are
  hot, optimization J can do it locally.
