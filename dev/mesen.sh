# Sourced by the dev scripts that run a ROM in Mesen (ADR record 33: MesenCE, dev/fetch_mesen.sh).
# Sets MESEN, the executable: the one MESEN names, else the Linux build in .mesen/, else the
# Windows build there (from WSL). MESEN_DIR is its folder, where the scripts put the ROM and the
# Lua script; Mesen runs portable there and the scripts' relative file names land there too.
#   mesen_run SECONDS ROM LUA     runs the test runner in MESEN_DIR, at most SECONDS
if [ -z "${MESEN:-}" ]; then
    if [ -x .mesen/Mesen ]; then MESEN=.mesen/Mesen; else MESEN=.mesen/Mesen.exe; fi
fi
[ -f "$MESEN" ] || { echo "no Mesen at $MESEN (dev/fetch_mesen.sh, or set MESEN=...)"; exit 1; }
MESEN_DIR=$(dirname "$MESEN")

mesen_run() {
    (cd "$MESEN_DIR" && LD_LIBRARY_PATH="$PWD/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        timeout "$1" ./"$(basename "$MESEN")" --testrunner --timeout=$(($1 - 100)) "$2" "$3" > /dev/null 2>&1) || true
}
