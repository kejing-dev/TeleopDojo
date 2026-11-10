#!/usr/bin/env python3
"""Loudness evidence (factory build.md: short cues only): every cue at the app's default gain (master 90 % x the cue's
gain in platform/Sound.kt), one after another with 0.6 s gaps, rendered (not recorded: the emulator has no audio
capture here). Measured like ui_gates: 20 ms windows above peak - 30 dB.

    python -I tools/loudness.py OUT.wav
"""
import os
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SFX = os.path.join(ROOT, "app", "src", "main", "assets", "sfx")
MASTER = 0.9
CUES = [("lit", 0.8), ("grab1", 0.9), ("grab2", 0.9), ("settle1", 0.9), ("settle2", 0.9), ("settle3", 0.9), ("bump1", 1.0), ("bump2", 1.0)]
SR = 44100


def read(name):
    with wave.open(os.path.join(SFX, name + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768


def main():
    out = sys.argv[1]
    parts = []
    for name, g in CUES:
        parts += [read(name) * MASTER * g, np.zeros(int(0.6 * SR))]
    x = np.concatenate(parts)
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    peak = np.max(np.abs(x))
    n = int(0.02 * SR)
    wins = [x[i:i + n] for i in range(0, len(x) - n, n)]
    rms = np.array([np.sqrt(np.mean(v ** 2)) for v in wins])
    loud = rms[rms > peak * 10 ** (-30 / 20)]
    print("peak %.1f dBFS, sounding RMS %.1f dBFS (%d windows), rendered at master %d %%" % (20 * np.log10(peak), 20 * np.log10(np.sqrt(np.mean(loud ** 2))), len(loud), MASTER * 100))


if __name__ == "__main__":
    main()
