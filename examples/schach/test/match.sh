#!/bin/sh
# Plays two versions of the console's player against each other on the PC (match_pc.c):
#   examples/schach/test/match.sh A/search.c B/search.c [nodes_a nodes_b openings]
# A version is a search.c (with the board.c of examples/schach/src); an old one comes from git:
#   git show HEAD~1:examples/schach/src/search.c > /tmp/old_search.c
set -e
A=${1:?usage: match.sh A/search.c B/search.c [nodes_a nodes_b openings]}
B=${2:?usage: match.sh A/search.c B/search.c [nodes_a nodes_b openings]}
shift 2
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/../src
OUT=$HERE/../build/match
INC="-idirafter $HERE/../../../src/main/resources/dampf/include -I $SRC"
mkdir -p "$OUT"
NAMES="evaluate search_clear search_start search_run best_from best_to best_flag best_score best_depth nodes"
for v in a b; do
    defs=""
    for n in $NAMES; do defs="$defs -D$n=${v}_$n"; done
    if [ $v = a ]; then file=$A; else file=$B; fi
    # shellcheck disable=SC2086
    cc -std=c99 -O2 $INC $defs -c "$file" -o "$OUT/search_$v.o"
done
# shellcheck disable=SC2086
cc -std=c99 -O2 $INC "$HERE/match_pc.c" "$SRC/board.c" "$OUT/search_a.o" "$OUT/search_b.o" -lm -o "$OUT/match"
"$OUT/match" "$@"
