#!/usr/bin/env bash
# Checks a Linux release zip the way a user gets it: unpacks it into a folder with a space in its
# name and builds an example for the SNES and the desktop with what the zip holds. JAVA_HOME points
# nowhere, so a launcher that does not take the zip's own Java fails. Windows: check_release.ps1.
#
#   dev/check_release.sh build/release/superdevkit-<version>-linux-x64.zip
set -euo pipefail

zip=$(realpath "${1:?usage: dev/check_release.sh <zip>}")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
dir="$tmp/a folder"
mkdir "$dir"
unzip -q "$zip" -d "$dir"
home=$(find "$dir" -mindepth 1 -maxdepth 1 -type d)
export JAVA_HOME=/nonexistent
unset EMCC

version=$(cat "$home/VERSION")
[[ $("$home/bin/superdevkit" --version) == "superdevkit $version" ]] || { echo "superdevkit: wrong version" >&2; exit 1; }
[[ $("$home/bin/dampf" --version) == *"$version"* ]] || { echo "dampf: wrong version" >&2; exit 1; }
[[ -f "$home/raylib/web/include/raylib.h" ]] || { echo "no raylib for the web" >&2; exit 1; }

cd "$dir"
"$home/bin/superdevkit" init hello
cd hello
"$home/bin/superdevkit" build snes
[[ -s build/snes/hello.sfc ]] || { echo "no ROM" >&2; exit 1; }
log=$("$home/bin/superdevkit" build linux)
echo "$log"
[[ -x build/linux/hello ]] || { echo "no desktop program" >&2; exit 1; }
[[ $log != *fetching* ]] || { echo "raylib was fetched, the zip should hold it" >&2; exit 1; }
echo "release zip checked: superdevkit $version"
