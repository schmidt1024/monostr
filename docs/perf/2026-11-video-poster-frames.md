# Video posters from the first frame (2026-11-12)

Commits 4941beaa..b20edd40: a video whose `imeta` has no `image` gets a poster from its own first seconds, read by HTTP
ranges (256 KiB blocks, at most 4 MiB, 20 s), one extraction app-wide, stored as JPEG in `cacheDir/video_frames`.

Pixel 7a over Tor, TFTC note c6199989… (blossom.primal.net, 17.8 MB MP4): first frame after 2857 ms, 524 288 bytes read;
later from the store at once.

Feed scroll, perf release builds, bench.sh (4 cold starts, 30 s scroll), alternating before (8e2d37a4) / after (b20edd40):

| round | janky | p50 | p90 | p95 | p99 | cold start (4 runs) |
|---|---|---|---|---|---|---|
| before 1 | 0.89 % | 5 | 7 | 9 | 16 | 589 / 543 / 522 / 498 |
| after 1 | 1.12 % | 5 | 7 | 9 | 19 | 541 / 498 / 504 / 499 |
| before 2 | 1.22 % | 5 | 7 | 8 | 19 | 491 / 508 / 495 / 510 |
| after 2 | 1.03 % | 5 | 7 | 9 | 19 | 505 / 497 / 497 / 492 |

No measurable cost. While scrolling fast, extractions of videos that leave the screen end as `failed CANCELED` (0-256 KiB
read), as intended.
