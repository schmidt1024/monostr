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
import coin  # noqa: E402
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
LOGO = os.path.join(ROOT, "assets", "monostr-logo")
SVG = os.path.join(LOGO, "svg")
DRAWABLE = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable", "ic_wordmark.xml")
SITE = os.path.join(ROOT, "site", "assets")
BANNER = os.path.join(SITE, "monostr-banner-3.png")
BANNER_BASE = os.path.join(SITE, "monostr-banner-2.png")   # rows below the cut (claim, orange bar) come from here once
SITE_ROOT = os.path.join(ROOT, "site")
ICON_STORE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "icon.png")
FEATURE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "featureGraphic.png")

BG = {"hell": "#F7F6F2", "dunkel": "#111113"}   # JPG backgrounds (PNGs are transparent)
JPG_PAD = 60                                      # viewBox units of margin on every side in the JPGs
# name: [(png width, png height)], [(jpg width, jpg height)]; sizes as they were before the M
SIZES = {
    "wortmarke": ([(1200, 223), (2400, 446)], [(1200, 475), (2400, 950)]),
    "lockup-horizontal": ([(1200, 185), (2400, 370)], [(1200, 391), (2400, 782)]),
    "lockup-gestapelt": ([(800, 482), (1600, 964)], [(800, 574), (1600, 1147)]),
}
# Banner / feature graphic: rows below `cut` (claim, orange bar) are kept from the existing file; the band above is
# redrawn at the scale measured on the originals, with the baseline where it was (banner 251.2 px, feature 249.67 px).
# Banner: wordmark only (the profile picture already is the coin); feature graphic: coin + wordmark.
# (target, svg name, ink left, ink right, scale, baseline y in px, baseline in the svg, cut)
BANDS = [
    (BANNER, "monostr-wortmarke-fuer-dunkel", wordmark.X0, wordmark.INK_RIGHT, 1.6119, 251.2, wordmark.BASELINE, 290),
    (FEATURE, "monostr-lockup-horizontal-fuer-dunkel", 0, wordmark.LOCKUP_H_WIDTH, 1.3608, 249.67, wordmark.H_BASELINE, 285),
]


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
    if not os.path.exists(BANNER):
        shutil.copyfile(BANNER_BASE, BANNER)
    bands = []
    for target, name, ink_l, ink_r, scale, base_px, base_svg, cut in BANDS:
        w, h = Image.open(target).size
        out = os.path.join(tmp, os.path.basename(target))
        jobs.append({"svg": os.path.join(SVG, f"{name}.svg"), "out": out, "width": w, "height": h, "background": BG["dunkel"],
                     "scale": scale, "top": base_px - base_svg * scale, "left": w / 2 - (ink_l + ink_r) / 2 * scale})
        bands.append((out, target, cut))
    jobs += icon_jobs(tmp)

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
    finish_icons(tmp)
    shutil.rmtree(tmp)


def icon_jobs(tmp):
    """Coin exports: logo PNGs, site icons, profile picture, store icon (spec 2026-11-09 coin logo)."""
    coin.write_svgs()
    small = os.path.join(tmp, "coin-small.svg")   # favicons: dark disc, light coin (coin.favicon_svg)
    with open(small, "w") as f:
        f.write(coin.favicon_svg())
    square = os.path.join(tmp, "coin-square-dark.svg")
    with open(square, "w") as f:
        f.write(coin.app_icon(coin.DARK, coin.WHITE, "quadrat").replace('rx="120"', 'rx="0"'))
    rund_dunkel = os.path.join(SVG, "monostr-app-icon-rund-dunkel.svg")
    avatar_svg = os.path.join(SVG, "monostr-avatar.svg")
    jobs = []
    for name in ("app-icon-rund-hell", "app-icon-rund-dunkel", "app-icon-quadrat-hell", "app-icon-quadrat-dunkel",
                 "icon-fuer-hell", "icon-fuer-dunkel"):
        for px in (512, 1024):
            jobs.append({"svg": os.path.join(SVG, f"monostr-{name}.svg"), "out": os.path.join(LOGO, "png", f"monostr-{name}-{px}.png"),
                         "width": px, "height": px})
    jobs += [
        {"svg": small, "out": os.path.join(SITE_ROOT, "favicon-32.png"), "width": 32, "height": 32},
        {"svg": small, "out": os.path.join(tmp, "ico-48.png"), "width": 48, "height": 48},
        {"svg": square, "out": os.path.join(SITE_ROOT, "apple-touch-icon.png"), "width": 180, "height": 180},
        {"svg": rund_dunkel, "out": os.path.join(SITE_ROOT, "icon-192.png"), "width": 192, "height": 192},
        {"svg": rund_dunkel, "out": os.path.join(SITE_ROOT, "icon-512.png"), "width": 512, "height": 512},
        # round-cropped pictures: the coin nearly fills them (coin.avatar); avatar-3 = new name, clients cache avatar-2
        {"svg": avatar_svg, "out": os.path.join(SITE_ROOT, "monostr-icon-2.png"), "width": 1024, "height": 1024},
        {"svg": avatar_svg, "out": os.path.join(SITE, "monostr-avatar-3.png"), "width": 512, "height": 512},
        {"svg": rund_dunkel, "out": ICON_STORE, "width": 512, "height": 512, "background": coin.DARK},
    ]
    coin.android_exports()
    android = os.path.join(LOGO, "android")
    for name, px in (("ic_launcher_foreground", 432), ("ic_launcher_monochrome", 432)):
        jobs.append({"svg": os.path.join(android, f"{name}.svg"), "out": os.path.join(android, f"{name}-{px}.png"), "width": px, "height": px})
    jobs.append({"svg": os.path.join(android, "play-store-icon.svg"), "out": os.path.join(android, "play-store-icon-512.png"), "width": 512, "height": 512})
    for px in (16, 32, 48):
        jobs.append({"svg": small, "out": os.path.join(LOGO, "favicon", f"favicon-{px}.png"), "width": px, "height": px})
    with open(os.path.join(SITE_ROOT, "favicon.svg"), "w") as f:
        f.write(coin.favicon_svg() + "\n")
    return jobs


def finish_icons(tmp):
    Image.open(os.path.join(tmp, "ico-48.png")).save(os.path.join(SITE_ROOT, "favicon.ico"), sizes=[(16, 16), (32, 32), (48, 48)])
    for path in (os.path.join(SITE_ROOT, "apple-touch-icon.png"), os.path.join(SITE, "monostr-avatar-3.png"), ICON_STORE):
        Image.open(path).convert("RGB").save(path, optimize=True)
    # assets/monostr-logo/favicon keeps a copy of what the site serves
    for name in ("favicon.ico", "favicon.svg", "apple-touch-icon.png", "icon-192.png", "icon-512.png"):
        shutil.copyfile(os.path.join(SITE_ROOT, name), os.path.join(LOGO, "favicon", name))


if __name__ == "__main__":
    main()
