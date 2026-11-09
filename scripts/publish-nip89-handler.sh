#!/usr/bin/env bash
# Publishes Monostr's NIP-89 handler event (kind 31990, d = monostr) from the project account.
# The app's `client` tag points at this event (see android/nostr/.../ClientTag.kt), so the
# pubkey that signs it MUST be the project account npub1zcqletx… (hex 1601fcac…4ed3).
#
# Dry run by default: prints the event built from docs/nip89/handler-31990.json, publishes nothing and
# needs no key. Publishing needs --publish and NSEC; the key is never printed.
#
# Usage: scripts/publish-nip89-handler.sh [--publish] [relay …]
#        NSEC=nsec1… scripts/publish-nip89-handler.sh --publish
# Default relays: relay.monostr.com, relay.damus.io, nos.lol, purplepag.es (rejects kind 31990 since 2026-11),
# relay.ditto.pub (NostrHub reads it; its directory lists only authors followed by its «experts», the
# t tags in the JSON put monostr in its category views).
set -euo pipefail
cd "$(dirname "$0")/.."
command -v nak >/dev/null || { echo "nak not found: https://github.com/fiatjaf/nak" >&2; exit 1; }
PUBLISH=0
ARGS=()
for A in "$@"; do
  case "$A" in
    --publish) PUBLISH=1;;
    --*) echo "unknown option $A" >&2; echo "usage: $0 [--publish] [relay …]" >&2; exit 2;;
    *) ARGS+=("$A");;
  esac
done
EXPECT=1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3
RELAYS=("${ARGS[@]:-}")
[ -n "${RELAYS[0]}" ] || RELAYS=()
[ ${#RELAYS[@]} -gt 0 ] || RELAYS=(wss://relay.monostr.com wss://relay.damus.io wss://nos.lol wss://purplepag.es wss://relay.ditto.pub)
FILE=docs/nip89/handler-31990.json
# nak with an empty stdin publishes a default "hello" note: never run it on a missing file
[ -s "$FILE" ] || { echo "$FILE missing or empty" >&2; exit 1; }
echo "event from $FILE (unsigned; signed and created_at set on --publish):"
cat "$FILE"
echo
if [ "$PUBLISH" -ne 1 ]; then
  echo "dry run: nothing published (publish with --publish and NSEC)"
  exit 0
fi
: "${NSEC:?set NSEC to the secret key of the project account (never commit it)}"
PUB=$(printf '%s\n' "$NSEC" | nak key public)
[ "$PUB" = "$EXPECT" ] || { echo "NSEC belongs to $PUB, expected the project account $EXPECT" >&2; exit 1; }
nak event --sec "$NSEC" "${RELAYS[@]}" < "$FILE"
echo "verify: nak req -k 31990 -a $EXPECT wss://relay.monostr.com"
