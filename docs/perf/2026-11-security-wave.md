# Security wave + «No new notes» (2026-11-17)

Commits after v0.38.0 up to 6b287c5c: bug-bounty fixes (AUTH policy for borrowed relays, Amber result check, picture
loader address check, receipt retractions, link spoofing, GIF metadata, …), the «No new notes» pill, the author relays
switch for hint relays, the unlinked payment info for anonymous tips.

Feed open and scroll, perf release builds (same signature as the store build), Pixel 7a on Wi-Fi, 4 cold starts per
round (TotalTime, PerfTrace «feed first notes»), then 30 s home feed scroll (4 down, 2 up, gfxinfo reset before).
Before = v0.38.0, after = 6b287c5c. The first cold start right after each install reads 0 / ? (measuring artefact).

| round | frames | janky | p50 | p90 | p95 | p99 | cold start TotalTime (ms) | feed first notes (ms) |
|---|---|---|---|---|---|---|---|---|
| before 1 | 2595 | 0.73 % | 5 | 7 | 8 | 16 | 924 / 1061 / 644 / 634 | 543 / 443 / 282 / 269 |
| after 1 | 2574 | 0.82 % | 5 | 7 | 8 | 17 | – / 857 / 632 / 627 | – / 289 / 288 / 284 |
| before 2 | 2668 | 0.75 % | 5 | 7 | 8 | 19 | – / 709 / 626 / 647 | – / 291 / 280 / 282 |
| after 2 | 2625 | 0.88 % | 5 | 7 | 8 | 16 | – / 624 / 618 / 609 | – / 277 / 268 / 263 |
| before 3 | 2531 | 0.99 % | 5 | 7 | 9 | 18 | – / 725 / 612 / 947 | – / 287 / 271 / 469 |
| after 3 | 2463 | 1.18 % | 5 | 7 | 9 | 19 | – / 657 / 662 / 640 | – / 293 / 284 / 291 |
| after 4 (run first) | 2617 | 0.88 % | 5 | 7 | 9 | 16 | – / 626 / 627 / 618 | – / 278 / 272 / 272 |
| before 4 (run second) | 2557 | 0.94 % | 5 | 7 | 9 | 17 | – / 641 / 612 / 626 | – / 268 / 267 / 279 |

Rounds 1-3 ran «after» second each time and it showed 2-4 more janky frames of ~2500; round 4 reversed the order and
«before» was the one behind: an order effect (second run), not the change. p50 to p99, cold start and feed first notes
are equal. Verdict: no measurable cost. Thermal status 0 throughout.

The jank the owner saw while scrolling was the debug build (debuggable, no R8, no baseline profile).
