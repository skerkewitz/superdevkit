#!/bin/sh
# Fetches raylib, ready-built, for a desktop target of the dev kit (ADR record 31: raylib is
# pinned to one version and not built by the kit). Writes into build/raylib/<target>/ (not in git).
#   dev/fetch_raylib.sh linux      (gcc)
#   dev/fetch_raylib.sh windows    (MSVC, for dev/build_desktop_windows.sh)
# Needs curl, and tar or unzip.
set -e
cd "$(dirname "$0")/.."
VERSION=5.5
TARGET=${1:?usage: dev/fetch_raylib.sh linux}
case "$TARGET" in
    linux) PACKAGE=raylib-${VERSION}_linux_amd64.tar.gz ;;
    windows) PACKAGE=raylib-${VERSION}_win64_msvc16.zip ;;
    *) echo "fetch_raylib: no package for target $TARGET (linux, windows)" >&2; exit 2 ;;
esac
DEST=build/raylib/$TARGET
if [ -f "$DEST/include/raylib.h" ]; then
    exit 0
fi
mkdir -p build/raylib
curl -sSfL -o "build/raylib/$PACKAGE" "https://github.com/raysan5/raylib/releases/download/$VERSION/$PACKAGE"
rm -rf "$DEST" && mkdir -p "$DEST"
case "$PACKAGE" in
    *.zip) unzip -q "build/raylib/$PACKAGE" -d "$DEST.tmp" && mv "$DEST.tmp"/*/* "$DEST" && rm -rf "$DEST.tmp" ;;
    *) tar -xzf "build/raylib/$PACKAGE" -C "$DEST" --strip-components=1 ;;
esac
echo "raylib $VERSION for $TARGET in $DEST"
