#!/usr/bin/env python3
"""
D13 contrast table (Drone Sight), the build.md pre-QA gate: every text colour x background pair the panels and 3D labels draw.

* Fixed colours (the design palette roles interaction / passable / alert / error, the ink #111418, the brand colours of the
  3D boards and tags) are computed exactly with the WCAG 2.x relative-luminance formula.
* The SpatialUI vibrant roles (labelPrimary, fillSecondary, fillTertiary, the Regular / Thick glass) have no fixed sRGB
  value: the app's `PALETTE` log line prints them as #FF000000 because the glass vibrancy draws them. They are measured on
  emulator screenshots instead: in a box around one piece of text, the background is the median colour of the pixels in
  the background side of the median, the text colour is the median of the stroke cores (stroke pixels = past the midpoint
  between the background and the most extreme pixel; cores = the 10 % of them nearest the extreme, so anti-aliased edges
  do not count and the result does not depend on how much empty background the box holds).

Usage: python3 tools/contrast.py SHOT_DIR [--md OUT.md]   exit 1 when any pair is below its threshold.
Needs Pillow (python3 without -I picks up the user site-packages).
"""
import sys

from PIL import Image


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = rgb[:3]
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexrgb(h):
    h = h.lstrip("#")
    if len(h) == 8:  # AARRGGBB
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def over(fg_argb, bg):
    """Composite an #AARRGGBB colour over an opaque rgb."""
    h = fg_argb.lstrip("#")
    a = int(h[0:2], 16) / 255.0
    f = hexrgb(h)
    return tuple(round(a * f[i] + (1 - a) * bg[i]) for i in range(3))


def hx(rgb):
    return "#%02X%02X%02X" % tuple(rgb)


INK = hexrgb("#16181C")  # TdColors.OnFill
FIXED_COLORS = dict(
    interaction="#FFC72C", clutched="#5FD38A", released="#9A9A96", paused="#FFA62B", error="#FF5F52", done="#5FD38A",
    danger="#FF8A80", tag_bg="#1E2329", tag_ink="#E8EDF2", key_fill="#353B43", star="#6B4400", target="#3FD0E0",
)
C = {k: hexrgb(v) for k, v in FIXED_COLORS.items()}

FIXED = [
    ("ink #16181C / interaction #FFC72C", "primary keys, selected options, coach / level pictogram glyphs", INK, C["interaction"], 4.5),
    ("ink #16181C / clutched #5FD38A", "status pill 握住, demo badge, scorecard demo pill", INK, C["clutched"], 4.5),
    ("ink #16181C / released #9A9A96", "status pill 松开, oops pills at 0", INK, C["released"], 4.5),
    ("ink #16181C / paused #FFA62B", "status pill 暂停, oops pills > 0, pause pictogram", INK, C["paused"], 4.5),
    ("ink #16181C / danger #FF8A80", "danger keys 退出应用 / 退出 / 清掉 / confirm 重来 / 回首页 (bold)", INK, C["danger"], 4.5),
    ("tag ink #E8EDF2 / tag slate #1E2329", "S04 banner, live hint box, control chip, tip tag and 平视前方 on the Stage", C["tag_ink"], C["tag_bg"], 4.5),
    ("tag ink #E8EDF2 / key fill #353B43", "normal keys, drill cards, steppers, Mute (v0.1.0 fix)", C["tag_ink"], C["key_fill"], 4.5),
]


def measure(path, box):
    im = Image.open(path).convert("RGB").crop(box)
    px = list(im.getdata())
    ls = sorted((lum(p), p) for p in px)
    n = len(ls)
    med = ls[n // 2][0]
    lo, hi = ls[max(0, int(n * 0.005))][0], ls[min(n - 1, int(n * 0.995))][0]
    dark_text = (med - lo) > (hi - med)
    # background: the pixels on the background side of the median
    bgp = [p for _, p in (ls[int(n * 0.45):int(n * 0.95)] if dark_text else ls[int(n * 0.05):int(n * 0.55)])]
    bg = tuple(sorted(p[i] for p in bgp)[len(bgp) // 2] for i in range(3))
    # text: the stroke pixels, i.e. past the midpoint between the background and the extreme; their median colour
    lb = lum(bg)
    ext = lo if dark_text else hi
    cut = (lb + ext) / 2
    strokes = [p for l, p in ls if (l <= cut if dark_text else l >= cut)]
    strokes = strokes if len(strokes) >= 3 else [p for _, p in (ls[:3] if dark_text else ls[-3:])]
    # stroke cores: the 10 % of stroke pixels nearest the extreme (anti-aliased edges excluded), median colour
    strokes.sort(key=lum, reverse=not dark_text)
    core = strokes[: max(3, len(strokes) // 10)]
    fg = tuple(sorted(p[i] for p in core)[len(core) // 2] for i in range(3))
    return fg, bg


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    shots = sys.argv[1].rstrip("/")
    md_out = sys.argv[sys.argv.index("--md") + 1] if "--md" in sys.argv else None
    from contrast_samples import EXEMPT, SAMPLES  # (pair, where, shot, box, threshold)

    rows = []
    for pair, where, fg, bg, th in FIXED:
        r = ratio(fg, bg)
        rows.append((pair, where, "exact", hx(fg), hx(bg), r, th))
    for pair, where, shot, box, th in SAMPLES:
        fg, bg = measure(f"{shots}/{shot}.png", box)
        rows.append((pair, where, f"{shot}.png {box}", hx(fg), hx(bg), ratio(fg, bg), th))
    out = ["| # | 前景 / 底 | 用在哪 | 取法 | 前景 | 底 | 对比度 | 要求 | 结论 |", "|---|---|---|---|---|---|---|---|---|"]
    bad = 0
    for i, (pair, where, how, f, b, r, th) in enumerate(rows, 1):
        ok = r >= th
        bad += 0 if ok else 1
        out.append(f"| {i} | {pair} | {where} | {how} | {f} | {b} | {r:.2f} : 1 | ≥ {th} | {'过' if ok else '**不过**'} |")
    for where, shot, box in EXEMPT:
        fg, bg = measure(f"{shots}/{shot}.png", box)
        out.append(f"| – | （豁免）{where} | WCAG 1.4.3 不要求 | {shot}.png {box} | {hx(fg)} | {hx(bg)} | {ratio(fg, bg):.2f} : 1 | — | 豁免 |")
    text = "\n".join(out)
    print(text)
    print(f"\n{len(rows)} pairs, {bad} below threshold")
    if md_out:
        open(md_out, "w", encoding="utf-8").write(text + "\n")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
