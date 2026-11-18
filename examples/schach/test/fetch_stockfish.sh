#!/bin/sh
# Fetches Stockfish (a pinned release, the official build for x86-64 with AVX2) for
# test/vs_stockfish.sh, into examples/schach/build/stockfish/ (not in git).
#   examples/schach/test/fetch_stockfish.sh
# Needs curl and tar.
set -e
cd "$(dirname "$0")/.."
VERSION=sf_17.1
PACKAGE=stockfish-ubuntu-x86-64-avx2.tar
DEST=build/stockfish
[ -x "$DEST/stockfish" ] && exit 0
mkdir -p "$DEST"
curl -sSfL -o "$DEST/$PACKAGE" "https://github.com/official-stockfish/Stockfish/releases/download/$VERSION/$PACKAGE"
tar -xf "$DEST/$PACKAGE" -C "$DEST"
cp "$DEST/stockfish/stockfish-ubuntu-x86-64-avx2" "$DEST/stockfish.bin"
rm -rf "$DEST/stockfish" && mv "$DEST/stockfish.bin" "$DEST/stockfish"
echo "Stockfish $VERSION in $DEST/stockfish"
