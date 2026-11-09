#!/usr/bin/env bash
# Rewrites the kind 0 profile of the Monostr project account with `name` and `display_name`
# «Monostr». Every other field of the current live kind 0 (about, picture, banner, nip05, …) and
# its tags are read from the relays and kept as they are.
#
# Dry run by default: prints the live profile and the new unsigned event, publishes nothing and
# needs no key. Publishing needs --publish and NSEC (signed the same way as
# scripts/publish-nip89-handler.sh); the key is never printed.
#
# Usage: scripts/publish-profile.sh [--publish] [relay …]
#        NSEC=nsec1… scripts/publish-profile.sh --publish
# Default relays: relay.monostr.com, relay.damus.io, nos.lol, purplepag.es
set -euo pipefail
cd "$(dirname "$0")/.."
command -v nak >/dev/null || { echo "nak not found: https://github.com/fiatjaf/nak" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

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
NAME=Monostr
RELAYS=("${ARGS[@]:-}")
[ -n "${RELAYS[0]}" ] || RELAYS=()
[ ${#RELAYS[@]} -gt 0 ] || RELAYS=(wss://relay.monostr.com wss://relay.damus.io wss://nos.lol wss://purplepag.es)

# the same relay twice (trailing slash, repeated argument) must not count as two answers
DEDUP=()
for R in "${RELAYS[@]}"; do
  R="${R%/}"
  case " ${DEDUP[*]:-} " in *" $R "*) ;; *) DEDUP+=("$R");; esac
done
RELAYS=("${DEDUP[@]}")

# nak with an open stdin waits for input (and nak event would publish a "hello" note): always < /dev/null
# one request per relay, so we know who answered with what
LIVE=""
ANSWERS=""
for R in "${RELAYS[@]}"; do
  OUT=$(timeout 30 nak req -k 0 -a "$EXPECT" -l 1 "$R" < /dev/null 2>/dev/null || true)
  LIVE+="$OUT"$'\n'
  ANSWERS+=$(printf '%s\n' "$OUT" | python3 -I -c '
import json, sys
best = None
for line in sys.stdin:
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if isinstance(e, dict) and e.get("kind") == 0 and e.get("pubkey") == sys.argv[2] and (best is None or e["created_at"] > best["created_at"]):
        best = e
print("%s\t%s\t%s" % (sys.argv[1], best["id"] if best else "-", best["created_at"] if best else "-"))
' "$R" "$EXPECT")$'\n'
done
echo "relays and their newest kind 0 (relay, id, created_at):"
printf '%s' "$ANSWERS"
# newest kind 0 of the project account over all relays
CURRENT=$(printf '%s\n' "$LIVE" | python3 -I -c '
import json, sys
best = None
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if isinstance(e, dict) and e.get("kind") == 0 and e.get("pubkey") == sys.argv[1] and (best is None or e["created_at"] > best["created_at"]):
        best = e
if best:
    print(json.dumps(best, ensure_ascii=False))
' "$EXPECT")
# never publish a profile without the live one: it would wipe about, picture, banner and nip05
[ -n "$CURRENT" ] || { echo "no kind 0 of $EXPECT found on ${RELAYS[*]}" >&2; exit 1; }

NEW=$(printf '%s' "$CURRENT" | python3 -I -c '
import json, sys
live = json.load(sys.stdin)
content = json.loads(live["content"])
content["name"] = sys.argv[1]
content["display_name"] = sys.argv[1]
if "displayName" in content:  # legacy key some clients still read
    content["displayName"] = sys.argv[1]
print(json.dumps({"kind": 0, "tags": live.get("tags", []), "content": json.dumps(content, ensure_ascii=False, separators=(",", ":"))}, ensure_ascii=False))
' "$NAME")

CURRENT_ID=$(printf '%s' "$CURRENT" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["id"])')
AGREE=$(printf '%s' "$ANSWERS" | awk -F'\t' -v id="$CURRENT_ID" '$2 == id' | wc -l)
echo "newest kind 0 $CURRENT_ID returned by $AGREE relay(s)"
echo "live kind 0 (created_at $(printf '%s' "$CURRENT" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["created_at"])')):"
printf '%s' "$CURRENT" | python3 -I -c 'import json,sys; print(json.dumps(json.loads(json.load(sys.stdin)["content"]), indent=2, ensure_ascii=False))'
echo "new kind 0 (unsigned, created_at set when signed):"
printf '%s\n' "$NEW"
echo "new content:"
printf '%s' "$NEW" | python3 -I -c 'import json,sys; print(json.dumps(json.loads(json.load(sys.stdin)["content"]), indent=2, ensure_ascii=False))'

if [ "$PUBLISH" -ne 1 ]; then
  echo "dry run: nothing published (publish with --publish and NSEC)"
  exit 0
fi

# a single relay may serve a stale or forked profile: the newest one must be confirmed by a second relay
[ "$AGREE" -ge 2 ] || { echo "aborting: newest kind 0 $CURRENT_ID was returned by only $AGREE relay(s), need at least 2; check the relays" >&2; exit 1; }

: "${NSEC:?set NSEC to the secret key of the project account (never commit it)}"
PUB=$(printf '%s\n' "$NSEC" | nak key public)
[ "$PUB" = "$EXPECT" ] || { echo "NSEC belongs to $PUB, expected the project account $EXPECT" >&2; exit 1; }
[ -n "$NEW" ] || { echo "empty event, not publishing" >&2; exit 1; }
printf '%s' "$NEW" | nak event --sec "$NSEC" "${RELAYS[@]}"
echo "verify: nak req -k 0 -a $EXPECT wss://relay.monostr.com < /dev/null"
