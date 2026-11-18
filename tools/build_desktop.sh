#!/bin/sh
# Builds an example for the desktop (Linux now) with the system's C compiler and raylib, on the
# pseudo SNES of lib/pseudo: the same game code as on the SNES. Until the superdevkit command
# (roadmap M7), this script is the desktop build.
#   tools/build_desktop.sh examples/catchme         -> examples/catchme/build/linux/catchme
#   examples/catchme/build/linux/catchme             plays it in a window
#   examples/catchme/build/linux/catchme --check examples/catchme/check.txt --out DIR
# CC picks the compiler (default cc).
set -e
cd "$(dirname "$0")/.."
EX=${1:?usage: tools/build_desktop.sh examples/<name>}
EX=${EX%/}
NAME=$(basename "$EX")
CONVERT_ONLY=1 sh "$EX/build.sh"
tools/fetch_raylib.sh linux
OUT=$EX/build/linux
mkdir -p "$OUT"
R=build/raylib/linux
# shellcheck disable=SC2086
${CC:-cc} -std=c99 -O2 -Wall -Wextra -Wno-unused-parameter \
    -I lib/include -I lib/pseudo -I "$EX/src" -I "$EX/build/gen" -I "$R/include" -DGAME_NAME="\"$NAME\"" \
    "$EX"/src/*.c "$EX"/build/gen/*.c lib/pseudo/*.c lib/desktop/*.c \
    "$R/lib/libraylib.a" -lGL -lm -lpthread -ldl -lrt -lX11 \
    -o "$OUT/$NAME"
echo "built $OUT/$NAME"
