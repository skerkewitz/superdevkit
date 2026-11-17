<!-- GENERATED from doc-specs/reference/runtime-and-memory.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Runtime and memory

A ROM built by dampf holds more than your compiled C: the [runtime](/glossary#runtime) starts
the machine, a few arithmetic routines do what the 65816 has no instruction for, and a header
and vectors make the image a valid SNES ROM. This page says where all of it goes, and where your
code, constant data, variables and the frames of functions end up, so that you can mix dampf's
code with your own assembly or find a variable in Mesen's memory viewer.

## Start-up and the entry function

The runtime's start-up code runs from the reset vector. In order, it:

1. switches the 65816 to native mode and jumps from bank `$00`, where the CPU starts, to the
   same code in bank `$80`;
2. sets A, X and Y to 16 bits, clears decimal mode, puts the stack pointer at `$1FFF` and sets
   the direct page register `D` to 0;
3. writes 0 to `$4200` (no NMI, no IRQ, no automatic joypad reading) and `$80` to `$2100`
   (forced blank);
4. sets the data bank register ([DBR](/glossary#dbr)) to `$7E`;
5. fills the uninitialized variables (the `BSS` segment) with zeros and copies the initial
   values of the other variables (the `DATA` segment) from ROM to RAM;
6. calls the [entry function](/glossary#entry-function) with `JSL`.

The entry function therefore finds the processor in native mode with interrupts disabled, the
accumulator and index registers 16 bits wide (both the
[`M` and `X` flags](/glossary#m-and-x-flags) clear), `D = 0`, `DBR = $7E`, the stack at
`$1FFF`, the screen in forced blank and every global variable at its initial value. It is
`main` unless `--entry` names another function (see [Command line](/reference/command-line)),
and has the form `void name(void)`.

When the entry function returns (with `RTL`, as every function dampf compiles does), the runtime
writes 1 to `$21FB` and then waits in an endless loop. `$21FB` is an unused port on the SNES's
B bus; the write tells a test script that the program has finished. The loop is a branch to
itself rather than `STP`, which would stop the CPU and leave a debugger nothing to step or
continue.

The NMI and IRQ vectors point to a bare `RTI`: the runtime does no interrupt handling of its
own.

The runtime also contains the arithmetic routines that generated code calls for multiplication
and division (`dampf_mul16`, `dampf_mul32`, `dampf_udiv16`, `dampf_udiv32`, `dampf_sdiv32`).
They are shift-and-add and shift-and-subtract loops, are called with `JSL` and return with
`RTL`, and expect 16-bit registers and `D = 0`. They work in `dampf_regs`, an 18-byte scratch
area in the direct page; an interrupt handler of your own that calls C code must save it.

## RAM

All variables live in bank `$7E`, and the start-up code sets `DBR = $7E` once; it never
changes. With the data bank pointing at RAM, 16-bit absolute addresses (`lda abs`, `lda abs,x`)
reach all 64 KB of the bank. Blubber Blasen needs about 25 KB of RAM, which would not fit into
the 8 KB that bank 0 mirrors.

The first 8 KB of bank `$7E` are the same memory as `$0000`–`$1FFF` in bank 0. The direct page
and the stack live there, because the 65816 always addresses both through bank 0.

| Address (bank `$7E`) | Use |
|---|---|
| `$0000`–`$001F` | the runtime's scratch registers in the direct page (`dampf_regs`, `dampf_ptr`) |
| `$0020`–`$1DFF` | the frames of functions compiled by the [code generator from MIR](/glossary#code-generator-from-mir) (`dampf_frame`) |
| `$1E00`–`$1FFF` | the stack |
| `$2000`–`$FFFE` | variables: globals, static locals, and the frames of the [naive code generator](/glossary#naive-code-generator) |

The byte at `$7E:FFFF` stays free, for the reason given under [Pointers](#pointers). Bank `$7F`,
another 64 KB of RAM, is not used.

The linker sees RAM addresses without the bank: a variable is at `$2000`, not `$7E2000`, so
16-bit address fixups fit. Code that needs the full 24-bit address of a variable, to build a
pointer to it, adds the bank itself.

## The direct page

The direct page is a 256-byte window of bank 0 that the 65816 reaches with one-byte addresses,
which are shorter and faster than full addresses (see [direct page](/glossary#direct-page)).
dampf keeps `D = 0`, so the window is `$0000`–`$00FF`.

The runtime takes the start of it: `dampf_regs` (18 bytes) for the arithmetic routines, and
`dampf_ptr`, room for two 24-bit pointers of 4 bytes each. The 65816 can only follow a 24-bit
pointer that is in the direct page (`lda [dampf_ptr],y`). The code generator from MIR uses a
pointer whose place in its frame is in the direct page where it is; any other pointer it copies
to `dampf_ptr` right before it uses it, as the naive code generator does with every pointer.

From `$0020` on come the frames of functions: the memory a function's parameters, locals and
intermediate values occupy. The code generator from MIR gives every value a fixed place in its
function's frame, 2 bytes for a value of up to 16 bits and 4 bytes for a 32-bit value or a
pointer; values whose lifetimes do not overlap share a place. The frames form one region from
`$0020` to `$1DFF`, and the part below `$0100` is in the direct page, where every access to a
value is cheaper. The build fails when the frames do not fit into this region. How dampf
decides which frames get that part is on [Optimizations](/inside/optimizations).

The naive code generator does not use this region; its frames are static variables above
`$2000`.

## Pointers

A pointer is 4 bytes: the 16-bit address, the [bank](/glossary#bank), and a zero byte. A
pointer to a variable has bank `$7E`, a pointer to constant data the bank of the ROM it is in.

No object crosses a bank boundary, so pointer arithmetic only changes the low 16 bits. Objects
are at most 32 KB, so every offset within an object fits a signed 16-bit number. The last byte
of RAM and of every ROM bank from `$81` on stays free (bank `$80` ends with the vectors), so a
pointer just past the end of an object never wraps to the start of its bank and still compares
greater than every pointer into the object, as C requires.

dampf can replace a pointer that always points into one global or frame object by a 16-bit
offset into that object, see [pointer compression](/glossary#pointer-compression). The code
generator then reaches the object as `symbol,x` with the offset in X, which needs the offset
never to be negative. The 4-byte pointer, its copies and its additions go. dampf does this in
two cases:

- **Within a function:** a pointer variable that takes different values on different paths, such
  as one stepped in a loop, whose values all start at the same global object or frame object and
  only move by additions (`o = oamMemory; ... o += 4`). It becomes the object's address plus a
  16-bit offset.
- **Across calls:** a pointer parameter that every call of the function fills with a pointer
  into the same global object. The parameter becomes a 16-bit offset, so callers pass 2 bytes
  instead of 4, and the function adds the object's address back at its start.

Both rely on C's rule that a pointer stays within its object or just past its end: the offset is
then between 0 and the object's size. A pointer that may point into several objects, or that is
loaded from memory, stays 4 bytes.

Pointers that always point into RAM could be 16 bits wide; this is not built yet, see
[Limits](/reference/limits).

## ROM

The ROM is LoROM, up to 4 MB: banks `$80` to `$FF`, each with 32 KB at `$8000`–`$FFFF`. dampf
uses the banks from `$80` up rather than their mirrors from `$00`, because only accesses
through `$80` and up can run at FastROM speed. The start-up switches it on (`MEMSEL` at `$420D`):
a ROM access then takes 6 master cycles instead of 8, and the header declares FastROM (map mode
`$30`). Flash cartridges and emulators run FastROM; for a SlowROM cartridge, build with
[`--slowrom`](/reference/command-line#slowrom), which keeps 2.68 MHz and map mode `$20`.

**Bank `$80`** holds the parts with fixed places: the start-up code, the runtime's arithmetic
routines, the initial values of variables (copied to RAM at start-up), the header at
`$80:FFC0` and the vectors at `$80:FFE0`. The rest of the bank is filled like the others.

**Every function and every constant object** gets a segment of its own: `C_` and the function's
name for code, `R_` and the object's name for constant data. ld65, the linker, cannot split a
segment over banks, so no function or constant object crosses a bank boundary. To pack them,
dampf links twice: once to measure every segment, then with a configuration that puts them into
banks, largest first, each into the first bank with room. The configuration of the second link
is written next to the ROM as `<name>.cfg`. For `examples/m1/calls.c` built with
`--entry test_main` everything fits into bank `$80`:

```
MEMORY {
    DP:    start = $0000, size = $0100, type = rw;   # direct page (D = 0)
    #      $0020-$1DFF the frames of the code from MIR (dampf_frame); $1E00-$1FFF stack
    RAM:   start = $2000, size = $DFFF, type = rw;   # bank $7E (DBR = $7E), see docs/memory-map.md; $FFFF stays free (one past the end)
    ROM80: start = $808000, size = $8000, type = ro, fill = yes, fillval = $FF, file = %O;
}
SEGMENTS {
    ZEROPAGE:   load = DP, type = zp, optional = yes;
    BSS:        load = RAM, type = bss, define = yes, optional = yes;
    STARTUP:    load = ROM80, type = ro;
    DAMPF_CODE: load = ROM80, type = ro;
    DATA:       load = ROM80, run = RAM, type = rw, define = yes, optional = yes;
    C_test_main: load = ROM80, type = ro;
    C_inc8: load = ROM80, type = ro;
    C_neg8: load = ROM80, type = ro;
    C_add3: load = ROM80, type = ro;
    HEADER:     load = ROM80, type = ro, start = $80FFC0;
    VECTORS:    load = ROM80, type = ro, start = $80FFE0;
}
```

**Calls** are `JSL` and `RTL` everywhere, because caller and callee may be in different banks;
jumps inside a function stay in its bank. **Constant data** is read with 24-bit long addresses,
because `DBR` points at RAM, not at the ROM.

**The image** is padded with `$FF` to a power of two of banks, and dampf writes the ROM size and
the checksum into the header afterwards. The header names the game `DAMPF TEST`, a LoROM
cartridge without RAM for North America (NTSC).

The build stops with a message when the program does not fit:

| Message | Cause |
|---|---|
| `the runtime and the initial values of variables (<n> bytes) do not fit into bank $80` | start-up code, arithmetic routines and initial values together exceed 32 KB |
| `the code of <name> is <n> bytes, more than a ROM bank (32 KB)` | one function is larger than a bank |
| `the constant object <name> is <n> bytes, more than a ROM bank (32 KB)` | one constant object is larger than a bank |
| `the program does not fit into 4 MB of ROM` | all 128 banks are full |

The name in the second and third message is the segment name without its first letter, such as
`_add3`.

## I/O registers

The PPU and CPU registers (`$2100`–`$21FF`, `$4200`–`$43FF`) are in bank 0, which `DBR` does not
point to, so the runtime writes them with 24-bit long addresses, such as `sta f:$002100`.

C code reaches them through a `volatile` pointer made from an integer. The integer becomes the
low 16 bits of the pointer and the bank is 0, so `0x2100` is `$00:2100`:

```c
#include <stdint.h>

void main(void) {
    *(volatile uint8_t *)0x2100 = 0x0F;
    uint8_t v = *(volatile uint8_t *)0x4212;
    *(volatile uint8_t *)0x2101 = v;
}
```

The code generator from MIR addresses them directly: a pointer made from a constant becomes a
long address, and the accumulator stays in 8-bit mode for the three accesses:

```
        lda     #$000F
        sep     #$20
        sta     f:$002100
        lda     f:$004212
        sta     f:$002101
        rep     #$20
```

A `volatile` byte is read as exactly one byte. A byte that is not `volatile` may be read as 16
bits and masked, which reads the byte after it as well.

A register with an index, `*(volatile uint8_t *)(0x210B + i)` for an `i` that is never negative,
becomes `sta f:$00210B,x`. Other pointers made from numbers go through `dampf_ptr`, and so does
every one in code from the naive code generator. In a playable game only the SNES platform layer
in `platform/snes` writes registers: it implements the game's console API in C, and the game
logic does not touch the registers itself.

The hardware intrinsics that will give C code access to the hardware are not built yet (see
[intrinsic](/glossary#intrinsic) and [Limits](/reference/limits)).
