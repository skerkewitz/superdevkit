<!-- GENERATED from doc-specs/guide/debugging.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Debugging in Mesen

A ROM built by dampf can be debugged at the level of its C source in
[Mesen](/glossary#mesen) (MesenCE; Mesen 2 works the same): Mesen shows the C file, stops on
a C line and steps from one C statement to the next. Beside it, the assembly dampf writes tells
you what each C line became on the 65816. dampf brings no debugger of its own; it writes the
files Mesen needs.

## Set up Mesen

Build the program with `dampf build`. This page uses `examples/m1/calls.c`, which the
repository keeps for exactly this purpose; its [entry function](/glossary#entry-function) is
`test_main`:

```sh
build/install/dampf/bin/dampf build --entry test_main -o build/examples/m1/calls.sfc examples/m1/calls.c
```

Next to the ROM `calls.sfc`, dampf writes the generated assembly `calls.s` and the debug file
`calls.dbg`, together with a label file. Every C line in the assembly carries a `.dbg line`
directive, and dampf turns those into the table Mesen uses to map an address back to a file
and a line. It writes the debug file in the format of [ld65](/glossary#ca65-and-ld65), which
Mesen reads; with `--assembler ca65`, ld65 writes it instead.
[The generated files](/reference/output-files) describes each file.

Open the ROM in Mesen with the `.dbg` file next to it. In the debugger's integration settings,
let Mesen load `.dbg` files automatically; then it reads the debug file every time you open
the ROM.

The debug file names the C sources relative to itself, as the path from the folder of the ROM
to the source file. For the build above that is `../../../examples/m1/calls.c`. Mesen finds the
sources as long as the repository keeps its layout, so move the ROM and the sources together
or not at all.

If you build in WSL and run the Windows Mesen, open the ROM through `\\wsl.localhost\...`.
The relative paths in the debug file then lead to the sources in the same file system.

## Step through C

Once the debug file is loaded, Mesen shows `calls.c` instead of the bare instructions. A C
line covers every instruction from its `.dbg line` directive up to the next one, so stepping
over a C statement runs all of them and stops at the first instruction of the next line. A
breakpoint on a C line stops at that first instruction.

::: warning TODO
Which Mesen commands set a breakpoint on a C line and step over a C statement. None of the
sources (`README.md`, `docs/architecture.md`) names them; the README would have to.
:::

Expect fewer stops than the C source has lines. dampf compiles the
[whole program](/glossary#whole-program) at once and the optimizer computes what it can at
compile time: in the default build, `test_main` calls none of the three small functions,
because their results are known constants (`result` is 3003, `small_result` is 0). A
breakpoint in `add3` is never reached, and stepping through `test_main` passes each line in a
few instructions. To step into every call as the C source shows it, build with
`--backend naive`, the [naive code generator](/glossary#naive-code-generator), which
translates each statement as written. Its options are on
[Command line](/reference/command-line).

## Read the assembly beside it

Every C line in the `.s` file starts with a header: the file name, the line number, the C
source of the line and, for straight-line code, the sum of its CPU cycles in brackets. Then
follow the `.dbg line` directive for Mesen, the instructions of the line and, in a column on
the right, the CPU cycles of each instruction. A conditional branch shows two numbers, not
taken and taken. A line with branches or jumps gets no sum, because its cost depends on the
path; a line that calls a function gets the sum of its own instructions marked `+ calls`.

This is line 30 of `calls.c` as the default build writes it:

```asm
; calls.c:30   small_result = inc8(-1);                                         [ 7 cyc ]
        .dbg    line, "../../../examples/m1/calls.c", 30
                                    ; store i8 @_small_result, 0
        sep     #$20                ;                                                           3
        stz     _small_result       ;                                                           4
```

The comment line `; store i8 @_small_result, 0` is the [MIR](/glossary#mir) instruction the
following code comes from: `inc8(-1)` has been reduced to storing the byte 0. Here the
comment column after the instructions stays empty; in a build with `--backend naive` it says
what each instruction does, such as `; A = a + b`.

The processor state is stated once at the top of the file:
`A/X/Y are 16 bit (A goes to 8 bit where SEP #$20 says so), DBR = $7E` (the
[DBR](/glossary#dbr) is the bank for data addresses). Where a line needs the accumulator in
[8-bit mode](/glossary#_8-bit-and-16-bit-mode), as for the byte
`small_result` above, `sep #$20` switches it, and the cycles of the instructions after it are
counted for 8 bits. `rep #$20` switches back where code needs 16 bits again; dampf leaves it
out as long as the following lines can stay in 8-bit mode, so here it comes at the start of
line 31. The naive code generator also writes the state under every C header, as
`; M=16 X=16`.

## When the PC and the SNES disagree

The [PC build](/glossary#pc-build) is the reference: what the program computes on the SNES must
match what gcc on x86-64 computes, bit for bit. So when a result on the SNES looks wrong, first
find out whether the PC build gives the same result, before you look for the error in the
assembly.

Narrow the difference down in steps:

1. **The same input on both sides.** Write a small entry function that calls the code in
   question with fixed values and stores the results in globals, as `test_main` does in
   `calls.c`. Call the same function from a `main` on the PC and print the globals. For
   `calls.c`, gcc gives `result` 3003, `nested` 666, `small_result` 0 and `signed_result` -127.
2. **Compare the globals.** The [runtime](/glossary#runtime) writes to `$21FB` when the entry
   function returns; stop there in Mesen and read the globals from memory. The label file
   next to the ROM gives the address of each global under its C name with a leading
   underscore, such as `_small_result`. Then go to the first global that differs and step
   through the C lines that write it.
3. **Try `--backend naive`.** The naive code generator works directly from the C source and
   stays as an independent reference. If the naive build matches the PC build and the default
   build does not, the difference comes from the
   [code generator from MIR](/glossary#code-generator-from-mir) or its optimizer, and the
   C line where the two builds part is the one to report.

dampf's own tests compare every test program this way, with gcc on the PC built with the
[undefined behaviour sanitizer](/glossary#ubsan): a program whose result depends on undefined
behaviour can differ between the two sides without either being wrong, and the sanitizer
reports it on the PC.
