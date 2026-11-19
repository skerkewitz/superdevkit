#!/bin/sh
# Plays a recording of the desktop (lib/desktop: F11 saves <game>_rec_<n>.txt) on the SNES side
# for one of the kit's examples: builds it, and compares the checksum of every tick with the one
# recorded in dampf's emulator (dev/replay.sh, which plays any ROM), and with WITH_MESEN=1 in
# Mesen too (dev/check_example.sh). Names the first tick that differs.
#   dev/replay_example.sh ertuep ertuep_rec_0.txt
#   WITH_MESEN=1 dev/replay_example.sh ertuep ertuep_rec_0.txt
#   NOBUILD=1 ...                             plays the ROM as it is
set -e
NAME=${1:?usage: dev/replay_example.sh <example> <recording>}
REC=$(realpath "${2:?usage: dev/replay_example.sh <example> <recording>}")
cd "$(dirname "$0")/.."
EX=examples/$NAME
# the kit's command: in a superdevkit home bin/superdevkit, in the repository its installation
S=bin/superdevkit; [ -x "$S" ] || S=build/install/superdevkit/bin/superdevkit
[ -x "$S" ] || ./gradlew -q installDist
[ "${NOBUILD:-0}" = 1 ] || "$S" build snes "$EX" > /dev/null
echo "dampf's emulator:"
dev/replay.sh "$EX/build/snes/$NAME.sfc" "$REC"
if [ "${WITH_MESEN:-0}" = 1 ]; then
    echo "Mesen:"
    INPUT="$REC" NOBUILD=1 dev/check_example.sh "$NAME" | tail -1
    awk 'NR == FNR { if ($1 == "hash") want[$2] = $3; next }
         { n++; if (($1 in want) && want[$1] != $2) { printf "tick %d has checksum %s in Mesen, the recording %s\n", $1, $2, want[$1]; bad = 1; exit 1 } }
         END { if (!bad) printf "%d ticks played, the checksums the same as the recording\n", n }' \
        "$REC" "$EX/build/check/hashes.txt"
fi
