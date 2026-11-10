#!/usr/bin/env python3
"""Builds the app's cue sounds (android/car/assets/sounds) from the generated instruments (art/sounds/sfx).

A generated effect has the right voice but not reliably the right notes: asked for "two notes rising", a take
may hold its pitch. So the generated sounds are used as instruments (a bell, a blip, an alert tone) and the
cues are played on them here, exactly: which note, when, how long. That also makes the set one family, and
makes a start, a stop and a warning different in ways an ear picks up without looking: up, down, three beeps.

    python3 tools/make_cues.py

Needs ffmpeg. Each cue is a list of notes: (instrument, semitones up or down, start in s, length in s).
"""
import os, struct, subprocess, wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "art/sounds/sfx")
OUT = os.path.join(ROOT, "android/car/assets/sounds")
RATE = 44100
BELL, BLIP, ALERT = "notice", "mark_on", "warn"
CUES = {
    "rec_start": [(BELL, 0, 0.00, 0.20), (BELL, 7, 0.16, 0.55)],          # up a fifth: it has begun
    "rec_stop":  [(BELL, 7, 0.00, 0.20), (BELL, 0, 0.16, 0.55)],          # and back down: it has ended
    "mark_on":   [(BLIP, 0, 0.00, 0.14)],
    "mark_off":  [(BLIP, 0, 0.00, 0.12), (BLIP, 0, 0.17, 0.14)],
    "lock":      [(BELL, -5, 0.00, 0.12), (BELL, -5, 0.14, 0.30)],        # two low knocks on one note
    "warn":      [(ALERT, 0, 0.00, 0.16), (ALERT, 0, 0.26, 0.16), (ALERT, 0, 0.52, 0.16)],
    "notice":    [(BELL, 4, 0.00, 0.80)],
}


def build(name, notes):
    args, parts = [], []
    for i, (inst, semis, start, length) in enumerate(notes):
        args += ["-i", os.path.join(SRC, inst + ".wav")]
        parts.append("[%d:a]asetrate=%d,aresample=%d,atrim=0:%.3f,afade=t=in:d=0.004,afade=t=out:st=%.3f:d=0.04,"
                     "adelay=%d[a%d]" % (i, round(RATE * 2 ** (semis / 12.0)), RATE, length, max(0, length - 0.04),
                                         round(start * 1000), i))
    graph = ";".join(parts) + ";" + "".join("[a%d]" % i for i in range(len(notes))) \
        + "amix=inputs=%d:normalize=0:duration=longest[out]" % len(notes)
    out = os.path.join(OUT, name + ".wav")
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args, "-filter_complex", graph, "-map", "[out]", "-ac", "1",
                    "-ar", str(RATE), "-c:a", "pcm_s16le", out], check=True)
    # level the peak to -1 dB
    w = wave.open(out)
    n = w.getnframes()
    data = struct.unpack("<%dh" % n, w.readframes(n))
    w.close()
    k = 29204.0 / max(1, max(abs(x) for x in data))
    w = wave.open(out, "wb")
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(struct.pack("<%dh" % n, *[int(max(-32767, min(32767, x * k))) for x in data]))
    w.close()
    print("%s %.2fs" % (name, n / RATE))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for cue, notes in CUES.items():
        build(cue, notes)
