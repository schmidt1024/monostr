# Gallery as one squircle block (2026-11-12)

Change 3b2878fd: galleries of 2+ pictures clip once to the 16 dp squircle (`MaterialTheme.shapes.large`) around the
whole block; the cells only clip to their bounds (2 dp lines, square inner corners). Before: a plain 12 dp rounded clip
per picture.

Pixel 7a, owner's feed, perf release builds (`-Pmonostr.perf=true`, same signature), bench.sh (4 cold starts, 30 s
scroll), alternating before (062e93ca) / after (3b2878fd):

| round | janky | p50 | p90 | p95 | p99 | cold start (4 runs) |
|---|---|---|---|---|---|---|
| before 1 | 1.21 % | 5 | 7 | 9 | 19 | 621 / 816 / 497 / 512 |
| after 1 | 1.35 % | 5 | 8 | 9 | 21 | 496 / 509 / 496 / 506 |
| before 2 | 1.20 % | 5 | 7 | 9 | 26 | 493 / 496 / 497 / 493 |
| after 2 | 1.17 % | 5 | 7 | 9 | 20 | 508 / 512 / 491 / 501 |

No measurable cost: one squircle clip per gallery replaces n rounded clips. Single pictures stay on `PlainShapes`
(squircle there cost +1-2 ms p90, 2026-11-10). bench.sh note: wait ~3 s and start the app after `install -r`, the
foreground check can see no activity right after the install.
