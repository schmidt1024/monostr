"""Collects harmless pictures for measuring the NSFW threshold.

    python fetch_calibration.py <target dir>

Three groups, one sub-directory each:
  photos/      ordinary photographs (picsum.photos, fixed ids)
  borderline/  harmless photographs and art with much skin or bodies
               (Wikimedia Commons categories below)
  graphics/    screenshots, charts, logos, comics from Wikimedia Commons, and
               flat pictures made here (solid colours, gradients, text on dark
               and light ground) - the kind a classifier trained on photographs
               is least sure about

The pictures are not part of the repository (licences differ per file); only
the measured numbers are, in CALIBRATION.md. Nothing here is NSFW by intent,
but Commons categories are edited by the public: look at borderline/ before
trusting a surprising score.
"""
import colorsys
import json
import os
import random
import sys
import time
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw

AGENT = "monostr-media-calibration/1.0 (https://monostr.com)"
PICSUM_IDS = range(0, 260)
PER_CATEGORY = 25
BORDERLINE = [
    "Beach volleyball players",
    "Female swimmers",
    "Male swimmers",
    "Bodybuilders",
    "Sumo wrestlers",
    "Ballet dancers",
    "Babies",
    "Portrait photographs of women",
    "Portrait photographs of men",
    "People at beaches",
    "Ancient Greek sculptures",
    "Hands",
]
GRAPHICS = [
    "Screenshots of free software",
    "Bar charts",
    "SVG logos",
    "Comic strips",
    "Maps of Europe",
    "Text logos",
]


def get(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def save(path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def picsum(target: str) -> None:
    for i in PICSUM_IDS:
        path = os.path.join(target, "photos", f"picsum-{i}.jpg")
        if os.path.exists(path):
            continue
        try:
            save(path, get(f"https://picsum.photos/id/{i}/1280/960"))
        except Exception as e:  # some ids do not exist
            print("skip picsum", i, e)


def commons(target: str, group: str, category: str) -> None:
    query = urllib.parse.urlencode({
        "action": "query", "format": "json", "generator": "categorymembers",
        "gcmtitle": "Category:" + category, "gcmtype": "file", "gcmlimit": str(PER_CATEGORY * 2),
        "prop": "imageinfo", "iiprop": "url|mime", "iiurlwidth": "1024",
    })
    try:
        pages = json.loads(get("https://commons.wikimedia.org/w/api.php?" + query)).get("query", {}).get("pages", {})
    except Exception as e:
        print("skip category", category, e)
        return
    done = 0
    for page in sorted(pages.values(), key=lambda p: p["pageid"]):
        info = (page.get("imageinfo") or [{}])[0]
        url = info.get("thumburl")
        if not url or done >= PER_CATEGORY:
            continue
        ext = ".png" if url.lower().endswith(".png") else ".jpg"
        path = os.path.join(target, group, f"{category.replace(' ', '_')}-{page['pageid']}{ext}")
        if not os.path.exists(path):
            try:
                save(path, get(url))
            except Exception as e:
                print("skip", url, e)
                continue
            time.sleep(1.5)  # Commons answers 429 to anything faster
        done += 1
    print(f"{group}/{category}: {done}")


def synthetic(target: str) -> None:
    rnd = random.Random(1)
    out = os.path.join(target, "graphics")
    os.makedirs(out, exist_ok=True)
    n = 0

    def put(img: Image.Image, name: str) -> None:
        nonlocal n
        img.save(os.path.join(out, f"synthetic-{n:03d}-{name}.png"))
        n += 1

    for hue in range(0, 360, 20):  # solid colours, pale and strong
        for sat, val in ((0.2, 0.9), (0.6, 0.9), (1.0, 0.6)):
            rgb = tuple(int(255 * c) for c in colorsys.hsv_to_rgb(hue / 360, sat, val))
            put(Image.new("RGB", (1500, 500), rgb), "solid")
    for grey in (0, 20, 128, 235, 255):
        put(Image.new("RGB", (800, 800), (grey, grey, grey)), "grey")
    for _ in range(20):  # two-colour gradients, banner shaped
        a = [rnd.randrange(256) for _ in range(3)]
        b = [rnd.randrange(256) for _ in range(3)]
        img = Image.new("RGB", (1500, 500))
        draw = ImageDraw.Draw(img)
        for x in range(1500):
            t = x / 1499
            draw.line([(x, 0), (x, 499)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)))
        put(img, "gradient")
    words = "the quick brown fox jumps over the lazy dog monero nostr relay note tip watcher".split()
    for dark in (True, False):  # text on dark and light ground, like a screenshot
        for _ in range(15):
            bg, fg = ((18, 18, 18), (230, 230, 230)) if dark else ((250, 250, 250), (20, 20, 20))
            img = Image.new("RGB", (1080, 1920), bg)
            draw = ImageDraw.Draw(img)
            y = 40
            while y < 1880:
                line = " ".join(rnd.choice(words) for _ in range(rnd.randrange(3, 9)))
                draw.text((40, y), line, fill=fg)
                y += rnd.randrange(18, 60)
            put(img, "text-dark" if dark else "text-light")
    print("graphics/synthetic:", n)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    target = sys.argv[1]
    picsum(target)
    for category in BORDERLINE:
        commons(target, "borderline", category)
    for category in GRAPHICS:
        commons(target, "graphics", category)
    synthetic(target)


if __name__ == "__main__":
    main()
