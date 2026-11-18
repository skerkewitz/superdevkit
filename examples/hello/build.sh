#!/bin/sh
# Builds hello for the SNES: converts the PNG files of assets/ into C (tools/png2snes.py), then
# compiles everything with dampf. Until the superdevkit command comes (roadmap M7), this script is
# the build.
#   examples/hello/build.sh            -> examples/hello/build/hello.sfc
#   CONVERT_ONLY=1 examples/hello/build.sh   only the assets into build/gen
set -e
cd "$(dirname "$0")"
KIT=../..
GEN=build/gen
mkdir -p "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/font_gfx.png font_gfx "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/ball_gfx.png ball_gfx "$GEN"
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests convert, then compile in the JVM (ExamplesTest)
DAMPF=${DAMPF:-$KIT/build/install/dampf/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @hello.args -o build/hello.sfc "$@"
