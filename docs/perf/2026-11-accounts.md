# Account switching (backlog 105): performance (2026-11)

Spec `docs/superpowers/specs/2026-11-24-account-switching-design.md`, section Performance; plan Task 11 step 3.

Pixel 7a, release builds with `-Pmonostr.perf=true` (R8, release signing key), 2026-11-25. BEFORE = `main` at 98ff845e. AFTER = `feat/accounts`: 66e58297 for the cold starts, aedebccd for the switches. aedebccd differs from 66e58297 only by moving the connection eviction off the main thread.
- Cold start: force-stop, then `am start -W`, then 20 s; Tor on, real account (Amber).
- Switch: the time from the `input tap` on a switcher row to the `MonostrPerf session ready` / `feed first notes` lines, from the logcat times of the device. A throwaway nsec account was added for the switch runs and removed afterwards.

## Cold start (spec: must not get slower)

Medians of the runs after the first run following the install: before, 4 runs; after, 5 runs.

| | Before | After |
|---|---|---|
| `am start` TotalTime | 1567 ms | 1151 ms |
| session ready | 419 ms | 240 ms |
| feed first notes | 688 ms | 392 ms |
| feed fresh page | ~6.2 s | ~6.0 s |

No regression. The spread is large (TotalTime 716 to 1946 ms after, 1074 to 1650 ms before). The phone was otherwise idle.

## Switch time (spec: comparable to the Tor rebuild)

5 switches each way, rows tapped at least 10 s apart (outside the NEWNYM rate-limit window).

| | session ready, median | feed first notes, median |
|---|---|---|
| Direct, to the real account | 206 ms | 316 ms |
| Direct, to the throwaway account | 200 ms | (empty feed) |
| Tor, to the real account | 216 ms | 416 ms |
| Tor, to the throwaway account | 261 ms | (empty feed) |

A second switch inside the 10 s NEWNYM window (one run, Tor): session ready after 244 ms, and the fresh relay page after 5.6 s, which includes the wait for the fresh route.

## Found on the Pixel, not on the AVD

- **Every key add (and every switch and remove) failed on the real device.** The cause was `NetworkOnMainThreadException`: `evictConnections` closed pooled TLS sockets on the main thread. The AVD tests used only plain-HTTP loopback, where a close writes nothing. Fixed in aedebccd, with `AccountSwitchMainThreadTest`, which uses an idle TLS connection under StrictMode.
- **Switcher sheet under the navigation bar.** The sheet, opened right after a switch, sat lower: its drag handle was at y=1880 instead of 1533. The second row lay behind the Android navigation bar, «Add account» was off screen, and the sheet could not be dragged up. Opened again later, it was laid out correctly.

## Not measured

- The countdown notice in the 10 s window: driving it over adb was too slow. It is covered by `SwitchNoticeDeviceTest` on the AVD.
- The background check with `keepDisplayFresh` on restore (one extra own-profile fetch per worker run).
