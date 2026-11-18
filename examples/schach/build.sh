#!/bin/sh
# Builds Schach for the SNES: converts the PNG files of assets/ into C (tools/png2snes.py), then
# compiles everything with dampf. Until the superdevkit command comes (roadmap M7), this script is
# the build.
#   examples/schach/build.sh            -> examples/schach/build/schach.sfc
#   CONVERT_ONLY=1 examples/schach/build.sh   only the assets into build/gen
set -e
cd "$(dirname "$0")"
KIT=../..
GEN=build/gen
mkdir -p "$GEN"
for s in board_gfx sprites_gfx font_gfx; do
    python3 -I "$KIT/tools/png2snes.py" tiles "assets/$s.png" "$s" "$GEN"
done
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests convert, then compile in the JVM (ExamplesTest)
DAMPF=${DAMPF:-$KIT/build/install/dampf/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @schach.args -o build/schach.sfc "$@"
