#!/bin/sh
# A long run of random programs before a release (docs/testing.md, Random programs): N programs of
# each generator (default 5,000), starting at a seed (default: today's, as the build takes it for
# the loop programs; version 1 starts at 1). A failure names its seed and the command that runs
# that program alone; dev/bisect_passes.sh then names the pass that broke it.
#   dev/random_soak.sh                 5,000 of each, today's seeds
#   dev/random_soak.sh 20000           20,000 of each
#   dev/random_soak.sh 5000 1234000    starting at seed 1234000
#   JOBS=4 dev/random_soak.sh          at most 4 Java processes at once (default: half the cores)
# The programs of each generator are split into JOBS shares; each share is one Java process
# (dampf.exec.RandomSoak, up to 2 GB of heap) that checks its programs one after another, as
# RandomProgramTest does. The logs are in build/soak/. The JVMs collect with one thread and use
# the full JIT: 1,000 programs of each generator on 8 cores took 675 s, against 940 s with C1 alone.
# JAVA_OPTS replaces these options.
set -e
cd "$(dirname "$0")/.."
COUNT=${1:-5000}
JOBS=${JOBS:-$(( $(nproc) / 2 ))}
[ "$JOBS" -ge 1 ] || JOBS=1
./gradlew -q :compiler:soakClasspath
CP=$(cat compiler/build/soak/classpath.txt)
OUT=$(pwd)/build/soak
rm -rf "$OUT"
mkdir -p "$OUT"
JAVA="java -Xmx2g ${JAVA_OPTS:--XX:+UseSerialGC} -Ddampf.include=compiler/src/main/resources/dampf/include -cp $CP dampf.exec.RandomSoak"
SHARE=$(( (COUNT + JOBS - 1) / JOBS ))
for gen in loop random; do
    first=${2:-$($JAVA first $gen)}
    done=0
    while [ "$done" -lt "$COUNT" ]; do
        n=$SHARE
        [ $(( done + n )) -le "$COUNT" ] || n=$(( COUNT - done ))
        echo "$gen $(( first + done )) $n"
        done=$(( done + n ))
    done
done > "$OUT/shares.txt"
echo "$(wc -l < "$OUT/shares.txt") shares of up to $SHARE programs, $JOBS at once"
# each share: its log, and its last line (the count) when it ends
xargs -P "$JOBS" -L 1 sh -c "$JAVA \"\$0\" \"\$1\" \"\$2\" \"$OUT/tmp-\$0-\$1\" > \"$OUT/\$0-\$1.log\" 2>&1; tail -n 1 \"$OUT/\$0-\$1.log\"" \
    < "$OUT/shares.txt" || true
rm -rf "$OUT"/tmp-*
FAILURES=$(grep -h " FAILED: " "$OUT"/*.log | cut -c1-300 || true)
if [ -n "$FAILURES" ]; then
    echo "$FAILURES"
    echo "random programs FAILED (the messages in $OUT/*.log)"
    exit 1
fi
if [ "$(grep -l " failed$" "$OUT"/*.log | wc -l)" -ne "$(wc -l < "$OUT/shares.txt")" ]; then
    echo "a share did not finish (see $OUT/*.log)"
    exit 1
fi
echo "random programs: all $(( COUNT * 2 )) passed"
