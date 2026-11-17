# dampf – Additional design notes

Date: 2026-11-17 · Status: agreed in discussion, not built yet · Extends: `kickoff.md`

This document collects the design decisions and ideas that came up after the kickoff. It is meant to be read on its own: every term is explained, and where it builds on the kickoff, that is said. In short, it covers:

1. How close dampf can get to hand-written assembly, and why
2. Hints: telling the compiler what a human assembly programmer would know
3. Reference assembly benchmarks: making the gap to hand-written code measurable
4. Knowing the machine: optimizations that only work because the target is one fixed piece of hardware
5. Exact timing: computing the worst-case run time of functions, and cycle budgets the compiler enforces
6. Intermediate representations: why dampf has three levels, and what each one does
7. What this changes in the milestones, and the open questions

Background from the kickoff, in one paragraph: dampf is a compiler for a subset of C, written in Java with an ANTLR parser. It targets the WDC 65816 CPU as used in the Super Nintendo (the Ricoh 5A22), and nothing else. It always compiles the whole program at once, emits commented ca65 assembly, links with ld65 and is debugged in the Mesen emulator at C source level. The same C source also compiles on the PC with gcc or clang and must behave bit-identically there; that is what makes it testable. Its first user is the game Blubber Blasen, whose deterministic replays (recorded input, checksum of the game state per frame) serve as the correctness oracle.

---

## 1. How close can dampf get to hand-written assembly?

### Expectation

| Code generator | Cycles compared with good hand-written assembly |
| --- | --- |
| 816-tcc (PVSnesLib) | probably 5–10 × (observed: very slow, needed workarounds) |
| Calypsi | unknown; currently being investigated |
| dampf, naive code generator (milestone M2) | like a simple compiler, clearly slower than hand-written code |
| dampf after the optimizations, hot loops, without hints | about 1.2–1.5 × |
| dampf after the optimizations, hot loops, with hints (section 2) | about 1.1–1.2 ×, in places equal |
| dampf, the rest of the code | equal to or better than what a human would write there in practice |

All of these are estimates, not measurements. Section 3 describes how they get replaced by measured numbers.

What matters in the end is not the distance to perfect assembly but whether the game logic fits its budget: at most 60 % of a frame in the busiest scene (see section 5 for the frame numbers).

### Where a gap remains, and why

A skilled assembly programmer knows things about the program that are not in the C code, and uses them:

1. **Data representation chosen for the CPU.** For example a state flag in bit 7, so that `BIT` followed by `BMI` tests it without a compare, or a lookup table in ROM instead of a calculation.
2. **Conventions across many functions.** For example "X always holds the index of the current bubble during the update phase", or "this whole routine runs in 8-bit mode".
3. **Flags as data.** The carry flag as a yes/no return value, the overflow flag after `BIT` as a second test bit.
4. **Instruction-level tricks.** `XBA`, moving the direct page register with `TCD`, `PEI`/`PEA` for fast copies, stack tricks for DMA tables.

Most of this can be closed with hints (section 2) and optimization rules. What cannot be closed fully: a human sometimes invents a new trick on the spot, while dampf only applies the tricks it has been taught. Section 3 describes the loop that keeps teaching it.

### Where dampf can beat a human

- **Consistency.** People hand-optimize the two or three hottest routines and write the rest pragmatically. dampf optimizes every line equally.
- **Whole-program direct page allocation.** Fixed direct page slots for thousands of local variables, shared between functions that are never active at the same time. Almost nobody does this cleanly by hand.
- **Re-optimizing after every change.** Hand-written assembly is rarely re-tuned after a design change. dampf re-optimizes on every build.
- **Aggressive inlining.** People keep functions separate so the code stays maintainable. dampf does not have to maintain its output.
- **Exhaustive search** for short sequences (superoptimization, see section 2).

---

## 2. Hints

### The idea

Most tricks of an assembly programmer rest on knowledge: "this value is always below 16", "these two pointers never overlap", "this branch is rare". Knowledge can be written down. dampf lets the programmer state it in the C source as hints, so the compiler knows what the human would know.

Hints are macros. In the PC build they expand to plain C (see the assertion rule below). In the dampf build they expand to built-ins the dampf parser recognises (for example `__dampf_range(...)`). The source stays valid C, and one preprocessor (`gcc -E`) serves both builds, so both builds see exactly the same code.

### Catalogue (working names)

| What a human knows | Hint in C | What dampf does with it |
| --- | --- | --- |
| "i is always below 16" | `DAMPF_RANGE(i, 0, 15)` | 8 bit instead of 16, no range checks, index directly in X, arrays can be aligned so indexed reads never cross a page (section 5) |
| "these pointers never overlap" | `restrict` | Values stay in registers instead of being reloaded after a store through a pointer |
| "this branch is rare" | `DAMPF_UNLIKELY(cond)` / `DAMPF_LIKELY(cond)` | The common path falls through without a jump. Often unnecessary once profile data from replays is used (section 4). |
| "this loop runs at most 16 times" | `DAMPF_TRIPS(16)` | Unrolling, an 8-bit counter, and a loop bound for the timing analysis (section 5) |
| "X holds the current bubble during the whole update" | `DAMPF_PIN_X(cur)` for a block or a module | The value stays in X, also across function boundaries |
| "store this array of structs as one array per field" | `DAMPF_SPLIT` on the array | One array per field, accessed as `lda bub_x,x` |
| "this function only ever gets 256 different inputs" | `DAMPF_TABLE` on the function | dampf evaluates the function for every input **at compile time** and places the results as a table in ROM. At run time the call becomes one indexed read. |
| "this variable belongs in the direct page" | `DAMPF_DP` | A fixed direct page slot |
| "this function must finish within N cycles" | `DAMPF_BUDGET(n)` | A compile error if its worst case is higher (section 5) |

`DAMPF_TABLE` shows how far this goes: with a known input range, the compiler can do what a human would do with a hand-made table or a generator script.

### Representation decisions stay in C

Some knowledge is not a fact about the program but a decision about how it represents things, such as using bit 7 as a flag. Those decisions are written in C by the programmer, and dampf recognises the patterns. If a state is defined as `0x80`, then `if (flags & 0x80)` becomes `BIT` + `BMI` without a compare. The division of work: the programmer decides *what* the code represents; dampf takes care of the instructions.

### Rule: every hint is a checked assertion on the PC

A wrong hint produces wrong code, silently. If `i` ever reaches 16 despite `DAMPF_RANGE(i, 0, 15)`, the SNES build misbehaves without any error.

Therefore: **every hint that states a fact is checked in the PC and test builds.**

- `DAMPF_RANGE(i, 0, 15)` asserts `0 <= i && i <= 15` on the PC.
- `DAMPF_TRIPS(16)` counts loop iterations on the PC and asserts the count stays within 16.
- `restrict` can be checked on the PC by an address-overlap assertion where practical.
- `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP` and `DAMPF_TABLE` do not state facts, they request a transformation; they need no assertion. (`DAMPF_TABLE` does require the function to be pure; the subset checker verifies that.)

The replays run with these assertions enabled, so a wrong hint is caught on the PC before it can do damage on the SNES.

### Limits of hints

1. **Knowledge alone is not enough.** dampf must also know the matching trick. That is what the loop in section 3 is for.
2. **Too many hints make code unreadable.** Hints belong only where measurement shows the code is hot. Most of the code should carry none.

### Superoptimization for very short sequences

For short, very hot instruction sequences (about 5–8 instructions), dampf can search instead of following rules: generate candidate instruction sequences up to a given length, run each in the cycle-counting emulator (section 7, milestones), and keep the one that produces the same result in the fewest cycles. For sequences with 8-bit inputs, equivalence can even be checked exhaustively (256 inputs for one byte, 65,536 for two). This sometimes finds sequences no human would think of. It is an optional, late optimization.

---

## 3. Reference assembly benchmarks

### What

3–5 hot functions of Blubber Blasen are written by hand in 65816 assembly as **reference versions**. They are not the game's code; they are the yardstick. Candidates (to be confirmed by the Calypsi investigation and by profiling): bubble movement and bubble-to-bubble pushing, the chain reaction when bubbles pop, collision against tiles, and building the sprite table (OAM).

### How they are used

1. Each reference function and dampf's version of the same C function run in the cycle-counting emulator on the same inputs.
2. The result is a ratio per function: dampf needs *x* times the cycles of the reference. This ratio is tracked over the milestones, so "how close are we" is a number, not a feeling.
3. Where the reference is faster, we look at why. The answer becomes either a new hint (section 2) or a new optimization rule. The gap shrinks step by step.
4. Escape hatch: if dampf stays too far behind on one function despite all efforts, the reference version already exists. dampf can then link that hand-written function in its place, with a declared calling convention and declared direct page usage, so the rest of the program and the timing analysis still know what it does. This is only used when measurement demands it.

---

## 4. Knowing the machine

A PC compiler has to produce code for thousands of different processors, under an operating system, next to other software. dampf knows exactly where its code runs: a Ricoh 5A22 at 60 Hz (NTSC), with 128 KB of WRAM, no operating system and no other software. Like on modern consoles, this fixed target allows optimizations that are impossible on general hardware.

| Knowledge | Optimization |
| --- | --- |
| **Exact cost of every memory access.** The SNES counts in master cycles (21.477 MHz on NTSC). A CPU memory access costs 6 master cycles for FastROM and most I/O registers, 8 for WRAM and SlowROM, 12 for a few old I/O registers. | dampf's cost model is exact, not estimated. A surprising consequence: constant tables are read *faster* from FastROM than from WRAM, so hot code and constant tables belong in FastROM. Because every instruction byte is fetched from ROM, code size counts directly in cycles. |
| **Two hardware multipliers.** The CPU's unit (8 × 8 bit, unsigned, result after 8 CPU cycles; it also divides 16 / 8 bit, result after 16 cycles) and the PPU's multiplier (16 × 8 bit, signed, result available immediately). The PPU multiplier is unavailable only while Mode 7 is being drawn. | Blubber Blasen does not use Mode 7, so dampf always has a multiplier with an immediate result. For each multiplication it picks the cheapest option: shifts and adds for constants, the PPU multiplier for signed 16 × 8, the CPU unit with useful work filled into its latency where that is cheaper. |
| **DMA units** | Large `memset` / `memcpy` become DMA, for example clearing WRAM by DMA from a constant byte in ROM to the WRAM data port (`$2180`). dampf knows the costs exactly and therefore the size from which DMA pays off. |
| **Fixed addresses, no operating system** | Every address is known at compile time. `array[3]` is a fixed address, not a pointer plus an offset. No foreign code expects registers to be preserved, and there is no external calling convention to follow. |
| **We control the interrupts** | dampf sees which registers and variables the NMI and IRQ handlers touch. It saves only what is needed and gives the handlers their own direct page. |
| **No dynamic memory** | Total memory use and the **maximum stack depth** are known at compile time. dampf reports them exactly, and a stack overflow cannot happen. |
| **Real game situations are recorded** (replays) | Profile-guided optimization, see below. |

### Profile-guided optimization with the replays

Blubber Blasen's replays are recordings of real play. Running them in the cycle-counting emulator shows how often each branch is taken and which functions are hot. This profile data is fed back into dampf:

- the common side of each branch falls through without a jump,
- hot functions are inlined more readily,
- hot code and its tables are placed in FastROM,
- optimizations that trade code size for speed are spent where they matter.

Modern compilers do this too, but rarely with such representative data. Many `DAMPF_LIKELY`/`DAMPF_UNLIKELY` hints become unnecessary.

### What this means for the expectation

This does not close a new gap to hand-written assembly: a good SNES programmer knows all of this too. It makes sure dampf applies the knowledge **everywhere and always**, not only in the three routines a human optimizes by hand. Exact timing (section 5) and profile-guided optimization go beyond what is practical by hand.

---

## 5. Exact timing and cycle budgets

### Why the 65816 can be timed exactly

The 65816 has **no cache, no pipeline and no branch prediction**. Every instruction takes a fixed, documented number of cycles, which depends only on a few known conditions. That makes it far easier to time exactly than any modern processor.

dampf counts internally in master cycles, because that is the only unit in which memory speed differences are exact. Reports show both master cycles and CPU cycles.

### Level 1: exact to the cycle, per path

For a piece of code without branches, dampf computes the run time exactly. Every condition that adds cycles is known to it:

| Extra cost | When | Known to dampf? |
| --- | --- | --- |
| 16-bit instead of 8-bit operand | Accumulator or index width (M and X flags) | Yes: dampf tracks the processor state at every instruction |
| Direct page access | The low byte of the direct page register D is not zero | Yes: dampf aligns D so this extra cycle does not occur |
| Memory region | FastROM, SlowROM, WRAM, I/O | Yes: every address is fixed at compile time |
| Indexed read across a page boundary | With an 8-bit index register, base + index crosses a 256-byte page. (With a 16-bit index, and for indexed writes, the extra cycle is always paid.) | Yes, **if dampf aligns the arrays**: base address fixed, index range known from `DAMPF_RANGE`. Then arrays can be placed so no page is crossed: the cycle disappears *and* the cost stops depending on the data. |
| Branch taken or not | Depends on data | See level 2 |

### Level 2: exact bounds for branches and loops

Once data decides which path runs, there is no single number any more but a **best case and a worst case**. dampf computes both exactly:

- **Function by function, bottom-up over the call graph.** The subset has no recursion, so the call graph has no cycles. The cost of a call is the callee's already computed bound plus the call overhead.
- **Loops need a bound.** Loops with a constant trip count are bounded automatically. Other loops need `DAMPF_TRIPS(n)`, which is checked as an assertion on the PC (section 2). A function with an unbounded loop gets the worst case "unbounded".
- **Structured code.** Bounds are computed on the structure of the code (sequence, branch, loop), which keeps the analysis simple and exact. Whether the subset allows `goto` at all, or only in restricted form, is an open question (section 7); unrestricted `goto` would need a more general analysis.
- **Pessimism.** The worst case may count paths that never occur together in practice (two branches that are never both taken). The report always shows the costliest path, line by line, so a human can judge it. Annotations that exclude impossible path combinations can be added later if needed.

The replays in the emulator add the third view: the **measured** cost in real game situations. Static bounds and measured values together give the full picture, and comparing the static worst case with the measured maximum shows how pessimistic the bound is.

### Level 3: what happens outside the CPU

Some time is lost without any instruction causing it. All of it is fixed and can be accounted for at the level of a whole frame:

- **DRAM refresh:** the CPU is halted for 40 master cycles on every scanline, about 3 % of the time.
- **HDMA** (if per-line effects are used): a small fixed cost per scanline for each active channel.
- **Interrupts:** NMI (start of vertical blank) and IRQ arrive at known times, and dampf knows the cost of their handlers.

A function's own bound excludes interrupt time. Frame-level budgets add the handlers' costs.

### Frame numbers

- One NTSC frame: 262 scanlines × 1364 master cycles ≈ 357,000 master cycles, which is roughly **45,000–55,000 CPU cycles** depending on the mix of FastROM and WRAM accesses.
- Vertical blank: scanlines 225–261, roughly **6,000–8,000 CPU cycles**. Everything that talks to the graphics hardware (sprite table via DMA, tiles, palettes) must fit in here.
- Target from the kickoff: game logic in the busiest scene uses at most 60 % of a frame.

### What dampf produces from this

**1. Cycle counts in the assembly comments.** Every instruction gets its cost, every C line the sum. (The numbers below only illustrate the format; dampf computes the real ones.)

```
; bubble.c:142   b->x += b->vx;                       [ 13 cyc ]
        ; M=16 X=8  D=&bubbles[i]
        lda z:BUB_VX        ; A = vx (8.8)              4
        clc                 ;                           2
        adc z:BUB_X         ; A = x + vx                4
        sta z:BUB_X         ; x = A                     3
```

For code with branches, the line shows best and worst case.

**2. A timing report per function:** best case, worst case, and the costliest path. From the same data, a view per C source line: where in the source do the cycles go?

**3. Cycle budgets checked by the compiler.**

```c
DAMPF_BUDGET(6800)
void vblank_handler(void) { ... }
```

- If the function's worst case exceeds its budget, **the build fails**. The error message shows the worst case, the budget, and the costliest path with the cost of each C line on it, so it is clear where to cut.
- If the worst case is above a warning threshold (for example 90 % of the budget), the build warns.
- A function with a budget whose worst case is "unbounded" (a loop without a known bound) is a build error: a budget can only be guaranteed for bounded code.
- In the PC build `DAMPF_BUDGET` expands to nothing. In the emulator test builds, the measured cycles of every budgeted function can additionally be checked against the budget during the replays.

Typical budgets in a SNES game: the vertical blank handler (must fit into vertical blank), the per-frame update of the game logic (the 60 % target), and any IRQ handler that runs at a fixed scanline. Without this check, an overrun is only noticed when the screen flickers or the game slows down; with it, the build refuses the change that causes it.

---

## 6. Intermediate representations

### Why more than one

High-level optimizations need the structure of the program: loops, structs, arrays, calls. Low-level optimizations need the details of the 65816: registers, processor state, memory regions, cycle costs. One representation can do both only badly. Modern compilers therefore use several levels: Swift goes from SIL to LLVM IR, Rust from MIR to LLVM IR to LLVM's machine IR. dampf uses **three levels**, plus the checked syntax tree in front.

```
C source
  → AST (checked)   subset enforced, types resolved, error messages
  → HIR             C structure and meaning: loops, structs, arrays, hints
  → MIR             SSA form, explicit widths, no structs, still machine-independent
  → LIR             65816 instructions, processor state, direct page slots, cycles
  → ca65 assembly
```

(Note on names: dampf's MIR is the *mid-level* IR, comparable to LLVM IR. It is not Rust's MIR and not LLVM's machine IR; dampf's LIR plays the role of LLVM's machine IR.)

### The three levels

| Level | Comparable to | What it represents | What happens there |
| --- | --- | --- | --- |
| **HIR** (high-level) | Rust MIR, Swift SIL | A control flow graph that still has struct fields, array indexing, calls and intrinsics. C's integer promotions are made explicit here, so the meaning of every operation is unambiguous. | Call graph and recursion check; inlining; recognising loops; `DAMPF_SPLIT` (arrays of structs into one array per field); `DAMPF_TABLE` (evaluating functions at compile time) |
| **MIR** (mid-level) | LLVM IR | SSA form (every value is assigned exactly once). Types `i8`, `i16`, `i24`; pointers as `ptr16` (within the current data bank) or `ptr24` (any bank). Memory as symbol plus offset. | Constant propagation, dead code removal, common subexpressions, hoisting invariant code out of loops, **narrowing widths** using value ranges (16 → 8 bit where provably identical), recognising 8.8 fixed-point patterns as byte accesses |
| **LIR** (low-level) | LLVM machine IR | Real 65816 instructions on virtual locations, with the processor state (M, X, D, DB) as part of the representation | Instruction selection; placing `REP`/`SEP` optimally; direct page slot allocation; assigning A, X and Y; peephole optimization; branch distances; **counting cycles and computing bounds** (section 5) |

### What runs through all levels

- **Hints as assumptions.** `DAMPF_RANGE(i, 0, 15)` becomes an `assume` operation (similar to LLVM's `llvm.assume`) that the range analysis in MIR uses. In the PC build the same condition is the assertion from section 2.
- **A source position on every operation.** Every operation carries its file and line, and every pass must preserve them. When two operations are merged, the result keeps one of the positions. Only this makes the assembly comments and the C source view in Mesen possible.

### What this brings for testing

1. **Every level has a text format** that can be printed *and parsed back*. Tests for a single pass then look like LLVM's: IR text in, run the pass, compare with the expected IR text. Such tests are precise and easy to generate in large numbers.
2. **Every level has a verifier** that checks its rules after every pass. In MIR: do widths match, is every value defined before it is used? In LIR: is the processor state consistent at every instruction? A broken pass is caught immediately, not three levels later as a wrong result.
3. **Interpreters for HIR and MIR.** The whole program can be executed on each level and the result compared with gcc on the PC and with the emulator. If HIR is still right and MIR is not, the bug is in the lowering between them. This narrows bugs down much faster than a wrong result at the end of the chain. The HIR interpreter is also an independent definition of what the program means, next to gcc.

### Deliberately not more than three

Every level costs a lowering step, a verifier, a text format and tests. Three levels cover the three kinds of knowledge: the structure of C, data flow, and the machine. A further level is only added if an optimization demonstrably fits nowhere cleanly.

---

## 7. Effect on the milestones, and open questions

### Where the new parts fit into the kickoff milestones

| Milestone | Additions from this document |
| --- | --- |
| M0 – define the subset | Decide on `goto` (forbidden, or restricted to keep code structured for the timing analysis). Define the hint macros and their PC assertions. |
| M1 – end to end | The Java 65816 CPU emulator counts **master cycles** with the exact memory speeds. Cycle counts appear in the assembly comments from the start. All three IR levels exist with text format and verifier, even if their passes are still trivial. |
| M2 – naive but complete | Measured cycles per function from the replays. First reference assembly function, so the first dampf/reference ratio exists. HIR and MIR interpreters run in the tests. |
| M3 – the big levers | Static best/worst case per function and the timing report. `DAMPF_BUDGET` checked at build time. Remaining reference functions. |
| M4 – fine tuning | Profile-guided optimization from the replays. Superoptimization of short hot sequences. `DAMPF_TABLE`, `DAMPF_PIN_X` and the other transforming hints. |

### Open questions

- `goto`: forbid it entirely, or allow a restricted form that keeps the code structured?
- How to express impossible path combinations, should the worst-case bounds turn out too pessimistic in practice.
- How `restrict` is checked on the PC in practice, and whether a dampf-specific no-alias hint is clearer than `restrict`.
- Which functions become reference benchmarks: decided once the Calypsi investigation and the first profiles show where the cycles go.
- How a linked-in hand-written assembly function declares its calling convention, direct page usage and cycle cost, so the timing analysis can include it.
- Whether HDMA will be used (per-line effects such as gradients), since it changes the frame-level budget.