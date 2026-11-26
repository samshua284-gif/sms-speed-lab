#!/usr/bin/env python3
"""Generate the Tamanina launcher icon (gold tower + signal arcs over dusk dunes).
The keystore is identity, the icon is reproducible: this script is the source."""
from PIL import Image, ImageDraw

S = 432

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# dusk gradient background with rounded corners
top, mid, bot = (22, 41, 77), (16, 30, 57), (19, 15, 28)
bg = Image.new("RGBA", (S, S))
bd = ImageDraw.Draw(bg)
for y in range(S):
    t = y / S
    c = lerp(top, mid, t * 2) if t < 0.5 else lerp(mid, bot, (t - 0.5) * 2)
    bd.line([(0, y), (S, y)], fill=c + (255,))
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=84, fill=255)
img.paste(bg, (0, 0), mask)
d = ImageDraw.Draw(img)

# dunes
import math
def dune(base, amp, color):
    pts = [(x, base + amp * math.sin(x / S * math.pi * 1.3 + base)) for x in range(0, S + 8, 8)]
    d.polygon(pts + [(S, S), (0, S)], fill=color + (255,))
dune(318, 16, (67, 48, 58))
dune(356, 12, (34, 23, 38))

# tower (gold)
gold = (201, 162, 39, 255)
cx = S // 2
d.polygon([(cx - 10, 300), (cx + 10, 300), (cx, 108)], outline=gold, width=10)
d.line([(cx - 44, 168), (cx + 44, 168)], fill=gold, width=10)
d.line([(cx - 32, 224), (cx + 32, 224)], fill=gold, width=10)
d.ellipse([cx - 11, 84, cx + 11, 106], fill=gold)

# signal arcs
for r, w in ((52, 11), (92, 11), (132, 11)):
    d.arc([cx - r, 96 - r, cx + r, 96 + r], start=305, end=595, fill=gold, width=w)

img.save("/tmp/icon-master.png")
dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
import os
for name, px in dens.items():
    out = f"src/res/mipmap-{name}/ic_launcher.png"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(out)
    print(out, px)
print("done")
