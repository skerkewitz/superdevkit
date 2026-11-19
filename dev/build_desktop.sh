#!/bin/sh
# Builds an example for the desktop (Linux now) with the system's C compiler and raylib, on
# Rachael (lib/rachael, the SNES rebuilt in portable C): the same game code as on the SNES. Until the superdevkit command
# (roadmap M7), this script is the desktop build.
#   dev/build_desktop.sh examples/catchme         -> examples/catchme/build/linux/catchme
#   examples/catchme/build/linux/catchme             plays it in a window
#   examples/catchme/build/linux/catchme --check examples/catchme/check.txt --out DIR
# CC picks the compiler (default cc).
#
# The game reaches only superdevkit's console API (ADR record 34), so it is built in three steps:
#   1. the game's sources alone, as on the SNES: without the host's headers (-nostdinc), with
#      dampf's include folder (<stdint.h>, <string.h>) and the kit's public headers only;
#      -ffreestanding and -fno-tree-loop-distribute-patterns keep gcc from turning the byte loop
#      of dampf's static memcpy into a call of memcpy, which in that file is the loop itself;
#   2. dev/check_game_symbols.py: what the game's objects call must be the kit's;
#   3. the link with Rachael, the frontend and raylib.
set -e
cd "$(dirname "$0")/.."
EX=${1:?usage: dev/build_desktop.sh examples/<name>}
EX=${EX%/}
NAME=$(basename "$EX")
CONVERT_ONLY=1 sh "$EX/build.sh"
dev/fetch_raylib.sh linux
OUT=$EX/build/linux
OBJ=$OUT/obj
rm -rf "$OBJ" && mkdir -p "$OBJ"
R=build/raylib/linux
DAMPF_INCLUDE=include                       # dampf's headers: in a superdevkit home include/,
[ -d "$DAMPF_INCLUDE" ] || DAMPF_INCLUDE=compiler/src/main/resources/dampf/include   # in the repository the sources
CC=${CC:-cc}                                # -g: the program carries debug information for gdb
WARN="-Wall -Wextra -Wno-unused-parameter"

GAME_OBJECTS=""
for src in "$EX"/src/*.c "$EX"/build/gen/*.c; do
    [ -f "$src" ] || continue
    obj=$OBJ/$(basename "$(dirname "$src")")_$(basename "$src" .c).o
    # shellcheck disable=SC2086
    $CC -std=c99 -O2 -g $WARN -nostdinc -ffreestanding -fno-tree-loop-distribute-patterns \
        -isystem "$DAMPF_INCLUDE" -I lib/include -I "$EX/src" -I "$EX/build/gen" -c "$src" -o "$obj"
    GAME_OBJECTS="$GAME_OBJECTS $obj"
done
[ -n "$GAME_OBJECTS" ] || { echo "build_desktop: no C sources in $EX/src"; exit 1; }
# shellcheck disable=SC2086
python3 -I dev/check_game_symbols.py lib/include/sdevkit $GAME_OBJECTS

# shellcheck disable=SC2086
$CC -std=c99 -O2 -g $WARN -I lib/include -I lib/rachael -I "$R/include" -DGAME_NAME="\"$NAME\"" \
    $GAME_OBJECTS lib/rachael/*.c lib/desktop/*.c \
    "$R/lib/libraylib.a" -lGL -lm -lpthread -ldl -lrt -lX11 \
    -o "$OUT/$NAME"
echo "built $OUT/$NAME"
