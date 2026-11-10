#!/usr/bin/env python3
"""Cuts the generated icon sheets (art/icons) into the app's icons (android/car/assets/icons).

The sheets are white glyphs on black. Each icon becomes a square PNG, white with the glyph as its alpha,
so the app can tint it (Icons.draw): cut from its cell, trimmed to the glyph, padded square, scaled to 128.
Needs only Python 3 and macOS's sips (which reads the sheet and does the scaling).

    python3 tools/cut_icons.py
"""
import os, struct, subprocess, tempfile, zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android/car/assets/icons")
SIZE = 128
# sheet, columns, rows, then the name of each cell in reading order (None: not used)
SHEETS = [
    ("art/icons/sheet-1.png", 4, 3, ["drive", "maps", "media", "dashcam", "more", "track", "sensors", "log",
                                    None, "settings", "home", None]),
    ("art/icons/convoy-1.png", 2, 2, [None, "convoy", None, None]),
]


def read(path):
    """The picture's brightness, as rows of bytes."""
    with tempfile.TemporaryDirectory() as d:
        bmp = os.path.join(d, "x.bmp")
        subprocess.run(["sips", "-s", "format", "bmp", path, "--out", bmp], check=True, capture_output=True)
        b = open(bmp, "rb").read()
    off = struct.unpack("<I", b[10:14])[0]
    w, h = struct.unpack("<ii", b[18:26])
    bpp = struct.unpack("<H", b[28:30])[0] // 8
    row = (w * bpp + 3) // 4 * 4
    rows = []
    for y in range(abs(h)):
        yy = y if h < 0 else abs(h) - 1 - y
        line = b[off + yy * row: off + yy * row + w * bpp]
        rows.append(bytes((line[i] + line[i + 1] + line[i + 2]) // 3 for i in range(0, w * bpp, bpp)))
    return w, abs(h), rows


def write(path, side, alpha):
    """A white square PNG with `alpha` (rows of bytes) as its alpha channel."""
    raw = b"".join(b"\0" + b"".join(b"\xff\xff\xff" + bytes([a]) for a in r) for r in alpha)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", side, side, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    os.makedirs(OUT, exist_ok=True)
    for sheet, cols, nrows, names in SHEETS:
        w, h, rows = read(os.path.join(ROOT, sheet))
        cw, ch = w // cols, h // nrows
        for i, name in enumerate(names):
            if not name:
                continue
            x0, y0 = (i % cols) * cw, (i // cols) * ch
            cell = [r[x0:x0 + cw] for r in rows[y0:y0 + ch]]
            ys = [y for y, r in enumerate(cell) if max(r) > 60]
            xs = [x for x in range(cw) if max(r[x] for r in cell) > 60]
            l, t, r_, b = min(xs), min(ys), max(xs) + 1, max(ys) + 1
            side = max(r_ - l, b - t)
            side += side // 12 * 2                               # a little air round the glyph
            ox, oy = (side - (r_ - l)) // 2, (side - (b - t)) // 2
            alpha = [bytearray(side) for _ in range(side)]
            for y in range(t, b):
                for x in range(l, r_):
                    v = cell[y][x]
                    alpha[y - t + oy][x - l + ox] = 0 if v < 24 else v     # the black is not quite black
            big = os.path.join(OUT, name + ".png")
            write(big, side, alpha)
            subprocess.run(["sips", "-z", str(SIZE), str(SIZE), big], check=True, capture_output=True)
            print(name, "%dx%d" % (r_ - l, b - t))


if __name__ == "__main__":
    main()
