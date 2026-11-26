#!/bin/sh
# Plays an example on the SNES (Mesen, dev/check_example.sh) and on the desktop (its headless
# check) with the same input, and compares the screenshots pixel for pixel (dev/compare_shots.py)
# and the checksum after every tick (sdevkit_dbg_hash), and the sound (every DSP write of the
# SNES's sound CPU, and what game_init uploaded into the audio RAM): dampf's code and gcc's, lib/snes and
# Rachael must compute, show and play the same.
#   dev/compare_example.sh catchme
#   dev/compare_example.sh dev/irq_check     a project outside examples/ (one of the kit's checks)
set -e
cd "$(dirname "$0")/.."
: "${1:?usage: dev/compare_example.sh <example, or a project's folder such as dev/irq_check>}"
case $1 in
    */*) EX=${1%/}; NAME=$(basename "$EX") ;;
    *) NAME=$1; EX=examples/$NAME ;;
esac
dev/check_example.sh "$EX"
# the kit's command: in a superdevkit home bin/superdevkit, in the repository its installation
S=bin/superdevkit; [ -x "$S" ] || S=build/install/superdevkit/bin/superdevkit
[ -x "$S" ] || ./gradlew -q installDist
"$S" build linux "$EX" > /dev/null
rm -rf "$EX/build/desktop-check" && mkdir -p "$EX/build/desktop-check"
"$EX/build/linux/$NAME" --check "$EX/check.txt" --out "$EX/build/desktop-check"
python3 -I dev/compare_shots.py "$EX/build/check" "$EX/build/desktop-check"
tr -d "\r" < "$EX/build/check/hashes.txt" > "$EX/build/check/hashes.unix.txt"
if ! cmp -s "$EX/build/check/hashes.unix.txt" "$EX/build/desktop-check/hashes.txt"; then
    echo "compare: the checksums differ, first at:"
    diff "$EX/build/check/hashes.unix.txt" "$EX/build/desktop-check/hashes.txt" | head -4
    exit 1
fi
echo "checksums: $(wc -l < "$EX/build/desktop-check/hashes.txt") ticks the same"
if ! cmp -s "$EX/build/check/sound.txt" "$EX/build/desktop-check/sound.txt"; then
    echo "compare: the sound differs, first at:"
    diff "$EX/build/check/sound.txt" "$EX/build/desktop-check/sound.txt" | head -4
    exit 1
fi
echo "sound: $(grep -c . "$EX/build/desktop-check/sound.txt") lines the same"
