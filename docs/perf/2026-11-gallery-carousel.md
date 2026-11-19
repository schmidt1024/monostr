# Gallery carousel (2026-11-19)

Change 9aeb7288..bf56dd6c (backlog 93): galleries of 2–20 items as one horizontal `LazyRow` of equal-height,
aspect-true cards with a dot indicator above; the 2×2 grid with «+N» is gone. The dot position is read only inside the
dots composable (a swipe recomposes the dots, not the row).

Pixel 7a, owner's feed, perf release builds (`-Pmonostr.perf=true`, same signature), bench.sh (4 cold starts, 30 s
vertical feed scroll), alternating before (9aeb7288) / after (bf56dd6c):

| round | janky | p50 | p90 | p95 | p99 | cold start (runs 2–4) | feed first notes |
|---|---|---|---|---|---|---|---|
| before 1 | 1.10 % | 5 | 7 | 9 | 20 | 2039 / 2015 / 2018 | 1048 / 963 / 998 / 957 |
| after 1 | 0.92 % | 5 | 7 | 8 | 18 | 1815 / 1474 / 1000 | 843 / 701 / 490 / 341 |
| before 2 | 0.84 % | 5 | 7 | 9 | 18 | 1505 / 1135 / 847 | 518 / 395 / 331 / 351 |
| after 2 | 1.07 % | 5 | 8 | 11 | 19 | 955 / 779 / 653 | 349 / 326 / 308 / 284 |

Verdict: no measurable cost in vertical feed scrolling; janky % and percentiles stay within the round-to-round noise
(before 0.84–1.10 %, after 0.92–1.07 %). Cold starts fall over the session in both builds (first start after each
install pays for profile compilation; run 1 reported 0, a known `am start -W` quirk right after `install -r`), so they
are not compared across builds. Horizontal swiping inside a gallery is not part of bench.sh; the owner checks it by
hand on the Pixel.
