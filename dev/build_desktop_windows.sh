#!/bin/sh
# Builds an example for the desktop on Windows, from WSL, with the Visual Studio on the Windows
# side (MSVC, found with vswhere) and raylib's MSVC package: the same three steps as
# dev/build_desktop.sh, which builds for Linux and whose checks are the reference.
#   dev/build_desktop_windows.sh examples/ertuep   -> examples/ertuep/build/windows/ertuep.exe
# Started from Windows (Explorer, or \\wsl.localhost\<distribution>\...\ertuep.exe), it plays the
# game with the keyboard and with the gamepads Windows knows, which WSL does not see.
#
#   1. the game's sources alone, without the host's headers (/X instead of -nostdinc), with
#      dampf's include folder and the kit's public headers;
#   2. dev/check_game_symbols.py on the objects (nm reads MSVC's COFF objects);
#   3. the link with Rachael, the frontend and raylib, as a Windows program without a console.
set -e
cd "$(dirname "$0")/.."
EX=${1:?usage: dev/build_desktop_windows.sh examples/<name>}
EX=${EX%/}
NAME=$(basename "$EX")
CONVERT_ONLY=1 sh "$EX/build.sh"
dev/fetch_raylib.sh windows
OUT=$EX/build/windows
OBJ=$OUT/obj
rm -rf "$OBJ" && mkdir -p "$OBJ"

VSWHERE="/mnt/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
[ -x "$VSWHERE" ] || { echo "build_desktop_windows: no Visual Studio on the Windows side ($VSWHERE)" >&2; exit 1; }
VS=$("$VSWHERE" -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 \
    -property installationPath | tr -d '\r')
[ -n "$VS" ] || { echo "build_desktop_windows: no Visual Studio with the C++ tools for x64" >&2; exit 1; }

w() { wslpath -w "$(realpath -m "$1")"; }
# One batch file per step, run by cmd.exe: vcvars64 sets up the compiler's environment.
# cmd.exe starts in the Windows folder (a WSL path cannot be its current folder), so every
# path is absolute.
run() {
    { printf '@echo off\r\ncall "%s\\VC\\Auxiliary\\Build\\vcvars64.bat" >nul || exit /b 1\r\n' "$VS"
      cat; } > "$OBJ/step.bat"
    cmd.exe /c "$(w "$OBJ/step.bat")" 2>&1 | tr -d '\r' \
        | grep -v -e '^UNC paths are not supported' -e '^CMD.EXE was started' -e "^'\\\\\\\\wsl" \
              -e '^Defaulting to Windows directory' || true
}
CL="cl /nologo /std:c11 /O2 /W3 /utf-8 /MD"   # /MD: the C runtime raylib's package was built with

GAME_SOURCES=""
GAME_OBJECTS=""
for src in "$EX"/src/*.c "$EX"/build/gen/*.c; do
    [ -f "$src" ] || continue
    GAME_SOURCES="$GAME_SOURCES $src"
    GAME_OBJECTS="$GAME_OBJECTS $OBJ/$(basename "$(dirname "$src")")_$(basename "$src" .c).obj"
done
[ -n "$GAME_OBJECTS" ] || { echo "build_desktop_windows: no C sources in $EX/src"; exit 1; }
for src in $GAME_SOURCES; do
    obj=$OBJ/$(basename "$(dirname "$src")")_$(basename "$src" .c).obj
    printf '%s /X /I "%s" /I "%s" /I "%s" /I "%s" /c "%s" /Fo"%s" || exit /b 1\r\n' "$CL" \
        "$(w src/main/resources/dampf/include)" "$(w lib/include)" "$(w "$EX/src")" "$(w "$EX/build")\\gen" \
        "$(w "$src")" "$(w "$obj")"
done | run
for obj in $GAME_OBJECTS; do
    [ -f "$obj" ] || { echo "build_desktop_windows: $obj was not compiled" >&2; exit 1; }
done
# shellcheck disable=SC2086
python3 -I dev/check_game_symbols.py lib/include/sdevkit $GAME_OBJECTS

R=build/raylib/windows
rm -f "$OUT/$NAME.exe"
{
    printf '%s /D_CRT_SECURE_NO_WARNINGS /I "%s" /I "%s" /I "%s" /DGAME_NAME="\\"%s\\"" /Fo"%s\\\\"' "$CL" \
        "$(w lib/include)" "$(w lib/rachael)" "$(w "$R/include")" "$NAME" "$(w "$OBJ")"
    for f in $GAME_OBJECTS lib/rachael/*.c lib/desktop/*.c; do
        printf ' "%s"' "$(w "$f")"
    done
    printf ' "%s" opengl32.lib gdi32.lib winmm.lib user32.lib shell32.lib /Fe"%s"' \
        "$(w "$R/lib/raylib.lib")" "$(w "$OUT")\\$NAME.exe"
    printf '  /link /SUBSYSTEM:WINDOWS /ENTRY:mainCRTStartup || exit /b 1\r\n'
} | run
[ -f "$OUT/$NAME.exe" ] || { echo "build_desktop_windows: the link failed" >&2; exit 1; }
chmod +x "$OUT/$NAME.exe"   # so WSL can start it too, as the checks do
echo "built $OUT/$NAME.exe"
