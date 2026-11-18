#!/bin/sh
# The console against Stockfish at a limited strength (test/vs_stockfish.c):
#   examples/schach/test/vs_stockfish.sh elo games [movetime_ms [nodes]]
# Fetches Stockfish first (test/fetch_stockfish.sh).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
EX=$HERE/..
"$HERE/fetch_stockfish.sh"
cc -std=c99 -O2 -Wall -Wextra -idirafter "$EX/../../src/main/resources/dampf/include" -I "$EX/src" \
    "$HERE/vs_stockfish.c" "$EX/src/board.c" "$EX/src/book.c" "$EX/src/book_data.c" "$EX/src/search.c" -lm \
    -o "$EX/build/vs_stockfish"
"$EX/build/vs_stockfish" "$EX/build/stockfish/stockfish" "$@"
