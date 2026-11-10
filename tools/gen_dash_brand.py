#!/usr/bin/env python3
"""Generate the coldshift.racing dash logo variants from the coldshift logo SVG.

The dash version is the coldshift tile with a row of shift lights under it (white, then the
brand red). Writes into brand/ and the Android app's launcher icon resources.

Usage: python3 tools/gen_dash_brand.py      (needs PySide6, for QtSvg)
"""
import os
import sys

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRAND = os.path.join(ROOT, "brand")
RES = os.path.join(ROOT, "android/car/res")
app = QGuiApplication(sys.argv)
svg = QSvgRenderer(os.path.join(BRAND, "coldshift-logo.svg"))

N = 1024                      # master size
RED = QColor("#ff0000")       # the red in the logo itself
WHITE = QColor("#fdfdfd")


def master():
    im = QImage(N, N, QImage.Format_ARGB32)
    im.fill(Qt.transparent)
    p = QPainter(im)
    p.setRenderHints(QPainter.Antialiasing | QPainter.SmoothPixmapTransform)
    # the tile occupies x 70..450, y 64..455 of the 520 square
    scale = N / 520 * 0.86
    tile_w, tile_h = 380 * scale, 391 * scale
    led_w, led_h, gap, count = N * 0.058, N * 0.044, N * 0.015, 9
    pad = led_h * 0.55
    bar_h = led_h + 2 * pad
    total_h = tile_h + N * 0.035 + bar_h
    top = (N - total_h) / 2
    p.save()
    p.translate((N - tile_w) / 2 - 70 * scale, top - 64 * scale)
    p.scale(scale, scale)
    svg.render(p, QRectF(0, 0, 520, 520))
    p.restore()
    # shift lights in a black housing, like the tile
    row_w = count * led_w + (count - 1) * gap
    bar_w = row_w + 2 * pad * 1.6
    bar_top = top + tile_h + N * 0.035
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#000000"))
    p.drawRoundedRect(QRectF((N - bar_w) / 2, bar_top, bar_w, bar_h), bar_h / 2.4, bar_h / 2.4)
    x0 = (N - row_w) / 2
    for i in range(count):
        p.setBrush(WHITE if i < 5 else RED)
        p.drawRoundedRect(QRectF(x0 + i * (led_w + gap), bar_top + pad, led_w, led_h), led_h / 2.6, led_h / 2.6)
    p.end()
    return im


m = master()
os.makedirs(BRAND, exist_ok=True)
m.save(os.path.join(BRAND, "dash-logo-1024.png"))
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for d, px in sizes.items():
    out = os.path.join(RES, f"drawable-{d}")
    os.makedirs(out, exist_ok=True)
    m.scaled(px, px, Qt.IgnoreAspectRatio, Qt.SmoothTransformation).save(os.path.join(out, "ic_launcher.png"))
m.scaled(512, 512, Qt.IgnoreAspectRatio, Qt.SmoothTransformation).save(os.path.join(BRAND, "dash-logo-512.png"))
import shutil
ASSETS = os.path.join(ROOT, "android/car/assets/brand")
os.makedirs(ASSETS, exist_ok=True)
shutil.copy(os.path.join(BRAND, "dash-logo-512.png"), ASSETS)
shutil.copy(os.path.join(BRAND, "tfinn-logo.png"), ASSETS)
print("wrote brand/dash-logo-1024.png, dash-logo-512.png and", len(sizes), "launcher icon sizes")
