#!/bin/sh
# Plays an example on the SNES (Mesen, dev/check_example.sh) and on the desktop (its headless
# check) with the same input, and compares the screenshots pixel for pixel (dev/compare_shots.py)
# and the checksum after every tick (sdevkit_dbg_hash): dampf's code and gcc's, lib/snes and
# Rachael must compute and show the same.
#   dev/compare_example.sh catchme
set -e
cd "$(dirname "$0")/.."
NAME=${1:?usage: dev/compare_example.sh <example>}
EX=examples/$NAME
dev/check_example.sh "$NAME"
dev/build_desktop.sh "$EX" > /dev/null
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
