# Schach

A chess game: the player is white, the console black. An example of superdevkit that has no
action at all but a search that runs for seconds over tables, which makes it a test of dampf's
code generator as much as a game. The plan is [docs/chess-game-proposal.md](../../docs/chess-game-proposal.md).

State: steps 1 and 2 of the proposal, the rules and the console's player. There is no screen yet.

## The rules

`src/board.c` and `src/chess.h`: a 0x88 board with a piece list per side, moves generated
pseudo-legally into one shared list, made on the board and dropped when the own king is then
attacked. Nothing is recursive: perft keeps one entry per ply in fixed arrays, as the search will.
Every rule is there: castling (not out of check, not through an attacked square, rights lost by
king and rook moves and by captures on the rook's square), en passant (also the one that uncovers
the king), promotion to all four pieces, the end of a game (mate, stalemate, 50 moves,
threefold repetition, too little material to mate), and moves as text (`Nbd2`, `exd6`, `O-O-O`,
`e8=Q+`).

## The console's player

`src/search.c` and `src/search.h`: alpha-beta (negamax) with iterative deepening, a quiescence
search over captures, a check extension (a side in check is never stood pat, so a mate at the
horizon is seen), a transposition table of 2,048 entries (16 KB), and move ordering: the table's
move, captures by MVV-LVA, killer moves, quiet moves by their piece squares. Quiet moves are
generated only when no capture has cut the node off. The evaluation is material and piece
squares (Tomasz Michniewski's simplified evaluation), with a king table for the middle game and one
for the endgame.

The search is a loop over one entry per ply (`s_*` arrays) with three stages: entering a node,
trying its next move, and taking the child's score back. `search_run(steps)` runs that many
stages and returns, so the search goes on frame after frame. It stops at a node limit, never by
time, and finds the same move on the SNES and on the PC.

## How it is checked

Perft counts every legal sequence of moves to a depth; the five standard positions of the
Chessprogramming wiki ("Perft Results", in `test/perft_positions.h`) hold every special case.

```bash
cc -std=c99 -O2 -Wall -Wextra -Wconversion -fsanitize=undefined,address -I examples/schach/src \
    examples/schach/test/perft_pc.c examples/schach/src/board.c -o build/perft
build/perft 5                     # all five positions to depth 5 (without the sanitizers: 8 s)
build/perft divide 3 "<fen>"      # the count per first move, to find the move that is wrong
```

On the SNES, `SchachTest` builds `test/perft_snes.c` (the same `board.c`) with gcc and with dampf,
runs perft to depth 2 or 3 in dampf's emulator, and compares the counts, the hashes and the
boards afterwards with gcc's (`./gradlew test --tests dampf.SchachTest -i`; with
`SCHACH_PROFILE=1` in the environment it also prints the cycles per function).

## Speed on the 65816

The proposal's open question. Measured 2026-11-18 with `SchachTest`, 15,503 leaves:

| | CPU cycles per leaf | Leaves per frame |
| --- | --- | --- |
| First version (legality by `attacked()` after every move) | 4,286 | 12 |
| Legality only on the line through the king, hash not saved twice, castling hash only when the rights change | 2,811 | 19 |

A leaf here is a move made, tested and taken back, with the move generation shared out. Where the
cycles go: `make_move` 39 %, `perft` 19 %, `gen_moves` 16 %, `unmake_move` 14 %, `attacked` 9 %.

A node of the search (one position entered, in the full or the quiescence search) costs much more:
it generates and orders its moves, and most nodes try only a few of them.

| Search, four positions of 600 nodes | CPU cycles per node | Nodes per frame |
| --- | --- | --- |
| First version | 12,433 | 4 |
| Check known from the move just made, quiescence pruned (pawn-defended victims, under-promotions, 6 plies deep), only the first 8 moves picked by score; `DAMPF_RANGE` on the 16-bit indices of the move list and the table | 11,479 | 4.5 |

The first version also explored too many quiescence nodes: Kiwipete needed 2,961 nodes for depth
1, now 600 nodes reach depth 2. Per call: `gen_moves` 6,100 cycles, `score_moves` 2,700, `next`
1,000, `make_move` 1,100, `attacked` 1,500. About 270 nodes a second: the console's node limit
is set by that (step 3).

What dampf's code costs here (from `make_move`'s assembly), the work for the optimizer the
proposal expects:

- **32-bit values go through frame slots.** `hash ^= zob_side` loads both operands into the
  frame, XORs into a third slot and copies that back: 81 cycles, where `lda/eor/sta` twice is
  about 30. Every Zobrist update pays this.
- **A shift by a constant above 8 is a loop.** `zob[p][sq]` (rows of 512 bytes) is `shl 9`, done
  as `ldx #9; asl; dex; bne`, about 65 cycles; `xba` and one `asl` would do.
- **Tables in RAM are read through a 24-bit pointer.** `psq_val[p][to]` builds a pointer in the
  direct page and reads `[dp],y` instead of `lda psq_val,x`; so does every array with a 16-bit
  index unless a `DAMPF_RANGE` hint bounds it: `mv_from[n] = from` is 32 cycles without the hint
  and 16 with it (`sta mv_from,x`). An index the C code cannot let past the end (it would be
  undefined) could get `abs,x` without the hint.
