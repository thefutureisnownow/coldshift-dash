"""Records a navigation voice pack with ElevenLabs: the app's built-in pack, or another.

    python3 tools/make_voice_pack.py Angie                      the built-in pack (android/car/assets/voice/angie)
    python3 tools/make_voice_pack.py "Some Voice" --out DIR     another pack, into DIR/<pack id>/

The voice is found by name or id in the ElevenLabs account (ELEVENLABS_API_KEY in the environment).
The lines are LINES below; their keys are the clips the app's Voice.java plays. Each paid take is cached
(keyed by voice, model and text) in the user cache folder (coldshift.dash/voice-takes) and reused, so a
line is only paid for again when its text changes. Clips are made with ffmpeg: silence trimmed from both
ends, levelled to -16 LUFS, mono MP3.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

API = "https://api.elevenlabs.io"
MODEL = "eleven_v4"
LOUDNESS = -16

# what each clip says
LINES = {
    "turn_left": "Turn left.",
    "turn_right": "Turn right.",
    "turn_slight_left": "Turn slightly left.",
    "turn_slight_right": "Turn slightly right.",
    "turn_sharp_left": "Turn sharp left.",
    "turn_sharp_right": "Turn sharp right.",
    "keep_left": "Keep left.",
    "keep_right": "Keep right.",
    "exit_left": "Take the exit on the left.",
    "exit_right": "Take the exit on the right.",
    "u_turn": "Make a U-turn.",
    "straight": "Continue straight.",
    "roundabout": "Enter the roundabout.",
    "roundabout_1": "At the roundabout, take the first exit.",
    "roundabout_2": "At the roundabout, take the second exit.",
    "roundabout_3": "At the roundabout, take the third exit.",
    "roundabout_4": "At the roundabout, take the fourth exit.",
    "roundabout_5": "At the roundabout, take the fifth exit.",
    "roundabout_6": "At the roundabout, take the sixth exit.",
    "roundabout_7": "At the roundabout, take the seventh exit.",
    "roundabout_8": "At the roundabout, take the eighth exit.",
    "destination": "You will reach your destination.",
    "in_2km": "In two kilometres,",
    "in_1km": "In one kilometre,",
    "in_500m": "In five hundred metres,",
    "in_300m": "In three hundred metres,",
    "in_200m": "In two hundred metres,",
    "in_100m": "In one hundred metres,",
    "then": "Then",
    "follow_5km": "Follow the road for five kilometres.",
    "follow_10km": "Follow the road for ten kilometres.",
    "follow_20km": "Follow the road for twenty kilometres.",
    "follow_50km": "Follow the road for fifty kilometres.",
    "arrived": "You have arrived at your destination.",
    "off_route": "Off route. Finding a new route.",
    "no_route": "No route found.",
    "gps_lost": "GPS signal lost.",
    "gps_back": "GPS signal is back.",
}


def pack_id(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "voice"


def cache_dir():
    if sys.platform == "darwin":
        d = os.path.expanduser("~/Library/Caches/coldshift.dash")
    elif os.name == "nt":
        d = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "coldshift.dash", "cache")
    else:
        d = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "coldshift.dash")
    return d


def call(path, payload=None):
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        sys.exit("ELEVENLABS_API_KEY is not set")
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, headers={"xi-api-key": key, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def find_voice(name_or_id):
    found = json.loads(call("/v2/voices?page_size=100&search=" + urllib.parse.quote(name_or_id)))["voices"]
    exact = [v for v in found if v["voice_id"] == name_or_id or v["name"].lower() == name_or_id.lower()]
    if not exact:
        sys.exit("no voice called %r in the account (matches: %s)" % (name_or_id, ", ".join(v["name"] for v in found) or "none"))
    return exact[0]["voice_id"], exact[0]["name"]


def clip(take, out):
    trim = ("silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.02,areverse,"
            "silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.06,areverse,"
            "loudnorm=I=%d:TP=-1.5:LRA=11" % LOUDNESS)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", take, "-af", trim, "-ac", "1", "-ar", "44100",
                    "-c:a", "libmp3lame", "-q:a", "4", out], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("voice", help="name or id in the ElevenLabs account")
    ap.add_argument("--name", help="the pack's name in the app (default: the voice's)")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--out", default=os.path.join(ROOT, "android", "car", "assets", "voice"),
                    help="folder the pack's folder goes in")
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("needs ffmpeg (brew install ffmpeg)")
    voice_id, voice_name = find_voice(a.voice)
    name = a.name or voice_name
    pid = pack_id(name)
    takes = os.path.join(cache_dir(), "voice-takes")
    os.makedirs(takes, exist_ok=True)
    os.makedirs(a.out, exist_ok=True)
    tmp = tempfile.mkdtemp(dir=a.out)
    try:
        lines = {}
        for i, (key, text) in enumerate(LINES.items(), 1):
            request = {"text": text, "model_id": a.model}
            take = os.path.join(takes, hashlib.sha256(json.dumps([voice_id, request], sort_keys=True).encode()).hexdigest() + ".mp3")
            paid = not os.path.exists(take)
            if paid:
                with open(take + ".part", "wb") as f:
                    f.write(call("/v1/text-to-speech/%s?output_format=mp3_44100_128" % voice_id, request))
                os.replace(take + ".part", take)
            clip(take, os.path.join(tmp, key + ".mp3"))
            lines[key] = key + ".mp3"
            print("%2d/%d %s%s" % (i, len(LINES), key, "" if paid else " (take reused)"))
        with open(os.path.join(tmp, "voice.json"), "w", encoding="utf-8") as f:
            json.dump({"version": 1, "id": pid, "name": name, "voice_id": voice_id, "model": a.model, "lines": lines}, f, indent=1)
        dest = os.path.join(a.out, pid)
        if os.path.exists(dest):
            shutil.rmtree(dest)
        os.replace(tmp, dest)
        print("voice pack:", dest)
    finally:
        if os.path.exists(tmp):
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
