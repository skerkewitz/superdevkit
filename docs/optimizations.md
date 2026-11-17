# Optimizations

The catalogue from the kickoff, with what each one has shown in measurements. Expected gains are
hypotheses until the naive compiler (M2) works and each row is measured.

## Baseline

Blubber Blasen's benchmark (`tools/snesbench` in the game repository) runs the same game code with a
fixed input for 1,800 ticks, with every PVSnesLib call stubbed out, and measures each tick in
Mesen 2. One NTSC frame is 357,366 master clocks. Results from the game's `build/bench/` as of
2026-11-17 (`calypsi_asm`, the latest Calypsi run):

| State | Ticks | 816-tcc | Calypsi |
| --- | --- | --- | --- |
| title | 61 | 1.90 frames | 0.91 frames |
| story | 530 | 1.22 | 0.58 |
| round intro | 120 | 6.47 | 4.11 |
| play | 1,089 | 6.42 | 2.72 (worst tick 3.82) |
| all | 1,810 | 4.76 | 2.13 |

The kickoff's goal is game logic in at most 0.6 frames in the busiest scene. During play that
means dampf has to be about 4.5 times as fast as Calypsi on average and 6.4 times on the worst
tick. The kickoff expects a factor of 3 to 6 over dampf's own naive code generator, so the naive
generator has to be no slower than Calypsi, or the game has to get cheaper as well (the game's
ROADMAP already lists a cheaper CPU partner as a candidate).

The round intro (4.11 frames with Calypsi) is not game play and may be allowed to take more than
one frame; that is for the game to decide.

The two compilers give different checksums from tick 1,133 on (tick 596 in older runs), so at least
one of them does not compute what the PC computes. dampf's benchmark run has to match the PC
reference checksums (`tools/snesbench/pc`) on every tick.

## Catalogue

| # | Optimization | Prerequisite | Measured gain | Status |
| --- | --- | --- | --- | --- |
| A | Static frames in the direct page | complete call graph, no recursion (given, see subset.md) | | open |
| B | Width analysis (8/16 bit) | H | | open |
| C | Inlining and calling convention | call graph | | open |
| D | Data layout (split struct arrays, D at the current object) | | | open |
| E | 8.8 fixed point as byte accesses | B | | open |
| F | Loops | H | | open |
| G | Branches on flags | | | open |
| H | Standard optimizations | IR in SSA form | | open |
| I | Multiply and divide | range analysis for signed division | | open |
| J | Banks and placement | pointer width analysis | | open |
| K | Peephole | | | open |
| L | `switch` as jump table | | | open |
| M | Copy and fill with `MVN`/`MVP` or DMA | | | open |
| N | Values in X/Y | | | open |
