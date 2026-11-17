# Search results with counts (2026-11-17)

Commit 5400d9b0: search hits ask for their NIP-45 counts like the feed (visible hits only, batched).

Perf release builds on the Pixel 7a (Wi-Fi), before = v0.39.0, after = 5400d9b0. Per run: cold start, search tab,
«#monero», 12 s for the hits, keyboard hidden, gfxinfo reset, 30 s scroll (4 down, 2 up). Round 3 in reverse order.

| run | frames | janky | p50 | p90 | p95 | p99 |
|---|---|---|---|---|---|---|
| before 1 | 2336 | 1.41 % | 6 | 9 | 12 | 53 |
| after 1 | 2428 | 1.65 % | 5 | 8 | 10 | 48 |
| before 2 | 2440 | 1.56 % | 5 | 7 | 9 | 48 |
| after 2 | 2433 | 1.23 % | 5 | 7 | 10 | 53 |
| after 3 (run first) | 2474 | 1.29 % | 5 | 8 | 11 | 40 |
| before 3 (run second) | 2337 | 1.45 % | 5 | 7 | 10 | 48 |

No measurable cost: janky share 1.23-1.65 % after vs 1.41-1.56 % before, percentiles within noise in both
directions. The high p99 in both builds comes from the pictures and video posters of #monero hits. Thermal status 0.
