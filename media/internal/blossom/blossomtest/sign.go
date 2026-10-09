// Package blossomtest builds signed authorization headers for tests.
package blossomtest

import (
	"encoding/base64"
	"encoding/json"
	"strconv"

	"github.com/nbd-wtf/go-nostr"
)

// Event is a signed kind 24242 event with the given tags, created at createdAt.
func Event(secretKey string, createdAt int64, tags nostr.Tags) nostr.Event {
	ev := nostr.Event{Kind: 24242, CreatedAt: nostr.Timestamp(createdAt), Content: "Upload", Tags: tags}
	if err := ev.Sign(secretKey); err != nil {
		panic(err)
	}
	return ev
}

// Header encodes an event as an Authorization header value in standard padded base64.
func Header(ev nostr.Event) string {
	raw, err := json.Marshal(ev)
	if err != nil {
		panic(err)
	}
	return "Nostr " + base64.StdEncoding.EncodeToString(raw)
}

// Auth is the header a well-behaved client sends: action, one hash, five minutes of life.
func Auth(secretKey, action, hash string, now int64) string {
	return Header(Event(secretKey, now, nostr.Tags{
		{"t", action}, {"x", hash}, {"expiration", strconv.FormatInt(now+300, 10)},
	}))
}
