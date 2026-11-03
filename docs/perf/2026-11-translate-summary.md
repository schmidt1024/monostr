# Translation feature: before/after on the Pixel 7a (2026-11-03)

Release builds with `-Pmonostr.perf=true` (R8, same signing key), BEFORE = 7c125260 (pre-UI), AFTER = cd23cfde.
Cold start = force-stop + `am start -W`; runs 2-4 (run 1 of BEFORE was the first launch after install, 524/611/5939 ms).
Raw: `2026-11-translate-before.txt`, `2026-11-translate-after.txt`.

| metric (ms after process start) | before run 2/3/4 | median | after run 2/3/4 | median | delta |
|---|---|---|---|---|---|
| am start TotalTime | 895 / 522 / 484 | 522 | 483 / 526 / 493 | 493 | -5.6 % |
| session ready | 369 / 230 / 213 | 230 | 217 / 233 / 225 | 225 | -2.2 % |
| feed first notes | 423 / 285 / 266 | 285 | 270 / 289 / 286 | 286 | +0.4 % |
| feed fresh page | 1666 / 1330 / 1408 | 1408 | 1360 / 1322 / 1373 | 1360 | -3.4 % |

(AFTER run 1, also post-install: 249 / 302 / 1387 ms.)

| 30 s feed scroll (gfxinfo) | before | after |
|---|---|---|
| frames | 2580 | 2518 |
| janky frames | 22 (0.85 %) | 24 (0.95 %) |
| p50 / p90 / p95 / p99 | 5 / 7 / 8 / 16 ms | 5 / 7 / 9 / 17 ms |
| slow UI thread | 21 | 24 |

Language detector (debug androidTest `LanguageDetectorBench`, 200 texts, 2 runs): init 1053 / 989 ms, heap +1.0 / 1.1 MB,
median 2111 / 2038 us, p95 4807 / 4755 us, detected 194/200. Release perf mark: `article language` 16 ms (11 614 chars), 0 ms (2 040 chars).

End-to-end (tap -> translated text on screen, from screenrecord frames, live translate.monostr.com):

| case | runs (ms) | median |
|---|---|---|
| note fr->en (perf build 7, release 1) | 456, 512, 520, 577, 710, 1243, 1469, 3296 | 643 |
| note fr->de (perf build) | 516, 522, 535, 578, 632, 832, 3408 | 578 |
| article fr->de, 11.6k chars | title + summary at 4.0 s; first body paragraph never translated, ended «Some parts could not be translated» | - |
| article fr->en, 2k chars | title + first paragraph at 7.4 s; complete afterwards | - |

Server alone (curl from the dev host): note fr->en 0.28-0.43 s, fr->de 0.65 s (spikes 1.8 / 2.2 s);
article lead 475 chars en 1.6 s, de 3.3 s; batch of 8 texts / 1324 chars en 5.9 s, de 12.5 s (client call timeout 15 s, 2 batches in flight).

## Re-check of article translation on the Pixel (HEAD 69730e83, release, fr->de, 2026-11-03)

One block per request, one in flight, timeout 10 s + 12 ms/char. Timing by uiautomator polling (+/-2.4 s).

| article | title | first paragraph | complete |
|---|---|---|---|
| 11.6k chars | <= 2.3 s | <= 4.8 s | yes, ~82 s total |
| 2.0k chars | <= 2.3 s | <= 4.6 s | yes, ~20 s total |

No «Einige Teile konnten nicht übersetzt werden» any more (was partial before). Note fr->de button, translation and mention link work. Details: `.superpowers/sdd/2026-11-03-note-translation/task-9c-report.md`.
