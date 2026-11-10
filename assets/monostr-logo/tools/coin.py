#!/usr/bin/env python3
"""The Monostr coin: a thin outer ring, a ring of beads inside it and the Monostr M in the middle.

One geometry for every use (spec docs/superpowers/specs/2026-11-09-coin-logo-design.md). On a 100-unit grid, centre 50,50:

- large (app icon, profile picture, site, stores): ring r 46 stroke 3; 40 beads r 1.5 on r 38, the first at 0°;
  M = polyline 24,72 24,28 50,56 76,28 76,72 scaled 0.74 about the centre, stroke 6, round caps and joins
- small (<= 48 px, the 24 dp tip icon): ring stroke 5; 24 beads r 2.2 on r 38; M stroke 7.5, same scale
- filled (a tip given): a disc r 46 in the mark's colour, beads and M in the background colour on top

Writes the coin SVG sources into ../svg (see main). Run from anywhere: python3 coin.py
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "..", "svg")

DARK = "#16161A"
LIGHT = "#F7F6F2"
WHITE = "#F2F1ED"

R_RING = 46
R_BEADS = 38
M_POINTS = [(24, 72), (24, 28), (50, 56), (76, 28), (76, 72)]
M_SCALE = 0.74
LARGE = {"ring": 3, "beads": 40, "bead_r": 1.5, "m": 6}
SMALL = {"ring": 5, "beads": 24, "bead_r": 2.2, "m": 7.5}


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def m_points(scale=M_SCALE):
    return [(50 + (x - 50) * scale, 50 + (y - 50) * scale) for x, y in M_POINTS]


def bead_centres(n):
    return [(50 + R_BEADS * math.cos(2 * math.pi * i / n), 50 + R_BEADS * math.sin(2 * math.pi * i / n)) for i in range(n)]


def coin_body(color, bg, small=False, filled=False):
    """The coin's SVG elements on the 100 grid. Outline: ring, beads and M in `color`. Filled: disc in `color`,
    beads and M in `bg` on top."""
    g = SMALL if small else LARGE
    ink = bg if filled else color
    parts = []
    if filled:
        parts.append(f'<circle cx="50" cy="50" r="{R_RING + g["ring"] / 2}" fill="{color}"/>')
    else:
        parts.append(f'<circle cx="50" cy="50" r="{R_RING}" fill="none" stroke="{color}" stroke-width="{g["ring"]}"/>')
    for x, y in bead_centres(g["beads"]):
        parts.append(f'<circle cx="{fmt(x)}" cy="{fmt(y)}" r="{g["bead_r"]}" fill="{ink}"/>')
    pts = " ".join(f"{fmt(x)},{fmt(y)}" for x, y in m_points())
    parts.append(f'<polyline points="{pts}" fill="none" stroke="{ink}" stroke-width="{g["m"]}" '
                 f'stroke-linecap="round" stroke-linejoin="round"/>')
    return "".join(parts)


def svg100(body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">'
            f'<title>Monostr</title>{body}</svg>')


def app_icon(bg, fg, shape):
    """512 px app icon: the large coin at 3.6x, centred, on a round or rounded-square background."""
    back = (f'<circle cx="256" cy="256" r="256" fill="{bg}"/>' if shape == "rund"
            else f'<rect width="512" height="512" rx="120" fill="{bg}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">'
            f'<title>Monostr</title>{back}<g transform="translate(76,76) scale(3.6)">{coin_body(fg, bg)}</g></svg>')


# --- outline of a stroked polyline (round caps and joins) as one closed path, for even-odd cut-outs ---------------------

def _unit(v):
    l = math.hypot(*v)
    return (v[0] / l, v[1] / l)


def _arc(p, a, b, h, via):
    """SVG arc from a to b around p (radius h), the way that passes direction `via` from p."""
    aa = math.atan2(a[1] - p[1], a[0] - p[0])
    bb = math.atan2(b[1] - p[1], b[0] - p[0])
    vv = math.atan2(via[1], via[0])
    def norm(x):
        while x <= -math.pi: x += 2 * math.pi
        while x > math.pi: x -= 2 * math.pi
        return x
    d_ab = norm(bb - aa)
    d_av = norm(vv - aa)
    # sweep 1 = increasing angle (clockwise on screen); pick the direction that meets `via` before b
    if d_ab >= 0:
        sweep = 1 if 0 <= d_av <= d_ab else 0
    else:
        sweep = 0 if d_ab <= d_av <= 0 else 1
    span = d_ab if sweep == 1 else -d_ab
    if span < 0: span += 2 * math.pi
    large = 1 if span > math.pi else 0
    return f"A{fmt(h)},{fmt(h)} 0 {large} {sweep} {fmt(b[0])},{fmt(b[1])}"


def _side(pts, h):
    """Points and arcs of one side of the outline, walking pts forward, offset to the side of n = (dy, -dx)."""
    out = []
    dirs = [_unit((pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])) for i in range(len(pts) - 1)]
    nrm = [(d[1], -d[0]) for d in dirs]
    start = (pts[0][0] + h * nrm[0][0], pts[0][1] + h * nrm[0][1])
    for i in range(1, len(pts) - 1):
        p, d0, d1, n0, n1 = pts[i], dirs[i - 1], dirs[i], nrm[i - 1], nrm[i]
        a = (p[0] + h * n0[0], p[1] + h * n0[1])
        b = (p[0] + h * n1[0], p[1] + h * n1[1])
        if d1[0] * n0[0] + d1[1] * n0[1] > 0:
            # the path turns toward this side: the offset lines cross, use their intersection
            den = d0[0] * d1[1] - d0[1] * d1[0]
            t = ((b[0] - a[0]) * d1[1] - (b[1] - a[1]) * d1[0]) / den
            out.append(f"L{fmt(a[0] + t * d0[0])},{fmt(a[1] + t * d0[1])}")
        else:
            out.append(f"L{fmt(a[0])},{fmt(a[1])}")
            out.append(_arc(p, a, b, h, (n0[0] + n1[0], n0[1] + n1[1])))
    end = (pts[-1][0] + h * nrm[-1][0], pts[-1][1] + h * nrm[-1][1])
    out.append(f"L{fmt(end[0])},{fmt(end[1])}")
    return start, out, dirs[-1]


def stroke_outline(pts, width):
    """The closed outline of `pts` stroked at `width` with round caps and joins, as SVG path data."""
    h = width / 2
    s1, side1, d_end = _side(pts, h)
    rev = list(reversed(pts))
    s2, side2, d_start = _side(rev, h)
    cmds = [f"M{fmt(s1[0])},{fmt(s1[1])}"] + side1
    cmds.append(_arc(pts[-1], (pts[-1][0] + h * d_end[1], pts[-1][1] - h * d_end[0]), s2, h, d_end))
    cmds += side2
    cmds.append(_arc(rev[-1], (rev[-1][0] + h * d_start[1], rev[-1][1] - h * d_start[0]), s1, h, d_start))
    return " ".join(cmds) + " Z"


def circle_path(cx, cy, r):
    return (f"M{fmt(cx + r)},{fmt(cy)} A{fmt(r)},{fmt(r)} 0 1 1 {fmt(cx - r)},{fmt(cy)} "
            f"A{fmt(r)},{fmt(r)} 0 1 1 {fmt(cx + r)},{fmt(cy)} Z")


def scaled(pts, k):
    return [(x * k, y * k) for x, y in pts]


def filled_path(k=1.0, small=True, beads=True):
    """One even-odd path: the disc, every bead and the M outline (holes), scaled by k from the 100 grid."""
    g = SMALL if small else LARGE
    parts = [circle_path(50 * k, 50 * k, (R_RING + g["ring"] / 2) * k)]
    if beads:
        parts += [circle_path(x * k, y * k, g["bead_r"] * k) for x, y in bead_centres(g["beads"])]
    parts.append(stroke_outline(scaled(m_points(), k), g["m"] * k))
    return " ".join(parts)


KOTLIN = os.path.join(HERE, "..", "..", "..", "android", "app", "src", "main", "kotlin", "com", "monostr", "app", "ui", "theme", "TipCoin.kt")


def kotlin_source():
    """TipCoin.kt: the 24 dp tip icon, outline and filled, from the small geometry (k = 0.24)."""
    k = 0.24
    g = SMALL
    ring = circle_path(50 * k, 50 * k, R_RING * k)
    beads = " ".join(circle_path(x * k, y * k, g["bead_r"] * k) for x, y in bead_centres(g["beads"]))
    m = " ".join(f"{'M' if i == 0 else 'L'}{fmt(x)},{fmt(y)}" for i, (x, y) in enumerate(scaled(m_points(), k)))
    filled = filled_path(k, small=True)
    return f'''package com.monostr.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated by assets/monostr-logo/tools/coin.py --kotlin; edit the geometry there, not here.

/**
 * The Monostr coin as the 24 dp tip icon (spec 2026-11-09 coin logo): outline until a tip is given, then [TipCoinFilled].
 * Both are drawn in one tint, so the filled coin is one even-odd path whose beads and M are holes.
 */
val TipCoinOutline: ImageVector by lazy {{
    ImageVector.Builder(name = "tip_coin", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes("{ring}"), stroke = SolidColor(Color.Black), strokeLineWidth = {fmt(g["ring"] * k)}f)
        .addPath(addPathNodes("{beads}"), fill = SolidColor(Color.Black))
        .addPath(
            addPathNodes("{m}"), stroke = SolidColor(Color.Black), strokeLineWidth = {fmt(g["m"] * k)}f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}}

val TipCoinFilled: ImageVector by lazy {{
    ImageVector.Builder(name = "tip_coin_filled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes("{filled}"), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()
}}

/** Filled once the note has a tip; a further tip still pending does not turn it back to the outline. */
fun tipCoin(tipped: Boolean): ImageVector = if (tipped) TipCoinFilled else TipCoinOutline
'''


def _vector(w, h, vw, vh, comment, paths, tx=0.0, ty=0.0):
    body = "\n".join(paths)
    if tx or ty:
        body = f'    <group android:translateX="{fmt(tx)}" android:translateY="{fmt(ty)}">\n{body}\n    </group>'
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{w}dp" android:height="{h}dp"\n'
            f'    android:viewportWidth="{fmt(vw)}" android:viewportHeight="{fmt(vh)}">\n'
            f'    <!-- {comment}; generated by assets/monostr-logo/tools/coin.py, option drawables -->\n{body}\n</vector>\n')


def _outline_paths(color, k, ox, oy, g):
    def sh(pts):
        return [(ox + x * k, oy + y * k) for x, y in pts]
    ring = circle_path(ox + 50 * k, oy + 50 * k, R_RING * k)
    beads = " ".join(circle_path(x, y, g["bead_r"] * k) for x, y in sh(bead_centres(g["beads"])))
    m = " ".join(f"{'M' if i == 0 else 'L'}{fmt(x)},{fmt(y)}" for i, (x, y) in enumerate(sh(m_points())))
    return [
        f'    <path android:pathData="{ring}" android:strokeColor="{color}" android:strokeWidth="{fmt(g["ring"] * k)}" android:fillColor="#00000000" />',
        f'    <path android:pathData="{beads}" android:fillColor="{color}" />',
        f'    <path android:pathData="{m}" android:strokeColor="{color}" android:strokeWidth="{fmt(g["m"] * k)}" '
        f'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />',
    ]


def drawables(res):
    """Launcher foreground and monochrome (coin 60 dp wide in the 108 dp canvas, inside the 66 dp safe zone), the
    status-bar icon (disc with the M cut out, beads dropped) and the logo mark (coin cropped to its edge, tinted by
    the caller)."""
    k = 60 / 95  # the coin with its ring stroke spans 95 units
    o = 54 - 50 * k
    write(os.path.join(res, "ic_launcher_foreground.xml"),
          _vector(108, 108, 108, 108, "Monostr coin, light on the dark launcher background (owner prefers dark)", _outline_paths(WHITE, k, o, o, LARGE)).rstrip("\n"))
    write(os.path.join(res, "ic_launcher_monochrome.xml"),
          _vector(108, 108, 108, 108, "Monostr coin for themed icons", _outline_paths("#FFFFFF", k, o, o, LARGE)).rstrip("\n"))
    kn = 0.24
    note = f'    <path android:pathData="{filled_path(kn, small=True, beads=False)}" android:fillType="evenOdd" android:fillColor="#FFFFFF" />'
    write(os.path.join(res, "ic_notification.xml"),
          _vector(24, 24, 24, 24, "status-bar icon: the coin as a white disc with the M cut out, as Android requires one colour", [note]).rstrip("\n"))
    write(os.path.join(res, "ic_logo_mark.xml"),
          _vector(32, 32, 95, 95, "Monostr coin cropped to its ring's outer edge, white, tinted by the caller",
                  _outline_paths("#FFFFFF", 1.0, 0, 0, LARGE), tx=-2.5, ty=-2.5).rstrip("\n"))


ANDROID = os.path.join(HERE, "..", "android")


def android_exports(out=ANDROID):
    """SVG twins of the launcher layers (108 grid) and the 512 px Play-store icon source; build.py rasterises them."""
    k = 60 / 95
    o = 54 - 50 * k
    def layer(color):
        body = coin_body(color, DARK)
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108"><title>Monostr</title>'
                f'<g transform="translate({fmt(o)},{fmt(o)}) scale({fmt(k)})">{body}</g></svg>')
    write(os.path.join(out, "ic_launcher_foreground.svg"), layer(WHITE))
    write(os.path.join(out, "ic_launcher_monochrome.svg"), layer("#FFFFFF"))
    # store icon: dark like fastlane images/icon.png (owner 2026-11-09)
    write(os.path.join(out, "play-store-icon.svg"), app_icon(DARK, WHITE, "quadrat").replace('rx="120"', 'rx="0"'))


AVATAR_FILL = 0.92   # profile picture: the coin's ring 4 % inside the round crop (owner 2026-11-10)


def avatar(bg=DARK, fg=WHITE, size=512):
    """Round profile picture / handler icon: the coin nearly filling a square that clients crop to a circle."""
    s = AVATAR_FILL * size / 95
    t = size / 2 - 50 * s
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size}" height="{size}">'
            f'<title>Monostr</title><rect width="{size}" height="{size}" fill="{bg}"/>'
            f'<g transform="translate({fmt(t)},{fmt(t)}) scale({fmt(s)})">{coin_body(fg, bg)}</g></svg>')


def favicon_svg():
    """Browser tab icon: a dark disc with the light small coin, readable on light and dark tab strips alike (a
    transparent mark that follows prefers-color-scheme fails when the browser theme differs from the system)."""
    s = 0.86 * 100 / 95
    t = 50 - 50 * s
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><title>Monostr</title>'
            f'<circle cx="50" cy="50" r="50" fill="{DARK}"/>'
            f'<g transform="translate({fmt(t)},{fmt(t)}) scale({fmt(s)})">{coin_body(WHITE, DARK, small=True)}</g></svg>')


def write(path, text):
    with open(path, "w") as f:
        f.write(text + "\n")


def write_svgs(out=SVG):
    # «für hell» = dark mark for light backgrounds, «für dunkel» = light mark for dark backgrounds (as the wordmark files)
    for variant, color, bg in (("hell", DARK, LIGHT), ("dunkel", WHITE, DARK)):
        write(os.path.join(out, f"monostr-coin-fuer-{variant}.svg"), svg100(coin_body(color, bg)))
        write(os.path.join(out, f"monostr-icon-fuer-{variant}.svg"), svg100(coin_body(color, bg)))
    for shape in ("rund", "quadrat"):
        write(os.path.join(out, f"monostr-app-icon-{shape}-hell.svg"), app_icon(LIGHT, DARK, shape))
        write(os.path.join(out, f"monostr-app-icon-{shape}-dunkel.svg"), app_icon(DARK, WHITE, shape))
    write(os.path.join(out, "monostr-avatar.svg"), avatar())


def main(argv):
    if "--drawables" in argv:
        drawables(argv[argv.index("--drawables") + 1])
        return
    if "--kotlin" in argv:
        with open(KOTLIN, "w") as f:
            f.write(kotlin_source())
        return
    write_svgs()
    android_exports()


if __name__ == "__main__":
    main(sys.argv[1:])
