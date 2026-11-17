# Memory map

How dampf lays out a program in the SNES address space, and why. The text describes the code
generated from MIR, the default. The naive code generator differs in three places: it keeps each
function's frame as static variables from `$2000` up instead of in the compiled stack, it copies
every pointer to `dampf_ptr` before reading through it, and it reaches I/O registers only through
`dampf_ptr`. D pointing at the current object (optimization D) or DBR switched locally (J) would
change the layout; neither is built.

## RAM: everything in bank $7E, DBR = $7E

The data bank register (DBR) is set to `$7E` at start-up and never changes. All variables live in
bank `$7E`, so 16-bit absolute addresses (`lda abs`, `lda abs,x`) reach all 64 KB of it. Blubber
Blasen needs about 25 KB of RAM, which does not fit into the 8 KB that bank 0 mirrors.

The first 8 KB of bank `$7E` are the same memory as `$0000-$1FFF` in bank 0. The direct page
(`D = 0`) and the stack live there, because both are always addressed through bank 0.

| Address (bank $7E) | Use |
| --- | --- |
| `$0000-$001F` | direct page: the runtime's scratch registers (`dampf_regs`, `dampf_ptr`) |
| `$0020-$1DFF` | the frames (`dampf_frame`, a compiled stack; the part below `$0100` is in the direct page). Unused by the naive generator. |
| `$1E00-$1FFF` | stack |
| `$2000-$FFFE` | variables: globals, static locals, and the naive generator's frames |

The frames must fit into `$0020-$1DFF`, or the build fails. A function's frame starts after the
frames of the functions it calls, so the leaves of the call graph lie in the direct page
([optimizations.md](optimizations.md), optimization A). ld65 does not know this area: the
generated code sets `dampf_frame = $0020` itself.

Bank `$7F` (another 64 KB) is not used yet.

The linker sees RAM addresses without the bank (`$2000`, not `$7E2000`), so 16-bit address fixups
fit. Code that needs a full 24-bit RAM address (a pointer) adds the bank itself.

## Pointers

A pointer is 4 bytes in memory: the 16-bit address, the bank, and a zero byte (MIR's `ptr` type).
A pointer to RAM has bank `$7E`, a pointer to ROM the bank of the data. Pointers are read through
the direct page: a pointer whose place is in the direct page is used where it is
(`lda [place],y`), any other is copied to `dampf_ptr` first (`lda [dampf_ptr],y`).

No object crosses a bank boundary, so pointer arithmetic only changes the low word. The last byte
of every ROM bank and of RAM ($7E:FFFF) stays free, so a pointer just past the end of an object
never wraps to the start of its bank and still compares greater than every pointer into the
object. Objects are at most 32 KB, so every offset within an object fits a signed 16-bit number
(MIR's `ptradd` with an i16 offset relies on that).

Pointers that provably always point into RAM could become 16 bits ([subset.md](subset.md),
decision 3); that is not built. Built is a narrower form: a pointer that provably points into one
known object becomes a 16-bit offset into it, within a function and across calls
(`PointerOffsets`, `PointerParams`; [optimizations.md](optimizations.md#pointer-compression)).

## I/O registers

The PPU and CPU registers (`$2100-$21FF`, `$4200-$43FF`) are in bank 0, which DBR does not point
to. The runtime writes them with 24-bit long addresses (`sta f:$002100`). C code reaches them
through pointers to `volatile` memory ([subset.md](subset.md), *Hardware access*); only the
platform layer (`platform/snes`) does this. A pointer made from a constant becomes a long address
in the code (`sta f:$00210B`, 5 cycles for a byte), and a constant plus a 16-bit index that is
never negative and stays in the bank an indexed long address (`REG8(0x210B + i)` is
`sta f:$00210B,x`): no pointer is built (it used to go through `[dampf_ptr],y`, about 30 cycles a
register). Other pointers made from numbers still go through `dampf_ptr`.

## ROM

LoROM, up to 4 MB: banks `$80` to `$FF`, each with 32 KB at `$8000-$FFFF`. dampf uses the banks
from `$80` up, not their mirrors from `$00`, because only accesses through `$80` and up can run at
FastROM speed. The reset code jumps from bank `$00`, where the CPU starts, to bank `$80` and
switches the ROM there to 3.58 MHz (`MEMSEL` at `$420D`); the header says FastROM (map mode
`$30`). A ROM access then takes 6 master cycles instead of 8. `dampf build --slowrom` leaves it at
2.68 MHz, header `$20`, for a SlowROM cartridge (flash cartridges and emulators run FastROM).

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
