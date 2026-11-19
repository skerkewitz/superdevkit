#!/bin/sh
# Builds CatchMe for the SNES: converts the PNG files of assets/ into C (tools/png2snes.py), then
# compiles everything with dampf. Until the superdevkit command comes (roadmap M7), this script is
# the build.
#   examples/catchme/build.sh            -> examples/catchme/build/catchme.sfc
#   CONVERT_ONLY=1 examples/catchme/build.sh   only the assets into build/gen
set -e
cd "$(dirname "$0")"
KIT=../..
GEN=build/gen
mkdir -p "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/maze_gfx.png maze_gfx "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/sprites_gfx.png sprites_gfx "$GEN"
for p in ginger black white brown scared hot; do
    python3 -I "$KIT/tools/png2snes.py" palette "assets/pal_$p.png" "pal_$p" "$GEN"
done
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests (ExamplesTest) and the desktop build convert only
# dampf: in a superdevkit home bin/dampf, in the kit's repository its installation
if [ -z "$DAMPF" ] && [ -x "$KIT/bin/dampf" ]; then DAMPF=$KIT/bin/dampf; fi
DAMPF=${DAMPF:-$KIT/build/install/superdevkit/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @catchme.args -o build/catchme.sfc "$@"
