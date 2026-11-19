#!/bin/sh
# Plays an example headless in Mesen with the input of examples/<name>/check.txt
# (dev/example_check.lua): no byte may reach VRAM, CGRAM or OAM outside a vertical blank, and the
# screenshots it asks for go to examples/<name>/build/check/.
#   dev/check_example.sh catchme             builds the example first (its build.sh)
#   NOBUILD=1 dev/check_example.sh catchme   checks the ROM as it is
# MESEN=<path> picks Mesen (default: MesenCE in .mesen/, dev/mesen.sh).
set -e
cd "$(dirname "$0")/.."
NAME=${1:?usage: dev/check_example.sh <example>}
EX=examples/$NAME
OUT=$EX/build/check
. dev/mesen.sh
[ "${NOBUILD:-0}" = 1 ] || "$EX/build.sh" > /dev/null
rm -rf "$OUT" && mkdir -p "$OUT"
cp "$EX/build/$NAME.sfc" "$MESEN_DIR/example_check.sfc"
cp dev/example_check.lua "$MESEN_DIR/example_check.lua"
cp "$EX/check.txt" "$MESEN_DIR/example_input.txt"
rm -f "$MESEN_DIR/example_check.txt" "$MESEN_DIR/example_hashes.txt" "$MESEN_DIR"/example_shot_*.png "$MESEN_DIR"/example_splash_*.png
mesen_run 600 example_check.sfc example_check.lua
[ -f "$MESEN_DIR/example_check.txt" ] || { echo "check: Mesen wrote no example_check.txt"; exit 1; }
mv "$MESEN_DIR/example_check.txt" "$OUT/check.txt"
[ -f "$MESEN_DIR/example_hashes.txt" ] && mv "$MESEN_DIR/example_hashes.txt" "$OUT/hashes.txt"
for f in "$MESEN_DIR"/example_shot_*.png "$MESEN_DIR"/example_splash_*.png; do [ -f "$f" ] && mv "$f" "$OUT/"; done
tail -1 "$OUT/check.txt"
tr -d "\r" < "$OUT/check.txt" | grep -q "lost 0$" || { echo "check: bytes lost outside the vertical blank (see $OUT/check.txt)"; exit 1; }
