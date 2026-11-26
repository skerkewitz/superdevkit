#!/bin/sh
# Plays an example headless in Mesen with the input of examples/<name>/check.txt
# (dev/example_check.lua): no byte may reach VRAM, CGRAM or OAM outside a vertical blank, and the
# screenshots it asks for go to examples/<name>/build/check/, with the checksums (hashes.txt) and the
# sound (sound.txt: the DSP writes of the SNES's sound CPU, as the desktop's check writes them).
#   dev/check_example.sh catchme             builds the example first (superdevkit build snes)
#   dev/check_example.sh dev/publish_check   a project outside examples/ (one of the kit's checks)
#   NOBUILD=1 dev/check_example.sh catchme   checks the ROM as it is
#   INPUT=rec.txt dev/check_example.sh ertuep with another input than check.txt (a recording)
# MESEN=<path> picks Mesen (default: MesenCE in .mesen/, dev/mesen.sh).
set -e
cd "$(dirname "$0")/.."
: "${1:?usage: dev/check_example.sh <example, or a project's folder such as dev/irq_check>}"
case $1 in
    */*) EX=${1%/}; NAME=$(basename "$EX") ;;
    *) NAME=$1; EX=examples/$NAME ;;
esac
OUT=$EX/build/check
. dev/mesen.sh
if [ "${NOBUILD:-0}" != 1 ]; then
    # the kit's command: in a superdevkit home bin/superdevkit, in the repository its installation
    S=bin/superdevkit; [ -x "$S" ] || S=build/install/superdevkit/bin/superdevkit
    [ -x "$S" ] || ./gradlew -q installDist
    "$S" build snes "$EX" > /dev/null
fi
rm -rf "$OUT" && mkdir -p "$OUT"
# Mesen's folder holds one check at a time (its files have fixed names): two checks at once, as
# test classes in parallel JVMs run them (IrqCheckTest, ModesPictureTest), wait for each other
if command -v flock > /dev/null 2>&1; then
    exec 9> "$MESEN_DIR/example_check.lock"
    flock 9
fi
cp "$EX/build/snes/$NAME.sfc" "$MESEN_DIR/example_check.sfc"
cp dev/example_check.lua "$MESEN_DIR/example_check.lua"
cp "${INPUT:-$EX/check.txt}" "$MESEN_DIR/example_input.txt"
rm -f "$MESEN_DIR/example_check.txt" "$MESEN_DIR/example_hashes.txt" "$MESEN_DIR/example_sound.txt" "$MESEN_DIR"/example_shot_*.png "$MESEN_DIR"/example_splash_*.png
mesen_run 600 example_check.sfc example_check.lua
[ -f "$MESEN_DIR/example_check.txt" ] || { echo "check: Mesen wrote no example_check.txt"; exit 1; }
mv "$MESEN_DIR/example_check.txt" "$OUT/check.txt"
[ -f "$MESEN_DIR/example_hashes.txt" ] && mv "$MESEN_DIR/example_hashes.txt" "$OUT/hashes.txt"
[ -f "$MESEN_DIR/example_sound.txt" ] && tr -d "\r" < "$MESEN_DIR/example_sound.txt" > "$OUT/sound.txt" && rm "$MESEN_DIR/example_sound.txt"
for f in "$MESEN_DIR"/example_shot_*.png "$MESEN_DIR"/example_splash_*.png; do [ -f "$f" ] && mv "$f" "$OUT/"; done
tail -1 "$OUT/check.txt"
tr -d "\r" < "$OUT/check.txt" | grep -q "lost 0$" || { echo "check: bytes lost outside the vertical blank (see $OUT/check.txt)"; exit 1; }
