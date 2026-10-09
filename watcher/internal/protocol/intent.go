package protocol

import (
	"errors"
	"fmt"
	"strconv"

	"github.com/nbd-wtf/go-nostr"
)

// Intent is a validated kind 9738 event. NoteID is empty for a tip to the
// recipient's profile; Anon means the sender asked not to be named.
type Intent struct {
	ID         string
	Sender     string
	Recipient  string
	NoteID     string
	PID        string
	Type       string
	Amount     int64
	CreatedAt  int64
	Expiration int64
	Anon       bool
}

var ErrInvalidIntent = errors.New("invalid tip intent")

// MaxFutureSkew is how far created_at may lie in the future (seconds).
const MaxFutureSkew int64 = 300

// MaxIntentTTL bounds expiration - created_at (seconds): an intent may keep
// a webhook and a pid reservation alive for at most seven days.
const MaxIntentTTL int64 = 7 * 86400

func invalid(reason string) error { return fmt.Errorf("%w: %s", ErrInvalidIntent, reason) }

func firstTagValue(tags nostr.Tags, key string) (string, bool) {
	for _, tag := range tags {
		if len(tag) >= 2 && tag[0] == key {
			return tag[1], true
		}
	}
	return "", false
}

// firstTag returns the first tag named key, whatever its length.
func firstTag(tags nostr.Tags, key string) (nostr.Tag, bool) {
	for _, tag := range tags {
		if len(tag) >= 1 && tag[0] == key {
			return tag, true
		}
	}
	return nil, false
}

// ParseIntent validates a foreign event and never panics. Registration of the
// recipient and rate limits are the caller's job.
func ParseIntent(ev *nostr.Event, now int64) (Intent, error) {
	if ev == nil {
		return Intent{}, invalid("nil event")
	}
	if ev.Kind != KindTipIntent {
		return Intent{}, invalid("wrong kind")
	}
	if !IsHex64(ev.ID) || !IsHex64(ev.PubKey) {
		return Intent{}, invalid("bad id or pubkey")
	}
	if !ev.CheckID() {
		return Intent{}, invalid("id mismatch")
	}
	if ok, err := ev.CheckSignature(); err != nil || !ok {
		return Intent{}, invalid("bad signature")
	}
	if int64(ev.CreatedAt) > now+MaxFutureSkew {
		return Intent{}, invalid("created_at in the future")
	}
	// e is optional (protocol 0.2): without it the tip goes to the profile.
	// A present but malformed e never turns into a profile tip.
	noteID := ""
	if tag, present := firstTag(ev.Tags, "e"); present {
		if len(tag) < 2 || !IsHex64(tag[1]) {
			return Intent{}, invalid("bad e tag")
		}
		noteID = tag[1]
	}
	_, anon := firstTag(ev.Tags, "anon")
	recipient, ok := firstTagValue(ev.Tags, "p")
	if !ok || !IsHex64(recipient) {
		return Intent{}, invalid("bad p tag")
	}
	amountStr, ok := firstTagValue(ev.Tags, "amount")
	if !ok {
		return Intent{}, invalid("missing amount")
	}
	amount, err := strconv.ParseInt(amountStr, 10, 64)
	if err != nil || amount <= 0 {
		return Intent{}, invalid("bad amount")
	}
	pid, ok := firstTagValue(ev.Tags, "pid")
	if !ok || !IsHex16(pid) {
		return Intent{}, invalid("bad pid")
	}
	typ, ok := firstTagValue(ev.Tags, "type")
	if !ok || (typ != "like" && typ != "boost" && typ != "tip") {
		return Intent{}, invalid("bad type")
	}
	expStr, ok := firstTagValue(ev.Tags, "expiration")
	if !ok {
		return Intent{}, invalid("missing expiration")
	}
	expiration, err := strconv.ParseInt(expStr, 10, 64)
	if err != nil {
		return Intent{}, invalid("bad expiration")
	}
	if expiration <= now {
		return Intent{}, invalid("expired")
	}
	if expiration > int64(ev.CreatedAt)+MaxIntentTTL {
		return Intent{}, invalid("expiration too far in the future")
	}
	return Intent{
		ID: ev.ID, Sender: ev.PubKey, Recipient: recipient, NoteID: noteID, PID: pid, Type: typ,
		Amount: amount, CreatedAt: int64(ev.CreatedAt), Expiration: expiration, Anon: anon,
	}, nil
}
