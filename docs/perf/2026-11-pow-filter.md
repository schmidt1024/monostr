# Proof of work filter, off: before/after on the Pixel 7a (2026-11-05)

Release builds with `-Pmonostr.perf=true` (R8, same signing key), BEFORE = 7f256754, AFTER = a115f85c, filter off.
Cold start = force-stop + `am start -W`, then 20 s; medians of runs 2-4, run 1 follows the install.

| metric (ms after process start) | before run 2/3/4 | median | after run 2/3/4 | median |
|---|---|---|---|---|
| am start TotalTime | 720 / 489 / 518 | 518 | 616 / 481 / 488 | 488 |
| session ready | 243 / 204 / 257 | 243 | 243 / 238 / 230 | 238 |
| feed first notes | 285 / 247 / 314 | 285 | 298 / 293 / 277 | 293 |
| feed fresh page | 1879 / 1333 / 1554 | 1554 | 1456 / 1446 / 1577 | 1456 |

| 30 s feed scroll (gfxinfo) | before | after |
|---|---|---|
| frames | 2564 | 2621 |
| janky frames | 29 (1.13 %) | 28 (1.07 %) |
| p50 / p90 / p95 / p99 | 5 / 7 / 9 / 19 ms | 5 / 7 / 9 / 19 ms |
| slow UI thread | 28 | 28 |

One scroll run per build, live relays. Both builds jank a little more than the same day's 0.31.0 / bare reference runs
(0.88-0.98 %, p99 16-18 ms); BEFORE does not contain the filter. Not measured with the filter on.
