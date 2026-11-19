#!/bin/sh
# Builds Mandelbrot for the SNES with dampf: no assets, the colours and the picture are computed.
# Until the superdevkit command comes (roadmap M7), this script is the build.
#   examples/mandelbrot/build.sh    -> examples/mandelbrot/build/mandelbrot.sfc
set -e
cd "$(dirname "$0")"
KIT=../..
mkdir -p build/gen
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests compile in the JVM (ExamplesTest)
# dampf: in a superdevkit home bin/dampf, in the kit's repository its installation
if [ -z "$DAMPF" ] && [ -x "$KIT/bin/dampf" ]; then DAMPF=$KIT/bin/dampf; fi
DAMPF=${DAMPF:-$KIT/build/install/superdevkit/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @mandelbrot.args -o build/mandelbrot.sfc "$@"
