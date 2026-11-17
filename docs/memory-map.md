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

## Pointers

The naive code generator makes every pointer 4 bytes: the 16-bit address, the bank, and a zero
byte. A pointer to RAM has bank `$7E`, a pointer to ROM the bank of the data. Pointers are read
through the direct page (`lda [dampf_ptr],y`). No object crosses a bank boundary, so pointer
arithmetic only changes the low word. Pointers that provably always point into RAM can later
become 16 bits (docs/subset.md, decision 3).

## I/O registers

The PPU and CPU registers (`$2100-$21FF`, `$4200-$43FF`) are in bank 0, which DBR does not point
to. Code writes them with 24-bit long addresses (`sta f:$002100`). Only the runtime and the
hardware intrinsics touch them.

## ROM

LoROM, up to 4 MB: banks `$80` to `$FF`, each with 32 KB at `$8000-$FFFF`. dampf uses the banks
from `$80` up, not their mirrors from `$00`, because only accesses through `$80` and up can run at
FastROM speed. Switching FastROM on is optimization J; the reset code already jumps from bank
`$00`, where the CPU starts, to bank `$80`.

- **Bank `$80`** holds the parts with fixed places: the start-up code, the runtime's arithmetic
  routines, the initial values of variables (copied to RAM at start-up), the header at `$FFC0` and
  the vectors at `$FFE0`. The rest of the bank is filled like the others.
- **Every function and every constant object** gets its own segment, and no segment crosses a bank
  boundary. ld65 cannot split a segment over banks, so dampf links twice: once to measure every
  segment, then with a configuration that packs them into banks (largest first, into the first
  bank with room). The configuration it used is written next to the ROM (`<name>.cfg`).
- **Calls** are `JSL`/`RTL`, because caller and callee may be in different banks; jumps inside a
  function stay in its bank.
- **ROM data** is read with 24-bit long addresses, because DBR points to RAM.
- The image is padded to a power of two, and the header gets its size and checksum.

Limits: a single function or constant object can be at most 32 KB (the game's own asset rule,
ADR-0006, already keeps graphics within that), and the start-up code, runtime and initial values
of variables together must fit into bank `$80`.

## Alternatives considered

- **DBR = 0 and only 8 KB of RAM with 16-bit addresses**, everything else through long addresses:
  the 65816 has no long addressing for `inc`, `dec`, shifts, `stz`, `ldx`, `ldy`, `stx`, `sty` or
  `bit`, so most variables would need extra instructions.
- **Switching DBR between RAM and ROM per access**: cheaper ROM table reads, but every switch costs
  instructions and makes the code harder to follow. If the measurements show that ROM tables are
  hot, optimization J can do it locally.
