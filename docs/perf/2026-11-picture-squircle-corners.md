# Picture squircle corners without clipping (2026-11-22) — dropped

Change 621f3a2a + 5b2391e1 (backlog 78): single pictures, carousel cards, thumbnails and the DM bubble picture drew
their content unclipped and painted the four squircle corner fillets (rect minus `SquircleShape`) in the background
colour on top; path cached per size in a `Modifier.Node`. Before: `clip(PlainShapes.medium/small)`.

Pixel 7a, owner's feed, perf release builds (`-Pmonostr.perf=true`, same signature), bench.sh (4 cold starts, 30 s
scroll), alternating before (64ccf611) / after (5b2391e1):

| round | janky | p50 | p90 | p95 | p99 | cold start (4 runs) |
|---|---|---|---|---|---|---|
| before 1 | 0.89 % | 5 | 7 | 9 | 17 | 949 / 692 / 670 / 614 |
| after 1 | 0.94 % | 5 | 8 | 9 | 17 | 727 / 825 / 636 / 633 |
| before 2 | 0.94 % | 5 | 8 | 10 | 18 | 621 / 614 / 631 / 618 |
| after 2 | 0.95 % | 5 | 8 | 10 | 17 | 666 / 624 / 615 / 617 |
| before 3 | 1.08 % | 5 | 7 | 9 | 16 | 633 / 637 / 623 / 620 |
| after 3 | 1.05 % | 5 | 8 | 10 | 16 | 613 / 635 / 629 / 614 |

p90 mean 7.3 → 8.0 ms (+0.7 ms, after was 8 ms in every round), p95 9.3 → 9.7 ms; janky and cold start unchanged.
Gate (spec 2026-11-22: ≤ +0.5 ms p90 mean) failed → both commits reverted (3c579337, 463323f0). Cheaper than the
squircle clip (+1–2 ms, 2026-11-10) but still measurable. Pictures stay on `PlainShapes`; galleries keep their one
outer squircle (2026-11-gallery-squircle.md).
