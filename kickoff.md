# dampf – Kickoff

dampf is a compiler for a subset of C that produces fast code for the WDC 65816, with exactly one target: the Super Nintendo. Its first user is Blubber Blasen (stage 3, the SNES port), and it runs as a separate project in parallel with the game.

The name: in German, *Dampf machen* ("make steam") means to get things moving fast. It also fits the steam factory of Blubber Blasen.

Date: 2026-11-17 · Status: decided, nothing built yet

## Why we build it

Blubber Blasen's game logic is written in C so it can be developed and tested on the PC and later run on the SNES. Stage 3 needs a C compiler for the 65816, and the existing ones are not good enough:

| Compiler | Result |
| --- | --- |
| PVSnesLib with 816-tcc | Very slow code. The data layout needed workarounds because the compiler throws errors on ordinary code. |
| Calypsi | Works as intended, but the code is still too slow. Why is being investigated; the results feed into this project. |
| llvm-mos | Good 6502 code, but no native 16-bit 65816 code generation. LLVM is a poor fit: it assumes many uniform registers and fixed-width values, while on the 65816 the operand width (8 or 16 bit) is processor state set by `REP`/`SEP`. |
| Hand-written assembly for the whole game | Tried years ago and failed. Hand-written assembly for a few hot functions stays possible later, but should not be the plan. |

We do not need a general C compiler. We need a compiler that makes SNES games fast. That is a much smaller and more focused problem, and it lets us make assumptions no general compiler can:

- One CPU, one target. No portability layers.
- A subset of C we define ourselves, and a checker that enforces it, so every assumption the optimizer makes is guaranteed.
- The whole program is always compiled at once, so every optimization sees everything (call graph, all uses of every variable).
- Hints in the source (as macros) give the compiler knowledge it cannot infer.
- C is only the contract that keeps the same source compiling on the PC with gcc or clang. That is what makes the code testable.

## Goals and non-goals

**Goals**

1. Fast 65816 code for SNES game logic: the game logic of Blubber Blasen's busiest scene fits in at most 60 % of a frame.
2. The same source compiles on the PC with gcc/clang and behaves **bit-identically** on both sides.
3. Source-level debugging of the C code in Mesen.
4. Readable output: the generated assembly is commented so a human can follow what each part is meant to do.
5. Correctness first: every optimization is checked automatically against a reference.

**Non-goals**

- Full C, or C standard conformance beyond the subset.
- Other CPUs or targets. The code keeps the 65816 core and the SNES target apart, but nothing is built for a second target.
- An own assembler, linker or debugger. We use ca65, ld65 and Mesen.
- Floating point.
- Fast compilation. A game this size compiles in seconds either way.

## The language: a C subset

The exact subset is derived from what Blubber Blasen actually uses (milestone M0). The starting position:

**Not supported** (rejected with a clear error message, never silently miscompiled)

- `float`, `double`, `long long`
- Recursion, direct or indirect. The checker names the cycle, for example `update_bubble → pop_chain → update_bubble`.
- Free function pointers. Function pointers are allowed only in constant tables known at compile time, so the call graph stays complete.
- `malloc` and any heap, variable-length arrays, variadic functions, `setjmp`/`longjmp`
- Structs passed or returned by value
- Separate compilation: all `.c` files are compiled as one unit.

**Types and semantics**

- Only fixed-width integer types (`int8_t`, `uint8_t`, `int16_t`, `uint16_t`, `int32_t`, `uint32_t`, and pointers). Plain `int` is rejected, because it is 32 bit on the PC and would be 16 bit on the SNES.
- C's integer promotions are implemented exactly as on the PC, so overflow behaves the same. The optimizer narrows operations only where it can prove the result is identical.
- Signed division rounds towards zero and right shifts of signed values are arithmetic, exactly as gcc/clang do on the PC. (The SNES hardware divider is unsigned only.)

**Hints**

Hints are macros. In the PC build they expand to nothing; in the dampf build they expand to built-ins the parser knows, so the source stays valid C and one preprocessor serves both builds.

| Hint (working names) | Effect |
| --- | --- |
| `DAMPF_RANGE(x, lo, hi)` | Promises a value range, so 8 bit can be enough |
| `restrict` or a dampf macro | No aliasing: values need not be reloaded after a store through a pointer |
| Direct page placement | A variable gets a fixed slot in the direct page |
| Split struct arrays | An array of structs is stored as one array per field |
| Unroll | A small loop is unrolled |

**Intrinsics** for SNES hardware, implemented as plain functions in the PC build: hardware multiply and divide, DMA, waiting for vertical blank, placing constant data in ROM banks.

## How we build it

### Pipeline

```
.c files
  → gcc -E                  preprocessing; keeps line markers (# 142 "bubble.c")
  → ANTLR 4 parser          based on the C grammar from grammars-v4; parses broadly
  → AST
  → subset checker          rejects everything outside the subset, with clear messages
  → IR                      SSA form, explicit widths (8 / 16 / 24 bit)
  → whole-program passes    call graph, inlining, range analysis, frame allocation, ...
  → 65816 back end          instruction selection, M/X width tracking, direct page allocation,
                            branch distances, peephole
  → ca65 assembly           commented, with source line directives
  → ca65 + ld65             ROM + .dbg debug file
  → Mesen                   runs and debugs at C source level
```

The parser accepts more C than the subset on purpose: a construct outside the subset is better reported by the checker ("recursion is not supported: …") than by a parse error.

### Core and target

Almost everything is plain 65816: the IR, width analysis, direct page frames, instruction selection. SNES specifics live in one target module:

- Memory map: direct page and stack in the first 8 KB of bank 0, WRAM, LoROM/HiROM
- Cost of each memory access in cycles (FastROM, WRAM and I/O registers differ); the optimizer needs these
- The multiply and divide registers
- Intrinsics for DMA, vertical blank and ROM banks

### Assembly output and debugging

We emit ca65 assembly and link with ld65. An own assembler or linker would gain nothing: with whole-program compilation the compiler already knows every instruction size (so it decides branch distances itself) and controls placement through segments. Using ld65 also gives us its `.dbg` debug files, which Mesen can read. With source line directives in the assembly, Mesen should show and step through the C source. **This is verified first, in M1**, because it was the biggest pain in the earlier attempt.

Every C statement is emitted with its source line, the processor state and a comment per instruction:

```
; bubble.c:142   b->x += b->vx;
        ; M=16 X=8  D=&bubbles[i]
        lda z:BUB_VX        ; A = vx (8.8)
        clc
        adc z:BUB_X         ; A = x + vx
        sta z:BUB_X         ; x = A
```

Optimizations add why the code looks the way it does, for example `; >>8 read as high byte`.

### Implementation

- Java (current LTS release): records, sealed interfaces and pattern matching suit an IR and compiler passes well, and strong types catch many errors early.
- ANTLR 4 for the parser.
- Compilation speed does not matter.

## Optimizations

**The budget.** An NTSC frame has roughly 45,000–55,000 CPU cycles, depending on how much code runs from FastROM and how often WRAM is accessed. Vertical blank work (DMA for sprites and tiles) and a safety margin come off that. Target: game logic in the busiest scene uses at most 60 % of a frame. Sound runs on its own CPU (SPC700) and costs nothing here.

**The catalogue.** Expected gains are hypotheses, not measurements. Each one is measured as soon as the naive compiler (M2) works, and the results are tracked in `optimizations.md` with the columns *prerequisite*, *measured gain* and *status*.

| # | Optimization | What it does | Expected gain |
| --- | --- | --- | --- |
| A | Static frames in the direct page | Locals, parameters and temporaries get fixed direct page slots, shared between functions that are never active at the same time. Possible because the call graph is complete and has no recursion. No stack frames, no stack-relative access. | Very high; probably the largest single gain |
| B | Width analysis (8/16 bit) | Choose the smallest width per value, track `REP`/`SEP` as processor state, minimise switches | High, especially for `uint8_t`-heavy code |
| C | Inlining and calling convention | Inline small functions, pass parameters in A/X/Y or the direct page, `JMP` instead of `JSR`+`RTS` for tail calls | High with many small helpers |
| D | Data layout | Split arrays of structs into one array per field (index in X/Y), or point D at the current object | High in the loops over bubbles and enemies |
| E | 8.8 fixed point | `>>8`, `<<8` and comparisons of the integer part as byte accesses instead of shifts | Medium to high; the game uses 8.8 everywhere |
| F | Loops | Counter in X/Y, count down to zero, hoist invariant code, unroll small loops (by hint) | Medium |
| G | Branches | Branch on flags instead of materialising 0/1, common path falls through, correct branch distances | Medium |
| H | Standard optimizations | Constant folding and propagation, dead code removal, common subexpressions | Medium; mostly a prerequisite for A–G |
| I | Multiply and divide | Constants as shifts and adds, hardware multiplier with useful work during its latency, division by powers of two with correct signed rounding | Medium, depends on the code |
| J | Banks and placement | Keep the data bank register right, 16-bit instead of 24-bit addresses, hot code in FastROM | Small to medium |
| K | Peephole | Remove redundant loads and stores, `STZ`, `INC`/`DEC` on memory | Small, but everywhere |
| L | `switch` as jump table | `JMP (abs,X)` | Small overall, large locally |
| M | Copy and fill | `MVN`/`MVP` or DMA instead of loops | Large locally |
| N | Values in X/Y | Keep values in the index registers within a block | Small to medium |

**Overall expectation:** roughly a factor of 3–6 over our own naive code generator. Compared with Calypsi we promise nothing until it is measured. The Calypsi investigation tells us where its cycles go, and that decides which rows come first.

## Testing

Correctness is the biggest risk: a miscompile does not crash, it makes a bubble pass through a wall in round 37, sometimes. Testing is therefore built in from the first day, in four layers:

| Layer | What | Speed |
| --- | --- | --- |
| Unit tests | Each compiler pass on its own, at IR level | Milliseconds |
| Execution tests | Small C programs compiled with gcc on the PC and with dampf; the results must match | Seconds |
| Random programs | A generator produces thousands of random programs within the subset; each is compared against gcc | Minutes, e.g. nightly |
| Replays | The whole of Blubber Blasen with its deterministic replays and a checksum of the game state per frame; also cycles per frame as the benchmark | Minutes |

Supporting pieces:

- **A 65816 CPU emulator in Java** (CPU only, no graphics or sound) runs the execution and random-program tests inside the JVM, without starting Mesen, and **counts cycles**, including the different memory speeds. Every test can then also report whether code got faster or slower. The emulator itself is validated against public per-instruction CPU test suites.
- **Naive versus optimized:** the naive code generator stays as a reference. Every optimized build is compared against it; a difference points to the optimization that caused it.
- **Performance tracking:** cycle counts of the benchmarks are recorded per commit, so a slowdown is noticed.

## Milestones

| Milestone | Goal | Done when |
| --- | --- | --- |
| M0 | Define the subset | Blubber Blasen's `src/game` is scanned; `subset.md` lists what is allowed, what is not, and what the game code has to change |
| M1 | End to end | A small function is compiled, assembled with ca65, linked with ld65, runs automatically in a test, and Mesen shows its C source while stepping |
| M2 | Naive but complete | All of Blubber Blasen compiles, all replays pass, cycles per frame are measured and compared with Calypsi |
| M3 | The big levers | Optimizations A–D. Goal: faster than Calypsi |
| M4 | Fine tuning | Optimizations E–N until the frame budget is met |

## Risks

- **Miscompilation.** Mitigated by the four test layers, the naive reference and the replays as oracle.
- **Different behaviour on PC and SNES.** Mitigated by fixed-width types only, exact promotion rules and the differential tests against gcc.
- **Mesen cannot show the C source with our debug output.** Checked in M1, before the code generator is settled.
- **Scope creep.** The compiler could become the project and stall the game. Mitigated by running it in parallel and by milestones that are measured against Blubber Blasen.
- **Gains smaller than hoped.** Each optimization is measured; the catalogue is re-prioritised after M2.

## Open questions

- ~~Build tool: Gradle or Maven?~~ Gradle (Kotlin DSL, with the wrapper), decided 2026-11-17.
- Repository hosting and license (the compiler should be open source like the game).
- Which debug and symbol formats Mesen reads exactly, and how it shows locals that live in fixed direct page slots.
- ~~How whole ROMs run headless in tests~~: Mesen 2 has a test runner mode (`Mesen.exe --testrunner rom.sfc script.lua`). Blubber Blasen's `tools/snesbench` already uses it to measure compilers tick by tick, with checksums against the PC.
- What the Calypsi investigation finds, and what that means for the order of the optimizations. First numbers are in `docs/optimizations.md`: Calypsi needs 2.72 frames per tick during play, so the 0.6-frame goal is about 4.5 times faster than Calypsi.
- How Blubber Blasen's stage 2 platform API maps onto dampf intrinsics.

## Relation to Blubber Blasen

Blubber Blasen is dampf's first user and its main test case. Its deterministic replays are the oracle for correctness, and its busiest scene is the benchmark for speed. Both projects run in parallel: the game keeps going on the PC, and dampf catches up until it can build the SNES version.

dampf works on a snapshot of the game's code in `examples/blubberblasen` (refreshed with `tools/update_game_snapshot.sh`), so the compiler is never broken by a change in the game that it cannot handle yet. What dampf finds worth changing in the game is collected in `docs/game-findings.md` and passed on to the game. When dampf is finished, a guide (`docs/game-guide.md`) explains how to write game code that dampf compiles well.
