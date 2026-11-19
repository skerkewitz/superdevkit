# Schach

A chess game: the player is white, the console black. An example of superdevkit with no action
at all, only a search that runs for seconds over tables, which makes it a test of dampf's code
generator as much as a game. The plan was [docs/chess-game-proposal.md](../../docs/chess-game-proposal.md);
all four of its steps are built.

```bash
examples/schach/build.sh                 # the SNES: examples/schach/build/schach.sfc
tools/build_desktop.sh examples/schach   # the desktop (Linux): examples/schach/build/linux/schach
```

On the desktop the keyboard is the joypad: the arrows, X (A), Z (B), Enter (Start).
`dev/compare_example.sh schach` plays a whole game on both with the input of `check.txt` and
compares the pictures, pixel for pixel.

## Playing

Left and right on the title screen choose the level (Easy, Normal, Strong); Start begins a game,
and you play white. The cursor moves with the joypad. A picks up the piece under it, and the
squares it can go to turn green; A on one of them makes the move, A on the piece again or B puts
it back. A pawn that reaches the last rank asks for the piece (up, down, A; B goes back). The
console thinks a few seconds and shows how deep it got; its move stays marked in yellow, a king
in check in red. Beside the board: whose move it is, the console's last move and, at the end, why
the game ended. Below it the last 14 moves. Start after the end begins the next game.

## How it is made

| File | What |
| --- | --- |
| `src/board.c`, `src/chess.h` | the rules: board, moves, making and taking back, legality, FEN, perft, the end of a game, moves as text |
| `src/search.c`, `src/search.h` | the console's player |
| `src/book.c`, `src/book.h`, `src/book_data.c` | the opening book; `book_data.c` is made from `book/lines.txt` by `book/make_book.sh` |
| `src/game.c`, `src/schach.h` | the screen, the joypad, the course of a game; the levels and how much the console thinks a frame |
| `assets/` | `board_gfx.png` (the squares with and without each piece, 16 x 16), `sprites_gfx.png` (the cursor, the pieces), `font_gfx.png` (ASCII); drawn by `dev/art/schach.py` |
| `test/` | the tests and tools below |
| `schach.args`, `build.sh`, `check.txt` | the build, and the input of the check in Mesen and on the desktop |

**The rules.** A 0x88 board (a square is `rank * 16 + file`, and `sq & 0x88` is not zero once a
step left the board) with a piece list per side. Moves are generated pseudo-legally into one
shared list, made on the board and dropped when the own king is then attacked; whether a move
gave check comes from the moved piece and the line through the square it left, not from a full
search for attackers. Every rule is there: castling (not out of check, not through an attacked
square, rights lost by king and rook moves and by captures on a rook's square), en passant (also
the one that uncovers the king), promotion to all four pieces, mate, stalemate, the 50-move rule,
threefold repetition, too little material to mate.

**No recursion.** dampf's subset has none, and the search does not need it. Perft and the search
keep one entry per ply in fixed arrays. The search has four stages per ply: entering a node,
trying its next move, taking the child's score back, and taking back a null move.
`search_run(steps)` runs that many stages and returns, so a search goes on frame after frame. It
stops at a node limit, never by time, and finds the same move on the SNES and on the PC.

**The player.** Alpha-beta (negamax) with iterative deepening; a quiescence search over captures
(pawn-defended victims of a bigger piece and under-promotions left out, 6 plies at most); a
check extension, so a side in check is never stood pat and a mate at the horizon is seen;
null-move pruning from depth 3 and, at depth 1, only captures and checks when the evaluation is
300 below alpha; a transposition table of 2,048 entries (16 KB); move ordering by the table's
move, captures by MVV-LVA, killer moves and the piece squares of quiet moves, which are generated
only when no capture cut the node off. The evaluation is material and piece squares (Tomasz
Michniewski's simplified evaluation), with a king table for the middle game and one for the
endgame. The strength did not need pawn structure or king safety.

**The book.** `book/lines.txt` holds 207 opening lines in standard notation: the main answers to
1.e4, 1.d4, 1.c4 and 1.Nf3, and an answer to each rarer first move. `book/make_book.sh` plays
them with `board.c` on the PC (`book/book_gen.c`, so the hashes are the game's; a move that is not
legal stops it with the line) and writes `src/book_data.c`: 559 positions with black to move and
608 answers, sorted by the hash, about 5 KB of ROM. `book.c` finds the position by a binary search
and picks one of its answers by weight (how many lines play it), from a seed the game takes when
Start is pressed: the console does not play the same game every time, and the SNES and the
desktop still play the same one.

**The screen.** Layer 1 is the board in 16 x 16 tiles, one map entry a square: light or dark, with
or without one of the twelve pieces. The squares of the last move, of the targets of the piece in
the hand and of a king in check keep their tiles and get another palette whose two square colours
differ: four palettes, one set of tiles. Layer 1 is scrolled by 4 pixels, so the letters and
numbers of layer 2 sit in the middle of their squares. Layer 2 is text in 8 x 8 tiles with a font of
all ASCII characters (moves need `x+#=` and lower case). The cursor and the piece in the hand are
sprites (`sdevkit_oam_set` takes a byte offset into the sprite table: sprite 1 is offset 4).

**Thinking and the frame.** The console runs 24 steps of the search a tick (`STEPS`,
`src/schach.h`), and a tick then takes about two frames; the cursor moves meanwhile. Fewer steps a
tick waste CPU time, because a tick that needs a little more than a frame waits for the next
vertical blank. Measured in Mesen for the answer to 1.e4 at 1,500 nodes: 676 frames with 8 steps
a tick, 481 with 14, 345 with 26, 305 with 40, where the CPU is busy all the time.

## Levels and strength

The node limit is the strength. Measured with `test/vs_stockfish.sh` against Stockfish 17.1 at a
limited strength (`UCI_LimitStrength`, 100 ms a move), 30 games each, the console as in the
game, book included:

| Level | Nodes a move | Against Stockfish | On Stockfish's scale | Thinking on the SNES |
| --- | --- | --- | --- | --- |
| Easy | 250 | 48 % against `UCI_Elo` 1320 | about 1,310 | about 1 s |
| Normal (the default) | 600 | 85 % against 1320 | about 1,620 | about 2.5 s |
| Strong | 1,500 | 90 % against 1320 (20 games), 72 % against 1600, 43 % against 1800 | about 1,750 | about 6 s |

Stockfish's limited levels are calibrated against engines, not people, and are said to play
harder than people of the same number. Where the proposal's 1,200 to 1,400 falls on that scale
only games against people tell, hence three levels. The proposal's floor holds at every level:
the console takes a hanging piece and sees a mate in one (`test/search_positions.h` passes at
250 nodes except the two mates in two, which need 300 to 430 nodes).

Against itself, doubling the nodes is worth about 150 Elo at these limits (3,000 against 1,500:
+197; 1,500 against 750: +143; 6,000 against 3,000: +117; `test/match.sh`). Null-move and futility
pruning gave +12 Elo at 1,500 nodes (400 games, within the noise), but find the mates in two in
fewer nodes.

## How it is checked

On the PC, with gcc and the sanitizers (`-idirafter src/main/resources/dampf/include` finds
`<dampf.h>`):

| Program | What |
| --- | --- |
| `test/perft_pc.c` | perft of the five standard positions of the Chessprogramming wiki ("Perft Results", `test/perft_positions.h`), which hold every special case: all right to depth 5. `perft divide 3 "<fen>"` gives the count per first move, to find a wrong one. |
| `test/end_pc.c` | `test/end_positions.h`: a position for each way a game ends (mate, stalemate, 50 moves and a mate on the 100th half move, threefold repetition, king against king, king and knight or bishop against king, bishops all on one colour) and for cases that are not an end (a check that is not mate, 49 and a half moves, a twofold repetition, bishops on both colours, two knights, bishop against knight, a pawn, a rook, the same board with other castling rights) |
| `test/search_pc.c` | `test/search_positions.h`: mates in one and two for both colours, a hanging queen and rook to take, a defended pawn and knight not to take, a promotion; then games of the engine against itself to the end, every move checked to be legal and the board given back after each search. `search [nodes] [games]` |
| `test/match.sh` | two versions of the player against each other (`test/match_pc.c`), each opening (six random half moves) with both colours: `match.sh A/search.c B/search.c [nodes_a nodes_b openings]` |
| `test/vs_stockfish.sh` | the console against Stockfish at a limited strength (`test/vs_stockfish.c`, UCI over a pipe): `vs_stockfish.sh elo games [movetime_ms [nodes]]`; `test/fetch_stockfish.sh` puts Stockfish into `build/stockfish/` (not in git) |

```bash
cc -std=c99 -O2 -Wall -Wextra -fsanitize=undefined,address -idirafter src/main/resources/dampf/include \
    -I examples/schach/src examples/schach/test/perft_pc.c examples/schach/src/board.c -o build/perft
build/perft 4                     # depth 5 takes 8 s without the sanitizers
```

In dampf's emulator, `SchachTest` (`./gradlew test --tests dampf.SchachTest -i`) builds
`test/perft_snes.c`, `test/search_snes.c` and `test/end_snes.c` (the same `board.c` and `search.c`)
with gcc and with dampf and compares the results: perft to depth 2 or 3 (the counts, the hashes,
the boards afterwards), four searches of 600 nodes (move, score, depth, nodes), and the 18 ends.
It prints the CPU cycles per leaf and per node; with `SCHACH_PROFILE=1` in the environment also
the cycles per function, and it writes `build/schach/<test>.sfc` with its execution counts for
`dampf profile build/schach/search_snes.sfc build/schach/search_snes.profile.txt`.

The whole game: `check.txt` is a game of 79 moves to the console's mate at the default level,
written by `test/make_check.py`. It lets `test/game_pc.c` play the game on the PC as the SNES does
(white a copy of the player at 300 nodes with its own table, black the console with the settings
of `schach.h`) and writes white's moves as joypad input (the cursor to the piece, A, to the
square, A; the promotion menu), with a screenshot after each answer. `dev/compare_example.sh
schach` plays it in Mesen and on the desktop: the 81 pictures are the same, pixel for pixel, and
no byte is lost outside the vertical blank (16,719 frames on the SNES for the 9,376 ticks).
Regenerate it after anything that changes how the console plays:

```bash
python3 examples/schach/test/make_check.py      # [white's node limit] [the most moves]
```

## Speed on the 65816

The proposal's open question, measured with `SchachTest`. Perft, 15,503 leaves (a leaf is a move
made, tested and taken back, the move generation shared out):

| Perft | CPU cycles per leaf | Leaves per frame |
| --- | --- | --- |
| First version (legality by `attacked()` after every move) | 4,286 | 12 |
| Legality only on the line through the king, the hash not saved twice, the castling hash only when the rights change | 2,811 | 19 |
| `DAMPF_RANGE` on the 16-bit index of the move list, castling without a second test for check | 2,641 | 20 |

A node of the search (one position entered, in the full or the quiescence search) costs much more:
it generates and orders its moves, and most nodes try only a few of them.

| Search, four positions of 600 nodes | CPU cycles per node | Nodes per frame |
| --- | --- | --- |
| First version | 12,433 | 4 |
| Check known from the move just made, the quiescence search pruned, only the first 8 moves picked by score, `DAMPF_RANGE` on the 16-bit indices of the move list and the table | 11,479 | 4.5 |

The first version also searched too many quiescence nodes: Kiwipete needed 2,961 nodes for depth
1, now 600 nodes reach depth 2. Per call: `gen_moves` 6,100 cycles, `score_moves` 2,700, `next`
1,000, `make_move` 1,100, `attacked` 1,500. That is about 270 nodes a second when the search has
the CPU to itself, against the proposal's guess of one to two thousand; the levels' node limits
are set by it.

What dampf's code costs here (from the assembly of `make_move` and `gen_moves`), work for the
optimizer, as the proposal expected:

- **32-bit values go through frame slots.** `hash ^= zob_side` loads both operands into the
  frame, XORs into a third slot and copies that back: 81 cycles, where `lda/eor/sta` twice is
  about 30. Every Zobrist update pays this.
- **A shift by a constant above 8 is a loop.** `zob[p][sq]` (rows of 512 bytes) is `shl 9`, done
  as `ldx #9; asl; dex; bne`, about 65 cycles; `xba` and one `asl` would do.
- **Arrays with a 16-bit index go through a 24-bit pointer.** `psq_val[p][to]` builds a pointer in
  the direct page and reads `[dp],y` instead of `lda psq_val,x`; so does every array with a 16-bit
  index unless a `DAMPF_RANGE` hint bounds it: `mv_from[n] = from` is 32 cycles without the hint
  and 16 with it (`sta mv_from,x`). An index that the C code cannot let past the end (it would be
  undefined) could get `abs,x` without the hint.
- The inner loop of the move generation (one square of a slider's ray) is about 50 cycles, close
  to what it can be; there the cost is the method, not the compiler.
