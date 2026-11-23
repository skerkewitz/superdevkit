<!-- GENERATED from doc-specs/reference/output-files.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# The generated files

A `dampf build` writes the ROM and, in the same directory, everything that went into it: the
generated assembly, a label file and the debug file for [Mesen](/glossary#mesen). dampf
assembles and links with its own assembler and linker; with `--assembler ca65` it uses
[ca65 and ld65](/glossary#ca65-and-ld65) instead, which build the same ROM and leave more files
behind. The examples on this page come from building `compiler/src/test/resources/exec/calls.c`,
one of dampf's execution tests and a single C file:

```sh
build/install/superdevkit/bin/dampf build --entry test_main -o build/calls.sfc compiler/src/test/resources/exec/calls.c
```

dampf creates the output directory if it does not exist yet. The options of `dampf build` are
on [Command line](/reference/command-line). For a game, `superdevkit build snes` writes the same
files into the game's `build/snes/`, named after the game, together with `<name>.args`, the
[args file](/glossary#args-file) of what it compiled
([The superdevkit command](/reference/superdevkit#build)).

## The files

All names start with the name of the ROM without `.sfc`, here `calls`, except the runtime's.

| File | What it contains | What reads it |
|---|---|---|
| `calls.sfc` | the ROM: a LoROM image, padded to a power of two, with size and checksum in its header, and the save RAM with `--sram` | Mesen |
| `calls.s` | the generated assembly, commented line by line | a person |
| `dampf_runtime.s` | the [runtime](/glossary#runtime), copied out of dampf | a person |
| `calls.lbl` | the label file, in ld65's format: one address per label | a person |
| `calls.dbg` | the debug file, in ld65's format: source files, lines, symbols | Mesen |
| `calls.timing.txt` | the [timing report](/glossary#timing-report): best and worst case in CPU cycles per function; only with the code generator from MIR | a person |

With `--assembler ca65`, the directory also holds what ca65 and ld65 write:

| File | What it contains | What reads it |
|---|---|---|
| `calls.o`, `dampf_runtime.o` | the assembled objects | ld65 |
| `calls.cfg` | the linker configuration: memory areas and which segment goes into which ROM bank | ld65, a person |
| `calls_measure.cfg`, `calls_measure.map` | the configuration and map of the first link, which only measures sizes | ld65, a person |
| `calls.map` | ld65's map: every module's segments, and the address and size of each segment | a person |

In that build ca65 reads the `.s` files, and ld65 writes the label file and the debug file.

The code generator puts every function into a segment of its own (`C_add3`) and every constant
object as well (`R_` and its name), because a linker cannot split a segment and so none of them
can cross a 32 KB [bank](/glossary#bank) of the ROM. dampf packs the segments into banks from
`$80` on, the largest first, each into the first bank with room. Its own linker knows the size
of every segment once the modules are assembled and links once. ld65 links twice: the first
link puts everything into one large area to learn the size of each segment, the second uses the
configuration in `calls.cfg`. The runtime's start-up code, the initial values of variables, the
header and the vectors stay in bank `$80`. Where code and data end up
in memory is on [Runtime and memory](/reference/runtime-and-memory).

When there is more than one assembly module, they are numbered: `calls_1.s`, `calls_2.s` and so
on, each with its `.o` when ca65 assembles them. With `--replace`, for example, each
hand-written `.s` file is added as a module of its own.

## Reading the assembly

The assembly is meant to be read: every C line appears in it, followed by the instructions dampf
wrote for it, with the CPU cycles of each instruction in a column on the right. The
[code generator from MIR](/glossary#code-generator-from-mir), the default, wrote this excerpt of
`calls.s` for `add3` in `calls.c`:

```asm
; ---- _add3    (compiler/src/test/resources/exec/calls.c:9:17)
        .segment "C_add3"
.proc _add3
                                    ; parameter i16 %a at z:dampf_frame+0
                                    ; parameter i16 %b at z:dampf_frame+2
                                    ; parameter i16 %c at z:dampf_frame+4
b0_entry:

; calls.c:11   uint16_t s = a + b;                                              [ 10 cyc ]
        .dbg    line, "../compiler/src/test/resources/exec/calls.c", 11
                                    ; %0 = add i16 %a, %b
        clc                         ;                                                           2
        lda     z:dampf_frame+0     ;                                                           4
        adc     z:dampf_frame+2     ;                                                           4

; calls.c:12   s = s + c;                                                       [ 6 cyc ]
        .dbg    line, "../compiler/src/test/resources/exec/calls.c", 12
                                    ; %1 = add i16 %0, %c
        clc                         ;                                                           2
        adc     z:dampf_frame+4     ;                                                           4

; calls.c:13   return s;                                                        [ 6 cyc ]
        .dbg    line, "../compiler/src/test/resources/exec/calls.c", 13
                                    ; ret i16 %1
        rtl                         ;                                                           6
.endproc
```

Each function starts with a line `; ---- ` that names its label and where it is defined (file,
line, column), then its segment. The parts of one C line are these:

- **The source line header** is the comment `; calls.c:11` with the text of the C line. When the
  line is straight-line code, the header ends with the sum of its cycles, `[ 10 cyc ]`.
- **The `.dbg line` directive** below it tells the assembler which C line the following
  instructions belong to; it ends up in the debug file (see [The debug file](#the-debug-file)).
- **The instruction comments** differ between the code generators. The code generator from MIR
  writes the [MIR](/glossary#mir) instruction it translates as a comment line above the 65816
  instructions for it (`; %0 = add i16 %a, %b`); the instructions themselves carry no comment.
- **The cycle counts** stand at the right end of each instruction line.

The processor state is not repeated per line in the code generator from MIR. The comment at
the top of its file says
`; A/X/Y are 16 bit (A goes to 8 bit where SEP #$20 says so), DBR = $7E`, and where a line
switches the accumulator to [8-bit mode](/glossary#_8-bit-and-16-bit-mode) the `sep #$20` and
`rep #$20` are in the code.
The counts follow the switch: the `sta` in 8-bit mode costs 4 cycles, in 16-bit mode 5.

```asm
; calls.c:31   signed_result = neg8(inc8(126));                                 [ 13 cyc ]
        .dbg    line, "../compiler/src/test/resources/exec/calls.c", 31
                                    ; store i8 @_signed_result, -127
        rep     #$20                ;                                                           3
        lda     #$0081              ;                                                           3
        sep     #$20                ;                                                           3
        sta     _signed_result      ;                                                           4
```

The `rep #$20` at the start belongs to line 30, which ended in 8-bit mode: dampf switches back
only when code needs 16 bits again, here the load of the 16-bit immediate.

The calls in this line are gone: the program is compiled as a [whole](/glossary#whole-program),
so dampf inlined `neg8` and `inc8` and computed the result at compile time.

The [naive code generator](/glossary#naive-code-generator) (`--backend naive`) writes the
processor state under every header (`; M=16 X=16`, the
[`M` and `X` flags](/glossary#m-and-x-flags)) and a comment for each instruction that says
what it does in terms of the C source. Built with
`--backend naive -o build/calls_naive.sfc`, line 28 of `calls.c` reads:

```asm
; calls.c:28   result = add3(1000, 2000, 3);                                    [ 37 cyc + calls ]
        .dbg    line, "../compiler/src/test/resources/exec/calls.c", 28
                                    ; M=16 X=16
        lda     #$03E8              ; A = 1000                                                  3
        sta     _add3__a            ; a = A                                                     5
        lda     #$07D0              ; A = 2000                                                  3
        sta     _add3__b            ; b = A                                                     5
        lda     #$0003              ; A = 3                                                     3
        sta     _add3__c            ; c = A                                                     5
        jsl     _add3               ; add3(1000, 2000, 3), result in A                          8
        sta     _result             ; result = A                                                5
```

A line with a call gets `+ calls` after its sum: the 37 cycles count the `jsl` itself, not the
code of `add3`.

A conditional branch shows two numbers, the cycles when it is not taken and when it is taken.
The code generator from MIR wrote this for a function that limits a global `uint16_t speed` to
300 (`if (speed > 300) speed = 300;`):

```asm
; clamp.c:7   if (speed > 300)
        .dbg    line, "clamp.c", 7
                                    ; %0 = load i16 @_speed
        lda     _speed              ;                                                           5
                                    ; %1 = cmp uge i16 %0, 301
                                    ; br %1, b1_then, b2_endif
        cmp     #$012D              ;                                                           3
        bcc     b2_endif            ;                                                         2/3
b1_then:

; clamp.c:8   speed = 300;                                                      [ 8 cyc ]
```

The header of line 7 has no sum, because what the line costs depends on the path taken. A line
gets no sum as soon as it contains a conditional branch, a `jmp`, `bra` or `jml`, or a label.

## Cycles

The numbers are CPU cycles of the 65816 in native mode, from the table in WDC's data sheet for
the W65C816S. They are not [master cycles](/glossary#master-clock): how many master cycles a
CPU cycle takes depends on the memory it touches (6 for
[FastROM](/glossary#fastrom-and-slowrom) and internal operations, 8 for SlowROM and WRAM), and
dampf does not count that yet.

The count depends on the processor state, and dampf's code fixes most of it:

- **Accumulator width**: dampf follows `sep #$20` and `rep #$20` through the code, so loads,
  stores and arithmetic cost one cycle more in 16-bit mode, read-modify-write instructions two.
- **Index registers**: dampf's code always runs with 16-bit X and Y, so their extra cycle is
  always counted, and so is the extra cycle of an indexed read.
- **Direct page**: dampf keeps the low byte of `D` at zero, so the extra cycle for a
  [direct page](/glossary#direct-page) that does not start on a page boundary never occurs and
  is not counted.
- **Branches**: a conditional branch costs one cycle more when it is taken, hence `2/3`.

A C line with branches gets no best and worst case of its own yet; see
[Limits](/reference/limits). For whole functions the code generator from MIR computes both and
writes them to `calls.timing.txt`, the most expensive function first:

```
# dampf timing: best and worst case per function in CPU cycles, from entry to return,
# calls included, interrupts and DMA not (docs/timing.md). "unbounded": a loop without
# a known bound on the way; the first such loop in the function, or the first call
# of a function without a bound, is named after it. "never": the function never returns.
        best        worst  function
        1940         2594  dampf_sdiv32
        1888         2432  dampf_udiv32
         564          772  dampf_udiv16
         398          400  dampf_mul32
         108          108  dampf_mul16
          45           45  _test_main
          22           22  _add3
          18           18  _inc8
          18           18  _neg8
```

The first five lines are the multiplication and division routines of the runtime, which the
file lists with the functions of the program. Unlike the sums in the line headers, these numbers
include the calls a function makes. A function with a loop whose number of rounds dampf cannot
bound gets `unbounded` as its worst case and comes first in the file. After its name stands
the reason: the first such loop in it (`loop at` and the C file and line), or else the first
call of a function without a bound (`calls` and that function). A function that waits for bit 7
of the register `$4212` in a `while` loop on line 5 of `wait.c` gets:

```
          22    unbounded  _wait_ready   (loop at wait.c:5)
```

A function that never returns has no best case either, and gets `never` in its place. For a
function `halt` whose body is the endless loop `for (;;) {}` on line 11 of the same file:

```
       never    unbounded  _halt   (loop at wait.c:11)
```

How dampf computes these numbers is on [Exact timing](/inside/timing).

## The debug file

Mesen shows the C source while you step through the ROM because of `calls.dbg`, the debug file.
dampf writes it in ld65's format, which Mesen reads; with `--assembler ca65`, ld65 writes it. It
lists the source files (the runtime's `.s`, the generated `.s` and, from the `.dbg file`
directives at the top of the assembly, the C files) and, for every instruction, the C line it
belongs to, taken from the `.dbg line` directives in the assembly. It also holds the labels of
the program. The naive code generator adds `.dbg func` and `.dbg sym` directives, so the debug
file also names the C functions and their parameters and locals; the code generator from MIR
writes only the file and line directives.

The paths of the C files are relative to the directory of the debug file. For
`build/calls.dbg` the source is `../compiler/src/test/resources/exec/calls.c`, and the generated
files are named without a directory, because dampf assembles them by their file name in the
output directory (and runs ca65 and ld65 there).
Mesen therefore finds the sources wherever the ROM is opened, as long as the ROM and the sources
keep their positions relative to each other. A ROM written outside the repository gets a path
that climbs up to the common parent directory.

How to load the debug file in Mesen is on [Debugging in Mesen](/guide/debugging).
