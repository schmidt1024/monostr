// Rasterise SVGs at exact pixel sizes with headless Chrome (playwright-core).
//
// Usage: node render.mjs <jobs.json>
// jobs.json: [{ "svg": "in.svg", "out": "out.png", "width": 1200, "height": 223,
//               "background": null | "#111113", "pad": 0,
//               "scale": null, "left": null, "top": null }, ...]
// The SVG's viewBox plus `pad` units on every side is fitted into width x height
// (scaled to fit, centred), unless scale/left/top place it explicitly (px per unit,
// px offset of the viewBox origin). background null = transparent PNG.
// Env: CHROME (default /usr/bin/google-chrome), PLAYWRIGHT_CORE (path to the package).
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE || "playwright-core");

const jobs = JSON.parse(readFileSync(process.argv[2], "utf8"));
const browser = await chromium.launch({
  executablePath: process.env.CHROME || "/usr/bin/google-chrome",
  headless: true,
});
const page = await browser.newPage({ deviceScaleFactor: 1 });
for (const job of jobs) {
  const src = readFileSync(job.svg, "utf8");
  const [, , vw, vh] = src.match(/viewBox="([^"]+)"/)[1].split(/\s+/).map(Number);
  const pad = job.pad || 0;
  const scale = job.scale ?? Math.min(job.width / (vw + 2 * pad), job.height / (vh + 2 * pad));
  const w = vw * scale, h = vh * scale;
  const left = job.left ?? (job.width - w) / 2, top = job.top ?? (job.height - h) / 2;
  const svg = src
    .replace(/ width="[^"]+"/, ` width="${w}"`)
    .replace(/ height="[^"]+"/, ` height="${h}"`)
    .replace("<svg ", '<svg style="display:block" ');
  const bg = job.background || "transparent";
  await page.setViewportSize({ width: job.width, height: job.height });
  await page.setContent(
    `<!DOCTYPE html><html><body style="margin:0;background:${bg};overflow:hidden">` +
      `<div style="position:absolute;left:${left}px;top:${top}px;width:${w}px;height:${h}px">${svg}</div>` +
      `</body></html>`,
  );
  await page.screenshot({ path: job.out, omitBackground: !job.background, type: "png" });
  console.log(job.out, job.width + "x" + job.height, "scale", scale.toFixed(4));
}
await browser.close();
