# Further tests for dampf

How gcc, LLVM, MSVC and HotSpot keep a compiler right, what dampf has of that on 2026-11-20
(commit `599a0bb`), and eight kinds of tests it does not have yet. Each is written as a step that
someone who has not seen the code, a person or an AI, can build: the reason, the place in the
code, the change, and how to tell that it works.

The tests that exist are in [testing.md](testing.md). The steps T1 to T4 of
[current-compiler-flaws.md](current-compiler-flaws.md#flaw-t-the-tests-cannot-see-these-flaws)
are that document's and are not repeated here. The steps here are called V1 to V8, so their names
do not collide with its letters. Nothing here is decided or scheduled; the
[order](#order) at the end is a proposal. A step that is built gets its description in
testing.md and loses it here.

## What the large compilers do

They are wrong regularly: gcc's bug tracker has a keyword for wrong code and LLVM's a label for
miscompilations, and both are in use. Their methods make a wrong result rare, found early, and
cheap to trace to its cause.

| Method | Who | What it is | dampf |
| --- | --- | --- | --- |
| Regression tests per pass | LLVM (lit and FileCheck), HotSpot (the IR framework) | IR in, expected IR out; every fixed bug becomes a test | A few per pass as MIR text; step T4 |
| Torture runs | gcc (c-torture) | Every execution test at every optimization level and with several option sets | One configuration, plus one build with arbitrary block counts |
| Stress flags | HotSpot (`StressIGVN`, `StressGCM`, `StressLCM`, `StressCCP`, with `StressSeed`) | The optimizer takes legal choices at random | None: [V1](#v1-stress-runs) |
| Real code in bulk | gcc (the three-stage bootstrap, distributions rebuilt with a release candidate), MSVC (Microsoft's own products) | The compiler builds itself and everything else, and the results are run | The examples with one recorded input each: [V2](#v2-random-joypad-input-on-the-examples) |
| Random differential testing | Csmith, YARPGen | Random programs through several compilers, results compared | `RandomProgram`, `RandomLoopProgram` (step T2) |
| Random IR | LLVM (`llvm-stress`, `llvm-opt-fuzzer`) | Random programs in the optimizer's own form | None: [V3](#v3-random-mir) |
| Proved rewrites | Alive2 (LLVM's peephole rules, with a solver), CompCert (proved as a whole) | A rewrite is shown right for every input | `StrengthReduceTest` for one pass: [V4](#v4-every-rewrite-checked-on-every-input) |
| Equivalence modulo inputs | Zhendong Su's group, on gcc and LLVM | Code a run never executes is changed; the run's result must stay | None: [V5](#v5-programs-changed-where-they-never-ran) |
| Reduction and bisection | C-Reduce, `llvm-reduce`, `-opt-bisect-limit` | A failing program shrinks to ten lines and names its pass | The pass limit (step T3); no reducer: [V6](#v6-a-reducer-for-failing-programs) |
| Mutation testing | PIT, for Java | The compiler's own code is changed; a change no test notices is a missing test | By hand, once per step: [V7](#v7-a-mutation-score-for-each-pass) |
| More than one reference | Csmith (a majority of compilers) | A result counts only when the reference compilers agree | gcc alone: [V8](#v8-a-second-opinion-for-the-reference) |
| Conformance suites | MSVC and other vendors (Plum Hall, Perennial, SuperTest) | Bought suites that test the language standard clause by clause | 39 execution tests, 6 of them from fixed bugs; see [what is not proposed](#considered-and-not-proposed) |

The Csmith paper of 2011 reports more than 325 bugs in gcc, LLVM and other compilers, the first
paper on equivalence modulo inputs (2014) 147 in gcc and LLVM. Both numbers are from memory.

dampf has three things those compilers lack. Its values are 8, 16 and 32 bits wide, with no
recursion and no heap, so many input spaces can be tried completely (V4). Its emulator runs in
the same JVM as the compiler, so running code costs no process (V2, V4). And it has two more
opinions on what a program means, the naive code generator and the MIR interpreter, both
independent of the code under test.

## Rules for whoever builds one of these

The [rules of current-compiler-flaws.md](current-compiler-flaws.md#rules-for-whoever-implements-this)
hold: one step a commit, with its tests and the documents it makes untrue; the book stays as it
is. In addition:

- A test that finds a miscompilation has done its job. Fix the bug first, with an execution test
  in `compiler/src/test/resources/exec/` (`regress_<what>.c`), then go on.
- A failure names its seed and the command that runs that one case again, as
  `RandomProgramTest` does.
- What takes longer than about a minute stays out of `./gradlew build`: a Gradle property turns
  it on, a script in `dev/` runs the long version, and `dev/README.md` lists the script
  (`dev/random_soak.sh` is the model).
- Each step ends with a mutation check: break the compiler by hand in the way the step names, do
  not commit that, and see whether the new test finds it. Write the result into the commit
  message, also when the test does not find it.

## V1. Stress runs

**Why.** Every MIR and LIR pass is optional, so the code must be right for any subset of them,
in any order. The tests run one subset: all of them, in one order. The pass limit (step T3) adds
the prefixes of that order and nothing else. The bugs of the last weeks were combinations that
one configuration did not have:

- Inlining up to 80 instructions made the benchmark's checksums differ from the PC's
  ([optimizations.md](optimizations.md#tried-and-rejected), 2026-11-18). The cause was a place
  shared by two φs, found a day later in a game and fixed in `02fa308`. A run with a different
  inlining limit would have shown it in the kit's own tests.
- `IndexParams` gave one of two outputs depending on hash codes (found while building T3).

HotSpot tests exactly this way: the flags in the table above make the optimizer's free choices at
random, with a seed that reproduces the run.

**Where.** `dampf.util.PassLimit.apply` is the one place every MIR and LIR pass goes through
([Optimizer.java](../compiler/src/main/java/dampf/mir/Optimizer.java), `Run.pass` and
`Run.module`; [MirCodeGen.java](../compiler/src/main/java/dampf/backend/MirCodeGen.java), the six
LIR passes). `Inline.run` asks `limit.allows()` for each call and has the limits `SMALL`,
`SINGLE` and `CALLER_LIMIT`. The block layout takes `Compiler.Options.blockCounts`.

**The change.** A stress seed beside the pass limit: `dampf build --stress s`,
`Compiler.Options.withStress(s)`, the system property `dampf.stress` (`-Pstress=s` for the
tests) and `DAMPF_STRESS`. All choices come from one `java.util.SplittableRandom` made from the
seed and the program's name, drawn in a fixed order, so a seed gives the same build every time.
The choices:

1. An application of a pass is skipped with probability 1/4 (in `PassLimit.apply`, before the
   pass runs; a skipped pass reports no change).
2. The thirteen passes of `Run.function` run in a random order, a new one each round.
   `SinkCompares` and `Schedule` stay last.
3. `Inline` declines a call with probability 1/4, and takes `SMALL` and `SINGLE` for the run from
   {0, 20, 60, 80, 150, 400} and `CALLER_LIMIT` from {300, 1500, 6000}.
4. The block counts are arbitrary, as in the third ROM of `ExecutionTest`.
5. `SelectionPlan`: each counter for X, index for Y, mask and forward that a finder found is
   dropped with probability 1/4. Check for each of the four finders first that selection gives
   right code without the entry, and write that into the finder's comment; I have not checked it.
6. `FrameLayout`: two values that may share a place do not, with probability 1/4.

Choices 5 and 6 go away with the flaws document's steps B2 and B3; their replacements (the place
of each value, the register of each value) go through the same class from the start, as T3
already asks for the pass limit.

The draws must not depend on the pass limit, so that `DAMPF_STRESS=s dev/bisect_passes.sh ...`
names the pass that breaks a stressed build.

Use: `ExecutionTest` builds a fourth ROM with a seed taken from the date and requires gcc's
results from it. `dev/stress.sh [seeds, default 200]` runs `ExecutionTest`, `RandomProgramTest`
and `ExamplesTest` once per seed. The tests of cost (`SpellingTest`'s tolerances,
`StyleGuideTest`'s claims, `BackendOutputTest`'s hashes, the budgets in `check.txt`) do not hold
for a stressed build: skip them when a seed is set, as a budget is only a warning under a pass
limit.

**Done when.** 200 seeds pass over the execution tests and 20 over the examples, or every failure
is fixed. Mutation check: undo the fix of `02fa308` in `FrameLayout` by hand, take
`regress_branch_phis.c` out, and see whether 200 seeds find the bug in the remaining tests. If
they do not, say so: then the stress does not reach the sharing of places, and choice 6 needs
more than a coin.

## V2. Random joypad input on the examples

**Why.** The examples are the only large programs dampf is tested on, and each is played with one
recorded input. The code that input does not reach is compiled and never run. How much that is
has not been measured; measuring it is the first part of this step. Any joypad input is a valid
input by definition, the reference exists (the same game compiled by the naive code generator,
and by gcc on the desktop), and the comparison exists (a checksum per tick, `ExampleCheck`).

**Where.** [ExampleCheck.java](../compiler/src/main/java/dampf/emu/ExampleCheck.java):
`Input(ticks, presses, budgets)` and `run(rom, input, limitFrames)`, whose result has the
checksum of every tick. `ExamplesTest.options(example, out)` gives an example's build options,
`withBackend` chooses the code generator, and the test shows how the desktop program plays a
check without a window. `dampf.emu.RomDiff` names the globals two ROMs differ in.
`dampf.tools.Profile.writeBlockCounts` turns counts per address into counts per block, today only
from a profile Mesen wrote.

**The change.**

1. Block counts from dampf's emulator: `dampf.emu.ExampleProfile` gets `--blocks file` and writes
   the lines `function block count` for a played input. With it, write a report of how many of
   each example's blocks its `check.txt` reaches (`build/emu-check/coverage.txt`), and put the
   numbers into this section.
2. `ExampleFuzz` (in `dampf.emu`, with a `main` like `ExampleBench`): for a seed, an input of N
   ticks made of stretches of 1 to 60 ticks, each with a random set of none to three buttons;
   `start` and `a` more often than the others, since every title screen waits for one of them. It
   builds the naive ROM and the ROM from MIR, plays the input in both, and requires the same
   checksum for every tick. Where the desktop program was built, it plays the input there too. A
   difference is reported as the seed, the first tick that differs, and the input written in the
   format of `check.txt` to `build/fuzz/<example>/<seed>.txt`, so `dev/replay_example.sh` and
   `RomDiff` take it from there.
3. In every build: one input of 600 ticks per example, the seed from the date. `dev/fuzz_examples.sh
   [count]` for a long run.
4. If the report of 1 shows that random input reaches little more than the recorded one (a game
   that needs a level to be won, for example), make it guided: keep an input when it reaches a
   block no earlier input reached, and make new inputs by changing a stretch of a kept one. That
   is what AFL and libFuzzer do with a program's input.

The naive ROM does up to six times the work (three times on the geometric mean). The checksums
do not depend on speed (the desktop has the same ones), but the frame limit of
`ExampleCheck.run` must allow for it.

**Done when.** The coverage numbers are written down. Mutation check: the four hand-made breaks of
step T2 (one condition in each of `Induction`, `SelectionPlan.findXCounters`, `PointerOffsets`
and `Inline`); for each, whether 50 inputs over all examples find it.

## V3. Random MIR

**Why.** A random C program reaches the passes only in the shapes the lowering makes. The passes
also run on each other's output, twenty rounds deep, and the shapes that come up there are in no
test. Random MIR needs no gcc and no ROM, so many more programs run in the same time (how many
has to be measured). It also forces an answer to a question that architecture.md does not answer
in one place: what each MIR instruction means for every input, for example whether a signed `add`
wraps. Where a pass assumes more than the interpreter does, this test fails, and then either the
pass is wrong or a rule is missing from the document.

**Where.** `MirParser.parse` (MIR text to a module), `Printer`, `Verifier`,
`Interpreter.call(function, args)` and `load(address, size)`, `Optimizer.run(module, true)` with
the verifier after every pass.
[StrengthReduceTest.java](../compiler/src/test/java/dampf/mir/StrengthReduceTest.java)
compares a module before and after the passes in the interpreter;
[MirRunner.java](../compiler/src/test/java/dampf/exec/MirRunner.java) shows how the range
checker is attached.

**The change.** `RandomMir` in the test tree writes a module as MIR text: one function with one
to three parameters of `i8`, `i16` and `i32`, two global arrays. The control flow comes from a
random tree of statements (a sequence, an `if` with one or two sides, a counted loop), written
out as blocks and φs, so the SSA form is right and every loop ends by construction, as
`RandomLoopProgram` makes its loops from the values the counter takes. The instructions are
every binary operation, comparison, conversion and `select` the verifier accepts, loads and
stores with the index masked into the array (lengths are powers of two), divisors made odd,
shift counts masked.

The test runs each function with 20 argument tuples (boundary values and random ones) before and
after the passes, and requires the same result and the same bytes in the arrays, with the range
checker attached to the second run. A failure prints the seed and the MIR text, and names the
pass by itself: it repeats the run with `new PassLimit(n, "")` for smaller n until the result is
right.

A second stage, later: the same module through `MirCodeGen` into a ROM and dampf's emulator,
against the interpreter. `Compiler.compile` starts from C sources today; the stage needs a way in
at the module.

**Done when.** 10,000 functions run in every build in a time that is acceptable there (measure;
reduce the number if it is not), and the meaning of the MIR instructions is one section of
[architecture.md](architecture.md). Mutation check: one condition broken in each of `Simplify`,
`Gvn`, `Narrow` and `RangeNarrow`.

## V4. Every rewrite checked on every input

**Why.** A rule that rewrites `i8` values has 256 inputs for one operand and 65,536 for two.
Trying them all is a proof for that width. Alive2 needs a solver to do the same for LLVM's 32
and 64 bits. `StrengthReduceTest` already works this way for multiplication and division by
constants. `Simplify` (490 lines of rules) has no test of its own, and the LIR rules are tested
only through whole programs.

**The change, MIR.** A test that writes every expression of these shapes as a function, for the
types `i8` and `i16`, all binary operations `op`, all comparisons `cmp`, and the constants 0, 1,
2, 3, 7, 8, 15, 127, 128, 254 and 255 (for `i16` also 256, 32767, 32768 and 65535):

- `op1(op2(x, c1), c2)` and `op1(c1, op2(x, c2))`
- `op1(op2(x, y), c)` and `op1(x, op2(x, c))`
- `cmp(op(x, c1), c2)`, and a branch on it that returns one of two constants
- the conversions around an operation: `trunc(op(zext x, zext y))`, the same with `sext`,
  `op(zext x, c)` in the wider type

Each function runs before and after `Optimizer.run` in the interpreter: with one variable on all
values of its type, with two on all 65,536 pairs for `i8` and on the pairs of 64 boundary values
for `i16`. Inputs the MIR leaves undefined (division by zero) are skipped, as
`StrengthReduceTest` skips them. With about fifteen operations this is some 50,000 functions of
one variable; if the whole set is too slow for every build, a build runs a tenth of it chosen by
the date, and `-Pexhaustive` all.

**The change, LIR.** For every rewrite that the class comments of `Peephole`, `KnownCarry`,
`ByteStretches`, `RedundantLoads` and `IndexInY` describe: the instructions before it as LIR text
(`LParser`; `LirTest` is the model), what is live after them (which of A, X, Y, the flags N, Z, C
and V, and which places), and the assertion that the pass changes them. Then both versions are
assembled (`dampf.asm.Assembler`) and run on a bare CPU
([CpuSingleStepTest.java](../compiler/src/test/java/dampf/emu/CpuSingleStepTest.java) shows how
to set one up with a memory of its own) for every value of A (65,536 with a 16-bit accumulator),
both values of the carry and eight boundary values of each place, and everything live must be
the same after both.

**Done when.** Mutation check: in `Peephole`, drop the condition "not with an 8-bit A" of the
rule `STA p / LDX p`; in `Simplify`, change one constant of a rule of `canonical`.

## V5. Programs changed where they never ran

**Why.** If a block never runs for an input, any change to that block leaves the result for that
input the same. So every example with its `check.txt` is the seed of as many test programs as
one likes, with the reference for free: the checksums of the unchanged build. This finds what
the other tests find badly, an optimizer that lets code that does not run affect code that does:
`Licm` moving something out of a block that is not always reached, `Gvn` taking a value from
such a block, a range concluded from a path that is never taken, `Inline` and the places of
values in the functions around it.

**Where.** The block counts of V2 (its part 1). The pass limit: a build with `--opt-limit 0` has
the blocks the lowering made, so its counts are those of the MIR before the passes. Check that
first: the block names in the counts of such a build must be the names `dampf mir` prints without
`-O`.

**The change.** `Compiler.Options` gets a hook that is given the module after lowering and before
the passes (null in normal use, as `blockCounts` is). `EmiTest` plays each example's check in a
build with limit 0 for the counts, then makes K variants. In a variant, each block with count 0
is changed with probability 1/2 by one of: a constant operand replaced by another constant of
its type; a binary operation replaced by another one; a comparison's predicate replaced; a store
removed. None of these changes a type or removes a definition, so the verifier still accepts the
module. Hints stay as they are. Each variant is compiled with all passes and plays the check: the
checksums must be those of the unchanged example. A failure names the seed and writes the
variant's MIR before the passes to `build/emi/`.

A changed loop in a dead block may no longer have a bound, and the static timing then cannot
keep a budget: budgets are warnings in these builds, as under a pass limit.

One variant per example in every build, the seed from the date; `dev/emi.sh [count]` for more.

**Done when.** Mutation check: let `Licm` move an instruction out of a loop without the test that
its block runs in every round, and see whether 100 variants find it.

## V6. A reducer for failing programs

**Why.** A random loop program is some hundred lines, and `dev/bisect_passes.sh` names the pass
that breaks it but not the lines that matter. Csmith became useful to the gcc and LLVM
developers when C-Reduce turned its programs into reports of ten lines. Neither `creduce` nor
`cvise` is installed here, and both would have to learn what dampf accepts.

**The change.** Two parts, both small.

- `dampf.exec.Differs` (test tree, with a `main`): given a C file with a `test_main`, it exits
  with 0 if gcc builds the file without warnings (`-O1 -Wall -Werror`, which finds most reads of
  a local before it is set), the build with the sanitizers runs clean, the naive ROM gives gcc's
  results, and the ROM from MIR does not. Everything else, a file that does not compile or a
  program that hangs included, is "not interesting". With `--same name` the first global that
  differs must be `name`, so the reduction does not slide into another bug.
- `dev/reduce.sh file.c`: delta debugging on lines (Zeller's ddmin): remove halves, then
  quarters, down to single lines, keep a removal when `Differs` still says 0, repeat until no
  single line can go. Removing whole lines cannot create an expression whose value depends on
  the order of evaluation; an out-of-bounds index or a loop that no longer ends is caught by the
  sanitizers and the time limit. Then it runs `dev/bisect_passes.sh` on the result and prints
  both: the lines and the pass.

`RandomProgramTest`'s failure message gets the command that writes the program of a seed to a
file and reduces it.

**Done when.** Mutation check: with one of the breaks of step T2 in place, a failing loop program
is reduced; write the sizes before and after into the commit message.

## V7. A mutation score for each pass

**Why.** Line and branch coverage say which code the tests ran. The known miscompilations were
all in code that ran. A mutation score says which code the tests would notice being wrong: PIT
changes one condition or constant of the compiler at a time and runs the tests, and every change
that survives is a test that is missing, with its line. That list is a better source for the
tests of step T4 than JaCoCo's missed branches. The steps here and in the flaws document already
do this by hand, four mutations at a time.

**The change.** The Gradle plugin `info.solidsoft.pitest` in `compiler/build.gradle.kts`, not
part of `build`: `./gradlew :compiler:pitest -Ppit=dampf.mir.DeadLoops` mutates one class and
runs the pass tests (`dampf.mir.*Test`), `ExecutionTest`, and `RandomProgramTest` with 50
programs. Two things I have not checked: whether the current PIT reads class files of Java 25
and runs on JDK 27, and how it is told to run the tests in the repository's top folder with the
system properties the `test` task sets. If PIT cannot be made to work, the fallback is a list of
mutations as patch files in `dev/mutations/` and a script that applies each, runs the tests and
reverts it.

It is slow (every mutant runs the tests until the first failure), so it runs for one pass, when
that pass is worked on. The score goes into the commit message of that work.

**Done when.** It has run for `DeadLoops` and `Induction`, and each surviving mutant is either
killed by a new MIR text test or listed with the reason it is equivalent.

## V8. A second opinion for the reference

**Why.** The reference can be wrong. `RandomLoopProgram`'s seed 1157 differed from gcc because
gcc 16.2.1 with `-fsanitize=bounds` miscompiled `a[i] ^= (... ? ... : a[i])`; clang and gcc
without the bounds check agreed with dampf (the comment in
[PcReference.java](../compiler/src/test/java/dampf/exec/PcReference.java)). Until someone saw
that, it was a bug of dampf's. Csmith never trusts one compiler for this reason.

**The change.** In `PcReference`, only when dampf's result differs from gcc's (so a passing test
costs nothing more): build the harness again with `gcc -O0`, `gcc -O2`, and `clang -O1` if
`clang` is on the path, and run each. If they do not all give the values of the first build, the
failure says "the reference compilers disagree" with each one's values, and the program is to be
looked at before dampf is. If they agree, the failure says that dampf differs from all four.

Also add `-fsanitize=address` to the checked build. The bounds check of UBSan knows array types
only; a pointer that walks past its array is found by the address sanitizer. What that costs per
program I have not measured.

**Done when.** A test gives `PcReference` two compilers that differ (a script in place of
`clang` that prints other values) and gets the message.

## Considered and not proposed

**Calling every function of the examples with random arguments and globals.** It would reach
more code than V2, but most random states are invalid: an index outside its array, a pointer to
nowhere, a value that breaks a hint. Such a call does something different in the naive ROM and
in the ROM from MIR, since their frames differ, and every one would be a false alarm. Sorting
them out needs a referee that knows the bounds of every object. The MIR interpreter is not one:
it checks hints and ranges, but an address only against the whole of its memory
([Interpreter.java](../compiler/src/main/java/dampf/mir/Interpreter.java)). With poisoned bytes
between its objects it would catch most of them. That is work for after V2, if V2's coverage
report leaves functions that no input reaches.

**gcc's c-torture and LLVM's test-suite.** dampf rejects plain `int`, `short`, `long` and
`unsigned`, recursion, free function pointers and every library function
([subset.md](subset.md)), and nearly all of those tests use `int` and end in `abort` or `exit`.
They would have to be rewritten by a tool before a single one compiles, and how many are left
after that nobody has counted. The same holds for the bought conformance suites. `RandomLoopProgram` is the cheaper way to the same kind of code.

**Csmith itself.** It writes fixed-width types and has switches for 64-bit values, unions and
bit fields, so its programs might pass `dampf check` with little change. Not tried. Worth an
hour once step T2 has stopped finding bugs, to see whether a different generator finds other
ones.

**A proof with a solver, as Alive2 does.** V4 gives the same certainty for 8 bits by trying
every input, with no new tool. For 16 and 32 bits a solver would say more than boundary values
do; that is a project of its own, and nothing so far suggests that dampf's bugs sit there.

## Order

| # | Step | Needs | Size | In every build | Why at this place |
| --- | --- | --- | --- | --- | --- |
| 1 | V8 second opinion | | small | on a failure only | A wrong reference has already cost time once |
| 2 | V1 stress runs | T3 | medium | one seed | Cheapest way to many configurations; guards steps 10 to 12 of the flaws plan |
| 3 | V2 joypad input, with the block counts | | medium | one input an example | The only test of large programs beyond their recordings; guards the same steps |
| 4 | V4 every rewrite on every input | | medium | all or a tenth | Independent of everything else; `Simplify` changes in steps C1 to C3 |
| 5 | V6 reducer | T2 | small | no | Pays as soon as random programs fail often, which they do now |
| 6 | V3 random MIR | | medium | 10,000 functions | Before step L, whose `CountedLoops` gets shapes no C program gives it |
| 7 | V5 changed dead code | V2 part 1 | medium | one variant an example | |
| 8 | V7 mutation score | | small, if PIT works | no | Feeds step T4, pass by pass |

V1 and V2 should be there before the back end is rewritten (the flaws plan's steps 10 to 12):
that work replaces the code the frame bugs were in, and these two are the tests that vary what
the back end is given.
