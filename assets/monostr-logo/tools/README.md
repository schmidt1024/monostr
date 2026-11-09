# Wordmark tools

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
  lockup), and the lockup band of `site/assets/monostr-banner.png` and
  `fastlane/metadata/android/en-US/images/featureGraphic.png`.

Pixel sizes are fixed in `build.py` (the sizes from before the capital M); the artwork is
scaled to fit and centred, so the scale is the same as before and the narrower wordmark
gets a little more side margin.

The banner and the feature graphic have no source file: their tagline and orange bar are
kept from the existing PNG (rows below `cut`), and the band above is redrawn with the dark
horizontal lockup at the scale and top measured on the originals (banner 1.6119 px/unit,
top 150.97; feature graphic 1.3608, top 165.09), its viewBox centred horizontally.
Re-running is idempotent.

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

Symbol-only files (icon, app icons, favicons, launcher) are not touched by these tools.
