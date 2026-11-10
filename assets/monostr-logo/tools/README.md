# Logo tools (wordmark and coin)

The Monostr wordmark is a capital M (variant B of the 2026-11-07 rename, see
`docs/superpowers/specs/2026-11-07-capital-name-design.md`, section 4) followed by the
outlined «onostr» of the earlier wordmark.

- `wordmark.py`: the geometry. The M is computed as one filled polygon (the outline of a
  mitred stroke clipped to the cap band: stem 11.676, cap 62, centre-line vertex at 0.636 of
  the cap height, outer width 60, flat ends); «onostr» is kept verbatim and moved left by
  8.136 units (M–o gap 1.5 units wider than the old m–o gap, as in the preview the owner
  chose). Writes the six SVGs (wortmarke, lockup-horizontal, lockup-gestapelt; hell/dunkel)
  and, with `--drawable`, `android/app/src/main/res/drawable/ic_wordmark.xml`.
- `render.mjs`: rasterises SVGs at exact pixel sizes with headless Chrome through
  playwright-core.
- `build.py`: runs both and writes every derived file: 12 PNGs (transparent), 12 JPGs
  (background #F7F6F2 / #111113, 60 viewBox units of margin, quality 95, 4:4:4), the site
  lockups (`site/assets/monostr-lockup-{light,dark}.svg` / `-1600.png`, copies of the stacked
  lockup), the wordmark band of `site/assets/monostr-banner-3.png` (no icon; first built from
  `monostr-banner-2.png`) and the coin lockup band of
  `fastlane/metadata/android/en-US/images/featureGraphic.png`.

Pixel sizes are fixed in `build.py` (the sizes from before the capital M); the artwork is
scaled to fit and centred, so the scale is the same as before and the narrower wordmark
gets a little more side margin.

The banner and the feature graphic have no source file: their claim and orange bar are
kept from the existing PNG (rows below `cut`), and the band above is redrawn at the scale
measured on the originals with the baseline where it was: the banner shows the wordmark only
(1.6119 px/unit, baseline at 251.2 px), the feature graphic the horizontal coin lockup
(1.3608 px/unit, baseline at 249.67 px); the ink is centred horizontally. Re-running is
idempotent.

## Regenerate

No system rasteriser is needed. Used on 2026-11-07: Google Chrome (`/usr/bin/google-chrome`),
node 24, playwright-core 1.59.1 from the npx cache, system python3 with Pillow 12 and numpy.

```bash
cd <repo>
PLAYWRIGHT_CORE=$HOME/.npm/_npx/e41f203b7505f1fb/node_modules/playwright-core \
  python3 assets/monostr-logo/tools/build.py
```

If playwright-core is not in the npx cache: `npm i playwright-core` in a scratch folder and
point `PLAYWRIGHT_CORE` at its `node_modules/playwright-core`. Another Chrome/Chromium binary:
set `CHROME=<path>`.

Only the SVGs and the drawable: `python3 assets/monostr-logo/tools/wordmark.py
assets/monostr-logo/svg --drawable android/app/src/main/res/drawable/ic_wordmark.xml`.

Since the coin (2026-11-09) `build.py` also writes every symbol file (see «Coin» below);
only `coin.py` itself writes the Android drawables and `TipCoin.kt`.

## Coin

`coin.py` is the geometry of the coin mark (spec `docs/superpowers/specs/2026-11-09-coin-logo-design.md`): thin
outer ring, a ring of beads, the Monostr M in the middle; a large and a small (≤ 48 px) set of strokes and beads,
outline and filled. `python3 assets/monostr-logo/tools/coin.py` writes `svg/monostr-coin-fuer-{hell,dunkel}.svg`,
`svg/monostr-icon-fuer-{hell,dunkel}.svg` (the same coin), `svg/monostr-app-icon-{rund,quadrat}-{hell,dunkel}.svg`
and the launcher exports in `android/` (`ic_launcher_{foreground,monochrome}.svg`, `play-store-icon.svg`, dark).

Two options write into the app (run them after changing the geometry, then commit the result):

- `python3 assets/monostr-logo/tools/coin.py --drawables android/app/src/main/res/drawable`: the launcher
  foreground and monochrome layers (coin 60 dp in the 108 dp canvas), `ic_notification.xml` (white disc, M cut
  out, no beads) and `ic_logo_mark.xml` (coin cropped to its edge, tinted by the caller).
- `python3 assets/monostr-logo/tools/coin.py --kotlin`: `android/app/src/main/kotlin/com/monostr/app/ui/theme/TipCoin.kt`,
  the 24 dp tip icon: outline (ring, beads, M) and filled (one even-odd path: disc with beads and M as holes,
  the M as the outline of its stroke from `stroke_outline`).

`build.py` also writes the coin exports (spec 2026-11-09): `png/monostr-{app-icon-*,icon-fuer-*}-{512,1024}.png`,
`site/favicon.svg`, `site/favicon.ico` and `favicon-32.png` (dark disc with the light small coin, `coin.favicon_svg`, readable on light and dark tabs),
`site/apple-touch-icon.png`, `site/icon-{192,512}.png`, `site/monostr-icon-2.png` (handler picture, relay NIP-11 icon),
`site/assets/monostr-avatar-3.png` (profile picture: the coin nearly filling the round crop, `coin.avatar`; `monostr-icon-2.png` likewise), fastlane `images/icon.png` (also Zapstore's icon, dark),
the rasters of the launcher exports (`android/*-432.png`, `android/play-store-icon-512.png`) and
`favicon/` (copies of the site's favicons plus 16/32/48 px PNGs).
Lockups: the coin (diameter 1.5x the cap height) left of the wordmark, or above it in the stacked lockup.
