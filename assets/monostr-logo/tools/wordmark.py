#!/usr/bin/env python3
"""Monostr wordmark generator: a capital M plus the outlined «onostr».

Usage:
    python3 wordmark.py <svg-out-dir> [--drawable <ic_wordmark.xml>]

Writes into <svg-out-dir>:
    monostr-wortmarke-fuer-{hell,dunkel}.svg
    monostr-lockup-horizontal-fuer-{hell,dunkel}.svg
    monostr-lockup-gestapelt-fuer-{hell,dunkel}.svg
and, with --drawable, the Android vector drawable of the wordmark.

The M is variant B of the 2026-11-07 rename (spec 2026-11-07-capital-name-design.md,
section 4): straight legs with the stem width of the other glyphs, flat ends, cap height
62, the middle vertex at 0.636 of the cap height down from the top like the app icon's M.
It is computed as the outline of a mitred polyline stroke clipped to the cap band, so it is
one filled polygon (no stroke, no clip-path) and renders the same everywhere.
«onostr» is the drawing the wordmark had before, kept verbatim below (y = 0 is the
baseline, y grows downwards) and only moved left by KERN_SHIFT.
"""
import math
import os
import coin
import re
import sys

S = 11.676            # stem width of the existing glyphs
CAP = 62.0            # cap height of the M
VERTEX = 0.636        # centre-line vertex, as a fraction of CAP down from the top (app icon M)
X0 = 6.636            # left edge of the M (= left edge of the old lowercase m)
M_WIDTH = 60.0        # outer width of the M
OLD_M_RIGHT = 76.272  # right edge of the old lowercase m
KERN_EXTRA = 1.5      # M-o gap is this much wider than the old m-o gap (variant B preview)
KERN_SHIFT = (X0 + M_WIDTH) - OLD_M_RIGHT + KERN_EXTRA   # = -8.136, «onostr» moves left

VB_HEIGHT = 64.16     # wordmark viewBox height (unchanged)
BASELINE = 62.16      # baseline inside that viewBox (unchanged)
OLD_ADVANCE = 345.32  # old wordmark viewBox width
ADVANCE = OLD_ADVANCE + KERN_SHIFT
INK_RIGHT = 344.148 + KERN_SHIFT   # right edge of the r
# Lockups with the coin (coin.py). The coin with its ring stroke spans 95 units on its 100 grid.
CAP = 62.0                 # cap height of the wordmark
COIN_H = 1.5 * CAP         # horizontal lockup: coin diameter 1.5x the cap height
COIN_H_SCALE = COIN_H / 95
COIN_GAP = 0.5 * CAP       # space between coin and the M
H_WORD_TX = COIN_H + COIN_GAP - X0           # wordmark origin so its M starts COIN_GAP after the coin
H_BASELINE = COIN_H / 2 + CAP / 2            # cap band centred on the coin
LOCKUP_H_WIDTH = H_WORD_TX + INK_RIGHT       # crop at the r's right edge
LOCKUP_H_HEIGHT = COIN_H
COIN_V = 96.0              # stacked lockup: coin diameter
V_GAP = 34.0               # coin bottom to cap top (as before between the M and the wordmark)

INK = {"hell": "#16161A", "dunkel": "#F2F1ED"}
TITLE = "Monostr"


def coin_symbol(c, scale, tx, ty):
    """The coin (outline, large geometry) in colour c, its 95-unit ink box placed at tx,ty and scaled."""
    return (f'<g transform="translate({fmt(tx - 2.5 * scale)},{fmt(ty - 2.5 * scale)}) scale({fmt(scale)})">'
            f'{coin.coin_body(c, "none")}</g>')

ONOSTR = (
    "M106.932 1.596Q100.884 1.596 96.26400000000001 -0.3360000000000001Q91.644 -2.2680000000000002 88.452 -5.586Q85.26 -8.904 83.622 -13.104000000000001Q81.984 -17.304000000000002 81.984 -21.84V-23.604000000000003Q81.984 -28.308000000000003 83.70599999999999 -32.550000000000004Q85.428 -36.792 88.662 -40.11Q91.896 -43.428000000000004 96.51599999999999 -45.318000000000005Q101.136 -47.208000000000006 106.932 -47.208000000000006Q112.72800000000001 -47.208000000000006 117.34800000000001 -45.318000000000005Q121.968 -43.428000000000004 125.202 -40.11Q128.436 -36.792 130.11599999999999 -32.550000000000004Q131.796 -28.308000000000003 131.796 -23.604000000000003V-21.84Q131.796 -17.304000000000002 130.15800000000002 -13.104000000000001Q128.52 -8.904 125.328 -5.586Q122.136 -2.2680000000000002 117.51599999999999 -0.3360000000000001Q112.896 1.596 106.932 1.596Z"
    "M106.932 -8.4Q111.21600000000001 -8.4 114.156 -10.290000000000001Q117.096 -12.180000000000001 118.608 -15.414000000000001Q120.12 -18.648 120.12 -22.764000000000003Q120.12 -26.964000000000002 118.566 -30.198Q117.012 -33.432 114.03 -35.322Q111.048 -37.212 106.932 -37.212Q102.816 -37.212 99.834 -35.322Q96.852 -33.432 95.256 -30.198Q93.66 -26.964000000000002 93.66 -22.764000000000003Q93.66 -18.648 95.214 -15.414000000000001Q96.768 -12.180000000000001 99.708 -10.290000000000001Q102.648 -8.4 106.932 -8.4Z"
    "M138.264 0.0V-45.612H147.50400000000002V-34.356Q147.924 -36.120000000000005 148.512 -37.716Q150.36 -42.336000000000006 154.014 -44.688Q157.668 -47.040000000000006 163.12800000000001 -47.040000000000006H163.632Q171.78000000000003 -47.040000000000006 175.98000000000002 -41.790000000000006Q180.18 -36.54 180.18 -26.124000000000002V0.0H168.50400000000002V-27.132Q168.50400000000002 -31.332 166.11 -33.936Q163.716 -36.54 159.51600000000002 -36.54Q155.23200000000003 -36.54 152.586 -33.894Q149.94000000000003 -31.248 149.94000000000003 -26.796000000000003V0.0Z"
    "M210.84 1.596Q204.792 1.596 200.172 -0.3360000000000001Q195.552 -2.2680000000000002 192.36 -5.586Q189.168 -8.904 187.53 -13.104000000000001Q185.892 -17.304000000000002 185.892 -21.84V-23.604000000000003Q185.892 -28.308000000000003 187.614 -32.550000000000004Q189.336 -36.792 192.57 -40.11Q195.804 -43.428000000000004 200.424 -45.318000000000005Q205.044 -47.208000000000006 210.84 -47.208000000000006Q216.636 -47.208000000000006 221.256 -45.318000000000005Q225.876 -43.428000000000004 229.11 -40.11Q232.344 -36.792 234.024 -32.550000000000004Q235.704 -28.308000000000003 235.704 -23.604000000000003V-21.84Q235.704 -17.304000000000002 234.066 -13.104000000000001Q232.428 -8.904 229.236 -5.586Q226.044 -2.2680000000000002 221.424 -0.3360000000000001Q216.804 1.596 210.84 1.596Z"
    "M210.84 -8.4Q215.124 -8.4 218.06400000000002 -10.290000000000001Q221.00400000000002 -12.180000000000001 222.51600000000002 -15.414000000000001Q224.02800000000002 -18.648 224.02800000000002 -22.764000000000003Q224.02800000000002 -26.964000000000002 222.47400000000002 -30.198Q220.92000000000002 -33.432 217.93800000000002 -35.322Q214.95600000000002 -37.212 210.84 -37.212Q206.724 -37.212 203.742 -35.322Q200.76 -33.432 199.164 -30.198Q197.568 -26.964000000000002 197.568 -22.764000000000003Q197.568 -18.648 199.122 -15.414000000000001Q200.676 -12.180000000000001 203.61599999999999 -10.290000000000001Q206.556 -8.4 210.84 -8.4Z"
    "M258.72 1.4280000000000002Q249.312 1.4280000000000002 243.978 -2.5620000000000003Q238.644 -6.5520000000000005 238.392 -13.608H248.892Q249.144 -11.256 251.496 -9.324Q253.848 -7.392 258.888 -7.392Q263.088 -7.392 265.56600000000003 -8.904Q268.044 -10.416 268.044 -13.020000000000001Q268.044 -15.288 266.154 -16.632Q264.264 -17.976000000000003 259.728 -18.396L255.696 -18.816000000000003Q248.052 -19.656000000000002 243.93599999999998 -23.184000000000005Q239.82 -26.712000000000003 239.82 -32.592Q239.82 -37.296 242.172 -40.488Q244.524 -43.68 248.68200000000002 -45.318Q252.84 -46.956 258.216 -46.956Q266.616 -46.956 271.82399999999996 -43.260000000000005Q277.032 -39.564 277.284 -32.424H266.784Q266.616 -34.776 264.432 -36.456Q262.248 -38.136 258.132 -38.136Q254.436 -38.136 252.42000000000002 -36.708Q250.404 -35.28 250.404 -33.012Q250.404 -30.828000000000003 252.042 -29.652Q253.68 -28.476000000000003 257.376 -28.056L261.408 -27.636000000000003Q269.472 -26.796000000000003 274.04999999999995 -23.184000000000005Q278.628 -19.572000000000003 278.628 -13.440000000000001Q278.628 -8.904 276.15 -5.586Q273.672 -2.2680000000000002 269.22 -0.42000000000000004Q264.76800000000003 1.4280000000000002 258.72 1.4280000000000002Z"
    "M304.332 0.5880000000000001Q298.03200000000004 0.5880000000000001 293.958 -1.05Q289.884 -2.688 287.86800000000005 -6.594Q285.85200000000003 -10.5 285.85200000000003 -17.136000000000003V-37.044000000000004H278.628V-45.612H285.93600000000004V-58.464000000000006H296.856V-45.612H310.884V-37.044000000000004H296.772V-16.380000000000003Q296.772 -13.020000000000001 298.578 -11.214000000000002Q300.384 -9.408000000000001 303.744 -9.408000000000001H310.884V0.5880000000000001Z"
    "M316.932 0.0V-45.612H326.17199999999997V-30.828000000000003Q326.928 -37.38 330.12 -41.160000000000004Q334.32 -46.2 342.468 -46.2H344.148V-36.036H340.956Q335.076 -36.036 331.842 -32.886Q328.608 -29.736 328.608 -23.772000000000002V0.0Z"
)


def _line_at_y(p, u, y):
    t = (y - p[1]) / u[1]
    return (p[0] + u[0] * t, y)


def _line_at_x(p, u, x):
    t = (x - p[0]) / u[0]
    return (x, p[1] + u[1] * t)


def m_outline():
    """The M as a list of (x, y) points, baseline y = 0, y downwards (SVG)."""
    a = X0 + S / 2                     # centre line of the left leg
    mid = X0 + M_WIDTH / 2
    top = -CAP
    vy = top + VERTEX * CAP            # centre-line vertex
    dx, dy = mid - a, vy - top
    ln = math.hypot(dx, dy)
    u = (dx / ln, dy / ln)             # down the left diagonal
    n = (u[1], -u[0])                  # normal pointing up-right
    h = S / 2
    upper = (a + n[0] * h, top + n[1] * h)
    lower = (a - n[0] * h, top - n[1] * h)
    top_join = _line_at_y(upper, u, top)            # miter tip clipped at the cap line
    apex_in = _line_at_x(upper, u, mid)             # inner (upper) vertex
    apex_out = _line_at_x(lower, u, mid)            # outer (lower) vertex, a sharp miter
    corner_in = _line_at_x(lower, u, X0 + S)        # where the diagonal meets the leg inside
    left = [(X0, 0.0), (X0, top), top_join]
    inner_left = [corner_in, (X0 + S, 0.0)]
    mirror = lambda p: (2 * mid - p[0], p[1])
    right = [mirror(p) for p in reversed(left)]
    inner_right = [mirror(p) for p in reversed(inner_left)]
    return left + [apex_in] + right + inner_right + [apex_out] + inner_left


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


_TOKEN = re.compile(r"[MLHVQZ]|-?\d+(?:\.\d+)?(?:e-?\d+)?")
_ARITY = {"M": 2, "L": 2, "Q": 4, "H": 1, "V": 1, "Z": 0}


def transform_path(d, sx=1.0, tx=0.0, ty=0.0):
    """Absolute M/L/Q/H/V/Z path data through x' = sx*x + tx, y' = sx*y + ty."""
    out, cmd, nums = [], None, []
    def flush():
        if cmd is None:
            return
        k = _ARITY[cmd]
        if k == 0:
            out.append("Z")
            return
        for i in range(0, len(nums), k):
            vals = nums[i:i + k]
            if cmd == "H":
                vals = [sx * vals[0] + tx]
            elif cmd == "V":
                vals = [sx * vals[0] + ty]
            else:
                vals = [sx * v + (tx if j % 2 == 0 else ty) for j, v in enumerate(vals)]
            out.append(cmd + " ".join(fmt(v) for v in vals))
    for tok in _TOKEN.findall(d):
        if tok.isalpha():
            flush()
            cmd, nums = tok, []
        else:
            nums.append(float(tok))
    flush()
    return "".join(out)


def wordmark_path(sx=1.0, tx=0.0, ty=0.0):
    """M outline + «onostr» as one path, origin on the baseline at x = 0."""
    pts = m_outline()
    m = "M" + "L".join(f"{fmt(sx * x + tx)} {fmt(sx * y + ty)}" for x, y in pts) + "Z"
    rest = transform_path("".join(ONOSTR), sx, sx * KERN_SHIFT + tx, ty)
    return m + rest


def svg(w, h, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">'
            f"<title>{TITLE}</title>{body}</svg>")


def wortmarke(c):
    w = fmt(ADVANCE)
    return svg(w, "64.16", f'<path d="{wordmark_path(ty=BASELINE)}" fill="{c}"/>')


def lockup_horizontal(c):
    # coin left (diameter 1.5x cap), wordmark after a gap of half the cap, cap band centred on the coin
    body = (coin_symbol(c, COIN_H_SCALE, 0, 0)
            + f'<path d="{wordmark_path(tx=H_WORD_TX, ty=H_BASELINE)}" fill="{c}"/>')
    return svg(fmt(LOCKUP_H_WIDTH), fmt(LOCKUP_H_HEIGHT), body)


def lockup_gestapelt(c):
    # wordmark at 6/7, full width; the coin centred above it, V_GAP between coin and cap top
    k = 6 / 7
    width = ADVANCE * k
    baseline = COIN_V + V_GAP + CAP * k
    body = (coin_symbol(c, COIN_V / 95, width / 2 - COIN_V / 2, 0)
            + f'<path d="{wordmark_path(sx=k, ty=baseline)}" fill="{c}"/>')
    return svg(f"{width:.2f}", fmt(baseline + 2), body)


def drawable():
    vw = INK_RIGHT - X0
    width_dp = round(32 * vw / VB_HEIGHT)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width_dp}dp" android:height="32dp"
    android:viewportWidth="{fmt(vw)}" android:viewportHeight="64.16">
    <!-- Monostr wordmark, generated by assets/monostr-logo/tools/wordmark.py; colour comes from the tint; viewport cropped to the glyph edges so the wordmark sits flush left/right -->
    <group android:translateX="-6.636" android:translateY="62.16">
        <path android:fillColor="#000000" android:pathData="{wordmark_path()}"/>
    </group>
</vector>
"""


def main(argv):
    if not argv or argv[0].startswith("-"):
        sys.exit(__doc__)
    out = argv[0]
    os.makedirs(out, exist_ok=True)
    for variant, c in INK.items():
        for name, fn in (("wortmarke", wortmarke), ("lockup-horizontal", lockup_horizontal),
                         ("lockup-gestapelt", lockup_gestapelt)):
            with open(os.path.join(out, f"monostr-{name}-fuer-{variant}.svg"), "w") as f:
                f.write(fn(c))
    if "--drawable" in argv:
        with open(argv[argv.index("--drawable") + 1], "w") as f:
            f.write(drawable())


if __name__ == "__main__":
    main(sys.argv[1:])
