#!/usr/bin/env python3
"""Regenerate every wordmark-bearing brand file from wordmark.py.

Usage (from anywhere):
    PLAYWRIGHT_CORE=<path to node_modules/playwright-core> python3 build.py

Writes the 6 SVGs, the Android drawable, the 12 PNGs and 12 JPGs, the site lockups, and the
lockup band of the site banner and the Play feature graphic. See README.md.
Needs: python3 with Pillow and numpy, node, playwright-core, Google Chrome (CHROME env).
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import wordmark  # noqa: E402
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
LOGO = os.path.join(ROOT, "assets", "monostr-logo")
SVG = os.path.join(LOGO, "svg")
DRAWABLE = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable", "ic_wordmark.xml")
SITE = os.path.join(ROOT, "site", "assets")
BANNER = os.path.join(SITE, "monostr-banner.png")
FEATURE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "featureGraphic.png")

BG = {"hell": "#F7F6F2", "dunkel": "#111113"}   # JPG backgrounds (PNGs are transparent)
JPG_PAD = 60                                      # viewBox units of margin on every side in the JPGs
# name: [(png width, png height)], [(jpg width, jpg height)]; sizes as they were before the M
SIZES = {
    "wortmarke": ([(1200, 223), (2400, 446)], [(1200, 475), (2400, 950)]),
    "lockup-horizontal": ([(1200, 185), (2400, 370)], [(1200, 391), (2400, 782)]),
    "lockup-gestapelt": ([(800, 482), (1600, 964)], [(800, 574), (1600, 1147)]),
}
# Banner / feature graphic: the dark horizontal lockup at a fixed scale and top, its viewBox
# centred horizontally (as measured on the originals); rows below `cut` (tagline, orange bar)
# are kept from the existing file.
BANDS = [(BANNER, 1.6119, 150.97, 290), (FEATURE, 1.3608, 165.09, 285)]


def main():
    wordmark.main([SVG, "--drawable", DRAWABLE])
    for svg_variant, site_variant in (("hell", "light"), ("dunkel", "dark")):
        shutil.copyfile(os.path.join(SVG, f"monostr-lockup-gestapelt-fuer-{svg_variant}.svg"),
                        os.path.join(SITE, f"monostr-lockup-{site_variant}.svg"))

    tmp = tempfile.mkdtemp(prefix="monostr-logo-")
    jobs, jpgs = [], []
    for name, (pngs, jpg_sizes) in SIZES.items():
        for variant in ("hell", "dunkel"):
            src = os.path.join(SVG, f"monostr-{name}-fuer-{variant}.svg")
            for w, h in pngs:
                jobs.append({"svg": src, "width": w, "height": h,
                             "out": os.path.join(LOGO, "png", f"monostr-{name}-fuer-{variant}-{w}.png")})
            for w, h in jpg_sizes:
                out = os.path.join(tmp, f"monostr-{name}-fuer-{variant}-{w}.png")
                jobs.append({"svg": src, "width": w, "height": h, "out": out,
                             "background": BG[variant], "pad": JPG_PAD})
                jpgs.append((out, os.path.join(LOGO, "jpg", f"monostr-{name}-fuer-{variant}-{w}.jpg")))
    lockup = os.path.join(SVG, "monostr-lockup-horizontal-fuer-dunkel.svg")
    bands = []
    for target, scale, top, cut in BANDS:
        w, h = Image.open(target).size
        out = os.path.join(tmp, os.path.basename(target))
        jobs.append({"svg": lockup, "out": out, "width": w, "height": h, "background": BG["dunkel"],
                     "scale": scale, "top": top, "left": w / 2 - wordmark.LOCKUP_H_WIDTH * scale / 2})
        bands.append((out, target, cut))

    with open(os.path.join(tmp, "jobs.json"), "w") as f:
        json.dump(jobs, f)
    subprocess.run(["node", os.path.join(HERE, "render.mjs"), os.path.join(tmp, "jobs.json")], check=True)

    for png, jpg in jpgs:
        Image.open(png).convert("RGB").save(jpg, quality=95, subsampling=0)
    for render, target, cut in bands:
        old = np.asarray(Image.open(target).convert("RGB")).copy()
        new = np.asarray(Image.open(render).convert("RGB"))
        old[:cut] = new[:cut]
        Image.fromarray(old).save(target, optimize=True)
    for svg_variant, site_variant in (("hell", "light"), ("dunkel", "dark")):
        shutil.copyfile(os.path.join(LOGO, "png", f"monostr-lockup-gestapelt-fuer-{svg_variant}-1600.png"),
                        os.path.join(SITE, f"monostr-lockup-{site_variant}-1600.png"))
    shutil.rmtree(tmp)


if __name__ == "__main__":
    main()
