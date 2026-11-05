# Per-relay read and write, default modes: before/after on the Pixel 7a (2026-11-05)

Release builds with `-Pmonostr.perf=true` (R8, same signing key), BEFORE = 17686118, AFTER = 06e68b6b, no mode set.
Cold start = force-stop + `am start -W`, then 20 s; medians of runs 2-4, run 1 follows the install.

| metric (ms after process start) | before run 2/3/4 | median | after run 2/3/4 | median |
|---|---|---|---|---|
| am start TotalTime | 496 / 493 / 493 | 493 | 521 / 503 / 485 | 503 |
| session ready | 209 / 218 / 237 | 218 | 233 / 210 / 212 | 212 |
| feed first notes | 260 / 280 / 293 | 280 | 299 / 256 / 279 | 279 |
| feed fresh page | 2291 / 2995 / 2793 | 2793 | 2780 / 2842 / 2365 | 2780 |

| 30 s feed scroll (gfxinfo) | before | after |
|---|---|---|
| frames | 2643 | 2482 |
| janky frames | 28 (1.06 %) | 27 (1.09 %) |
| p50 / p90 / p95 / p99 | 5 / 7 / 9 / 16 ms | 5 / 7 / 9 / 21 ms |
| slow UI thread | 28 | 25 |

Fresh page is about twice this afternoon's 1399-1456 ms in both builds, so it comes from the live relays at this hour,
not from the change. p99 is one scroll run per build; the same day's runs lie between 16 and 19 ms.
Not measured: a user with one-way relays.
