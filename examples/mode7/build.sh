#!/bin/sh
# Builds the mode7 example for the SNES with dampf. It has no image files: everything is
# computed in src/mode7.c. Until the superdevkit command comes (roadmap M7), this script is the
# build.
#   examples/mode7/build.sh            -> examples/mode7/build/mode7.sfc
#   CONVERT_ONLY=1 examples/mode7/build.sh   nothing to convert (the desktop build calls it)
set -e
cd "$(dirname "$0")"
KIT=../..
mkdir -p build/gen
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0
DAMPF=${DAMPF:-$KIT/build/install/dampf/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @mode7.args -o build/mode7.sfc "$@"
