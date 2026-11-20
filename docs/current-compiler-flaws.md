# Current compiler flaws

What is structurally wrong with dampf's optimizer and back end, measured on 2026-11-20 at commit
`5bd65bb` (0.7.1), and how to fix it, step by step. Written so that someone who has not seen the
code, a person or an AI, can do the work: every flaw has its evidence, the place in the code, the
change and the test that shows the change worked.

The plan is the order of M9 since 2026-11-20 ([roadmap.md](roadmap.md#m9-the-optimizer-090)):
the roadmap's rows 2, 3 and 9 and "the gaps the C style guide measures" are the steps B, B5, R3
and C/L here. A step that takes a decision (they are marked) writes its ADR record when it is
taken.

## The finding in one paragraph

dampf's code is fast where a hand-written pattern in the optimizer matches the program exactly,
and falls back to slow code where one condition of the pattern fails. The same loop costs between
25 and 138 CPU cycles a round depending on how it is written, and in two cases on the value of
its bound. There are three causes. The MIR has no canonical form, so every pattern has to know
every spelling and none does. Loops are recognised separately by four passes with four different
sets of restrictions. And the back end has no register allocation: every value lives in memory,
A, X and Y are used by special cases that are decided on MIR before any 65816 code exists. The
tests do not see any of this, because they check results and not cost, and the random programs
contain no arrays, calls or real loops.

## Rules for whoever implements this

- Work in the order of [the last section](#order-of-work). The tests of step T come first: they
  are how every later step is judged.
- One step is one commit, on a branch. It contains the change, its tests, and the documents it
  makes untrue (`docs/*.md`, the READMEs, `lib/C_STYLE.md`). Leave the book alone (CLAUDE.md).
- A step that changes generated code records new hashes (`./gradlew test -PbackendOutput=update`),
  measures all examples against the commit before it (`dev/bench_examples.sh` with `BASELINE=`),
  and writes both into [optimizations-log.md](optimizations-log.md). A step that only moves code
  keeps every hash. Never both in one step (ADR record 24).
- A step that closes a gap the C style guide measures breaks that rule's claim in
  `StyleGuideTest`. Change or retire the rule in the same commit (`STYLE_GUIDE=update` writes the
  table). Each step below names the rules it should retire.
- Correctness before speed: every result matches gcc bit for bit (ADR record 4). A step that
  makes something faster and one test fail is not done.
- A step that does not give what this document expects is still reported: write the measurement
  and the reason into the log, revert the change, and correct this document.
- When a fix changes what a game should write (the counter width, for example), put it into
  `docs/game-findings.md` for Blubber Blasen. Do not touch the game's folders.

## How to reproduce the measurements

```
./gradlew installDist
D=build/install/superdevkit/bin/dampf
$D build --entry test_main -o out.sfc loops.c     # writes out.s and out.timing.txt beside the ROM
$D mir -O loops.c                                 # the MIR after the passes
./gradlew :compiler:test -Pcoverage               # compiler/build/reports/jacoco/test/html/index.html
./gradlew :compiler:test --tests dampf.exec.SpellingTest   # compiler/build/reports/spelling.txt
```

`loops.c` is in the [appendix](#appendix-the-loop-spellings). "Cycles a round" below is the worst
case of the timing report, minus the cost of entry and return, divided by the number of rounds.

## The evidence

### One loop, fifteen spellings

Every function sums `data[0]` to `data[99]` into a `uint16_t` and returns the same value.

| Function | Spelling | Cycles a round | Why it is slower than the first | Fixed by |
| --- | --- | ---: | --- | --- |
| `a_u16_lt100` | `uint16_t i; i < 100` | 25 | (the pattern matches: counter in X) | |
| `b_u8_lt100` | `uint8_t i; i < 100` | 25 | | |
| `e_u8_le99` | `uint8_t i; i <= 99` | 25 | | |
| `f_u16_le99` | `uint16_t i; i <= 99` | 25 | | |
| `i_u16_dowhile` | `do { ... i++; } while (i < 100)` | 40 | the test reads the stepped value, the X pattern wants the φ | L3 |
| `m_u16_from1` | `i = 1; i < 101; data[i - 1]` | 43 | the index is `i - 1`, the X pattern wants `i` itself | C1, L4 |
| `d_u8_ne100` | `uint8_t i; i != 100` | 46 | `!=` gives the range analysis no bound, so the byte counter "might wrap" | L2, C5 |
| `l_u16_local_bound` | `n = n16; i < n` | 55 | bound not a constant; index not proved below 32,768, so a 24-bit pointer | R3, B3 |
| `k_u8_var_bound` | `uint8_t i; i < n8` | 57 | the same, and `n8` is loaded again every round | C7, B3 |
| `g_u16_down` | `i = 100; i > 0; i--; data[i - 1]` | 60 | the step is a `sub`, every loop pattern wants an `add`; 24-bit pointer | C1, R2 |
| `c_i16_lt100` | `int16_t i; i < 100` | 61 | the range analysis loses "i is never negative"; signed compare, 24-bit pointer | R1 |
| `x_ptr_walk` | `*p++` with a counter | 61 | two counters that always hold the same value, both kept | L5 |
| `h_u8_down` | `uint8_t i` counting down | 63 | as `g_u16_down`, with an 8-bit `DEC` between `SEP` and `REP` | C1, R2, C5 |
| `j_u16_var_bound` | `i < n16` (a global) | 66 | as `l_u16_local_bound`, and `n16` is loaded again every round | C7, R3, B3 |
| `y_ptr_end` | `for (p = data; p < data + 100; p++)` | 138 | the pointers are compared as 32-bit values every round | C6 |

The target of this plan is that all fifteen cost what the best does, within 10 %. Getting the
best closer to hand-written code is a different job, and an easier one once every spelling
reaches the same code.

### The value of the bound changes the code

| Function | Cycles a round | Cause |
| --- | ---: | --- |
| `int16_t i; i < 20` | 25 | fewer than 24 rounds: the range analysis iterates to the end |
| `int16_t i; i < 30` | 61 | more than 24 changes: the counter is widened to the whole type ([Ranges.java:108](../compiler/src/main/java/dampf/mir/Ranges.java#L108)) |
| `uint8_t i; i < 128` | 25 | 0..127 is an interval of signed bytes |
| `uint8_t i; i < 129` | 42 | 0..128 is not, so the counter has no range and stays in memory |

The second pair is what the C guide's rule `slow-u8-counter` measured with 200 rounds. The rule
"count with `uint16_t` over bytes" and its opposite `slow-u16-counter-words` are two of these
cliffs, taken for properties of the counter's width.

### How much of the examples is affected

Counted in the assembly of the examples' benchmark build of 2026-11-19 (`build/bench-examples`,
89,080 instructions, static, not weighted by how often they run):

- 29.7 % of all instructions read or write a value's place in the frame; they are 35.3 % of the
  cycles summed over the code.
- Of 1,170 loop heads, about 120 test a counter in X or Y (`CPX`, `CPY`): one loop in ten. The
  count is rough (it looks at the head block only), but the order of magnitude holds.

### What the tests cover

JaCoCo, whole suite: 87.6 % of lines and 79.0 % of branches; `dampf.mir` 94.5 % and 87.2 %,
`dampf.backend` 96.2 % and 89.0 %, `dampf.lir` 91.8 % and 81.6 %. That is high, and it says
little here:

- The known miscompilations (the commits `b33eeba`, `02fa308`, and the one
  `exec/regress_x_step_address.c` reproduces) were in lines the tests ran. They were wrong for a
  combination of conditions no test had.
- [RandomProgram.java](../compiler/src/test/java/dampf/exec/RandomProgram.java) writes global
  scalars, expressions, `if`, and `for (k = 0; k < n; k++)` with n from 1 to 4. No arrays, no
  calls, no pointers, no structs, no locals. So no random program reaches `Inline`, `Induction`,
  `PointerOffsets`, `PointerParams`, `ParamRanges`, the counters in X or the indexes in Y. The
  seeds are 1 to 50 in every build: the same 50 programs each time.
- The pass tests are few: `InductionTest` 2, `RangesTest` 3, `LicmTest`, `InlineTest`,
  `PointerRematTest` and `PointerParamsTest` 1 each. `Simplify`, `RangeNarrow`, `BitSelect`,
  `Schedule`, `FrameLayout`, `SelectionPlan` and the selection classes have none of their own.
- Nothing tests cost except the 20 rules of the C guide. `BackendOutputTest` sees that the output
  changed, not whether it got better.

## Flaw T: the tests cannot see these flaws

### T1. A test for spellings

Add `SpellingTest` beside `StyleGuideTest`
([StyleGuideTest.java](../compiler/src/test/java/dampf/exec/StyleGuideTest.java) is the model:
it builds a file, reads the worst cases from `Compiler.Result.timing()`, runs the program
against gcc with `PcReference` and `EmulatorRunner`, and checks the static bounds with
`Timing.check`).

One file per family in `compiler/src/test/resources/spelling/`. A family is a set of functions
without parameters that return the same value. The header comment says:

```
/* family: sum-bytes
 * tolerance: 10
 * gap: c_i16_lt100 R1 the range analysis loses the lower bound of a signed counter
 * gap: y_ptr_end C6 pointers compared as 32-bit values
 */
```

The test checks, for every file:

1. All functions return the same value on the PC and on the SNES (a `test_main` stores each
   result into a global array; the globals are compared as in the execution tests, and the test
   also requires all elements equal).
2. A function without a `gap:` line costs at most `tolerance` percent more than the family's
   cheapest function. The cost is the CPU cycles of one call, measured in dampf's emulator with
   the function as the ROM's entry (`Compiler.Options` takes the entry; `EmulatorRunner.cyclesOf`
   measures it). The static worst case will not do for every spelling: for a loop whose bound is
   a variable it assumes the largest bound the type allows. Globals that are bounds get their
   value in their definition, so the function runs the same without `test_main`.
3. A function with a `gap:` line is outside the tolerance. When a fix closes the gap the test
   fails until the line is removed, so the list can only shrink and always says what is open.

The first family is the appendix's file with the eleven gaps of the table above. Add at least
these families, each with the spellings a programmer would write (counter types `uint8_t`,
`uint16_t`, `int16_t`; up and down; `<`, `<=`, `!=`; `for`, `while`, `do`; index and pointer;
constant, local and global bound; bounds 20, 100, 200 and 300 where the type allows):

- `fill-words`: store a constant into a `uint16_t` array (today `fill_u16_counter` 1,945 against
  `fill_u8_counter` 1,233 cycles, the rule `slow-u16-counter-words`);
- `struct-fields`: update two fields of every element of an array of structs, by index, by
  pointer, and as one array per field;
- `pairs`: the nested loop over all pairs (i, j) with j > i, as in Er-Tüp;
- `search`: find the first element equal to a value, with `break` and with `return`;
- `copy`: copy one byte array into another;
- `table-2d`: sum a `uint8_t t[8][16]` row by row.

Done when the test runs in `./gradlew build`, passes with every open gap listed, and
`docs/testing.md` describes it.

Built (2026-11-20): `SpellingTest`, 13 families with 90 members, 64 of them gaps; the families
above, and `sum-bound-N` for the bounds 20, 30, 128, 129, 200 and 300. Two things differ from
the description above. The test writes `test_main` itself: a family has an optional `setup`
(run before each member) and `check` (whose value after each member must be the same, for
members that write memory instead of returning a value). And the cost is measured with a
generated entry that calls the member, which inlines it, as dampf inlines every small function:
for the appendix's functions that gives the numbers of the table plus 13 cycles, except
`x_ptr_walk` (6,765 against 6,132) and `y_ptr_end` (6,989, 69 cycles a round against 138). The
first difference is a flaw of its own, [R4](#r4-inlining-drops-the-range-of-a-derived-variable),
found by the test: inlined, `fill_u16` costs 3,930 cycles, on its own 1,945. The test also found
[C9 and C10](#flaw-c-the-mir-has-no-canonical-form) (`table-2d`), and that the stride of a struct
costs 12 % (`struct_u8` against `fields_u8`, the gap named `split`: only roadmap M9 row 4 closes
it). The report `compiler/build/reports/spelling.txt` has every member's cycles.

### T2. Random programs that reach the optimizer

Extend `RandomProgram` (keep the old generator's output for seeds 1 to 50 unchanged behind a
version switch, so they stay as regression programs) with:

- global arrays of `uint8_t`, `uint16_t`, `int16_t` and of a small struct, read and written with
  an index that is in bounds by construction: a loop counter below the array's length, or
  `(unsigned)(expr) % N` with the constant length N;
- loops over them with a random counter type, direction (up, down), step (1, 2, the element
  count), test (`<`, `<=`, `!=`, `>`), form (`for`, `while`, `do`), and bounds up to 300 where
  the counter's type holds them, so that 24, 127 and 255 are crossed; `break` and `continue`;
  nested two deep;
- helper functions with one to three parameters, some of three lines (inlined), some of forty
  (not), called from loops and from each other without recursion; pointer parameters that
  always get the same array (for `PointerParams`) and ones that get two different arrays;
- locals, a local array, a pointer walking an array (`*p++`, `p < a + N`), `switch` on a byte.

Undefined behaviour stays excluded by construction, and the PC side runs with UBSan as now.

Seeds: the fixed 50 stay. Add 50 new seeds a build, taken from the date
(`LocalDate.now().toEpochDay() * 1000`), with the seed in the failure message so a failure is
reproduced with `-PrandomSeed`. Add `dev/random_soak.sh` that runs `-Prandom=5000` for a long
run before a release.

Done when a deliberately broken pass is caught: break one condition in each of `Induction`,
`SelectionPlan.findXCounters`, `PointerOffsets` and `Inline` by hand (do not commit it) and check
that 200 random programs find each. Write the four mutations and their results into the commit
message.

Built (2026-11-20): `RandomLoopProgram`, described in
[testing.md](testing.md#the-layers) under *Random programs*; version 1 is unchanged, and the
loop programs run 50 seeds a day. In its first 1,250 programs it found three bugs in dampf and
one in gcc, each fixed or worked around in a commit of its own with an execution test:

- `Lower`: the SSA construction returned a φ it had just removed (a `do` loop with a `?:` in its
  body and a variable read after it); 18 of the first 50 programs.
- `Selection` and `RegContents`: an element of a local array (`z:dampf_frame+0,x`) counted as a
  fixed place, so a second load with another X was left out.
- `FrameLayout.directPhis`: a φ reading another φ through a `zext` was written directly, and
  lost the old value.
- gcc 16.2.1 miscompiles `a[i] ^= (... ? ... : a[i])` under `-fsanitize=bounds`; the PC values
  now come from a build without the sanitizer, which still runs to find undefined behaviour.

The mutations, each in 200 loop programs (seeds 1 to 200) and, for comparison, 200 programs of
version 1: `Induction.step` taking a `sub` for an `add`, 8 failures (version 1: none);
`findXCounters` without "nothing in the loop needs X", 61 (none); `PointerOffsets` dropping the
base offset of `p + k`, 114 (none); `Inline` copying a two-way φ with its operands swapped, 46,
all through the MIR verifier (none). A first choice for `findXCounters`, without "the stepped
value has one user", changed the code of none of the 285 programs of `BackendOutputTest`: the
other conditions already exclude what it guards.

### T3. Find the pass that broke a program

Add a limit on pass applications: `Optimizer.run` counts every pass call that reported a change,
and stops applying passes after N (`dampf build --opt-limit N`, and a system property for the
tests). With it a program that is wrong with the passes and right without is bisected to one
application of one pass on one function, by a script (`dev/bisect_passes.sh rom-check-command`).
LLVM has this as `-opt-bisect-limit`. The same for the LIR passes and for each decision of the
back end that step B introduces. The roadmap's risk "little human knowledge of the code" is
about exactly this: a miscompilation should name its pass without anyone reading the code
generator.

Built (2026-11-20): `dampf.util.PassLimit`, described in
[testing.md](testing.md#supporting-pieces). The passes on the whole module (`ParamRanges`,
`PointerParams`, `IndexParams`) count once per run, not per function. Two findings on the way:
`IndexParams` gave one of two outputs depending on hash codes (fixed in the commit before), and
code without the passes is up to ten times slower than with them, which the tests' emulator
limit now allows for.

### T4. Tests for the conditions of every pattern

For each pass in `dampf.mir` write its conditions as MIR text tests, one where the pass applies
and one for each reason it must not (the form `InductionTest` has). The JaCoCo branch report is
the list of what is missing: `DeadLoops` misses 73 of 200 branches, `SelectionPlan` 56 of 494,
`FrameLayout` 55 of 457, `Induction` 37 of 218, `Ranges` 48 of 332. Do this for a pass in the
step that touches it, not as one big step: several of these patterns go away in L and B.

## Flaw C: the MIR has no canonical form

**What is wrong.** A pass that looks for "a counter stepped by a constant" asks for
`add φ, const` ([Induction.java:203](../compiler/src/main/java/dampf/mir/Induction.java#L203),
[SelectionPlan.java:338](../compiler/src/main/java/dampf/backend/SelectionPlan.java#L338)). `i--`
is lowered to `sub φ, 1`, so loops that count down are no counters to these passes, while
`Timing.headRuns` and `DeadLoops` accept both forms. `Simplify.canonical` already puts a
comparison's constant on the right and turns `x <= c` into `x < c + 1`; arithmetic has nothing
like it.

**Why it matters.** With N spellings and M passes there are N x M cases, and today each pass
covers the ones its author met. LLVM's answer is to rewrite every spelling into one form first
(InstCombine, loop-simplify), so a later pass needs to know one. HotSpot's C2 does the same when
it creates a node (`Ideal`, `Identity`).

**The fix.** Put the rules into `Simplify` (or a class `Canonical` called first in
`Optimizer.function`). Each rule gets a MIR text test and a line in the class comment, which
becomes the definition of the canonical form. Passes may then rely on it, and the alternatives
they handle today are deleted in the same step (search for `BinOp.SUB` in `Induction`, `Timing`,
`DeadLoops`, `ArithSelection.incDec`).

- **C1.** `sub x, C` becomes `add x, -C` (wrapped to the type). Check that `ArithSelection`
  writes the same code for `add x, -1` as it did for `sub x, 1` (`incDec` takes both today; the
  hashes of `BackendOutputTest` show every difference).
- **C2.** A commutative operation has its constant on the right, and of two non-constants the
  one defined later on the right (any fixed order will do; it lets `Gvn` find `a + b` and `b + a`).
- **C3.** Comparisons with a constant use only `eq`, `ne`, `ult`, `uge`, `slt`, `sge`:
  `uge x, 1` becomes `ne x, 0`, `ult x, 1` becomes `eq x, 0`, `ugt x, C` becomes `uge x, C + 1`,
  and the same for the signed ones, where the constant does not overflow.
- **C4.** A signed comparison of two values that the ranges show are never negative becomes the
  unsigned one. `RangeNarrow` does this already; with R1 it applies to signed counters.
- **C5.** A loop counter of `i8` that does not wrap (the counted-loop analysis of L says so) and
  is read as its zero extension becomes an `i16` φ; readers of the byte get a `trunc`. One
  width for all counters: the back end keeps an `i8` in two bytes anyway, X is 16 bits wide,
  and a 16-bit `INC` needs no `SEP` and `REP` around it. Retires `slow-u8-counter`.
- **C6.** A comparison of two pointers into the same object, `cmp (ptradd @o, a), (ptradd @o, b)`,
  compares the offsets as unsigned 16-bit values (C's rule that a pointer stays in its object,
  which `PointerOffsets` relies on already). Where one side is a pointer φ that `PointerOffsets`
  turns into an offset, the comparison must follow. `y_ptr_end` is the test.
- **C7.** `Licm` moves a load out of a loop when nothing in the loop may write what it reads:
  no store or copy that may overlap (the rules `Gvn` uses: two different globals or slots never
  overlap), no call of a function that writes memory (`Gvn.writesMemory`), not volatile. Only
  from a block every round passes, as now. `j_u16_var_bound` and `k_u8_var_bound` are the tests.
- **C8.** Every loop has one block before it that only jumps to its head (a preheader), and one
  latch. `Licm` needs the first and skips loops without it; the X pattern needs the second.
  Insert the blocks where they are missing, in one place (`Loops` or a `LoopForm` pass), instead
  of each pass checking and giving up.

Built (2026-11-20): C1 to C3 in `Simplify`, whose class comment is the definition of the form,
and `SimplifyTest`. C2's second half is in `Gvn` instead (it finds `b + a` as `a + b`), since
reordering the operands themselves changes which one selection hands on in A. The gain is small
([log](optimizations-log.md#a-canonical-form-for-arithmetic-and-comparisons-2026-11-20-on-the-spelling-families-and-the-examples)):
`i - 1` and `i--` became one instruction, which the X pattern refuses (a step with two users),
so `g`, `h` and `m` wait for L. C1 also brought a miscompilation, found by the random loop
programs and fixed in its own commit: `c--` on a byte became `c + -1`, which the code generator
found clean without a mask (`ADC #$00FF`) and then stepped with a 16-bit `DEC`.

- **C9.** A `ptradd` of a `ptradd` of a global or a slot is one `ptradd` with the sum of the
  offsets: `t[r][c]` is `ptradd (ptradd @t, r * 16), c` today, so the row's pointer is computed
  every inner round and read as `[dp],y`, where `@t + (r * 16 + c)` is an index in X
  (`table-2d`: `rows_u16` 7,950 cycles, the row pointer written by hand 5,578).
- **C10.** `(x >> k) << k` (and `* 2^k`) is `x & ~(2^k - 1)`, and the sum or `|` of `x & ~m` and
  `x & m` is x: `t[i >> 4][i & 15]` is `t` indexed by `i` (`flat_index`).

Built (2026-11-20, [log](optimizations-log.md#two-dimensional-arrays-as-one-index-2026-11-20-on-the-spelling-families-and-the-examples)):
C9 and C10. `flat_index` closed, Steinschlag 12.8 % faster; a row pointer kept by hand pays one
addition a round until L4 (`rows_ptr` +3.5 %, Er-Tüp +0.2 %).

Done when each rule has its test, the spelling test's gaps marked C1, C6 and C7 are closed or
the remaining cause is named in their `gap:` line, and no pass handles a form the canonical
form excludes.

## Flaw R: the range analysis loses facts it could have

Many decisions hang on one question to `Ranges`: is this index never negative, does this byte
counter not wrap. When the answer is "unknown" the code falls back to a 24-bit pointer or a
counter in memory. Three weaknesses make it say "unknown" for ordinary loops.

### R1. Widening throws away the bound that did not move

[Ranges.java:108](../compiler/src/main/java/dampf/mir/Ranges.java#L108): after 24 changes a value
is widened to its type's whole range. For `int16_t i` from 0 upwards only the upper end grows,
but the lower end is thrown away too, and narrowing with `i < 100` can only bring the upper end
back. So `i` is "-32,768..100", the comparison stays signed and the array access goes through a
24-bit pointer.

Fix: remember per value which end moved since the last change. Widen only that end to the type's
limit; widen both only if both moved. If the stepped value could then leave the type
(`add` of a range that reaches the limit), `eval` already returns the whole range, the join
changes the other end, and the next widening takes both: the result is still sound and the
iteration still ends, after at most two widenings per value. The cliff at 24 rounds goes
away with it, because a counter's range no longer depends on whether the iteration reached the
bound before `WIDEN_AFTER`. Tests: `RangesTest` with a signed counter up, a signed counter down,
an unsigned counter, and a counter that really wraps; `c_i16_lt100` in the spelling test;
`Ranges.checker` runs in every execution and random test as now.

Built (2026-11-20, [log](optimizations-log.md#widening-by-the-end-that-moved-2026-11-20-on-the-spelling-families)):
eight gaps closed, the cliff at 24 rounds gone; no example changed its code.

### R2. Bytes above 127 have no range

A range is one interval of signed values. An `i8` that holds 0..199 is not one, so it is the
whole range; conditions with an unsigned constant above the signed maximum give nothing
([Ranges.java:318](../compiler/src/main/java/dampf/mir/Ranges.java#L318)), and so does
`uge x, 1`. [optimizations.md](optimizations.md#tried-and-rejected) records the consequence
("it needs unsigned ranges for bytes").

Fix: keep two intervals for every value, one of its signed and one of its unsigned reading, both
true for every execution. Every transfer function computes both (the unsigned one of an `add`
is the whole range when the sum may pass the unsigned maximum, as the signed one is today for
the signed limits); each can tighten the other where one lies on one side of the sign change. A
condition constrains the interval of its own signedness and then the other if it can. `of` and
`at` keep returning the signed interval; add `ofUnsigned` and `atUnsigned`, and replace the
callers of `Ranges.unsigned(range, type)`, which can only succeed when the signed interval
happens to be convertible: `Induction` (lines 64 and 255), `RangeNarrow` (208, 209),
`Timing` (442), `SelectionPlan` (358), `ArithSelection` (26, 27), and inside `Ranges` (289, 499).
`Ranges.checker` checks both intervals. LLVM keeps one interval that may wrap around
(`ConstantRange`); two plain intervals are easier to get right and to test.

Tests: the byte counters with bounds 129, 200 and 255, up and down, in `RangesTest` and in the
spelling test. Retires what is left of `slow-u8-counter`, and the entry in *Tried and rejected*.

Built (2026-11-20, [log](optimizations-log.md#unsigned-ranges-2026-11-20-on-the-spelling-families-and-the-examples)):
as described; `slow-u8-counter` and the entry are gone, the byte gaps closed. A byte counting
down with `!= 0` (the canonical form of `> 0`) still gets no bound from its test: L2.

### R3. An array access says nothing about its index (a decision: needs an ADR record)

`a[i]` with a 16-bit `i` is indexed with X only if the ranges show `i >= 0` as a signed number
([FrameLayout.java:181](../compiler/src/main/java/dampf/backend/FrameLayout.java#L181)); otherwise
the address is computed into a 24-bit pointer every time (`l_u16_local_bound`, and the C guide's
`fast-range-index`). But C makes an access outside the object undefined, no object is larger
than 32 KB (ADR record 10), and `PointerOffsets` already relies on that rule.

Fix: a `ptradd` whose base is a global or a slot itself and whose every use is the address of a
load or store (directly, or through a constant `ptradd` that is folded) is indexable without a
range. Then, as a second step, the range analysis learns from it: after such an access the index
lies between 0 and the object's size minus the access's size, in the blocks the access
dominates.

Before taking it, make the tests able to catch a program that breaks the rule: the MIR
interpreter should fail on a load or store through `ptradd @object, i` outside the object, as it
fails on a wrong hint, and the PC build's UBSan catches most cases already
(`-fsanitize=undefined` in `PcReference`). The record states that an out-of-bounds index, which
gcc's code tolerates by reading a neighbour, reads another address on the SNES. Retires
`fast-range-index`.

### R4. Inlining drops the range of a derived variable

`Induction` gives the variable it derives for `i * C` the range it knows (`Instr.Phi.known`);
the selection then indexes with X, since the offset is never negative. `Inline` copies a φ
without that field ([Inline.java:224](../compiler/src/main/java/dampf/mir/Inline.java#L224)), and
the passes do not derive it again in the caller, so the inlined loop computes a 24-bit pointer
every round. Found by the spelling test: `fill_u16` costs 1,945 cycles as a function and 3,930
inlined, `pairs_u16` 29,523 inlined. Every small function with such a loop that dampf inlines is
affected, in the examples too.

Fix: copy `known` with the φ. Test: an `InlineTest` case where the callee's φ has `known` and
the copy keeps it, and the spelling gaps marked R4.

Built (2026-11-20, [log](optimizations-log.md#ranges-kept-when-inlining-2026-11-20-on-the-spelling-families-and-the-examples)):
18 of the 90 spelling members faster, none of the gaps closed alone (each has a second cause,
mostly L4 or L5), `pairs_ptr` 3.4 % slower until C6; the examples 0.4 % faster on the geometric
mean.

## Flaw L: four passes recognise loops, each differently

**What is wrong.** "A φ of the loop head that every way back steps by the same constant" is
found by:

| Where | Accepts | Also demands |
| --- | --- | --- |
| [Induction.step](../compiler/src/main/java/dampf/mir/Induction.java#L203) | `add` only, constant on either side | moves the loop test only for `i8` counters ([line 90](../compiler/src/main/java/dampf/mir/Induction.java#L90)) |
| [SelectionPlan.findXCounters](../compiler/src/main/java/dampf/backend/SelectionPlan.java#L300) | `add φ, const` only | the step last in the latch, the latch ends in a jump, the step has one user, compared only with constants by `eq`, `ne`, `ult`, `uge`, not used after the loop |
| [Timing.headRuns](../compiler/src/main/java/dampf/backend/Timing.java#L230) | `add` and `sub` | its own two forms of exit test |
| `DeadLoops` | the φ or the stepped value in the test | one way out, its own proof that the loop ends |

`Ranges` then finds the counter's interval a fifth way, by iteration. No pass can ask how many
rounds a loop has, although `Timing` computes it and `DeadLoops` proves that it is finite. The rule
`slow-u16-counter-words` is `Induction` line 90: for an `i16` counter the test is not moved to
the stepped offset, because "the offset might wrap", which the number of rounds (64) would
settle.

**The fix.** One analysis, `dampf.mir.CountedLoops`, on the canonical form of C. For every loop
of `Loops.find` it gives:

- the preheader, the latch and the exit edges;
- the basic counters: each header φ of integer type with a start value (from the preheader) and
  a constant step c, where every way back brings `add φ, c`;
- the exit test, if a block that every round passes leaves the loop on a comparison of a
  counter, or of its stepped value, with a bound that is a constant or defined outside the loop;
  which of the two is compared, the predicate, and which side leaves;
- the number of rounds: exact when start, step and bound are constants; else an upper limit from
  the ranges of start and bound; else unknown. It is derived once, with the reasoning
  `DeadLoops` and `Timing.untilEqual`/`untilBound` have today (the cases `!=` with an odd step,
  `<` going up, `>` going down, and "does not wrap before it reaches the bound");
- per counter, whether it can wrap, and its interval: start to start + rounds x step.

Steps, each its own commit:

- **L1.** Write `CountedLoops` with MIR text tests for every form of the spelling families, and
  for the loops it must refuse (a counter stepped twice, a step in a branch, a bound written in
  the loop, a counter that wraps). No pass uses it yet: all hashes stay.
- **L2.** `Ranges` takes a counter's interval from it (the φ's `known` field exists for this, see
  `Instr.Phi.known`, which `Induction` and `PointerOffsets` set). The loop `i != 100` gets its
  range. Changes code.
- **L3.** Move the users over, one per commit: `Timing.headRuns` and `DeadLoops` (should keep
  every hash and every bound; `ExamplesTimingTest` checks the bounds), then `Induction`, then
  `findXCounters`, which then accepts a test on the stepped value (`INX`, `CPX`, branch: the
  form of a `do` loop).
- **L4.** `Induction` works on any counter with a known number of rounds: a derived variable
  `start x F + n x c x F` for `i * F`, and also for `i + K` (the index `i - 1`). It moves the
  loop test to a derived variable for every counter type when the number of rounds shows the
  variable does not wrap, and when the counter then has no other use. Retires
  `slow-u16-counter-words`.
  Built (2026-11-20, [log](optimizations-log.md#affine-values-of-a-counter-as-variables-the-loop-test-on-them-2026-11-20-on-the-spelling-families-and-the-examples)):
  L4 as described, for every affine value (also `r * 16 + c`, and a byte counter's zero
  extension); `slow-u16-counter-words` and `slow-word-array` retired. Then the X pattern takes a
  test against a 16-bit bound in a place (`CPX` on it), which a moved test often has
  ([log](optimizations-log.md#a-counter-in-x-compared-with-a-value-in-its-place-2026-11-20-on-the-spelling-families-and-the-examples)).
- **L5.** Two counters of one loop with the same start and step are one: replace the second by
  the first (`x_ptr_walk`: the counter and the pointer's offset). A counter that differs from
  another by a constant factor or offset is derived from it and falls under L4.
  Built (2026-11-20, [log](optimizations-log.md#two-counters-with-one-start-and-step-are-one-2026-11-20-on-the-spelling-families-and-the-examples)):
  the first sentence, before L4, which needed it (L4 made the second variable of `apply_s` in
  the pair loops after inlining, and moved the loop test onto it). The second waits for L4.
- **L6.** A counter read after the loop, in a loop with one exit and an exact number of rounds,
  is replaced there by its final value. (`i` used after the loop keeps it out of X today.)

Done when the gaps marked L in the spelling test are closed and `Induction`, `SelectionPlan`,
`Timing` and `DeadLoops` contain no loop recognition of their own.

Built (2026-11-20): L1, `dampf.mir.CountedLoops` as described, its class comment the
definition. `!=` counts as `<` (`>`) when a step of 1 (-1) starts on the near side of the
bound. `CountedLoopsTest` has the forms of the spelling families, the loops to refuse, and
random counting loops (three shapes, every predicate, zero-extended or not, constant or ranged
start and bound) whose claims the interpreter checks; four mutations of the arithmetic each
made it fail. A bound may change in the loop, unlike the description above
([log](optimizations-log.md#bounds-that-change-in-the-loop-2026-11-20-on-the-execution-programs)):
`Timing` takes such bounds, and an ordered test needs only their range.

Built (2026-11-20, [log](optimizations-log.md#counters-take-their-range-from-the-counted-loop-2026-11-20-on-the-spelling-families-and-the-examples)):
L2. `Ranges` meets a counter φ with what `CountedLoops` says, before and after widening, and
`x != c` cuts c off an end of x's interval where it holds, so that `i - 1` in the body of
`i != 0` is an index. `n_u16_ne100` closed; the loops counting down lost 25 to 60 %, and wait for
L3, L4 and C5 now.

Built (2026-11-20): L3 for `Timing` and `DeadLoops`, every hash and every bound kept. `Timing`
asks `CountedLoops` how often a head runs (its own `untilEqual` and `untilBound` are gone);
`DeadLoops` takes the loop's exit from it, still only on a constant, not zero-extended, and with
`!=` only for an odd step, which is all its own proof took; the commit after it lifts that
([log](optimizations-log.md#dead-loops-proved-by-the-counted-loop-2026-11-20-on-the-execution-programs)). Then `Induction`: its counters are those of `CountedLoops` (with
one step instruction), every hash kept. Last `findXCounters`: its counters from `CountedLoops` (every hash
kept), then a step anywhere in the round, its stepped value read in X by the exit test and as
an index, and a signed test against 0
([log](optimizations-log.md#counters-in-x-read-after-their-step-2026-11-20-on-the-spelling-families-and-the-execution-programs)).
With that the `do` loop and the loops counting down are closed. `SelectionPlan`, `Timing`,
`DeadLoops` and `Induction` recognise no counter of their own any more; the 54 gap lines before
L1 are 38, of which L4 names 19.

## Flaw B: no register allocation, and places are decided before the code exists

**What is wrong.** Three things, in order of weight.

1. Every MIR value has a fixed place in memory. A, X and Y hold a value across instructions only
   where a pattern says so: `findForwards` (a result handed on in A to the next instruction),
   `findXCounters` (a loop counter in X, with the conditions listed under L), `findYIndexes`,
   and on LIR `RedundantLoads`, `IndexInY`, and the selector's own `aHolds`, `xHolds`, `yHolds`
   ([SelectionPlan.java](../compiler/src/main/java/dampf/backend/SelectionPlan.java),
   [Selection.java:212](../compiler/src/main/java/dampf/backend/Selection.java#L212)). Each
   pattern is all or nothing. A loop whose counter is also added to something
   (`o_u16_i_used`) keeps the counter in memory, although `TXA` would do. A loop that calls a
   function has no register at all, since every call is taken to destroy X and Y.
2. `FrameLayout` decides on MIR which values share a place, and has to predict what selection
   will write: which comparison is evaluated at the branch, which `ptradd` is never computed,
   in which order a two-word result is written, where φ copies go. Its class comment lists six
   such assumptions
   ([FrameLayout.java:19](../compiler/src/main/java/dampf/backend/FrameLayout.java#L19)). A
   wrong prediction is a miscompilation that no verifier sees, because the liveness is computed
   on one representation and the reads and writes happen in another. The φ bug of `b33eeba` and
   the two-loops bug of `02fa308` were both this; the X bug of `regress_x_step_address.c` was the
   same kind of prediction in `SelectionPlan`.
3. LIR operands are text (`"z:dampf_frame+6"`). The passes find out what an instruction reads
   by parsing it (`Selection.frameOffset`, `RegContents`), and a place is an address from the
   start, so nothing after selection can move a value.

**How others do it.** LLVM and GCC select instructions first, onto an unlimited supply of
virtual registers, and allocate afterwards on that code, where every read and write is an
operand of an instruction (LLVM's machine IR, GCC's RTL with pseudo-registers). Eliminating φs
and removing copies are passes of their own on that level. For the 6502 family, llvm-mos gives
the allocator the zero page as a class of "imaginary registers" next to A, X and Y, and KickC
tries combinations of register assignments and keeps the cheapest by cycles. dampf's frame
places are such imaginary registers already, and static frames in a program without recursion
are what llvm-mos does too. What is missing is the order: allocation after selection, by cost.

**The fix.** In four steps. B1 and B2 should change no code quality by more than ADR record 24
allows; B3 is where the speed comes from.

### B1. Virtual places in LIR (a decision: amends ADR record 14)

Selection writes operands that name a value, not an address: word w of MIR value %n, a φ's
incoming place, a temporary. Give `LInstr` a typed operand instead of a string (a sealed type:
immediate, symbol plus offset, virtual place with word and width, each with its addressing
form), so a pass asks an instruction what it reads and writes. Until a later phase assigns
addresses, the LIR text names the places symbolically; after it, the text is ca65 source as
ADR record 14 says. The record that amends it says that LIR has these two stages.

Move the passes that read operand text (`RegContents`, `RedundantLoads`, `DeadStores`,
`IndexInY`, `Peephole`, `KnownCarry`, `ByteStretches`) to the typed operands. This is a move:
with the places assigned exactly as `FrameLayout` assigns them today, every hash of
`BackendOutputTest` must stay.

### B2. Places assigned on LIR

Compute liveness of the virtual places on the LIR (`DeadStores` has this analysis for frame
bytes; generalise it) and colour them into the frame there, with the greedy colouring by byte
ranges that `FrameLayout` has. φs always go through their incoming place (the scheme that is
"correct for any set of φs, with no analysis", see `MirCodeGen`'s class comment); a copy between
two places that do not interfere is removed by giving both the same place, which is ordinary
coalescing. Then delete from `FrameLayout` everything that predicts selection: `direct`,
`samePlaceOnly`, the special handling of fused and folded instructions in the liveness. What
stays in it is what is really about MIR (which `ptradd` is folded, which comparison is fused),
as input to selection.

Add to `LVerifier`: no virtual place is read on a path where it was not written, and after
assignment no two places that are live together share a byte. This check would have caught both
frame bugs.

Expect small differences in the code (places shared a little differently). Measure, log.

### B3. X and Y assigned by cost

A pass on LIR, before places are assigned. For a virtual place p of one word and a register R
(X, then Y), over a region (p's whole live range, or one loop with a load before it and a store
after it where p is live outside):

- **Legal?** Every instruction in the region that mentions p has a form with R, from a table:
  `LDA p` is `TXA`; `STA p` is `TAX`; `LDX p` disappears; `LDY p` is `TXY`; `INC p`, `DEC p` are
  `INX`, `DEX`; `STZ p` is `LDX #0`; `LDA p`, `CMP q` with A dead afterwards is `CPX q`; an
  indexed access that loaded X from p needs no load. An instruction with p as a memory operand of
  arithmetic (`ADC p`, `AND p`, `CMP p`) has none: either p is not eligible, or the cost of
  keeping a copy in memory is counted. Accesses in 8-bit mode make p ineligible while X and Y
  stay 16 bits wide.
- **Free?** Nothing else in the region writes R: another index load, a call, a runtime routine,
  `MVN`. A conflict with another candidate is settled by benefit; the loser may still get the
  other register where its instructions have that form (`LDA abs,Y` exists, `STZ abs,Y` and
  `LDA long,Y` do not; the table says so, where today `SelectionPlan.otherIndex` knows it by
  hand).
- **Benefit.** The sum over the region's instructions of (cycles before - cycles after) x the
  weight of the block: the profile's count where there is one (`--block-counts`), else 8 to the
  power of the loop depth. The cycles come from `Cycles65816`, as for the listing.

Assign greedily by benefit. Build it beside the patterns first and compare on the spelling
families and all examples; when it gives the same or better code everywhere, delete
`findXCounters`, `findYIndexes`, `IndexInY` and the `xHolds`/`yHolds` bookkeeping in `Selection`
in a separate step. If it stays behind the patterns somewhere, write down where and why before
deciding (ADR record 24 sets the price that may be paid).

After that, two extensions that the patterns could never have:

- **Registers across calls.** Compute per function, callees first (the call graph has no
  cycles), which of X and Y its code writes. A call of a function that leaves X alone does not
  end a region. Small leaf functions that are not inlined keep a loop's counter in X.
- **Splitting.** A place that is hot in one loop and cold elsewhere gets a register for the loop
  only.

Then try whether `findForwards` and `Schedule` are still needed: with every result stored to a
virtual place, `RedundantLoads` removes the reload and the dead store goes with the place. If
the output is the same without them, they go.

Tests for B: every table entry as a `LirTest` case (text in, text out, and the emulator run of
the execution tests for meaning); the gaps marked B in the spelling test; the examples' benchmark
before and after. The roadmap's estimate for register allocation was 10 to 20 % of the game's
cycles; treat it as an estimate until measured.

### B4. A convention for A is not part of this

A stays the working register of selection: operands are loaded into it and results stored from
it, and the LIR passes remove what is redundant. Allocating A like X and Y would mean a
different selector (tree matching with costs, as HotSpot's and GCC's back ends have). It is not
needed for the flaws measured here; note it in the roadmap as a later question.

### B5. The accumulator's width as a data flow problem

Today selection switches to 8 bits and back around single instructions, and `ByteStretches`
joins short stretches inside a block (roadmap M9 row 3). The general form: every instruction
needs M = 8, M = 16, or does not care; choose the mode of every block's entry and exit so that
the weighted number of `SEP`/`REP` is smallest, and place them on the edges. GCC has a generic
pass for exactly this (mode switching, used for FPU modes). It needs B1's typed operands (a
value kept zero-extended in two bytes is read differently in each mode) and is independent of
B3. After C5 the most frequent case, the 8-bit step of a byte counter, is gone already, so
measure what is left before building it.

## Flaw K: no decision uses the cost model

**What is wrong.** dampf knows the exact cycles of every instruction, and has a static timing
analysis per function. Neither takes part in a decision: outside that analysis, `Cycles65816`
is used by the assembler, the listing, the verifier and the profile tool only. Every transformation is applied when its
pattern matches, on the belief that it pays off. `Induction` makes a new variable for every
product whether or not the old counter can then go (`fill_u16_counter`: two counters, one in
memory). The inlining limits are numbers tuned on one game, and raising one made the game
slower for reasons [optimizations.md](optimizations.md#tried-and-rejected) could not name.

**The fix.**

- **K1.** One class for the weight of a block (the profile's count, else 8 to the power of the
  loop depth) and the cycles of a LIR instruction, used by B3 and by the block layout. No pass
  invents its own.
- **K2.** `Induction` after L4: a derived variable is kept only if it removes a multiplication
  or lets the original counter go; otherwise the access keeps computing from the counter. The
  measure is the number of values the loop carries from round to round, weighted.
- **K3.** A report of direction. `BackendOutputTest` stores a hash per program; store beside it
  the static worst case of every function of every example and of the spelling families in one
  tracked text file (`compiler/src/test/resources/cost-baseline.txt`, rewritten by
  `-PbackendOutput=update`). The diff of that file in a commit then shows which functions got
  slower, which is what a reviewer needs and what the hash hides.
- **K4.** For choices where no local rule is right (the inlining limits): build the function
  both ways and keep the cheaper by the static bounds, or do it per program with the user's
  workload, as the `--tune` idea in [optimizations.md](optimizations.md#ideas-not-yet-built)
  says. Last, and only if the examples show a gain.

## Order of work

| # | Step | Depends on | Changes code | Should close |
| --- | --- | --- | --- | --- |
| 1 | T1 spelling test, T3 pass limit | | no | |
| 2 | T2 random programs | | no | (may find bugs: fix them first, each with an execution test) |
| 3 | C1 to C3 canonical arithmetic and comparisons, C9 and C10 addresses | 1 | little | part of `g`, `h`, `m`; `table-2d` |
| 4 | R4 ranges kept when inlining, R1 widening by end, R2 unsigned intervals | 1 | yes | the gaps marked R4, `c_i16_lt100`, the bound cliffs, `slow-u8-counter` |
| 5 | L1 to L3 `CountedLoops` and its users | 3, 4 | yes | `d`, `i` (done 2026-11-20: `i`, `g`, `n` and the loops counting down closed; `d` waits for C5) |
| 6 | C5, C8, L4 to L6 | 5 | yes | `g`, `h`, `m`, `x`, `slow-u16-counter-words` |
| 7 | C6 pointer comparisons, C7 loads out of loops | 3 | yes | `y`, part of `j`, `k` |
| 8 | R3 indexes from array bounds (ADR) | 4 | yes | `l`, `fast-range-index` |
| 9 | K3 cost baseline | 1 | no | |
| 10 | B1 typed operands and virtual places (ADR) | 2 | no | |
| 11 | B2 places assigned on LIR | 10 | little | the class of the frame bugs |
| 12 | B3 X and Y by cost, then the patterns deleted | 11, K1 | yes | `j`, `k`, `l`, loops with calls |
| 13 | B5, K2, K4 | 12 | yes | |

Steps 3 to 8 are small (each a few hundred lines with tests) and remove most of the table at the
top. Steps 10 to 12 are the large ones and the only ones that change the architecture. Do 1 and
2 first even though they produce no faster code: without them nobody can tell whether 3 to 12
worked, and step 2 guards the rewrite of the back end.

Signs that the work is done: the spelling test has no `gap:` line; the C guide has no rule that
tells the programmer how to write a loop counter; a miscompilation found by a random program is
named by `dev/bisect_passes.sh` down to one pass on one function.

## Appendix: the loop spellings

The functions of the table, with the `test_main` they were measured with. The spelling test has
them as its first family ([sum-bytes.c](../compiler/src/test/resources/spelling/sum-bytes.c)),
with a `setup` in place of `test_main` and two more spellings. `test_main` fills `data` and stores every result; the loops' time does not depend
on what `data` holds.

```c
#include <stdint.h>

uint8_t data[200];
uint8_t n8 = 100;
uint16_t n16 = 100;
uint16_t r[15];

uint16_t a_u16_lt100(void) { uint16_t s = 0; uint16_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t b_u8_lt100(void) { uint16_t s = 0; uint8_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t c_i16_lt100(void) { uint16_t s = 0; int16_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t d_u8_ne100(void) { uint16_t s = 0; uint8_t i; for (i = 0; i != 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t e_u8_le99(void) { uint16_t s = 0; uint8_t i; for (i = 0; i <= 99; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t f_u16_le99(void) { uint16_t s = 0; uint16_t i; for (i = 0; i <= 99; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t g_u16_down(void) { uint16_t s = 0; uint16_t i; for (i = 100; i > 0; i--) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t h_u8_down(void) { uint16_t s = 0; uint8_t i; for (i = 100; i > 0; i--) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t i_u16_dowhile(void) { uint16_t s = 0; uint16_t i = 0; do { s = (uint16_t)(s + data[i]); i++; } while (i < 100); return s; }
uint16_t j_u16_var_bound(void) { uint16_t s = 0; uint16_t i; for (i = 0; i < n16; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t k_u8_var_bound(void) { uint16_t s = 0; uint8_t i; for (i = 0; i < n8; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t l_u16_local_bound(void) { uint16_t s = 0; uint16_t i; uint16_t n = n16; for (i = 0; i < n; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t m_u16_from1(void) { uint16_t s = 0; uint16_t i; for (i = 1; i < 101; i++) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t x_ptr_walk(void) { uint16_t s = 0; const uint8_t *p = data; uint16_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + *p++); return s; }
uint16_t y_ptr_end(void) { uint16_t s = 0; const uint8_t *p; for (p = data; p < data + 100; p++) s = (uint16_t)(s + *p); return s; }

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)(i * 7);
    }
    r[0] = a_u16_lt100();  r[1] = b_u8_lt100();   r[2] = c_i16_lt100();     r[3] = d_u8_ne100();
    r[4] = e_u8_le99();    r[5] = f_u16_le99();   r[6] = g_u16_down();      r[7] = h_u8_down();
    r[8] = i_u16_dowhile(); r[9] = j_u16_var_bound(); r[10] = k_u8_var_bound(); r[11] = l_u16_local_bound();
    r[12] = m_u16_from1(); r[13] = x_ptr_walk();  r[14] = y_ptr_end();
}
```

The worst cases measured on 2026-11-20, in CPU cycles: `a` 2,525, `b` 2,525, `c` 6,133,
`d` 4,628, `e` 2,525, `f` 2,525, `g` 6,033, `h` 6,333, `i` 4,017, `m` 4,333, `x` 6,132,
`y` 13,876. For `j`, `k` and `l` the timing report assumes the largest bound the type allows
(65,535 or 255 rounds), so their worst cases are 4,325,348, 14,576 and 3,604,465; divided by
those rounds they are the 66, 57 and 55 cycles of the table. This is why T1 measures in the
emulator.

The bound cliffs (the second table) were measured with the same loop and the bounds 20, 30, 128
and 129; add them to the family as `int16_t` and `uint8_t` spellings with those bounds, each
compared with the `uint16_t` loop of the same bound.
