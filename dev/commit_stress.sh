#!/bin/sh
# The queue's budget against the console's time (lib/README.md, "The commit's time"): builds
# dev/commit_stress, whose every tick asks for the commit's worst case (every group marked, the
# HDMA channels 1 to 7, the queue filled to its budget, with as many copies as it takes and with
# one copy of all its bytes in turn), and plays 600 ticks in Mesen headless. Prints the bytes that
# reached VRAM, CGRAM or OAM outside the vertical blank (must be 0) and the latest scanline a
# DMA starts on and the commit ends on (the vertical blank is lines 225 to 261).
#   dev/commit_stress.sh
set -e
cd "$(dirname "$0")/.."
. dev/mesen.sh
S=bin/superdevkit; [ -x "$S" ] || S=build/install/superdevkit/bin/superdevkit
[ -x "$S" ] || ./gradlew -q installDist
"$S" build snes dev/commit_stress > /dev/null
cp dev/commit_stress/build/snes/stress.sfc "$MESEN_DIR/stress.sfc"
cp dev/commit_stress/stress.lua "$MESEN_DIR/stress.lua"
rm -f "$MESEN_DIR/stress_out.txt"
mesen_run 300 stress.sfc stress.lua
[ -f "$MESEN_DIR/stress_out.txt" ] || { echo "commit_stress: Mesen wrote nothing"; exit 1; }
cat "$MESEN_DIR/stress_out.txt"
grep -q " lost 0 " "$MESEN_DIR/stress_out.txt" || { echo "commit_stress: bytes lost outside the vertical blank"; exit 1; }
