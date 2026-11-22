# DM peer leg under Tor (backlog 99) — Pixel before/after

Date 2026-11-20, Pixel 7a, perf release builds (`-Pmonostr.perf=true`), owner sends DMs Schmidt → Monostr project
account (inbox relay.monostr.com only). Mark: `dm send done … ms=` in DmSync.deliver (send start → peer + self result).

| Build | Mode | ms (counted sends) | Median |
|---|---|---|---|
| before (e6a3fa65, marks only) | direct, warm | 826 · 1524 | ~1.2 s |
| before | Tor | 1266 · 889 · 1005 · 1365 · 1212 | 1.21 s |
| after (7a5d51bb) | direct | 7490 · 972 · 897 · 4666 · 503 | 0.97 s |
| after | Tor (`self=false` = deferred self copy) | 1206 · 1236 · 1222 · 1445 · 1140 | 1.22 s |

- Tor: no measurable cost; the detached peer leg costs about what the session path did (circuits were warm).
- Direct: code path unchanged; the 7.5 s / 4.7 s outliers and the cold-start failures below are pre-existing.
- Before the run, the first two DMs after the app start took ~20 s each and failed (`peer=false`), a third 9.4 s:
  pre-existing, logged as backlog 101. Logs: session scratchpad (not kept).
- Before-Tor samples are taken from the process after the Tor switch restart; the mode was not logged.
