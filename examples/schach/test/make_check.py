#!/usr/bin/env python3
"""Writes examples/schach/check.txt: a whole game, white played by the joypad as a person would
(the cursor to the piece, A, the cursor to the square, A; the promotion menu), black by the
console. Which moves: test/game_pc.c plays the game on the PC as the SNES does, white by a
weaker copy of the player, and says how many ticks each answer of the console takes.

    python3 examples/schach/test/make_check.py [white_nodes] [max_moves]

A screenshot after each move of the console and at the end; dev/compare_example.sh schach plays
the input on the SNES and on the desktop and compares them.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EX = os.path.dirname(HERE)
KIT = os.path.dirname(os.path.dirname(EX))
white_nodes = sys.argv[1] if len(sys.argv) > 1 else "300"
max_moves = sys.argv[2] if len(sys.argv) > 2 else "200"

START = 60                  # the tick Start is pressed at (the title waits for it)
MARGIN = 6                  # ticks after the console's move before the next input

# build game_pc: board.c, book.c and search.c for black, a second search.c as w_ for white
out = os.path.join(EX, "build", "check_game")
os.makedirs(out, exist_ok=True)
inc = ["-idirafter", os.path.join(KIT, "src/main/resources/dampf/include"), "-I", os.path.join(EX, "src")]
names = "evaluate search_clear search_start search_run best_from best_to best_flag best_score best_depth nodes".split()
subprocess.run(["cc", "-std=c99", "-O2", *inc, *[f"-D{n}=w_{n}" for n in names], "-c",
                os.path.join(EX, "src/search.c"), "-o", os.path.join(out, "w_search.o")], check=True)
subprocess.run(["cc", "-std=c99", "-O2", *inc, os.path.join(HERE, "game_pc.c"),
                *[os.path.join(EX, "src", f) for f in ("board.c", "book.c", "search.c")],
                os.path.join(out, "w_search.o"), "-o", os.path.join(out, "game_pc")], check=True)
# the seed of the book: the frame count when Start is pressed (game_tick counts before it reads)
seed = START + 1
lines = subprocess.run([os.path.join(out, "game_pc"), white_nodes, str(seed), max_moves], check=True,
                       capture_output=True, text=True).stdout.split("\n")

check = []
t = START
check.append(f"press {t} {t + 2} start")
t += 1                      # the game starts in the tick of the press
cursor = [4, 1]             # e2


def press(button):
    global t
    check.append(f"press {t} {t + 1} {button}")
    t += 2                  # one tick down, one up: every press is a new edge


def go(square):
    f, r = ord(square[0]) - 97, int(square[1]) - 1
    while cursor[0] != f:
        press("right" if f > cursor[0] else "left")
        cursor[0] += 1 if f > cursor[0] else -1
    while cursor[1] != r:
        press("up" if r > cursor[1] else "down")
        cursor[1] += 1 if r > cursor[1] else -1


moves = 0
for line in lines:
    w = line.split()
    if not w:
        continue
    if w[0] == "white":
        m = w[1]
        go(m[:2])
        press("a")
        go(m[2:4])
        press("a")
        if len(m) == 5:     # the menu: queen, rook, bishop, knight
            for _ in range("qrbn".index(m[4])):
                press("down")
            press("a")
        made = t - 1        # the tick of the last A: the move is made, the console starts
    elif w[0] == "black":
        moves += 1
        t = made + int(w[2]) + MARGIN
        check.append(f"shot {t - 1}")
    elif w[0] == "end":
        check.append(f"shot {t + 30}")
        t += 32
summary = [l for l in lines if l.startswith("end")]
with open(os.path.join(EX, "check.txt"), "w") as f:
    f.write(f"ticks {t}\nshot 30\n")
    f.write("\n".join(check) + "\n")
print(f"check.txt: {moves} moves of the console, {t} ticks, {summary[0] if summary else ''}")
