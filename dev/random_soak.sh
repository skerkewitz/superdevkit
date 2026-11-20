#!/bin/sh
# A long run of random programs before a release (docs/testing.md, Random programs): N programs of
# each generator (default 5,000), starting at a seed (default: today's, as the build takes it for
# the loop programs; version 1 starts at 1). A failure names its seed and the command that runs
# that program alone; dev/bisect_passes.sh then names the pass that broke it.
#   dev/random_soak.sh                 5,000 of each, today's seeds
#   dev/random_soak.sh 20000           20,000 of each
#   dev/random_soak.sh 5000 1234000    starting at seed 1234000
set -e
cd "$(dirname "$0")/.."
COUNT=${1:-5000}
if [ -n "$2" ]; then
    SEED="-PrandomSeed=$2"
else
    SEED=""
fi
# one Gradle run per generator: the test task stops after 2 hours, and 5,000 loop programs take
# about 85 minutes
./gradlew :compiler:test --tests 'dampf.exec.RandomProgramTest.loopProgramsMatchGcc' -Prandom="$COUNT" $SEED
./gradlew :compiler:test --tests 'dampf.exec.RandomProgramTest.randomProgramsMatchGcc' -Prandom="$COUNT" $SEED
