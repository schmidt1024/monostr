# monostr.com

The static website: `index.html` (light/dark by `prefers-color-scheme`, no external resources),
`assets/` (lockups, banner and avatar of the project account, two app screenshots), favicons, and
`.well-known/nostr.json` (NIP-05 `monostr@monostr.com` → the project account).

Hosted as a Hestia web domain; the docroot is `public_html/` of the domain. Deploy = copy the files
there and give them to the web user. `ops/nginx.ssl.conf_nostr` is the custom nginx include that
adds the CORS header for `nostr.json` (Hestia includes `nginx.ssl.conf_*` from the domain's conf
directory); after changing it, `nginx -t` and reload.

The screenshots come from `fastlane/metadata/android/en-US/images/phoneScreenshots/` (1 and 3),
scaled to 540×1200 WebP.
