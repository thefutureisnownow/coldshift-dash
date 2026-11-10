#!/usr/bin/env python3
"""Generate images through OpenRouter's Image API and record how they were made.

Reads the API key from the OPENROUTER_API_KEY environment variable (never
pass it on the command line or write it to a file in the repo). Writes each
image as <out-dir>/<name>-<n>.png and a record <out-dir>/<name>.json with the
model, parameters, full prompt, reference hashes and output hashes, so the
result can be traced and reproduced.

Example: the app's icon sheet (cut into icons by tools/cut_icons.py).
  python3 tools/generate_image.py --name sheet --prompt-file art/icons/prompts/sheet.txt \
      --aspect-ratio 1:1 --quality low --background opaque --out-dir art/icons
"""
import argparse
import base64
import datetime
import hashlib
import json
import mimetypes
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://openrouter.ai/api/v1/images"
DEFAULT_MODEL = "openai/gpt-image-2.5-flare"


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def data_url(path):
    kind = mimetypes.guess_type(path)[0] or "image/png"
    with open(path, "rb") as f:
        return "data:%s;base64,%s" % (kind, base64.b64encode(f.read()).decode())


def image_size(path):
    # sips ships with macOS; fall back to reading the PNG header elsewhere.
    try:
        out = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", path], capture_output=True, text=True, check=True).stdout
        # Only the property lines: the first line is the file's path, which may say "pixel" too.
        values = [int(line.split(":")[1]) for line in out.splitlines() if line.strip().startswith(("pixelWidth:", "pixelHeight:"))]
        return values[0], values[1]
    except (OSError, subprocess.CalledProcessError):
        with open(path, "rb") as f:
            header = f.read(24)
        return int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True, help="output file stem")
    prompt = parser.add_mutually_exclusive_group(required=True)
    prompt.add_argument("--prompt")
    prompt.add_argument("--prompt-file")
    parser.add_argument("--reference", action="append", default=[], help="reference image path (repeatable)")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--aspect-ratio", default="16:9")
    parser.add_argument("--quality", default="high")
    parser.add_argument("--background", help="auto, transparent or opaque")
    parser.add_argument("--n", type=int, default=1, help="variants to generate")
    parser.add_argument("--resize", help="WxH to scale each output to (macOS sips), e.g. 1672x941")
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        sys.exit("OPENROUTER_API_KEY is not set")
    text = args.prompt if args.prompt is not None else open(args.prompt_file, encoding="utf-8").read().strip()
    payload = {"model": args.model, "prompt": text, "aspect_ratio": args.aspect_ratio, "quality": args.quality, "n": args.n}
    if args.background:
        payload["background"] = args.background
    if args.reference:
        payload["input_references"] = [{"type": "image_url", "image_url": {"url": data_url(p)}} for p in args.reference]

    request = urllib.request.Request(API, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    started = datetime.datetime.now(datetime.timezone.utc)
    try:
        with urllib.request.urlopen(request, timeout=900) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit("OpenRouter error %d: %s" % (error.code, error.read().decode(errors="replace")[:2000]))
    seconds = (datetime.datetime.now(datetime.timezone.utc) - started).total_seconds()

    os.makedirs(args.out_dir, exist_ok=True)
    outputs = []
    for index, image in enumerate(result.get("data", []), 1):
        path = os.path.join(args.out_dir, "%s-%d.png" % (args.name, index))
        if image.get("b64_json"):
            with open(path, "wb") as f:
                f.write(base64.b64decode(image["b64_json"]))
        elif image.get("url"):
            urllib.request.urlretrieve(image["url"], path)
        else:
            continue
        generated = image_size(path)
        if args.resize:
            width, height = args.resize.lower().split("x")
            subprocess.run(["sips", "-z", height, width, path], capture_output=True, check=True)
        outputs.append({"path": path, "generated_size": list(generated), "size": list(image_size(path)), "sha256": sha256(path)})
    if not outputs:
        sys.exit("No images returned: %s" % json.dumps(result)[:2000])

    record = {
        "created": started.isoformat(timespec="seconds"),
        "api": API,
        "model": args.model,
        "parameters": {k: v for k, v in payload.items() if k not in ("model", "prompt", "input_references")},
        "prompt": text,
        "references": [{"path": p, "sha256": sha256(p)} for p in args.reference],
        "resize": args.resize,
        "outputs": outputs,
        "seconds": round(seconds, 1),
        "usage": result.get("usage"),
    }
    with open(os.path.join(args.out_dir, args.name + ".json"), "w", encoding="utf-8") as f:
        json.dump(record, f, indent=2)
        f.write("\n")
    for output in outputs:
        print("%s %dx%d (generated %dx%d)" % (output["path"], *output["size"], *output["generated_size"]))
    if result.get("usage"):
        print("usage:", json.dumps(result["usage"]))


if __name__ == "__main__":
    main()
