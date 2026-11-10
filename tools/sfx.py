#!/usr/bin/env python3
"""Teleop Dojo cues (design rules.audio; profile D51: physical sounds from CC0 recordings, synthesis only for UI cues).

    python -I tools/sfx.py SRC_DIR       (SRC_DIR holds the freesound HQ previews listed in SOURCES)

Needs numpy and miniaudio (mp3 decoding; PYTHONPATH may point at a --target install). Writes app/src/main/assets/sfx/*.wav
(44.1 kHz mono 16-bit, peak -1 dBFS) and prints a peak / RMS table of the sounding part for ui-audit.md.
"""
import os
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sfx")
SR = 44100

# cue variant -> (file, which onset, length s); all Creative Commons 0 (freesound pages in sound-sources.md)
SOURCES = {
    "grab1": ("452606_612689.mp3", 0, 0.18),
    "grab2": ("452606_612689.mp3", 1, 0.18),
    "settle1": ("343164_6001460.mp3", 0, 0.30),
    "settle2": ("596484_10974703.mp3", 0, 0.30),
    "settle3": ("343164_6001460.mp3", 1, 0.30),
    "bump1": ("454222_612689.mp3", 0, 0.35),
    "bump2": ("808200_13523213.mp3", 0, 0.35),
}


def decode(path):
    import miniaudio

    d = miniaudio.decode_file(path, output_format=miniaudio.SampleFormat.FLOAT32, nchannels=1, sample_rate=SR)
    return np.frombuffer(d.samples, dtype=np.float32).astype(np.float64)


def onsets(x, min_gap=0.15):
    env = np.abs(x)
    win = int(0.005 * SR)
    env = np.convolve(env, np.ones(win) / win, mode="same")
    thr = max(env.max() * 0.25, 1e-4)
    out = []
    i = 0
    while i < len(env):
        if env[i] > thr:
            out.append(max(0, i - int(0.004 * SR)))
            i += int(min_gap * SR)
        else:
            i += 1
    return out


def cut(x, start, length):
    seg = x[start:start + int(length * SR)].copy()
    n = len(seg)
    fade = int(0.04 * SR)
    if n > fade:
        seg[-fade:] *= np.linspace(1, 0, fade)
    seg[: int(0.002 * SR)] *= np.linspace(0, 1, int(0.002 * SR))
    return seg


def norm(x, peak_db=-1.0):
    p = np.max(np.abs(x)) or 1.0
    return x / p * (10 ** (peak_db / 20))


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    pcm = np.clip(x, -1, 1)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((pcm * 32767).astype("<i2").tobytes())


def chime():
    """点亮: a soft two-partial UI chime (synthesised: a UI cue, not a physical sound)."""
    t = np.arange(int(0.35 * SR)) / SR
    env = np.exp(-t * 9) * (1 - np.exp(-t * 400))
    return env * (np.sin(2 * np.pi * 1318.5 * t) + 0.5 * np.sin(2 * np.pi * 1975.5 * t))


def db(v):
    return 20 * np.log10(max(v, 1e-9))


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "."
    rows = []
    for name, (f, k, length) in SOURCES.items():
        x = decode(os.path.join(src, f))
        on = onsets(x)
        start = on[min(k, len(on) - 1)] if on else 0
        y = norm(cut(x, start, length))
        write(name, y)
        rows.append((name, f, start / SR, y))
    y = norm(chime())
    write("lit", y)
    rows.append(("lit", "synth", 0.0, y))
    print("| cue | source | onset s | peak dBFS | RMS dBFS (sounding part) |")
    print("|---|---|---|---|---|")
    for name, f, t0, y in rows:
        sounding = y[np.abs(y) > 10 ** (-40 / 20)]
        print("| %s | %s | %.2f | %.1f | %.1f |" % (name, f, t0, db(np.max(np.abs(y))), db(np.sqrt(np.mean(sounding ** 2))) if len(sounding) else -99))


if __name__ == "__main__":
    main()
