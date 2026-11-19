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
# dampf: in a superdevkit home bin/dampf, in the kit's repository its installation
if [ -z "$DAMPF" ] && [ -x "$KIT/bin/dampf" ]; then DAMPF=$KIT/bin/dampf; fi
DAMPF=${DAMPF:-$KIT/build/install/superdevkit/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @mode7.args -o build/mode7.sfc "$@"
