#!/usr/bin/env python3
"""The examples' sound effects, made the way their graphics are: synthesised here, so they are the
kit's own, need no licence and can be made again (docs/sound.md).

    dev/art/sfx.py            writes examples/<example>/assets/sfx/<effect>.wav

In the manner of sfxr: each sound is a list of parts, a part a wave (square with a duty, triangle,
sine, noise) with a pitch that slides, an envelope (attack, hold, decay) and a volume; the parts
are added. 16 bits, mono, 32 kHz. Their settings for the SNES (rate, treble, gain, volume) are in
each example's sfx.json, chosen in tools/brr_lab.html.
"""
import math
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
RATE = 32000


class Noise:
    """A fixed LCG, so the noise is the same every time."""

    def __init__(self, seed):
        self.s = seed

    def next(self):
        self.s = (self.s * 1103515245 + 12345) & 0x7FFFFFFF
        return ((self.s >> 15) & 0xFFFF) / 32768.0 - 1.0


def part(wave, f0, f1=None, attack=0.002, hold=0.0, decay=0.1, vol=1.0, start=0.0, duty=0.5, seed=1,
         noise_hz=None):
    """One part: the pitch slides from f0 to f1 (exponentially) over its length."""
    f1 = f0 if f1 is None else f1
    length = attack + hold + decay
    n = int(length * RATE)
    out = []
    phase = 0.0
    noise = Noise(seed)
    held = 0.0
    hold_n = 0
    for i in range(n):
        t = i / RATE
        f = f0 * (f1 / f0) ** (t / length)
        phase = (phase + f / RATE) % 1.0
        if wave == "square":
            v = 1.0 if phase < duty else -1.0
        elif wave == "triangle":
            v = 4 * phase - 1 if phase < 0.5 else 3 - 4 * phase
        elif wave == "sine":
            v = math.sin(2 * math.pi * phase)
        else:   # noise, held for RATE / noise_hz samples: a lower noise_hz sounds deeper
            if hold_n <= 0:
                held = noise.next()
                hold_n = max(1, int(RATE / (noise_hz or f)))
            hold_n -= 1
            v = held
        if t < attack:
            e = t / attack
        elif t < attack + hold:
            e = 1.0
        else:
            e = max(0.0, 1.0 - (t - attack - hold) / decay) ** 2
        out.append(v * e * vol)
    return int(start * RATE), out


def mix(*parts):
    n = max(s + len(p) for s, p in parts)
    y = [0.0] * n
    for s, p in parts:
        for i, v in enumerate(p):
            y[s + i] += v
    peak = max(1e-9, max(abs(v) for v in y))
    return [v / peak * 0.9 for v in y]


def write(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = b"".join(struct.pack("<h", int(round(max(-1.0, min(1.0, v)) * 32767))) for v in samples)
    head = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack(
        "<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16) + b"data" + struct.pack("<I", len(data))
    path.write_bytes(head + data)


def schach():
    return {
        # a piece put down: a short wooden knock
        "put": mix(part("triangle", 220, 110, decay=0.07, vol=1.0),
                   part("noise", 1, noise_hz=4000, decay=0.02, vol=0.5, seed=3)),
        # a piece taken: a sharper knock with a click
        "take": mix(part("square", 330, 120, decay=0.09, vol=0.6, duty=0.3),
                    part("noise", 1, noise_hz=9000, decay=0.03, vol=0.8, seed=5),
                    part("triangle", 160, 80, decay=0.12, vol=0.8, start=0.01)),
        # your move: a two-tone chime
        "your_move": mix(part("triangle", 784, decay=0.15, vol=0.8),
                         part("triangle", 1047, decay=0.25, vol=0.8, start=0.11)),
        # check: two short beeps
        "check": mix(part("square", 880, hold=0.06, decay=0.02, vol=0.7),
                     part("square", 880, hold=0.06, decay=0.04, vol=0.7, start=0.13)),
        # checkmate: a falling arpeggio
        "mate": mix(*[part("square", f, hold=0.08, decay=0.05 if i < 3 else 0.35, vol=0.6, start=0.12 * i, duty=0.25)
                      for i, f in enumerate([784, 659, 523, 392])]),
    }


def steinschlag():
    return {
        # digging: a dull crunch of noise
        "dig": mix(part("noise", 1, noise_hz=2500, attack=0.004, decay=0.06, vol=1.0, seed=7)),
        # a gem taken: a quick rising sparkle
        "gem": mix(*[part("square", f, decay=0.06 if i < 2 else 0.18, vol=0.6, start=0.04 * i, duty=0.25)
                     for i, f in enumerate([1319, 1568, 2093])]),
        # a boulder landing: a low thump
        "land": mix(part("sine", 110, 45, decay=0.14, vol=1.0),
                    part("noise", 1, noise_hz=1500, decay=0.05, vol=0.6, seed=9)),
        # a beetle hit: a squelch, a falling buzz
        "beetle": mix(part("square", 600, 150, decay=0.18, vol=0.6, duty=0.15),
                      part("noise", 1, noise_hz=6000, decay=0.08, vol=0.4, seed=11)),
        # the miner crushed: a long falling rumble
        "crushed": mix(part("noise", 1, noise_hz=3000, attack=0.005, hold=0.05, decay=0.45, vol=1.0, seed=13),
                       part("square", 300, 60, hold=0.1, decay=0.35, vol=0.5, duty=0.4)),
        # the exit opening: a rising fanfare
        "exit": mix(*[part("square", f, hold=0.05, decay=0.05 if i < 3 else 0.3, vol=0.6, start=0.09 * i, duty=0.5)
                      for i, f in enumerate([523, 659, 784, 1047])]),
    }


def main():
    for example, sounds in (("schach", schach()), ("steinschlag", steinschlag())):
        for name, samples in sounds.items():
            path = ROOT / "examples" / example / "assets" / "sfx" / (name + ".wav")
            write(path, samples)
            print(path.relative_to(ROOT), f"{len(samples) / RATE:.3f} s")


if __name__ == "__main__":
    main()
