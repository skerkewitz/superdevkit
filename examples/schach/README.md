# Schach

A chess game: the player is white, the console black. An example of superdevkit that has no
action at all but a search that runs for seconds over tables, which makes it a test of dampf's
code generator as much as a game. The plan is [docs/chess-game-proposal.md](../../docs/chess-game-proposal.md).

State: step 1 of the proposal, the rules. There is no screen yet.

## The rules

`src/board.c` and `src/chess.h`: a 0x88 board with a piece list per side, moves generated
pseudo-legally into one shared list, made on the board and dropped when the own king is then
attacked. Nothing is recursive: perft keeps one entry per ply in fixed arrays, as the search will.
Every rule is there: castling (not out of check, not through an attacked square, rights lost by
king and rook moves and by captures on the rook's square), en passant (also the one that uncovers
the king), promotion to all four pieces, the end of a game (mate, stalemate, 50 moves,
threefold repetition, too little material to mate), and moves as text (`Nbd2`, `exd6`, `O-O-O`,
`e8=Q+`).

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

A leaf here is a move made, tested and taken back, with the move generation shared out; a node
of the search will cost more (evaluation, ordering). Where the cycles go: `make_move` 39 %,
`perft` 19 %, `gen_moves` 16 %, `unmake_move` 14 %, `attacked` 9 %.

What dampf's code costs here (from `make_move`'s assembly), the work for the optimizer the
proposal expects:

- **32-bit values go through frame slots.** `hash ^= zob_side` loads both operands into the
  frame, XORs into a third slot and copies that back: 81 cycles, where `lda/eor/sta` twice is
  about 30. Every Zobrist update pays this.
- **A shift by a constant above 8 is a loop.** `zob[p][sq]` (rows of 512 bytes) is `shl 9`, done
  as `ldx #9; asl; dex; bne`, about 65 cycles; `xba` and one `asl` would do.
- **Tables in RAM are read through a 24-bit pointer.** `psq_val[p][to]` builds a pointer in the
  direct page and reads `[dp],y` instead of `lda psq_val,x`.
