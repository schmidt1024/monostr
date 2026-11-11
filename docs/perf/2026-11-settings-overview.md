# Settings overview with subpages: before/after on the Pixel 7a (2026-11-11)

Release builds with `-Pmonostr.perf=true` (R8, same signing key 619d7b95…). BEFORE = 9c811c81 (one long page) plus a
throwaway `settings_overview_drawn` mark after its first frame; AFTER = 404bce51 (branch feat/settings-overview).
Script `.superpowers/sdd/2026-11-11-settings-overview/perf/settings-bench.sh`: cold start, 15 s, then tap «Settings»
(tap time = device clock in the same `adb shell` as `input tap`, so each value includes ~100 ms of `input` start-up,
equal in both builds). 4 runs each.

## Open latency (tap → first frame, ms)

| screen | before runs 1-4 | median | after runs 1-4 | median |
|---|---|---|---|---|
| Settings (old long page / new overview) | 232 / 231 / 205 / 234 | 232 | 135 / 120 / 142 / 140 | 138 |
| Tor & privacy page | — | — | 111 / 92 / 112 / 86 | 102 |
| Relays page | — | — | 153 / 157 / 136 / 148 | 151 |
| Monero & tips page | — | — | 119 / 120 / 101 / 116 | 118 |

The overview opens ~95 ms faster than the old page; every subpage opens faster than the old page did.

## Frames (gfxinfo, whole run)

| | before (open + 2× scroll the long page) | after (open, 3 pages, scroll Relays + Monero, 6 navigations) |
|---|---|---|
| frames | 684-769 | 797-809 |
| janky | 0.52-0.91 % | 1.38-1.73 % |
| p50 / p90 | 6 / 9-12 ms | 5-6 / 8-12 ms |
| p95 / p99 | 10-21 / 17-29 ms | 9-13 / 25-36 ms |

Scroll medians are unchanged (p50 5-6 ms in both). The after run contains six page transitions the before run does not
have; the extra janky frames and the higher p99 most likely come from those transitions (navigation slide + first
composition of a page) rather than from scrolling (not separated in this run). Not measured: the same transitions in isolation, a profile with a Monero setup.

Verdict: no regression in scrolling, Settings opens faster.

## Round 2 (cards, reviewed order, confirms; build 17613471) — 2026-11-11 evening

Open latency, same method, 4 runs (ms): overview 109 / 110 / 124 / 150 (median 117, was 232 for the old page);
Tor & privacy 100 / 109 / 113 / 107 (108); Relays 169 / 120 / 139 / 129 (134); Monero & tips 141 / 118 / 109 / 124 (121).

Scroll only (gfxinfo reset after navigation, 24 swipes, 3 runs) — answers the open point of the first run:

| screen | janky | p50 / p90 / p95 / p99 |
|---|---|---|
| before: old long page | 0.35-0.57 % | 6 / 9-11 / 11-12 / 13-15 ms |
| after: Relays page | 0.00-0.12 % | 6 / 8 / 9-10 / 11-12 ms |
| after: Monero & tips page | 0.11-0.12 % | 8 / 8-9 / 9 / 11-14 ms |

Scrolling is as smooth or smoother than before; the extra janky frames of the first run's whole-session numbers came
from page transitions, not from scrolling. The Monero page's p50 of 8 ms (p90 lower than before) is likely due to a short page
where most swipes hit the overscroll stretch.
