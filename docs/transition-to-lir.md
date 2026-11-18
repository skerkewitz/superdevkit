# Transition to LIR

This document plans how dampf's back end gets from where it is today to where
[architecture.md](architecture.md) wants it: a slim code generator from MIR, a low-level IR (LIR)
of real 65816 instructions with its own passes, and finally machine code written by dampf
itself, without ca65 and ld65. The last part is milestone M6 in [roadmap.md](roadmap.md) and
comes last on purpose.

The plan has one rule above all others: every step leaves dampf working, and every step is
checked by a test that would fail if the step broke something.

## Where we are

`MirCodeGen` (about 2,700 lines) turns MIR into ca65 assembly text. It does several jobs at
once, all while writing text:

- instruction selection: which 65816 instructions compute each MIR instruction;
- tracking what A, X and Y hold (`RegState`, `entryIn`, `arriving`), so loads that are already
  done are left out;
- leaving out stores whose place is never read (`skipStore`);
- byte masks (`findMasks`), loop counters in X (`findXCounters`), indexes in Y (`findYIndexes`),
  values passed on in A (`findForwards`);
- choosing `BRA` or `JMP` and short or long conditional branches;
- the accumulator width (`REP`/`SEP #$20`);
- source line and debug directives for Mesen.

Several of these depend on each other, so `generate()` writes each function several times (up
to 14 passes, and a second attempt if the first ends inconsistent): every pass learns which branches reach and what the registers hold at block entries, and the next pass
writes the function again with that knowledge. The second attempt writes it without the
register assumptions.

`AsmWriter` collects the text and, next to it, a list of `Item`s (mnemonic, operand, cycles) for
the static timing analysis (`Timing`). It also follows the accumulator width and the byte
position of each label, which the branch passes use. So a small LIR exists already, but as a
side product of the text.

`NaiveCodeGen` (about 1,600 lines) writes text the same way, from the AST. `RomBuilder` runs
ca65 on each module and on `runtime.s`, links twice with ld65 (once to measure the segments,
once to pack them into 32 KB banks), and fixes the header checksum. ld65 writes the `.dbg` file
that Mesen reads.

## Where we want to be

```
MIR → instruction selection → LIR → LIR passes → encoder → linker → .sfc + .dbg
                                         └──────→ printer → .s (for people, and for ca65 in tests)
```

**Instruction selection** turns each MIR instruction into LIR instructions and makes only the
decisions that need MIR: which value goes into X or Y, whether a byte needs a mask, which
operation fits. It writes each function once.

**LIR** holds each function as basic blocks of 65816 instructions, plus the data. Each instruction
has an opcode, an addressing mode and an operand (a number, a symbol plus an offset, or a
direct page slot). The processor state (M flag, and later X flag, D and DB) is part of the
representation: every instruction knows the width it runs in. LIR has a text format that is
valid ca65 source, a parser for it, and a verifier.

**LIR passes** do what `MirCodeGen` does on the side today, each in its own class:

- register contents across blocks (a data flow analysis instead of repeated writing), and with
  it removal of redundant loads and stores;
- `REP`/`SEP` placement across blocks (rows 3 and 5 of the optimization list);
- peephole rules;
- branch relaxation: short branches where they reach, long forms where they do not;
- cycle counts and bounds for the timing report, on the final code.

**Encoder and linker** (M6) turn LIR into bytes, place the segments into banks, resolve symbols,
write the ROM and the `.dbg` file. ca65 and ld65 stay installed for development, as the oracle
the tests compare against.

What the user sees does not change: the same ROM, the same `.s` files with cycle columns, the
same debugging in Mesen. What changes is that each job has its own place and its own tests, and
that new optimizations of the generated code have somewhere to go.

## How we keep from breaking things

The test suite already catches wrong code: execution tests and random programs against gcc,
replays and the game's benchmark against the PC. A refactoring can still slip through those in
two ways: it can make the code slower without making it wrong, and it can change code that no
test runs. Two more checks close that gap.

**Identical output.** Before the first step, a test records a hash of the assembly that dampf
writes for every execution test, a fixed set of random programs and the whole game
(`BackendOutputTest`, new). A step that is meant to move code without changing what it does
must leave every hash as it was. If a hash changes, the step is not a pure move, and the diff of
the `.s` file shows exactly where. Most steps of phases A and B are of this kind.

**Measured changes.** A step that is meant to change the code (a new pass, a pass that replaces
an older and weaker one) gets new hashes, but only together with the game's numbers: cycles per
tick from `tools/bench_game.sh`, code size, and the timing report. The numbers go into
[optimizations-log.md](optimizations-log.md) as usual. A step never moves code and changes it at
the same time; that would make a difference in the output impossible to explain.

In addition, every step keeps the usual rule: `./gradlew build` is green, and the replays pass
with `-Preplays` before the step is committed.

## Phase A: LIR as data (output stays identical)

**A1. Freeze the output.** Write `BackendOutputTest` with the hashes of today's assembly. It is
the safety net for everything after it.

**A2. LIR classes.** `dampf.lir`: `LFunction` with basic blocks (`LBlock`), `LInstr` (opcode,
addressing mode, operand, comment, source position), data items (`.byte`, `.word`, `.res`,
addresses with bank byte) and directives that carry meaning (`.segment`, `.dbg line`,
`.export`). The opcode and mode come from `Cycles65816`, which already knows every opcode, its
length and its cycles. A builder cuts the flat stream that `AsmWriter` receives into blocks:
every label starts one, every branch, jump or return ends one (see the decisions below).

**A3. `AsmWriter` builds LIR.** Its methods (`ins`, `label`, `raw`, `sourceHeader`) keep their
signatures but add LIR objects; a printer turns the LIR into exactly the text written today,
cycle column included. Neither code generator changes. `BackendOutputTest` must stay unchanged.
The `raw` lines are the hard part: each kind of raw line gets its own LIR item, until no
unparsed text is left.

**A4. Timing and branches read LIR.** `Timing`, `branchesReach` and `labelAt` work on LIR
instead of `AsmWriter.Item`. The cycle column comes from `LInstr.cycles()`, and the cost of
each C line from `Timing` instead of `AsmWriter`'s running sum (see the decisions below).
Output unchanged.

**A5. Parser and verifier.** A parser for LIR text, and a round trip test: parse every `.s`
file dampf writes and print it again, the text must be identical (as `MirTextTest` does for
MIR). The verifier checks that every label is defined once, every branch target exists, every
opcode has the addressing mode it is written with, and that the width each instruction assumes
matches the `REP`/`SEP` before it on every path. The verifier runs after every code generation
in the tests.

At the end of phase A nothing has changed for the user, and dampf has a LIR it can print, parse
and verify.

## Phase B: slim the code generator

Each step takes one job out of `MirCodeGen` and gives it its own place. The order goes from
jobs that are only bookkeeping to jobs that interact with selection.

**B1. Split the file.** Move instruction selection for arithmetic, compares and branches,
memory, and calls into their own classes, with the shared state in one object. Pure move:
hashes unchanged.

**B2. Branch relaxation as a LIR pass.** Today the passes in `generate()` rewrite the function
to learn which branches reach. A relaxation pass on LIR does the same on the finished code:
start with short branches everywhere and widen those that do not reach until nothing changes.
This has to give the same code as today; if it gives better code (the current passes can stop
early), the step is split: first the pass that reproduces today's result, then the improvement,
measured.

**B3. Register contents as a LIR analysis.** What A, X and Y hold at each block start, computed
by a data flow analysis over LIR rather than by writing the function again. Selection keeps
asking "does A already hold this?", but the answer comes from the analysis of the previous
selection round, and in a later step from a pass that removes the redundant loads after
selection. This is the step that removes the repeated writing from `generate()`. It changes the
most and is done in small parts: first the analysis next to the existing tracking with a check
that both agree on every block of every test program, then the switch.

**B4. Dead stores as a LIR pass.** The `skipStore` mechanism becomes a pass that removes stores
to frame slots that no path reads afterwards.

**B5. `NaiveCodeGen` on LIR.** It already writes through `AsmWriter`, so after A3 it produces
LIR. It stays a separate, simple generator: it is the reference the optimized code is compared
against, and it must not share the passes it is meant to check.

After phase B, `MirCodeGen` selects instructions and nothing else, and the following
optimizations have their place in LIR, each as its own measured step: `REP`/`SEP` across blocks,
8-bit index registers (`SEP #$10`) for page-aligned arrays, peephole rules, and later the
superoptimization of short sequences from M4.

## Phase C: machine code without ca65 and ld65 (M6)

This phase starts only when M5 is done and everything else runs to our satisfaction. It changes
nothing in the code dampf generates, only who turns it into bytes.

**C1. Encoder.** LIR to bytes, with the opcode table of `Cycles65816`. Symbols stay open as
relocations: a 16-bit address, a 24-bit address, a bank byte, the low word of an address.
Test: for every function of every test program and of the game, the bytes match what ca65
produces from the printed text.

**C2. Assembler for the runtime.** `runtime.s` (about 300 lines) and hand-written functions such
as `reference/level_solid_px.s` are ca65 source. The LIR parser from A5 learns the few things
they use beyond dampf's own output: `.proc`, `.if`/`.else`/`.endif`, `.a`/`.i` width directives,
`.smart`, `.loword` and `^`. That is a small subset of ca65, documented as such; anything else is
an error with a clear message. Test: the same bytes as ca65 for these files.

**C3. Linker.** Segments into banks, symbol resolution, the ROM image, padding and header
checksum. Because dampf knows every segment's size, it packs the banks directly; the two links
of `RomBuilder` become one step. Test: the `.sfc` is byte-identical to the one ca65 and ld65
build, for every execution test, the random programs and the game.

**C4. Debug file.** dampf writes the `.dbg` file itself, in ld65's format, which Mesen reads.
Test: dampf's file and ld65's file are parsed and compared (files, lines with their address
ranges, symbols, C functions and locals). A check by hand in Mesen, as in M1, closes the step.

**C5. Switch.** `dampf build` uses its own encoder and linker by default; `--assembler ca65`
keeps the old path. The tests build every program both ways and compare the ROMs. ca65 and ld65
leave the list of things a user needs, and stay in the list for developing dampf.

## What each phase gives

| Phase | For the user | For dampf |
| --- | --- | --- |
| A | nothing visible | LIR with text format, parser and verifier; a test that catches any unplanned change in the output |
| B | faster code from the optimizations that need LIR | a code generator that only selects instructions; each job tested on its own; each function written once |
| C | dampf needs only Java (with M5's preprocessor) | ca65 and ld65 only in the tests, as the oracle |

## Decisions

**LIR is made of basic blocks with explicit successors** (2026-11-18). A basic block is straight
code: it is entered only at its start and left only at its end. Every label starts a block,
every branch, jump or return ends one. Each block knows its successors, including the block it
falls through to, its place in the layout order (where it lies in the ROM), and the MIR block it
comes from (for `Timing` and the loop bounds). This is how LLVM's `MachineBasicBlock` works.

The alternative was a flat list of instructions and labels, as `AsmWriter` builds today, with
the control flow computed when a pass needs it. That would have made phase A a little simpler,
but every pass of phase B (register contents, dead stores, `REP`/`SEP` across blocks) is a data
flow analysis over blocks, and with a flat list each of them would rebuild the graph or work on
a stale one. Phase A is done once; the passes of phase B and later keep coming. One block per
MIR block was ruled out as well: the small branches the code generator writes inside a MIR
block (shifts, copies, skips over a few instructions) would disturb the analyses as they
disturb `Timing` today. With real basic blocks those special cases in `Timing` go away.

The extra risk in phase A is the builder that cuts `AsmWriter`'s stream into blocks. It sits
behind `BackendOutputTest`: the printer must turn the blocks back into the same text.

**Cycles of an instruction belong to the instruction, cycles of a C line belong to `Timing`**
(2026-11-18). `LInstr.cycles()` computes the cycles from opcode, addressing mode and width with
`Cycles65816`; the printer, `Timing` and the verifier all ask it. The cost of a C line (the
`[ n cyc ]` header in the `.s` file) comes from `Timing`, and the printer only prints what it is
given. In phase A, `Timing` gives exactly what `AsmWriter` writes today (the sum for straight
code, nothing for a line with branches), so the output stays identical. After that, the next
timing step of the roadmap (the costliest path per C line, and master cycles) extends `Timing`,
and the `.s` file shows it without a change to the printer.

The alternative was to leave both in the printer, as today. Then the cost of a C line would be
computed in two places, a sum in the printer and the path analysis in `Timing`, and the
printer's sum stays empty for every line with a branch, which are the lines that loops and
conditions are made of. The price of the decision: the line costs in the `.s` file need a run
of `Timing`, which every build does anyway.

**The debug file means what ld65's means; the text may differ** (2026-11-18). There is no
written specification of the `.dbg` format, but both ends are open source and small enough to
read: ld65 writes it in `src/ld65/dbgfile.c` (cc65, zlib licence), and Mesen reads it in
`UI/Debugger/Integration/DbgImporter.cs` (GPLv3, so it is read to learn the format, never
copied). The format is text, one record per line (`line id=0,file=0,line=224,span=134`). Mesen
reads `file`, `line`, `span`, `seg`, `sym`, `csym` and `scope`, and ignores `mod`, `type` and
`info`.

dampf writes every record type ld65 writes, including the ones Mesen ignores: they are simple,
and cc65's `dbginfo` library, which other tools use, needs `version` and `info`. The test reads
dampf's file and ld65's file and compares what they say: which source line covers which ROM
address range, which symbol is at which address, which C functions and variables exist. The
ids and the order of the records may differ, so a plain `diff` is not the test. Writing the text
byte for byte as ld65 does was ruled out: the ids and the order follow ld65's internals (the
order in which it reads object files and spans), and copying them helps neither Mesen nor a
user. Should Mesen turn out to rely on the order, for example on `span` ids without gaps, dampf
matches ld65 in that one point.

Better C debugging (variables shown with their C type, struct fields, locals per function in
their direct page slots) needs more than ld65 writes and Mesen reads. That is a separate step
after M5, in [roadmap.md](roadmap.md), not part of this plan.

**A step may cost some speed if the code gets much cleaner** (2026-11-18). A step that changes
the generated code tries first not to make the game slower. If a step makes the back end much
cleaner (for example, B3, which writes each function once instead of up to 14 times) and costs
about 1 % of cycles per tick that no reasonable effort wins back, it is taken anyway. A bigger
loss is taken too, since a step can be reverted, but every loss gets an entry in
[optimizations-log.md](optimizations-log.md): how much slower, where, and why it could not be
avoided. The work happens on the branch `lir`, one commit per step, and is merged after review.
