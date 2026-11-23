#!/bin/sh
# Plays a recording of the desktop (lib/desktop: F11 saves <game>_rec_<n>.txt) on the SNES side,
# in dampf's emulator (dampf.emu.ExampleCheck), and compares the checksum of every tick with the
# one recorded. Names the first tick that differs; the exit status is then 1.
#   dev/replay.sh build/mygame.sfc mygame_rec_0.txt [hashes.txt [sound.txt]]
# hashes.txt and sound.txt get the ROM's checksums and DSP writes, in the formats of the desktop's
# --check, so a game can compare them with its desktop build using the kit alone.
# Works in a superdevkit home (the jars in java/) and in the kit's repository (its installation).
# The ROM must be the build of the same source as the desktop program that recorded the file.
# The Java is the one bin/dampf takes: a release's own in jre/, else JAVA_HOME, else the PATH's.
set -e
ROM=$(realpath "${1:?usage: dev/replay.sh <rom.sfc> <recording>}")
REC=$(realpath "${2:?usage: dev/replay.sh <rom.sfc> <recording> [hashes.txt [sound.txt]]}")
HASHES=${3:+$(realpath -m "$3")}
SOUND=${4:+$(realpath -m "$4")}
cd "$(dirname "$0")/.."
JARS=java                                   # in a superdevkit home
if [ ! -d "$JARS" ]; then                   # in the repository
    JARS=build/install/superdevkit/java
    [ -d "$JARS" ] || ./gradlew -q installDist
fi
JAVA=java
[ -n "$JAVA_HOME" ] && JAVA=$JAVA_HOME/bin/java
[ -x jre/bin/java ] && JAVA=jre/bin/java
"$JAVA" -cp "$JARS/*" dampf.emu.ExampleCheck "$ROM" "$REC" ${HASHES:+"$HASHES"} ${SOUND:+"$SOUND"}
