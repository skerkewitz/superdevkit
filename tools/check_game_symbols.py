#!/usr/bin/env python3
"""Checks that a game's object files reach nothing but superdevkit's console API (ADR record 34).

    python3 -I tools/check_game_symbols.py HEADER_DIR OBJECT...

Every symbol the objects leave undefined (nm -u) must be
  - a function or variable declared in HEADER_DIR/*.h (lib/include/sdevkit), read from the
    headers, so the list cannot fall behind them;
  - defined by one of the game's own objects; or
  - one of COMPILER_SYMBOLS, which the C compiler calls on its own.
Anything else (a raylib or libc function declared by hand, the pseudo SNES's internals) is an
error naming the symbol and the object; the exit status is then 1.
"""
import os
import re
import subprocess
import sys

COMPILER_SYMBOLS = {
    # gcc and clang copy and clear structs and arrays with these even with -ffreestanding; a file
    # that includes dampf's <string.h> gets its static memcpy and memset instead
    "memcpy", "memset", "memmove",
    # gcc on distributions that turn on -fstack-protector by default (Arch, Debian, Ubuntu, Fedora)
    "__stack_chk_fail",
}

DECLARATION = re.compile(r"\b(\w+)\s*\([^(){};]*\)\s*;")
EXTERN = re.compile(r"\bextern\b[^;(]*?\b(\w+)\s*(?:\[[^\]]*\])*\s*;")


def api(header_dir):
    names = set()
    for f in sorted(os.listdir(header_dir)):
        if f.endswith(".h"):
            with open(os.path.join(header_dir, f), encoding="utf-8") as h:
                c = re.sub(r"(?s)/\*.*?\*/", " ", h.read())
            c = re.sub(r"//[^\n]*", " ", c)
            c = re.sub(r"(?m)^\s*#.*$", " ", c)
            names |= set(DECLARATION.findall(c)) | set(EXTERN.findall(c))
    return names


def symbols(obj, undefined):
    flags = ["-u"] if undefined else ["--defined-only", "-g"]
    out = subprocess.run(["nm"] + flags + [obj], check=True, capture_output=True, text=True).stdout
    return [line.split()[-1] for line in out.splitlines() if line.strip()]


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip().split("\n\n")[1], file=sys.stderr)
        return 2
    allowed = api(argv[0]) | COMPILER_SYMBOLS
    for obj in argv[1:]:
        allowed |= set(symbols(obj, False))
    bad = 0
    for obj in argv[1:]:
        for name in symbols(obj, True):
            if name not in allowed:
                print("%s: uses %s, which is not part of superdevkit's console API (lib/include/sdevkit, "
                      "ADR record 34)" % (obj, name), file=sys.stderr)
                bad += 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
