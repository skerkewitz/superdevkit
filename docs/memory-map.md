# Memory map

How dampf lays out a program in the SNES address space, and why. The text describes the code
generated from MIR, the default. The naive code generator differs in three places: it keeps each
function's frame as static variables from `$2000` up instead of in the compiled stack, it copies
every pointer to `dampf_ptr` before reading through it, and it reaches I/O registers only through
`dampf_ptr`. D pointing at the current object (optimization D) or DBR switched locally (J) would
change the layout; neither is built.

The compiler takes these addresses and banks from one place, `dampf.target.SnesTarget`, and the
interrupt contexts' pages from `dampf.target.ContextAreas` (ADR-6FPTPCDM); the runtime has its
own copies, which a test compares with them.

## RAM: everything in bank $7E, DBR = $7E

The data bank register (DBR) is set to `$7E` at start-up and never changes. All variables live in
bank `$7E`, so 16-bit absolute addresses (`lda abs`, `lda abs,x`) reach all 64 KB of it. Blubber
Blasen needs about 25 KB of RAM, which does not fit into the 8 KB that bank 0 mirrors.

The first 8 KB of bank `$7E` are the same memory as `$0000-$1FFF` in bank 0. The direct page
(`D = 0`) and the stack live there, because both are always addressed through bank 0.

| Address (bank $7E) | Use |
| --- | --- |
| `$0000-$001F` | direct page: the runtime's scratch registers (`dampf_regs`, `dampf_ptr`) |
| `$0020-$1DFF` | the frames (`dampf_frame`, a compiled stack; the part below `$0100` is in the direct page). The naive generator uses only the start, for the frame of a function written in assembly ([hints.md](hints.md), `DAMPF_ASM`). |
| below `$1E00` | only with interrupt handlers in C: each interrupt context's direct page and frames, whole pages ([below](#interrupt-contexts)) |
| `$1E00-$1FFF` | stack |
| `$2000-$FFFE` | variables: globals, static locals, and the naive generator's frames |

The frames must fit into `$0020-$1DFF`, or the build fails (less with interrupt handlers, see
[Interrupt contexts](#interrupt-contexts)). A function's frame starts after the
frames of the functions it calls, so the leaves of the call graph lie in the direct page
([optimizations.md](optimizations.md), optimization A). The linker does not know this area: the
generated code sets `dampf_frame = $0020` itself.

Bank `$7F` (another 64 KB) is not used yet.

## Interrupt contexts

A program with interrupt handlers in C (`DAMPF_INTERRUPT`, [subset.md](subset.md), ADR-6FM9TXV4)
gives each handler's context a direct page of its own, made of whole pages right below the
stack: the NMI's highest, then the IRQ's. The first 32 bytes of the page are the context's copy of
`dampf_regs` and `dampf_ptr`, which the runtime and the generated code reach in the direct page
wherever it is. The context's frames follow from the page's byte `$20`, laid out as the main
code's are from `$0020`, so its leaves lie in the direct page. The generated code writes a frame
place there as `z:dampf_frame+n`, relative to D, and any other one, and the address of a local,
with the context's own symbol (`dampf_frame_irq = $1C20`). A context whose frames take at most 224
bytes takes one page. A function that the main code and a handler both call has a copy in each
context, with its frames there (ADR-6FQ4NSHE); its `static` locals stay one variable. The code
generator exports each page (`dampf_irq_dp`), and the runtime's entry stub sets D to it.

The main code's frames must end below the lowest page: with two small handlers the NMI's page is
`$1D00`, the IRQ's `$1C00`, and the main code has `$0020-$1BFF` (7,136 bytes instead of 7,648);
with one, `$0020-$1CFF`. The naive code generator keeps the frames of C functions static from
`$2000`, so its contexts take one page each. A program without handlers keeps the whole area.

Interrupt code reads the game's data only through the copies the tick publishes
(`sdevkit/publish.h`, ADR-6FPV1EQV and ADR-6FRDS030). They are an ordinary
global among the variables from `$2000`, an array of two structs as large as the game's, beside
the struct itself and three bytes: `sdevkit_publish_side` (16 bits), which says which copy
interrupt code reads, and `sdevkit_publish_ready`; the kit reserves nothing for a game that does not publish.
A game that publishes has the kit's NMI (ADR-6FS0T4WB), so its main code's frames end below the
NMI's page, `$1D00`, and below the IRQ's too if it has one. `lib/snes/irq.c` keeps two bytes for
what the kit switched on in NMITIMEN, the timer's bits and the NMI's.

The linker sees RAM addresses without the bank (`$2000`, not `$7E2000`), so 16-bit address fixups
fit. Code that needs a full 24-bit RAM address (a pointer) adds the bank itself.

## Enum objects

An object of an enum type takes the smallest width that holds all of its enumerators: 1 byte for
an enum whose enumerators lie in 0..255 (or -128..127 when one is negative), 2 bytes up to
65535 (or -32768..32767), 4 bytes beyond. The PC takes 4 bytes for every enum. The values are the
same because an enum object takes only its enumerators and values of its enum
([ADR-6FKZFDJ3](adr/6FKZFDJ3-enum-objects-take-only-values-of-their-enum-and.md)); struct fields
and array elements of an enum type are laid out with that width.

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
platform layer (`lib/snes`) does this. A pointer made from a constant becomes a long address
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

Save RAM, with `dampf build --sram BYTES`: the cartridge's battery-backed RAM at `$70:0000` to
`$70:7FFF` (8 master cycles a byte), up to 32 KB. The header's cartridge type becomes `$02` (ROM,
RAM and battery) and its RAM size the smallest of 2, 4, 8, 16 and 32 KB that holds the bytes;
`sdevkit_sram()` returns the address ([lib/README.md](../lib/README.md)).

- **Bank `$80`** holds the parts with fixed places: the start-up code, the runtime's arithmetic
  routines, the initial values of variables (copied to RAM at start-up), the header at `$FFC0` and
  the vectors at `$FFE0`. The rest of the bank is filled like the others.
- **Every function and every constant object** gets its own segment, and no segment crosses a bank
  boundary. Segments are packed into banks largest first, into the first bank with room. dampf's
  own linker knows every segment's size after assembling; with `--assembler ca65`, ld65 cannot
  split a segment over banks, so dampf links twice: once to measure every segment, then with a
  configuration that packs them (written next to the ROM, `<name>.cfg`).
- **Calls** are `JSL`/`RTL`, because caller and callee may be in different banks; jumps inside a
  function stay in its bank.
- **ROM data** is read with 24-bit long addresses, because DBR points to RAM.
- The image is padded to a power of two, and the header gets its size and checksum.

Limits: a single function or constant object can be at most 32 KB (the game's asset
rule, Blubber Blasen's record 6, already keeps graphics within that), and the start-up code, runtime and initial values
of variables together must fit into bank `$80`.

## Why

Why RAM is laid out this way, and the alternatives weighed, are in ADR
[ADR-6F0R69B6](adr/6F0R69B6-all-variables-live-in-bank-7e-and-dbr-stays-7e.md); pointers in
[ADR-6F0R6A8V](adr/6F0R6A8V-pointers-are-4-bytes-and-no-object-crosses-a-bank.md), the ROM in
[ADR-6F0R6C4B](adr/6F0R6C4B-lorom-with-fastrom-code-from-bank-80-one-segment.md).
