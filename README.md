# monostr

**Nostr client for Android with tips in Monero.** No Lightning, no custodian, no wallet inside the app.

[monostr.com](https://monostr.com) · relay `wss://relay.monostr.com` · watcher `https://watcher.monostr.com`

monostr is in beta.

## What it does

- Feed, threads, profiles with Posts and Replies tabs; like, repost, quote, reply
- Pictures in notes and own uploads (Blossom), sensitive content blurred
- Private messages (NIP-17), private bookmarks and mutes (NIP-51), search (NIP-50), notifications
- Sign in with Amber (NIP-55) or a key; light and dark theme; English, German, Spanish, French, Italian, Portuguese, Russian, Turkish

## How the Monero tips work

1. The sender taps the Monero icon under a note or on a profile. monostr opens the sender's own wallet app (Monerujo, Cake, Feather …) with a `monero:` link; the app never holds a wallet or a spend key.
2. To receive tips, a user registers the primary address and the **private view key** with a *watcher* — `watcher.monostr.com` by default, or one they run themselves (`watcher/`). The view key lets the watcher see incoming payments, nothing more; the watcher operator can see them too.
3. The watcher publishes a signed tip receipt on Nostr. Clients show the tip under the note.

The protocol is specified in [`docs/protocol/monostr-tips.md`](docs/protocol/monostr-tips.md).

## Repository

| Path | Content |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose, [rust-nostr](https://github.com/rust-nostr/nostr)) |
| `watcher/` | Go service that detects payments (monero-lws) and publishes tip receipts |
| `relay/`, `search/`, `media/` | Configuration of relay.monostr.com, the search relay and the Blossom server |
| `docs/protocol/` | Tip protocol (NIP style) |
| `fastlane/` | Store texts and pictures |

## Build

JDK 21, Android SDK (compileSdk 37):

```sh
cd android
./gradlew clean :app:assembleRelease --no-build-cache
```

Without signing properties the result is an unsigned APK at `android/app/build/outputs/apk/release/app-release.apk`. The release build is reproducible: a fresh clone builds the same bytes as the published APK (before signing). The version lives in `android/version.properties`.

Release APKs are signed with this certificate (SHA-256):

```
61:9d:7b:95:c3:ed:13:1c:35:bc:ee:f6:34:89:34:2d:45:5b:3a:99:f4:ab:b8:be:28:91:9e:33:33:02:fe:1e
```

## Privacy notes

- Notes, likes and reposts carry a NIP-89 `client` tag ("via monostr"); Settings can switch it off.
- Follower numbers can come from Primal's index (`cache2.primal.net`), a proprietary service; Settings can switch it off, then only relays count.

## License

monostr (app, watcher, relay and ops configuration) is free software under the GNU Affero General Public License v3.0 or later (`SPDX-License-Identifier: AGPL-3.0-or-later`), see [`LICENSE`](LICENSE). Copyright (C) 2026 The monostr contributors.

Parts of the code were written with the help of AI coding tools.
