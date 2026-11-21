#!/bin/sh
# Fetches MesenCE (ADR-6F3CK1V6), the emulator of the console checks and the debugger, into
# .mesen/ (not in git): the Linux build (Mesen) and the Windows build (Mesen.exe) side by side.
# Both run portable: they keep their settings and files in .mesen/, next to settings.json.
#   dev/fetch_mesen.sh
# The settings the scripts and tests need are merged into .mesen/settings.json:
#   - a SNES joypad in both ports (without one, emu.setInput does nothing);
#   - Lua may read and write files (the scripts write their results);
#   - no frame skipping: at full speed Mesen otherwise skips drawing frames depending on the
#     host's speed, and a screenshot then shows an older frame.
# The Linux build needs libICE, libSM and SDL2 even headless (its UI toolkit starts first).
# When they are missing and pacman is there (Arch), they are unpacked into .mesen/lib, which
# dev/mesen.sh and the tests put on LD_LIBRARY_PATH; elsewhere the script says what to install.
# Needs curl, unzip and python3.
set -e
cd "$(dirname "$0")/.."
VERSION=2.2.1
URL=https://github.com/nesdev-org/MesenCE/releases/download/$VERSION
DIR=.mesen
mkdir -p "$DIR" build/mesen
for zip in "Mesen_${VERSION}_Linux_x64.zip" "Mesen_${VERSION}_Windows.zip"; do
    [ -f "build/mesen/$zip" ] || curl -sSfL -o "build/mesen/$zip" "$URL/$zip"
    unzip -o -q "build/mesen/$zip" -d "$DIR"
done
chmod +x "$DIR/Mesen" "$DIR/Mesen.exe"
python3 -I - "$DIR/settings.json" <<'EOF'
import json, os, sys
path = sys.argv[1]
s = {}
if os.path.exists(path):
    with open(path, encoding="utf-8-sig") as f:
        s = json.load(f)
snes = s.setdefault("Snes", {})
snes.setdefault("Port1", {})["Type"] = "SnesController"
snes.setdefault("Port2", {})["Type"] = "SnesController"
snes["DisableFrameSkipping"] = True
s.setdefault("Debug", {}).setdefault("ScriptWindow", {})["AllowIoOsAccess"] = True
with open(path, "w", encoding="utf-8") as f:
    json.dump(s, f, indent=2)
    f.write("\n")
EOF
missing=""
for lib in libICE.so.6 libSM.so.6 libSDL2-2.0.so.0; do
    ldconfig -p 2>/dev/null | grep -q "$lib" || [ -e "$DIR/lib/$lib" ] || missing="$missing $lib"
done
if [ -n "$missing" ]; then
    if command -v pacman > /dev/null; then
        mkdir -p build/mesen/pkg "$DIR/lib"
        for url in $(pacman -Sp libice libsm sdl2-compat 2>/dev/null); do
            pkg=build/mesen/pkg/$(basename "$url")
            [ -f "$pkg" ] || curl -sSfL -o "$pkg" "$url"
            tar --zstd -xf "$pkg" -C build/mesen/pkg --wildcards 'usr/lib/*.so*' 2>/dev/null || true
        done
        cp -a build/mesen/pkg/usr/lib/*.so* "$DIR/lib/"
    else
        echo "fetch_mesen: the Linux Mesen needs$missing; install them (Debian/Ubuntu: libice6 libsm6 libsdl2-2.0-0)"
    fi
fi
echo "MesenCE $VERSION in $DIR (Mesen for Linux, Mesen.exe for Windows)"
