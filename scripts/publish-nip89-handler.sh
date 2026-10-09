#!/usr/bin/env bash
# Publishes Monostr's NIP-89 handler event (kind 31990, d = monostr) from the project account.
# The app's `client` tag points at this event (see android/nostr/.../ClientTag.kt), so the
# pubkey that signs it MUST be the project account npub1zcqletx… (hex 1601fcac…4ed3).
#
# Usage: NSEC=nsec1… scripts/publish-nip89-handler.sh [relay …]
# Default relays: relay.monostr.com, relay.damus.io, nos.lol, purplepag.es (the NIP-89 indexers look there).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${NSEC:?set NSEC to the secret key of the project account (never commit it)}"
command -v nak >/dev/null || { echo "nak not found: https://github.com/fiatjaf/nak" >&2; exit 1; }
EXPECT=1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3
PUB=$(nak key public "$NSEC")
[ "$PUB" = "$EXPECT" ] || { echo "NSEC belongs to $PUB, expected the project account $EXPECT" >&2; exit 1; }
RELAYS=("$@")
[ ${#RELAYS[@]} -gt 0 ] || RELAYS=(wss://relay.monostr.com wss://relay.damus.io wss://nos.lol wss://purplepag.es)
FILE=docs/nip89/handler-31990.json
# nak with an empty stdin publishes a default "hello" note: never run it on a missing file
[ -s "$FILE" ] || { echo "$FILE missing or empty" >&2; exit 1; }
nak event --sec "$NSEC" "${RELAYS[@]}" < "$FILE"
echo "verify: nak req -k 31990 -a $EXPECT wss://relay.monostr.com"
