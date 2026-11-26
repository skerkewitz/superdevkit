<!-- GENERATED from doc-specs/reference/runtime-and-memory.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Runtime and memory

A ROM built by dampf holds more than your compiled C: the [runtime](/glossary#runtime) starts
the machine, a few arithmetic routines do what the 65816 has no instruction for, and a header
and vectors make the image a valid SNES ROM. This page says where all of it goes, and where your
code, constant data, variables and the frames of functions end up, so that you can mix dampf's
code with your own assembly or find a variable in [Mesen](/glossary#mesen)'s memory viewer.

## Start-up and the entry function

The runtime's start-up code runs from the reset vector. In order, it:

1. disables interrupts, switches the 65816 to native mode and jumps from
   [bank](/glossary#bank) `$00`, where the CPU starts, to the same code in bank `$80`;
2. sets A, X and Y to 16 bits, clears decimal mode, puts the stack pointer at `$1FFF` and sets
   the [direct page](/glossary#direct-page) register `D` to 0;
3. writes 0 to `$4200` (no NMI, no IRQ, no automatic joypad reading) and `$80` to `$2100`
   (forced blank), and 1 to `$420D` (`MEMSEL`), which switches banks `$80` to `$FF` to
   [FastROM](/glossary#fastrom-and-slowrom) speed, unless the ROM is built with
   [`--slowrom`](/reference/command-line#slowrom);
4. sets the data bank register ([DBR](/glossary#dbr)) to `$7E`;
5. fills the uninitialized variables (the `BSS` segment) with zeros and copies the initial
   values of the other variables (the `DATA` segment) from ROM to RAM;
6. in a program with an IRQ handler written in C, clears the interrupt-disable flag (`CLI`);
7. calls the [entry function](/glossary#entry-function) with `JSL`.

The entry function therefore finds the processor in native mode with the interrupt-disable flag
set (clear in a program with an IRQ handler in C), no interrupt switched on in `$4200`, the
accumulator and index registers 16 bits wide (both the
[`M` and `X` flags](/glossary#m-and-x-flags) clear), `D = 0`, `DBR = $7E`, the stack at
`$1FFF`, the screen in forced blank and every global variable at its initial value. It is
`main` unless `--entry` names another function (see [Command line](/reference/command-line)),
and has the form `void name(void)`, or `int main(void)`, whose result is ignored.

When the entry function returns (with `RTL`, as every function dampf compiles does), the runtime
writes 1 to `$21FB` and then waits in an endless loop. `$21FB` is an unused port on the SNES's
B bus; the write tells a test script that the program has finished. The loop is a branch to
itself rather than `STP`, which would stop the CPU and leave a debugger nothing to step or
continue.

The SNES has two interrupts a game uses: the NMI, which comes at the start of every
[vertical blank](/glossary#vertical-blank), and the IRQ, which the CPU's timer raises at a
picture row the program sets. Their vectors point to a bare `RTI`, unless the program has a
handler for the interrupt written in C: then the vector leads to the runtime's entry stub for
it, see [Interrupt handlers](#interrupt-handlers). The runtime switches no interrupt on.

That is left to the kit's library on the SNES, the
[platform layer](/glossary#platform-layer) in `lib/snes`, whose `main.c` sets up the console,
shows the splash screen and runs the main loop after the runtime's start-up. Its main loop waits
for the vertical blank itself and calls `game_vblank`. Its `irq.c` switches on the game's IRQ,
and, in a game that publishes its data for interrupt code, the kit's NMI, which then runs the
game's vertical-blank code. How a game writes its IRQ and publishes its data is on
[The console API](/reference/console-api).

The runtime leaves the sound chip alone. In a game built with the kit, the library uploads the
sound driver when the game calls `sdevkit_sfx_init` in its `game_init`: it waits for the boot
code of the SPC700, the SNES's sound processor, and sends it the driver, the sample directory
and the samples through the ports `$2140`–`$2143`. The upload moves about 350 bytes a frame, as
Mesen measures it, and the CPU does nothing else until it ends. A game that draws its title and
turns the screen on before this call shows the title, standing still, instead of a black
screen.

The runtime also contains the arithmetic routines that generated code calls for multiplication
and division (`dampf_mul16`, `dampf_mul32`, `dampf_udiv16`, `dampf_udiv32`, `dampf_sdiv32`).
The two multiplications build their product from byte products on the CPU's 8 x 8 multiplier
(factors at `$4202` and `$4203`, product at `$4216`); the three divisions are
shift-and-subtract loops. All of them are called with `JSL`, return with `RTL` and expect 16-bit
registers and `D = 0`, or the direct page of an interrupt context. They work in `dampf_regs`, an
18-byte scratch area in the direct page, of which every interrupt context has a copy of its own.
A program with interrupt handlers also gets two multiplications without the multiplier,
`dampf_mul16_soft` and `dampf_mul32_soft`, for the code of its handlers.

The [code generator from MIR](/glossary#code-generator-from-mir) multiplies two values that fit
a byte on the multiplier without a call. Assembly of your own that uses the multiplier or the
CPU's divider must therefore not run a multiplication between its write and its read:
`dampf_mul32` also writes `$4204`, the divider's dividend. The code of interrupt handlers never
uses the multiplier, so an interrupt cannot spoil a product of the code it interrupts.

## RAM

All variables live in bank `$7E`, and the start-up code sets `DBR = $7E` once; it never
changes. With the data bank pointing at RAM, 16-bit absolute addresses (`lda abs`, `lda abs,x`)
reach all 64 KB of the bank. [Blubber Blasen](/glossary#blubber-blasen) needs about 25 KB of
RAM, which would not fit into the 8 KB that bank 0 mirrors.

The first 8 KB of bank `$7E` are the same memory as `$0000`–`$1FFF` in bank 0. The direct page
and the stack live there, because the 65816 always addresses both through bank 0.

| Address (bank `$7E`) | Use |
|---|---|
| `$0000`–`$001F` | the runtime's scratch registers in the direct page (`dampf_regs`, `dampf_ptr`) |
| `$0020`–`$1DFF` | the frames of functions compiled by the [code generator from MIR](/glossary#code-generator-from-mir) (`dampf_frame`) |
| below `$1E00` | only with interrupt handlers in C: each interrupt context's direct page and frames, in whole pages |
| `$1E00`–`$1FFF` | the stack |
| `$2000`–`$FFFE` | variables: globals, static locals, and the frames of the [naive code generator](/glossary#naive-code-generator) |

The byte at `$7E:FFFF` stays free, for the reason given under [Pointers](#pointers). Bank `$7F`,
another 64 KB of RAM, is not used.

The pages of the interrupt contexts take the top of the frames' region, so the main code's
frames end below them; [Interrupt handlers](#interrupt-handlers) gives the addresses. The data a
game publishes for its interrupt code (`sdevkit/publish.h`) is an ordinary global among the
variables from `$2000`: an array of two copies of the game's struct, beside the struct itself,
and three bytes, `sdevkit_publish_side` (16 bits), which says which copy interrupt code reads,
and `sdevkit_publish_ready`. A game that does not publish has none of it.

The linker sees RAM addresses without the bank: a variable is at `$2000`, not `$7E2000`, so
16-bit address fixups fit. Code that needs the full 24-bit address of a variable, to build a
pointer to it, adds the bank itself.

## The direct page

The direct page is a 256-byte window of bank 0 that the 65816 reaches with one-byte addresses,
which are shorter and faster than full addresses (see [direct page](/glossary#direct-page)).
The main code runs with `D = 0`, so its window is `$0000`–`$00FF`; an interrupt handler in C
runs with a direct page of its own, see [Interrupt handlers](#interrupt-handlers).

The runtime takes the start of it: `dampf_regs` (18 bytes) for the arithmetic routines, and
`dampf_ptr`, room for two 24-bit pointers of 4 bytes each. The 65816 can only follow a 24-bit
pointer that is in the direct page (`lda [dampf_ptr],y`). The code generator from MIR uses a
pointer whose place in its frame is in the direct page where it is; any other pointer it copies
to `dampf_ptr` right before it uses it, as the naive code generator does with every pointer.

From `$0020` on come the frames of functions: the memory a function's parameters, locals and
intermediate values occupy. The code generator from MIR gives every value a fixed place in its
function's frame, 2 bytes for a value of up to 16 bits and 4 bytes for a 32-bit value or a
pointer; values whose lifetimes do not overlap share a place. The frames form one region from
`$0020` to `$1DFF`, less in a program with interrupt handlers, and the part below `$0100` is in
the direct page, where every access to a value is cheaper. The build fails when the frames do
not fit into this region. How dampf decides which frames get that part is on
[Optimizations](/inside/optimizations).

The naive code generator keeps the frames of C functions as static variables from `$2000`. Of
this region it uses only the start, for the frame of a function written in assembly
(`DAMPF_ASM`, a [hint](/glossary#hint)).

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
through `$80` and up can run at FastROM speed. The start-up switches it on (`MEMSEL` at
`$420D`): a ROM access then takes 6 [master clocks](/glossary#master-clock) instead of 8, and
the header declares FastROM (map mode `$30`). Flash cartridges and emulators run FastROM; for a
SlowROM cartridge, build with [`--slowrom`](/reference/command-line#slowrom), which keeps
2.68 MHz and map mode `$20`.

**Bank `$80`** holds the parts with fixed places: the start-up code with the entry stubs of
interrupt handlers, the runtime's arithmetic routines, the initial values of variables (copied
to RAM at start-up), the header at `$80:FFC0` and the vectors at `$80:FFE0`. The rest of the
bank is filled like the others.

**Every function and every constant object** gets a segment of its own: `C_` and the function's
name for code, `R_` and the object's name for constant data. No segment is split over banks, so
no function or constant object crosses a bank boundary. dampf packs the segments into banks,
largest first, each into the first bank with room. Its own linker knows the size of every
segment after assembling and links once. With
[`--assembler ca65`](/reference/command-line#assembler), [ld65](/glossary#ca65-and-ld65)
cannot split a segment over banks either, and dampf links twice: once to measure every segment,
then with a configuration that packs them in the same way. That configuration is written next
to the ROM as `<name>.cfg`. For the execution test `compiler/src/test/resources/exec/calls.c`,
built with `--entry test_main` and `--assembler ca65`, everything fits into bank `$80`:

```sh
build/install/superdevkit/bin/dampf build --entry test_main --assembler ca65 -o build/calls.sfc compiler/src/test/resources/exec/calls.c
```

```text [build/calls.cfg]
# dampf: LoROM, 1 bank(s) of 32 KB from $80, packed by dampf.snes.RomBuilder
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
cartridge for North America (NTSC), FastROM unless the ROM is built with `--slowrom`, and
without RAM unless the build asks for save RAM.

**Save RAM** is the cartridge's battery-backed RAM, which keeps a high score or a saved game
while the console is off. Build with [`--sram`](/reference/command-line#sram) and the number of
bytes, up to 32 KB, and dampf announces it in the header: the cartridge type becomes `$02` (ROM,
RAM and battery) and the RAM size the smallest of 2, 4, 8, 16 and 32 KB that holds the bytes.
The console maps it at `$70:0000` to `$70:7FFF`, outside the bank `DBR` points at, so code
reaches it with long addresses, at 8 master clocks a byte. The kit's `sdevkit_sram()` returns
its address. The runtime neither clears nor fills it, and dampf places nothing there: what it
holds is up to the game.

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

The [PPU](/glossary#ppu) and CPU registers (`$2100`–`$21FF`, `$4200`–`$43FF`) are in bank 0,
which `DBR` does not point to, so the runtime writes them with 24-bit long addresses, such as
`sta f:$002100`.

C code reaches them through a `volatile` pointer made from an integer. The integer is the 24-bit
address, with the bank in bits 16 to 23, so `0x2100` is `$00:2100` (and `0x700000`, the start of
save RAM, is `$70:0000`):

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

A register with an index, `*(volatile uint8_t *)(0x210B + i)`, becomes `sta f:$00210B,x` when
dampf can see that `i` is never negative and that the sum stays below `$8000`, as with an `i` of
type `uint8_t`. Other pointers made from numbers are computed and followed as 24-bit pointers:
`[place],y` where their place is in the direct page, through `dampf_ptr` otherwise. Code from
the naive code generator takes `dampf_ptr` for every one.

In a playable game only the SNES platform layer in `lib/snes` writes registers from C: it
implements the game's console API, and the game logic does not touch the registers itself.
dampf's own code also uses the CPU's multiplier, see
[Start-up and the entry function](#start-up-and-the-entry-function).

dampf has no [intrinsics](/glossary#intrinsic): `volatile` pointers reach every register, so
the platform layer needs none, and dampf uses the CPU's multiplier itself.

## Interrupt handlers

dampf gives every value of a function a fixed place and lets functions that are never active at
the same time share places, and the runtime's scratch registers lie at `$0000`. Code that an
interrupt runs is active at the same time as anything else, so a handler compiled like the main
code would overwrite the values of the code it interrupts. dampf therefore makes each handler
the root of an interrupt context: the handler and every function it reaches, with a direct page
and frames of their own. The main code is the context of the entry function.

A function becomes a handler with `DAMPF_INTERRUPT(nmi)` or `DAMPF_INTERRUPT(irq)` from
`<dampf.h>` in front of its definition. This program has a handler for each interrupt:

```c
#include <stdint.h>
#include <dampf.h>

uint16_t line_count;
uint16_t frame_count;
uint16_t scale = 3;

DAMPF_INTERRUPT(irq) void count_line(void)
{
    line_count = line_count * scale;
}

DAMPF_INTERRUPT(nmi) void count_frame(void)
{
    frame_count++;
}

void main(void)
{
    line_count = 1;
}
```

The runtime has an entry stub for each handler, assembled only into a program that has that
handler, and the interrupt's vector points to it. The vector leads into bank `$00`; the stub's
first instruction jumps on into bank `$80`, as the start-up does. The CPU has pushed
`P`, the program counter and the program bank; the stub then saves A, X and Y in 16 bits, `DBR`
and `D`, sets `DBR` to `$7E` and `D` to the context's page, and acknowledges the interrupt: the
NMI's stub reads `$4210` (`RDNMI`) in 8 bits, the IRQ's reads `$4211` (`TIMEUP`), without which
the IRQ would come again at once. It calls the handler with `JSL`, restores everything and
returns with `RTI`.

The stub takes 66 CPU cycles from the interrupt to the handler's first instruction (71 for the
NMI) and 31 from the handler's return to the end of `RTI`, so an interrupt costs 97 cycles (IRQ)
or 102 (NMI) on top of its handler, plus the rest of the instruction it interrupted. The
[timing report](/glossary#timing-report) gives each interrupt a line of its own with the stub's
cycles and the handler's: for the program above, `dampf_irq` takes 156 to 666 cycles and
`dampf_nmi` 123.

Each context's direct page is made of whole pages right below the stack: the NMI's highest, then
the IRQ's. The first 32 bytes of the page are the context's own copy of `dampf_regs` and
`dampf_ptr`, and its frames follow from byte `$20`, laid out as the main code's are from
`$0020`, so the functions that call no other lie in the direct page. A context whose frames
take at most 224 bytes takes one page. The generated code reaches a frame place in the direct
page relative to `D`, as `z:dampf_frame+n` in every context, and any other place, and the
address of a local, through the context's own symbol. For the program above:

```asm
dampf_frame_nmi = $1D20        ; the NMI context's frames, absolute; its direct page is $1D00
dampf_frame_irq = $1C20        ; the IRQ context's frames, absolute; its direct page is $1C00
; ...
dampf_nmi_dp = $1D00                   ; with the direct page here
; ...
dampf_irq_dp = $1C00                   ; with the direct page here
```

The [naive code generator](/glossary#naive-code-generator) keeps the frames of C functions
static from `$2000`, so its contexts take one page each.

A function that the main code and a handler both call, or both handlers, is compiled once per
context, with its frames in that context's area. The copy's label is two underscores, the
context and the original's label, such as `__irq_clamp` for `_clamp`, and its code segment
`C__irq_clamp`; a `static` local of the function stays one variable for all copies. A
context's frames grow by the frames of its copies, and with them its pages. A function written
in assembly (`DAMPF_ASM` or `--replace`) that a handler reaches is a compile error, because its
convention assumes `D = 0` and the main code's frames.

The main code's frames end below the lowest page. With two small handlers they have
`$0020`–`$1BFF` (7,136 bytes), with one `$0020`–`$1CFF` (7,392 bytes), instead of
`$0020`–`$1DFF` (7,648 bytes). A game that publishes its data for interrupt code has the kit's
NMI, so its main code's frames end below `$1D00`, and below `$1C00` if it has an IRQ as well. A
program without handlers keeps the whole region, and its runtime has no entry stubs.

Code of an interrupt context never uses the CPU's multiplier: an interrupt between the main
code's write of the factors and its read of the product would spoil the product, and the factors
cannot be read back to save them. The runtime's `dampf_mul16_soft` and `dampf_mul32_soft`, which
shift and add, take the place of `dampf_mul16`, `dampf_mul32` and the multiplication of two
bytes without a call. They give the same results, but take longer: `dampf_mul16_soft` 22 to 532
cycles against 108 for `dampf_mul16`.
