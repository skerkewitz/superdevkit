#!/bin/sh
# The examples' benchmark: builds every example that has a check.txt with dampf (with the options
# given here, so a code generator option or a tuned parameter is measured on all of them), plays
# its check.txt in dampf's emulator (dampf.emu.ExampleBench) and prints a table of the work per
# tick. Writes it to build/bench-examples/examples.txt as well.
#   dev/bench_examples.sh                          dampf's code as it is
#   dev/bench_examples.sh --backend naive          with options for dampf build
#   BASELINE=old.txt dev/bench_examples.sh         and the ratio to an earlier table, per example
#                                                  and as their geometric mean
# The work is master cycles from the end of one tick to the end of the next minus the main loop's
# waiting (a frame has 357,368); "busiest" is the busiest 60 ticks in a row, "runtime" the share
# of the work in dampf's runtime routines (multiplication, division, shifts by a variable).
set -e
cd "$(dirname "$0")/.."
OUT=build/bench-examples
mkdir -p "$OUT"
[ -x build/install/superdevkit/bin/dampf ] || ./gradlew -q installDist
CP='build/install/superdevkit/java/*'
TABLE=$OUT/examples.txt
printf '%-12s %7s %8s %10s %10s %8s\n' example ticks frames work busiest runtime > "$TABLE"
for check in examples/*/check.txt; do
    ex=$(dirname "$check")
    name=$(basename "$ex")
    # the example's build (superdevkit build snes), then dampf again with its argument file and the
    # options given here
    build/install/superdevkit/bin/superdevkit build snes "$ex" > /dev/null || exit 1
    log=$(build/install/superdevkit/bin/dampf build "@$ex/build/snes/$name.args" -o "$OUT/$name.sfc" "$@" 2>&1) \
        || { echo "$log"; exit 1; }
    line=$(java -cp "$CP" dampf.emu.ExampleBench "$OUT/$name.sfc" "$OUT/$name.lbl" "$check")
    echo "$name $line" | awk '{ printf "%-12s %7d %8.3f %10d %10d %7.1f%%\n", $1, $2, $3, $4, $5, $6 }' >> "$TABLE"
done
if [ -n "$BASELINE" ]; then
    # the ratio of the work per tick to the baseline's, per example, and the geometric mean
    awk 'NR == FNR { if (FNR > 1) base[$1] = $4; next }
         FNR == 1 { printf "%s %9s\n", $0, "vs base"; next }
         { r = ($1 in base && base[$1] > 0) ? $4 / base[$1] : 0
           if (r > 0) { lsum += log(r); n++ }
           printf "%s %9.3f\n", $0, r }
         END { if (n > 0) printf "%-12s %57s %9.3f\n", "geomean", "", exp(lsum / n) }' "$BASELINE" "$TABLE"
else
    cat "$TABLE"
fi
