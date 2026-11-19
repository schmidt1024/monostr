# Tip receipts from watcher relays (2026-11-19)

Change 7d8562ef..41c29323 (backlog 97): one-shot receipt fetches from the note author's watcher relays, gated by
«author relays», one watcher fetch at a time, 5-minute throttle per note; plus the ForeignRelays AUTH-window fix.

Pixel 7a, owner's feed, perf release builds (same signature), bench.sh (4 cold starts, 30 s feed scroll), alternating:

| round | janky | p50 | p90 | p95 | p99 | cold (runs 2–4) | watcher-receipts marks |
|---|---|---|---|---|---|---|---|
| before 1 | 0.92 % | 5 | 7 | 9 | 17 | 1255 / 895 / 676 | 0 |
| after 1 | 0.85 % | 5 | 7 | 9 | 16 | 984 / 881 / 653 | 1 |
| before 2 | 1.60 % | 5 | 8 | 10 | 22 | 974 / 730 / 656 | 0 |
| after 2 | 0.84 % | 5 | 8 | 9 | 17 | 660 / 640 / 670 | 1 |

Verdict: no measurable cost; one watcher round per run (session cache + throttle), within the round-to-round noise.
