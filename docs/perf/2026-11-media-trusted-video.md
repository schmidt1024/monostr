# Trusted accounts: longer videos (2026-11-19)

Change 44aa1b51..ef8d587d, server deployed as v0.39.2-28-gef8d587d (MEDIA_TRUSTED_PUBKEYS = Schmidt only).

Owner test on the Pixel 7a (Wi-Fi), perf release build of ef8d587d, signed in as Schmidt:

| step | result |
|---|---|
| picked video | ~8 min, accepted (no «up to 2 minutes» message) |
| limits fetch | 3 `GET /limits` in the server log over the test (one per long pick; session cache) |
| encode on the phone | «went quickly» (owner, not timed) |
| upload + server check | `PUT /upload` 201 in **33.6 s** server-side (body + NSFW frame sampling + bucket write); the poster upload 283 ms |
| screen off | upload finished with the screen off |
| sidecar memory after | nsfw 117 MiB / 1 GiB, media 11 MiB / 512 MiB |

Observation: the tile «hung a bit at the end» — the app's progress reaches the end of the body while the server still
scores the sampled frames and writes to the bucket (part of the 33.6 s). Candidate polish: a «checking» state once the
body is sent (not done).

Default accounts: unchanged path (no limits fetch, no signature below the 2-minute default).
