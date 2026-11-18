#!/bin/sh
# Makes the opening book: book/lines.txt -> src/book_data.c and src/book_data.h (book_gen.c, on the
# PC, with the game's board.c, so the hashes are the game's).
#   examples/schach/book/make_book.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
EX=$HERE/..
mkdir -p "$EX/build"
cc -std=c99 -O2 -Wall -Wextra -idirafter "$EX/../../src/main/resources/dampf/include" -I "$EX/src" \
    "$HERE/book_gen.c" "$EX/src/board.c" -o "$EX/build/book_gen"
"$EX/build/book_gen" "$HERE/lines.txt" "$EX/src/book_data.c" "$EX/src/book_data.h"
