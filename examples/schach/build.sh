#!/bin/sh
# Builds Schach for the SNES: converts the PNG files of assets/ into C (tools/png2snes.py) and its
# sound effects (superdevkit sfx), then compiles everything with dampf. Until the superdevkit command comes (roadmap M7), this script is
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
# the sound effects (assets/sfx/sfx.json, docs/sound.md) with the superdevkit command: in a
# superdevkit home bin/superdevkit, in the kit's repository its installation
if [ -z "$SUPERDEVKIT" ] && [ -x "$KIT/bin/superdevkit" ]; then SUPERDEVKIT=$KIT/bin/superdevkit; fi
SUPERDEVKIT=${SUPERDEVKIT:-$KIT/build/install/superdevkit/bin/superdevkit}
[ -x "$SUPERDEVKIT" ] || (cd "$KIT" && ./gradlew -q installDist)
"$SUPERDEVKIT" sfx assets/sfx/sfx.json sfx "$GEN"
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests (ExamplesTest) and the desktop build convert only
# dampf: in a superdevkit home bin/dampf, in the kit's repository its installation
if [ -z "$DAMPF" ] && [ -x "$KIT/bin/dampf" ]; then DAMPF=$KIT/bin/dampf; fi
DAMPF=${DAMPF:-$KIT/build/install/superdevkit/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @schach.args -o build/schach.sfc "$@"
