# Proposal: a chess game as an example of superdevkit

Status: proposal, 2026-11-18. Nothing is built yet.

A small chess game for the SNES, written in dampf's C subset like the other examples
(`examples/hello`, `examples/catchme`, `examples/steinschlag`). The player is always white, the
console is always black. It is something quite different from the examples so far: no action, no
physics, but a search that runs for seconds and hot loops over tables. That makes it a good test of
dampf's code generator as well as a game.

Real chess programs for the SNES existed (The Chessmaster, 1991). This one has its own name, look
and code; nothing is taken from an existing game.

## What the first version must do

- The player moves white with the joypad (cursor, A picks a piece up, A again puts it down, B
  cancels). The console answers as black.
- Every rule of chess, without exceptions: castling on both sides (not out of check, not through
  an attacked square, not after the king or that rook has moved), en passant (also the case where
  taking en passant uncovers an attack on the own king), promotion to any of the four pieces with
  a choice for the player, pins, double check.
- The end of the game: checkmate, stalemate, the 50-move rule, threefold repetition, insufficient
  material. The screen says why the game ended.
- The console plays at roughly 1200 to 1400 Elo. The first user of the game beats 1200 and calls
  1400 a coin flip, so that is the target. The console must never be a complete disaster: no
  piece left hanging for nothing, no missed mate in one.
- Runs on the SNES and on the desktop (`tools/build_desktop.sh`) with the same picture, pixel for
  pixel, as `dev/compare_example.sh` checks for the other examples.

Not in the first version: playing black, two players, a clock, sound, saving a game.

## The two constraints of the subset

The subset ([subset.md](subset.md)) has no recursion and no heap (ADR record 6). Neither is a real
obstacle for chess.

**No recursion.** Alpha-beta search is usually written as a recursive function, but it can be a
loop over an explicit stack of fixed arrays, one entry per ply: where this ply's moves start in
the move list, which move it is at, alpha, beta, the best score and move so far, and what is
needed to take the move back (captured piece, castling rights, en passant square, 50-move
counter, hash). 32 plies are more than the search will ever reach. Quiescence search runs in the
same loop, with a flag per ply.

The loop has an advantage the recursive version lacks: it can stop after any node and carry on in
the next frame. The search then runs a fixed number of nodes per `game_tick`, the cursor and a
"thinking" display keep moving, and `game_tick` stays inside its cycle budget
([timing.md](timing.md)).

**No heap.** Chess programs rarely need one. All move lists share one static array (a few
thousand entries is plenty), each ply using a section of it. The position history for repetition
is a static array of hashes, one per half move since the last capture or pawn move (at most 100).
A transposition table of fixed size fits in WRAM, for example 4096 entries of 8 bytes, 32 KB.

## How it is made

**The board** is 0x88: 128 bytes, a square is `rank * 16 + file`, and `sq & 0x88` is non-zero
exactly when a step left the board. One test per step, all 8-bit, which suits the 65816. A
piece list per side (16 entries) saves scanning the board for pieces and for the king.

**Moves** are generated pseudo-legally, made on the board, and dropped when the own king is then
attacked. That is slower than generating only legal moves, but much harder to get wrong, and it
handles pins, the en passant discovery and checks for free. A move fits in 16 bits: from, to and
a flag (promotion piece, castling, en passant, double step).

**The evaluation** is material plus a piece-square table per piece (one for the king in the
middle game and one for the endgame), from black's or white's view by mirroring the square. Later,
if the strength is not enough: pawn structure (doubled, isolated, passed pawns) and king safety.

**The search** is iterative deepening with alpha-beta and a quiescence search over captures.
Moves are ordered: the move of the transposition table first, then captures by MVV-LVA (most
valuable victim, least valuable attacker), then killer moves. The hash is Zobrist, 32 bits
(`uint32_t`) or two times 16 bits if 32-bit operations turn out too slow on the 65816.

**The opening book** is a small table in ROM, a few hundred positions with one or more replies
each, so the console does not open with silly moves and does not play the same game every time.
The choice among replies needs a random source that is the same on the SNES and the desktop: a
seed from the frame count when the player presses Start.

**The screen** uses 16 x 16 background tiles as Steinschlag does: the board is 8 x 8 entries,
128 x 128 pixels, with the pieces in the tiles. The cursor and the piece in the hand are sprites.
Beside the board: whose move it is, the last moves in algebraic notation, the thinking display,
and the menu for the promotion. The graphics are drawn by a script in `dev/art/` like the other
examples'.

## Deterministic on both platforms

The search is limited by the number of nodes, never by time. A node count per frame, and a node
limit per move, give the same move on the SNES and on the desktop, so `dev/compare_example.sh`
can check a whole game played from `check.txt`. The node limit is also the knob for the strength.

## How it is checked

**Perft** proves the rules. Perft counts all legal move sequences to a depth; for a set of
standard positions the counts are known exactly, and those positions contain every special case
(castling, en passant, promotions, pins, checks). The counts below are from the Chessprogramming
wiki (page "Perft Results"), to be checked against it when the test is written:

| Position | FEN | Depth 1 to 4 |
| --- | --- | --- |
| Start | `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1` | 20, 400, 8902, 197281 |
| Kiwipete | `r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1` | 48, 2039, 97862, 4085603 |
| 3 | `8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1` | 14, 191, 2812, 43238 |
| 4 | `r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1` | 6, 264, 9467, 422333 |
| 5 | `rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8` | 44, 1486, 62379, 2103487 |

Perft runs on the PC with gcc to depth 4 or 5, and in dampf's emulator to depth 2 or 3, as an
execution test (`src/test/resources/exec/`): the same source, the same counts. A perft "divide"
(the count per first move) finds the wrong move when a count differs.

**The end of the game**: tests with positions for each way a game ends (mate, stalemate, 50
moves, repetition, each case of insufficient material) and for the cases that are not an end
(king and two knights against king is not a draw by the rules, but cannot force mate).

**The strength**: test positions with one right move (mate in one and two, win a hanging piece,
do not take a poisoned one). For the Elo, play many games on the PC against Stockfish with
`UCI_LimitStrength` and `UCI_Elo` set to 1200 and 1400. That needs a UCI front end for the
engine on the PC, a small `main` outside the game. Stockfish's limited levels do not match human
Elo exactly, so this gives a rough figure, and the first user's games give the real one.

## The open question: speed

I do not know yet how many nodes per second the 65816 manages with dampf's code. A guess: one to
two thousand, which allows 3 to 4 plies plus quiescence search in a few seconds of thinking. That
is about what 1200 to 1400 needs, but the guess may be off by a factor of two either way. The
first measurement (perft and a search to fixed depth in dampf's emulator, cycles per node) comes
right after step 1 and decides how much of the evaluation the console can afford. If it is too
slow, that is work for dampf's optimizer (the move generator is a good benchmark) before it is a
reason to make the chess simpler.

## Steps

1. The core: board, move generation, making and taking back moves, legality, perft, FEN for the
   tests. The perft tests on the PC and in the emulator. The first measurement of cycles per node.
2. The console: evaluation, search as a loop spread over frames, transposition table, the end of
   the game. The test positions.
3. The screen: board, pieces, cursor, picking up and putting down, the promotion menu, the move
   list, the messages at the end. The check of a played game in `check.txt`, SNES against
   desktop.
4. Polish: the opening book, tuning the evaluation and the node limit, the Stockfish games for
   the strength.

Each step is a commit of its own, tested and measured. The example goes to `examples/schach`.
