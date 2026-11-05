# GIF playback: before/after on the Pixel 7a (2026-11-05)

Release builds with `-Pmonostr.perf=true` (R8, same signing key), BEFORE = 261b5435 (0.32.0), AFTER = 33789149.
Cold start = force-stop + `am start -W`, then 20 s; medians of runs 2-4, run 1 follows the install.

| metric (ms after process start) | before run 2/3/4 | median | after run 2/3/4 | median |
|---|---|---|---|---|
| am start TotalTime | 611 / 500 / 486 | 500 | 485 / 496 / 480 | 485 |
| session ready | 267 / 217 / 213 | 217 | 206 / 237 / 193 | 206 |
| feed first notes | 316 / 272 / 266 | 272 | 259 / 291 / 240 | 259 |
| feed fresh page | 1399 / 1432 / 1321 | 1399 | 1358 / 1416 / 1504 | 1416 |

| 30 s feed scroll (gfxinfo) | before | after |
|---|---|---|
| frames | 2591 | 2500 |
| janky frames | 25 (0.96 %) | 26 (1.04 %) |
| p50 / p90 / p95 / p99 | 5 / 7 / 8 / 17 ms | 5 / 7 / 8 / 18 ms |
| slow UI thread | 25 | 26 |

One scroll run per build, live relays, the account's own feed with its settings (media on tap on, as far as known).
This shows the feed without playing GIFs is not slower. It does not measure scrolling past a GIF that plays.
