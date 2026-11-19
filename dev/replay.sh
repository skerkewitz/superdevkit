#!/bin/sh
# Plays a recording of the desktop (lib/desktop: F11 saves <game>_rec_<n>.txt) on the SNES side,
# in dampf's emulator (dampf.emu.ExampleCheck), and compares the checksum of every tick with the
# one recorded. Names the first tick that differs; the exit status is then 1.
#   dev/replay.sh build/mygame.sfc mygame_rec_0.txt
# Works in a superdevkit home (the jars in java/) and in the kit's repository (its installation).
# The ROM must be the build of the same source as the desktop program that recorded the file.
# JAVA_HOME picks the Java, as for bin/dampf.
set -e
ROM=$(realpath "${1:?usage: dev/replay.sh <rom.sfc> <recording>}")
REC=$(realpath "${2:?usage: dev/replay.sh <rom.sfc> <recording>}")
cd "$(dirname "$0")/.."
JARS=java                                   # in a superdevkit home
if [ ! -d "$JARS" ]; then                   # in the repository
    JARS=build/install/superdevkit/java
    [ -d "$JARS" ] || ./gradlew -q installDist
fi
JAVA=java
[ -n "$JAVA_HOME" ] && JAVA=$JAVA_HOME/bin/java
"$JAVA" -cp "$JARS/*" dampf.emu.ExampleCheck "$ROM" "$REC"
