#!/usr/bin/env bash
# Finds the pass application that breaks a program (dampf.util.PassLimit; LLVM's -opt-bisect-limit).
#
#   dev/bisect_passes.sh command [args...]
#
# The command must succeed when the program is right and fail when it is wrong. It runs with
# DAMPF_OPT_LIMIT set to a number n: dampf (the command line and the tests) then stops its MIR and
# LIR passes after n applications that changed something. The script checks that the command
# succeeds with no pass and fails with all of them, finds the smallest n at which it fails, and
# names the n-th application: the pass and the function it broke. For example:
#
#   dev/bisect_passes.sh ./gradlew :compiler:test --tests dampf.exec.ExecutionTest -Pexec=loops
#   dev/bisect_passes.sh ./gradlew :compiler:test --tests dampf.exec.RandomProgramTest -PrandomSeed=1234 -Prandom=1
#   dev/bisect_passes.sh sh -c 'dampf build --entry test_main -o /tmp/t.sfc t.c && ./check_rom.sh /tmp/t.sfc'
#
# A test command should run one program: the limit counts per program, so with several the
# smallest failing n names the first application that broke any of them.
set -u

if [ $# -eq 0 ]; then
    sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi

log=$(mktemp)
trap 'rm -f "$log"' EXIT

# runs the command with the limit $1 (empty: no limit); success when the program is right
run() {
    if [ -z "$1" ]; then
        env -u DAMPF_OPT_LIMIT -u DAMPF_OPT_LIMIT_LOG "$@" >/dev/null 2>&1
    else
        local n=$1
        shift
        DAMPF_OPT_LIMIT=$n DAMPF_OPT_LIMIT_LOG=$log "$@" >/dev/null 2>&1
    fi
}

limited() {
    local n=$1
    shift
    : > "$log"
    run "$n" "$@"
}

echo "with all passes ..."
if run "" "$@"; then
    echo "the command succeeds with all passes: nothing to find"
    exit 1
fi
echo "with no pass ..."
if ! limited 0 "$@"; then
    echo "the command fails with no pass applied: the cause is not a pass (lowering, selection, the runtime, the test)"
    exit 1
fi

# good: a limit at which the command succeeds; bad: one at which it fails
good=0
bad=1
while limited "$bad" "$@"; do
    echo "  $bad: right"
    good=$bad
    bad=$((bad * 2))
    if [ "$bad" -gt 100000000 ]; then
        echo "right with $good applications and wrong with all: the failure needs something the limit does not stop"
        exit 1
    fi
done
echo "  $bad: wrong"
while [ $((bad - good)) -gt 1 ]; do
    mid=$(((good + bad) / 2))
    if limited "$mid" "$@"; then
        echo "  $mid: right"
        good=$mid
    else
        echo "  $mid: wrong"
        bad=$mid
    fi
done

limited "$bad" "$@"
echo
echo "right with $good pass applications, wrong with $bad. The application that breaks it:"
if [ -s "$log" ]; then
    sort -u "$log" | sed 's/^/  /'
else
    echo "  (not reported: does the command pass DAMPF_OPT_LIMIT_LOG on to dampf? see dampf build --opt-limit $bad)"
fi
exit 0
