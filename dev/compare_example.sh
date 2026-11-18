#!/bin/sh
# Plays an example on the SNES (Mesen, dev/check_example.sh) and on the desktop (its headless
# check) with the same input, and compares the screenshots pixel for pixel (dev/compare_shots.py):
# dampf's code and gcc's, lib/snes and the pseudo SNES must show the same.
#   dev/compare_example.sh catchme
set -e
cd "$(dirname "$0")/.."
NAME=${1:?usage: dev/compare_example.sh <example>}
EX=examples/$NAME
dev/check_example.sh "$NAME"
tools/build_desktop.sh "$EX" > /dev/null
rm -rf "$EX/build/desktop-check" && mkdir -p "$EX/build/desktop-check"
"$EX/build/linux/$NAME" --check "$EX/check.txt" --out "$EX/build/desktop-check"
python3 -I dev/compare_shots.py "$EX/build/check" "$EX/build/desktop-check"
