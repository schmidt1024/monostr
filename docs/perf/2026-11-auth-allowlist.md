# NIP-42 AUTH allowlist (2026-11-19)

Change ab025bd0..8c6e6a48: AUTH only for the user's own relays (normal set, own DM inbox, explicit search relays minus
defaults); foreign lists gated by «author relays» and leased; joining-mark hardening.

Pixel 7a, owner's feed, perf release builds (same signature), bench.sh (4 cold starts, 30 s feed scroll), alternating:

| round | janky | p50 | p90 | p95 | p99 | cold (runs 2–4) | tab title shown | AUTH errors in logcat |
|---|---|---|---|---|---|---|---|---|
| before 1 | 0.76 % | 5 | 8 | 10 | 16 | 1114 / 652 / 733 | 334 ms | 0 |
| after 1 | 0.79 % | 5 | 7 | 8 | 16 | 920 / 685 / 633 | 327 ms | 0 |
| before 2 | 0.72 % | 5 | 7 | 9 | 16 | 668 / 679 / 737 | 312 ms | 0 |
| after 2 | 0.89 % | 5 | 8 | 9 | 22 | 708 / 707 / 647 | 317 ms | 0 |

Verdict: no measurable cost (one set lookup per AUTH challenge); no AUTH error loop for the default relays.
