# ADR-6FM7MWGX: A switch becomes a jump table where that costs fewer cycles than its compares

**Context.** Until M10 dampf compiled every `switch` as a chain of compares (`CMP #c / BEQ`): 5
cycles for each case before the one taken, so the last cases of a state machine or an
interpreter paid for all the others on every call, and the worst case grew with the number of
cases. A jump table costs the same for every case, but its dispatch (`ASL A / TAX / JMP
(table,X)`, 10 cycles) and the range check in front of it (5 cycles per end) cost more than the
first few compares. The tables had been considered and set aside on 2026-11-18 because the game's
switches were short.

**Decision.** The code generator from MIR makes a `switch` on an 8- or 16-bit value a jump table
where the table's dispatch costs fewer cycles than the chain does on average over the cases,
counted with the cycle table, and where the table has at most four entries per case (values that
are no case go to `default`). A check is left out where the value's range lies within the table,
and the table is widened to the value's range where that drops a check within the same limit. A
first case 0 is tested before the table with a `BEQ` that costs 2 cycles on the other ways. The
table lies in the function's own segment, after its code, so `JMP (abs,X)` finds it in the
program bank. Measured in dampf's emulator against the chain, all cases equally often, the table
wins from:

- 4 cases when the value's range needs no check (`switch (k & 7)`);
- 6 cases from 0 with an unknown upper end (a byte or an enum read from memory);
- 7 cases that start far from 0 or include negative values (two checks, or a subtraction and one).

The rule in the code is the same cost comparison, so it gives these numbers. A switch inside a
loop whose counter dampf keeps in X stays a chain, since the dispatch needs X; 32-bit switch
values stay chains too. The naive code generator keeps its chain (ADR-6F0R6FT6).

**Alternatives.**

- A fixed number of cases: simpler to state, but the break-even moves from 4 to 7 cases with the
  checks a table needs, so a fixed number either leaves cheap tables out or puts dear ones in.
- Assuming that earlier cases are more common than later ones: the examples point that way (an
  empty slot or a falling boulder first), but how much more is a guess. The first case 0 before
  the table covers the most common form of it at almost no cost.
- Splitting a sparse switch into clusters of tables and compares, as larger compilers do: no
  example has such a switch.
- Saving X around the dispatch inside a loop that keeps its counter there: every target would
  need the counter back, which costs about as much as the table saves.

**Consequences.** Every case of a dense switch costs the same, and its worst case in the timing
report no longer grows with the number of cases (`fast-switch-table` in lib/C_STYLE.md). The
timing analysis, the block layout and the LIR passes follow a block with many successors; the
LIR has data blocks for the tables. Where one case is much more common than the others and is
not a first case 0, the chain can be the faster one: Steinschlag's scan of the cave, whose first
case is a boulder, got 0.4 % slower in total. With a profile (`--block-counts`) the decision could
weigh the cases by how often they run; that is not built.
