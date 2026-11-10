"""Makes the app's cue sounds with ElevenLabs (eleven_text_to_sound_v2).

    python3 tools/generate_audio.py [CUE ...] [--dry-run] [--redo]

Reads the API key from the ELEVENLABS_API_KEY environment variable. The cues and their prompts are in
art/sounds/sounds.json. Each paid take is kept as it came in art/sounds/takes/ and reused, so a cue is only
paid for once (--redo pays for a new take of the cues named). The app's files are made from the takes with
ffmpeg, offline: silence trimmed from both ends, a few milliseconds' fade in and out, peaks levelled to
-1 dB, mono 16-bit WAV, in art/sounds/sfx/CUE.wav. Those are the instruments: tools/make_cues.py builds
the app's cues from them. What every take was asked for, what it cost (credits, measured from the
subscription before and after), its hash and the file made from it are in
art/sounds/generation.json. --dry-run prints what would be generated and roughly its cost, and spends nothing.
(The music and sliced-cue parts come from the game pipeline this was taken from and are not used here.)
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AUDIO = ROOT / 'art/sounds'
APP_SOUNDS = AUDIO / 'sfx'
TAKES = AUDIO / 'takes'
REPORT = AUDIO / 'generation.json'
API = 'https://api.elevenlabs.io/v1'
SFX_MODEL = 'eleven_text_to_sound_v2'
MUSIC_MODEL = 'music_v1'
PROMPT_INFLUENCE = 0.5
## Seconds of a looping track's end crossfaded into its start.
LOOP_FADE = 3.0
## How far below a track's typical loudness its closing fade counts as begun (dB).
FADE_DROP = 7.0
## The silence (dB, seconds) that separates a sliced take's sounds.
SLICE_SILENCE = [(-40, 0.08), (-35, 0.06), (-45, 0.1), (-30, 0.05), (-50, 0.12)]
## Rough costs for --dry-run, credits per second (measured: see the report).
SFX_RATE = 13
MUSIC_RATE = 20
RETRIES = 6
## A take whose loudest moment is quieter than this (dB) is a dud.
SILENT = -30.0
## Loops (a gentle breeze, distant dripping) are quiet by nature, so only this counts as silent.
SILENT_LOOP = -50.0


def key():
    value = os.environ.get('ELEVENLABS_API_KEY')
    if not value:
        sys.exit('ELEVENLABS_API_KEY is not set')
    return value


def call(path, payload=None):
    """An API call, waiting and trying again (up to RETRIES times) when it's rate-limited."""
    for attempt in range(RETRIES + 1):
        request = urllib.request.Request(API + path, headers={'xi-api-key': key(), 'Content-Type': 'application/json'},
                                         data=json.dumps(payload).encode() if payload is not None else None,
                                         method='POST' if payload is not None else 'GET')
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == RETRIES:
                raise
            wait = 10 * (attempt + 1)
            print(f'  rate-limited; trying again in {wait}s')
            time.sleep(wait)


def credits_used():
    return json.loads(call('/user/subscription'))['character_count']


def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-v', 'error', '-y', *args], check=True)


def seconds_of(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', str(path)],
                         capture_output=True, text=True, check=True).stdout
    return float(out.strip())


def peak_gain(path, filters, silent=None):
    """The gain (dB) that brings the filtered sound's peak to -1 dB."""
    silent = SILENT if silent is None else silent
    out = subprocess.run(['ffmpeg', '-v', 'info', '-i', str(path), '-af', filters + ',volumedetect', '-f', 'null', '-'],
                         capture_output=True, text=True).stderr
    found = re.search(r'max_volume: (-?[\d.]+) dB', out)
    if found is None or float(found.group(1)) < silent:
        raise SilentTake(path.name)
    return -1.0 - float(found.group(1))


class SilentTake(Exception):
    """A take with nothing (or next to nothing) in it: pay for another with --redo."""


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def silences(path, noise, length):
    out = subprocess.run(['ffmpeg', '-v', 'info', '-i', str(path), '-af', f'silencedetect=noise={noise}dB:d={length}',
                          '-f', 'null', '-'], capture_output=True, text=True).stderr
    starts = [float(x) for x in re.findall(r'silence_start: (-?[\d.]+)', out)]
    ends = [float(x) for x in re.findall(r'silence_end: ([\d.]+)', out)]
    return starts, ends


def sounds_in(path, count):
    """The (start, end) of each separate sound in a take, trying quieter and louder silences
    until there are count of them (or keeping the count longest)."""
    total = seconds_of(path)
    best = []
    for noise, length in SLICE_SILENCE:
        starts, ends = silences(path, noise, length)
        # The sounds are what lies between the silences.
        edges = [0.0] + [e for e in ends]
        cuts = [s for s in starts] + [total]
        if starts and starts[0] <= 0.01:
            cuts = cuts[1:]
        spans = [(a, b) for a, b in zip(edges, cuts) if b - a > 0.06]
        if len(spans) == count:
            return spans
        if len(spans) > len(best):
            best = spans
    if not best:
        return [(0.0, total)]
    if len(best) < count:
        print(f'  {path.name}: only {len(best)} separate sounds (wanted {count}); keeping those')
    return sorted(sorted(best, key=lambda s: s[1] - s[0], reverse=True)[:count])


def make_effect(name, cue, take, bank=''):
    out_dir = APP_SOUNDS
    out_dir.mkdir(parents=True, exist_ok=True)
    made = []
    if cue.get('loop'):
        gain = peak_gain(take, 'anull', SILENT_LOOP)
        out = out_dir / f'{name}.wav'
        ffmpeg('-i', str(take), '-af', f'volume={gain}dB', '-c:a', 'pcm_s16le', str(out))
        made.append(out)
    else:
        trim = ('silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.01,areverse,'
                'silenceremove=start_periods=1:start_threshold=-60dB:start_silence=0.05,'
                'afade=t=in:d=0.03,areverse,afade=t=in:d=0.004')
        spans = sounds_in(take, cue['slices']) if cue.get('slices') else [None]
        for i, span in enumerate(spans):
            out = out_dir / (f'{name}_{len(made) + 1}.wav' if span else f'{name}.wav')
            cut = ['-ss', f'{max(0.0, span[0] - 0.01):.3f}', '-to', f'{span[1] + 0.03:.3f}'] if span else []
            gain = peak_gain(take, trim) if not span else None
            if span:
                # Level each slice on its own.
                tmp = out_dir / f'.{name}_{i}.wav'
                ffmpeg(*cut, '-i', str(take), '-ac', '1', str(tmp))
                try:
                    gain = peak_gain(tmp, trim)
                except SilentTake:
                    # A slice that's only a faint scrape: leave it out.
                    tmp.unlink()
                    continue
                ffmpeg('-i', str(tmp), '-af', f'{trim},volume={gain}dB', '-ac', '1', '-c:a', 'pcm_s16le',
                       str(out))
                tmp.unlink()
            else:
                ffmpeg('-i', str(take), '-af', f'{trim},volume={gain}dB', '-ac', '1', '-c:a', 'pcm_s16le',
                       str(out))
            made.append(out)
    return made


def levels(path):
    """The take's loudness (RMS, dB) second by second."""
    out = subprocess.run(['ffmpeg', '-v', 'info', '-i', str(path), '-af',
                          'asetnsamples=44100,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level',
                          '-f', 'null', '-'], capture_output=True, text=True).stderr
    return [float(x) if x not in ('-inf', 'inf') else -120.0 for x in re.findall(r'RMS_level=(-?[\d.]+|-inf)', out)]


def loop_end(path):
    """Where a track's closing fade begins: just after the last second that's still within
    FADE_DROP dB of its typical loudness. Looping from there skips the fade's near-silence."""
    rms = levels(path)
    typical = sorted(rms)[len(rms) // 2]
    last = max(i for i, level in enumerate(rms) if level >= typical - FADE_DROP)
    return min(seconds_of(path), last + 1.0)


def make_music(name, track, take):
    out_dir = AUDIO / 'music'
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f'{name}.ogg'
    total = loop_end(take) if track.get('loop') else seconds_of(take)
    gain = peak_gain(take, 'anull')
    if track.get('loop'):
        x = LOOP_FADE
        graph = (f'[0]volume={gain}dB,asplit=3[a][b][c];'
                 f'[a]atrim=0:{x},asetpts=PTS-STARTPTS,afade=t=in:d={x}[head];'
                 f'[b]atrim=start={total - x}:end={total},asetpts=PTS-STARTPTS,afade=t=out:d={x}[tail];'
                 f'[head][tail]amix=inputs=2:normalize=0[mix];'
                 f'[c]atrim=start={x}:end={total - x},asetpts=PTS-STARTPTS[body];'
                 f'[mix][body]concat=n=2:v=0:a=1[out]')
        ffmpeg('-i', str(take), '-filter_complex', graph, '-map', '[out]', '-c:a', 'libvorbis', '-q:a', '5', str(out))
    else:
        ffmpeg('-i', str(take), '-af', f'volume={gain}dB', '-c:a', 'libvorbis', '-q:a', '5', str(out))
    return [out]


def generate(kind, name, entry, theme):
    if kind == 'music':
        prompt = (f"{entry['prompt']}. {theme}. Original instrumental game soundtrack, no vocals with words, no "
                  'artist imitation.' + (' Designed as a seamless repeating game loop.' if entry.get('loop') else ''))
        return prompt, call('/music?output_format=mp3_44100_128', {
            'prompt': prompt, 'music_length_ms': int(entry['seconds'] * 1000), 'model_id': MUSIC_MODEL,
            'force_instrumental': True})
    payload = {'text': entry['prompt'], 'duration_seconds': entry['seconds'], 'prompt_influence': PROMPT_INFLUENCE,
               'model_id': SFX_MODEL}
    if entry.get('loop'):
        payload['loop'] = True
    return entry['prompt'], call('/sound-generation?output_format=mp3_44100_128', payload)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('names', nargs='*')
    parser.add_argument('--music', action='store_true', help='the music instead of the effects')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--redo', action='store_true', help='pay for a new take of the cues named')
    parser.add_argument('--bank', default='', help="a later act's own effects: sounds<N>.json into sfx<N>")
    args = parser.parse_args()
    kind = ('music' if args.music else 'sfx') + args.bank
    entries = json.loads((AUDIO / ('music.json' if args.music else f'sounds{args.bank}.json')).read_text())
    theme = entries.get('theme', '')
    entries = {k: v for k, v in entries.items() if isinstance(v, dict)}
    unknown = [n for n in args.names if n not in entries]
    if unknown:
        sys.exit(f'unknown: {", ".join(unknown)}')
    names = args.names or list(entries)
    report = json.loads(REPORT.read_text()) if REPORT.exists() else {}
    TAKES.mkdir(parents=True, exist_ok=True)
    rate = MUSIC_RATE if args.music else SFX_RATE
    to_pay = [n for n in names if args.redo or not (TAKES / f'{kind}-{n}.mp3').exists()]
    print(f'{len(to_pay)} to generate, about {sum(entries[n]["seconds"] for n in to_pay) * rate:.0f} credits')
    if args.dry_run:
        return
    for name in names:
        entry = entries[name]
        take = TAKES / f'{kind}-{name}.mp3'
        record = report.get(f'{kind}/{name}', {})
        if name in to_pay:
            before = credits_used()
            prompt, audio = generate(kind, name, entry, theme)
            take.write_bytes(audio)
            spent = credits_used() - before
            record = {'kind': kind, 'model': MUSIC_MODEL if args.music else SFX_MODEL, 'prompt': prompt,
                      'seconds_asked': entry['seconds'], 'take': str(take.relative_to(ROOT)), 'credits': spent}
            print(f'  {name}: {spent} credits')
        try:
            made = make_music(name, entry, take) if args.music else make_effect(name, entry, take, args.bank)
        except SilentTake:
            print(f'{name}: the take is silent; make another with  --redo {name}')
            continue
        record['take_sha256'] = sha(take)
        record['files'] = [{'path': str(p.relative_to(ROOT)), 'seconds': round(seconds_of(p), 3), 'sha256': sha(p)}
                           for p in made]
        report[f'{kind}/{name}'] = record
        REPORT.write_text(json.dumps(report, indent=2) + '\n')
        print(f'{name}: ' + ', '.join(f'{f["path"].split("/")[-1]} {f["seconds"]}s' for f in record['files']))


if __name__ == '__main__':
    main()
