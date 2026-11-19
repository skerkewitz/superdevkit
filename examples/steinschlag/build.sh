#!/bin/sh
# Builds Steinschlag for the SNES: converts the PNG files of assets/ into C (tools/png2snes.py),
# then compiles everything with dampf. Until the superdevkit command comes (roadmap M7), this
# script is the build.
#   examples/steinschlag/build.sh            -> examples/steinschlag/build/steinschlag.sfc
#   CONVERT_ONLY=1 examples/steinschlag/build.sh   only the assets into build/gen
set -e
cd "$(dirname "$0")"
KIT=../..
GEN=build/gen
mkdir -p "$GEN"
for s in cave_a_gfx cave_b_gfx cave_c_gfx miner_gfx font_gfx; do
    python3 -I "$KIT/tools/png2snes.py" tiles "assets/$s.png" "$s" "$GEN"
done
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests (ExamplesTest) and the desktop build convert only
# dampf: in a superdevkit home bin/dampf, in the kit's repository its installation
if [ -z "$DAMPF" ] && [ -x "$KIT/bin/dampf" ]; then DAMPF=$KIT/bin/dampf; fi
DAMPF=${DAMPF:-$KIT/build/install/superdevkit/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @steinschlag.args -o build/steinschlag.sfc "$@"
