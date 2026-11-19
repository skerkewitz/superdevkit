#!/usr/bin/env python3
"""The examples' music, synthesised: CatchMe's chase loop.

    python3 dev/art/music.py examples/catchme/assets/music

A tune of 4 bars at 120 beats a minute (8 seconds, made to loop): a square-wave melody over a
triangle bass, as an 8-bit mono WAV at 11,025 Hz (about 88 KB), which raylib plays on the desktop
(sdevkit_music_play). Python 3 only, no libraries.
"""
import math
import os
import struct
import sys

RATE = 11025
BPM = 120
STEP = 60.0 / BPM / 2          # an eighth note

# MIDI note numbers, an eighth each; 0 is a rest
MELODY = [
    72, 0, 75, 77, 79, 0, 77, 75,   72, 0, 70, 72, 75, 0, 0, 0,
    72, 0, 75, 77, 79, 82, 79, 77,  75, 0, 77, 75, 72, 0, 0, 0,
]
BASS = [
    48, 48, 55, 48, 51, 51, 58, 51,  46, 46, 53, 46, 48, 48, 55, 48,
    48, 48, 55, 48, 51, 51, 58, 51,  53, 53, 51, 50, 48, 48, 43, 47,
]


def freq(note):
    return 440.0 * 2 ** ((note - 69) / 12.0)


def tune():
    n = int(len(MELODY) * STEP * RATE)
    out = [0.0] * n
    per = int(STEP * RATE)
    for i in range(len(MELODY)):
        for k in range(per):
            t = k / RATE
            env = min(1.0, k / 60.0) * max(0.0, 1.0 - k / per * 0.6)
            v = 0.0
            if MELODY[i]:
                phase = (t * freq(MELODY[i])) % 1.0
                v += 0.28 * env * (1.0 if phase < 0.5 else -1.0)
            if BASS[i]:
                phase = (t * freq(BASS[i])) % 1.0
                v += 0.32 * min(1.0, k / 30.0) * (4 * abs(phase - 0.5) - 1)
            out[i * per + k] = v
    return out


def write_wav(path, samples):
    data = bytes(max(0, min(255, int(128 + 127 * s))) for s in samples)
    with open(path, "wb") as f:
        f.write(b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt ")
        f.write(struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE, 1, 8))
        f.write(b"data" + struct.pack("<I", len(data)) + data)


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__.split("\n\n")[1])
    os.makedirs(argv[1], exist_ok=True)
    write_wav(os.path.join(argv[1], "chase.wav"), tune())


if __name__ == "__main__":
    main(sys.argv)
