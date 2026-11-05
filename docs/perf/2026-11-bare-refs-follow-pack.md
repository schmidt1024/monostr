# Bare nostr references + follow pack cards: before/after on the Pixel 7a (2026-11-05)

Release builds with `-Pmonostr.perf=true` (R8, same signing key), BEFORE = 0fa6b9bc (0.31.0), AFTER = 5880190e.
Cold start = force-stop + `am start -W`, then 20 s; medians of runs 2-4, run 1 follows the install.

| metric (ms after process start) | before run 2/3/4 | median | after run 2/3/4 | median |
|---|---|---|---|---|
| am start TotalTime | 650 / 480 / 474 | 480 | 605 / 481 / 484 | 484 |
| session ready | 241 / 205 / 229 | 229 | 242 / 210 / 219 | 219 |
| feed first notes | 279 / 262 / 294 | 279 | 316 / 274 / 279 | 279 |
| feed fresh page | 1494 / 1562 / 1404 | 1494 | 1325 / 2073 / 1456 | 1456 |

| 30 s feed scroll (gfxinfo) | before | after |
|---|---|---|
| frames | 2621 | 2549 |
| janky frames | 23 (0.88 %) | 25 (0.98 %) |
| p50 / p90 / p95 / p99 | 5 / 7 / 9 / 16 ms | 5 / 7 / 9 / 18 ms |
| slow UI thread | 22 | 25 |

One scroll run per build, live relays: the differences are within what the 2026-11-03 translation runs show
(0.85-0.95 % jank, p99 16-17 ms), but one run cannot rule out a small effect.

Regex alone (JVM, 642 characters, no match): 6 us -> 16 us per pass; a text's spans are then cached.
