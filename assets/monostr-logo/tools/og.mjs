// Link preview image for monostr.com (Open Graph, 1200 x 630): coin left, wordmark and claim right.
// Owner 2026-11-12: horizontal lockup for the preview, title without «Monostr» (the image and the domain carry it).
//
// Usage: PLAYWRIGHT_CORE=<path to playwright-core> node og.mjs [out.png]
// Default out: site/assets/monostr-og.png. Colours as the banner: background #111113, claim #AAAAB0.
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..", "..");
const out = process.argv[2] || join(root, "site", "assets", "monostr-og.png");
const svg = (name) => readFileSync(join(here, "..", "svg", name), "utf8");
const font = readFileSync(join(root, "android", "app", "src", "main", "res", "font", "inter_regular.ttf")).toString("base64");

const html = `<!DOCTYPE html><html><head><style>
@font-face { font-family: Inter; src: url(data:font/ttf;base64,${font}); }
html, body { margin: 0; width: 1200px; height: 630px; background: #111113; }
.wrap { width: 1200px; height: 630px; display: flex; align-items: center; justify-content: center; gap: 56px; }
.coin svg { width: 240px; height: 240px; display: block; }
.word svg { height: 112px; width: auto; display: block; }
.claim { font: 46px/1 Inter, sans-serif; color: #AAAAB0; margin: 28px 0 0 9px; letter-spacing: 0.2px; /* 9 px: flush with the M stem, the wordmark viewBox starts 6.6 units before it */ }
</style></head><body><div class="wrap">
<div class="coin">${svg("monostr-coin-fuer-dunkel.svg")}</div>
<div><div class="word">${svg("monostr-wortmarke-fuer-dunkel.svg")}</div><div class="claim">Nostr with Monero tips</div></div>
</div></body></html>`;

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");
const browser = await chromium.launch({ executablePath: process.env.CHROME || "/usr/bin/google-chrome", headless: true });
const page = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
await page.setContent(html);
await page.evaluate(() => document.fonts.ready);
await page.screenshot({ path: out, clip: { x: 0, y: 0, width: 1200, height: 630 } });
await browser.close();
console.log(out);
