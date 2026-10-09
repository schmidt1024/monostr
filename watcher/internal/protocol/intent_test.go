package protocol

import (
	"errors"
	"strconv"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

const now int64 = 1700000000

func validIntentEvent(t *testing.T, mutate func(ev *nostr.Event)) *nostr.Event {
	t.Helper()
	sk := nostr.GeneratePrivateKey()
	ev := &nostr.Event{
		Kind:      KindTipIntent,
		CreatedAt: nostr.Timestamp(now),
		Content:   "thanks",
		Tags: nostr.Tags{
			{"e", strings.Repeat("b", 64)},
			{"p", strings.Repeat("c", 64)},
			{"amount", "5000000000"},
			{"pid", "0123456789abcdef"},
			{"type", "like"},
			{"expiration", strconv.FormatInt(now+86400, 10)},
		},
	}
	if mutate != nil {
		mutate(ev)
	}
	if err := ev.Sign(sk); err != nil {
		t.Fatal(err)
	}
	return ev
}

func TestParseIntentAccepts(t *testing.T) {
	ev := validIntentEvent(t, nil)
	in, err := ParseIntent(ev, now+10)
	if err != nil {
		t.Fatal(err)
	}
	if in.ID != ev.ID || in.Sender != ev.PubKey || in.Recipient != strings.Repeat("c", 64) ||
		in.NoteID != strings.Repeat("b", 64) || in.PID != "0123456789abcdef" || in.Amount != 5000000000 ||
		in.Type != "like" || in.CreatedAt != now || in.Expiration != now+86400 {
		t.Fatalf("unexpected intent %+v", in)
	}
}

func TestParseIntentProfileTipHasNoNote(t *testing.T) {
	ev := validIntentEvent(t, func(ev *nostr.Event) {
		// FilterOut matches tag names by prefix and would drop "expiration" too
		var kept nostr.Tags
		for _, tag := range ev.Tags {
			if tag[0] != "e" {
				kept = append(kept, tag)
			}
		}
		ev.Tags = kept
	})
	in, err := ParseIntent(ev, now+10)
	if err != nil {
		t.Fatal(err)
	}
	if in.NoteID != "" || in.Anon || in.Recipient != strings.Repeat("c", 64) {
		t.Fatalf("unexpected intent %+v", in)
	}
}

func TestParseIntentAnonAndUnknownTags(t *testing.T) {
	if in, err := ParseIntent(validIntentEvent(t, nil), now+10); err != nil || in.Anon {
		t.Fatalf("plain intent: %+v %v", in, err)
	}
	for name, tag := range map[string]nostr.Tag{"bare": {"anon"}, "with value": {"anon", "1"}} {
		ev := validIntentEvent(t, func(ev *nostr.Event) {
			ev.Tags = append(ev.Tags, tag, nostr.Tag{"option", "2"}) // unknown tags are ignored
		})
		in, err := ParseIntent(ev, now+10)
		if err != nil || !in.Anon || in.NoteID != strings.Repeat("b", 64) {
			t.Fatalf("%s: %+v %v", name, in, err)
		}
	}
}

func TestParseIntentRejectsMalformed(t *testing.T) {
	set := func(key, value string) func(*nostr.Event) {
		return func(ev *nostr.Event) {
			for i, tag := range ev.Tags {
				if tag[0] == key {
					ev.Tags[i] = nostr.Tag{key, value}
				}
			}
		}
	}
	drop := func(key string) func(*nostr.Event) {
		return func(ev *nostr.Event) { ev.Tags = ev.Tags.FilterOut([]string{key}) }
	}
	cases := map[string]func(*nostr.Event){
		"wrong kind":            func(ev *nostr.Event) { ev.Kind = 1 },
		"amount zero":           set("amount", "0"),
		"amount negative":       set("amount", "-5"),
		"amount float":          set("amount", "1.5"),
		"amount overflow":       set("amount", "99999999999999999999"),
		"amount text":           set("amount", "five"),
		"pid uppercase":         set("pid", "0123456789ABCDEF"),
		"pid short":             set("pid", "0123456789abcde"),
		"note id short":         set("e", "b"),
		"recipient uppercase":   set("p", strings.Repeat("C", 64)),
		"type unknown":          set("type", "zap"),
		"expiration missing":    drop("expiration"),
		"expiration text":       set("expiration", "soon"),
		"expiration past":       set("expiration", strconv.FormatInt(now-1, 10)),
		"expiration huge":       set("expiration", "99999999999999999999"),
		"expiration too far":    set("expiration", strconv.FormatInt(now+MaxIntentTTL+1, 10)),
		"e empty":               set("e", ""),
		"e without value":       func(ev *nostr.Event) { ev.Tags[0] = nostr.Tag{"e"} },
		"p missing":             drop("p"),
		"amount missing":        drop("amount"),
		"pid missing":           drop("pid"),
		"type missing":          drop("type"),
		"created_at far future": func(ev *nostr.Event) { ev.CreatedAt = nostr.Timestamp(now + 10 + MaxFutureSkew + 1) },
		"short tag":             func(ev *nostr.Event) { ev.Tags[2] = nostr.Tag{"amount"} },
	}
	for name, mutate := range cases {
		ev := validIntentEvent(t, mutate)
		if _, err := ParseIntent(ev, now+10); !errors.Is(err, ErrInvalidIntent) {
			t.Errorf("%s: want ErrInvalidIntent, got %v", name, err)
		}
	}
}

func TestParseIntentAcceptsEveryType(t *testing.T) {
	for _, typ := range []string{"like", "boost", "tip"} {
		ev := validIntentEvent(t, func(ev *nostr.Event) {
			ev.Tags = ev.Tags.FilterOut([]string{"type"})
			ev.Tags = append(ev.Tags, nostr.Tag{"type", typ})
		})
		in, err := ParseIntent(ev, now+10)
		if err != nil {
			t.Fatalf("%s: %v", typ, err)
		}
		if in.Type != typ {
			t.Errorf("%s: got type %q", typ, in.Type)
		}
	}
}

func TestParseIntentRejectsBadSignatureAndID(t *testing.T) {
	ev := validIntentEvent(t, nil)
	ev.Content = "tampered"
	if _, err := ParseIntent(ev, now+10); !errors.Is(err, ErrInvalidIntent) {
		t.Fatalf("tampered content: %v", err)
	}
	ev = validIntentEvent(t, nil)
	ev.Sig = strings.Repeat("0", 128)
	if _, err := ParseIntent(ev, now+10); !errors.Is(err, ErrInvalidIntent) {
		t.Fatalf("bad sig: %v", err)
	}
	ev = validIntentEvent(t, nil)
	ev.ID = strings.Repeat("0", 64)
	if _, err := ParseIntent(ev, now+10); !errors.Is(err, ErrInvalidIntent) {
		t.Fatalf("bad id: %v", err)
	}
}

func TestParseIntentDuplicateTagsUseFirst(t *testing.T) {
	ev := validIntentEvent(t, func(ev *nostr.Event) {
		ev.Tags = append(ev.Tags, nostr.Tag{"amount", "1"})
	})
	in, err := ParseIntent(ev, now+10)
	if err != nil || in.Amount != 5000000000 {
		t.Fatalf("got %+v %v", in, err)
	}
}

func TestParseIntentAcceptsMaxTTL(t *testing.T) {
	ev := validIntentEvent(t, func(ev *nostr.Event) {
		for i, tag := range ev.Tags {
			if tag[0] == "expiration" {
				ev.Tags[i] = nostr.Tag{"expiration", strconv.FormatInt(now+MaxIntentTTL, 10)}
			}
		}
	})
	in, err := ParseIntent(ev, now+10)
	if err != nil || in.Expiration != now+MaxIntentTTL {
		t.Fatalf("created_at + 7d must be accepted: %+v %v", in, err)
	}
}
