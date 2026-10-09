package nip98

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

const (
	testURL = "https://watcher.example/v1/accounts"
	now     = int64(1700000000)
)

func TestBuildAndVerifyRoundTrip(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	pk, _ := nostr.GetPublicKey(sk)
	body := []byte(`{"address":"4","view_key":"c"}`)
	header, err := Build(sk, testURL, "post", body, now)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(header, "Nostr ") {
		t.Fatalf("scheme: %q", header[:10])
	}
	got, err := Verify(header, testURL, "POST", body, now+5)
	if err != nil || got != pk {
		t.Fatalf("verify: %q %v", got, err)
	}
	// no body: no payload tag, still valid
	header, _ = Build(sk, testURL, "DELETE", nil, now)
	if _, err := Verify(header, testURL, "DELETE", nil, now); err != nil {
		t.Fatal(err)
	}
}

func TestVerifyRejects(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	body := []byte(`{"a":1}`)
	good, _ := Build(sk, testURL, "POST", body, now)

	cases := []struct {
		name   string
		header string
		url    string
		method string
		body   []byte
		now    int64
		want   error
	}{
		{"missing", "", testURL, "POST", body, now, ErrMissing},
		{"basic scheme", "Basic abc", testURL, "POST", body, now, ErrMissing},
		{"bad base64", "Nostr @@@", testURL, "POST", body, now, ErrInvalid},
		{"not json", "Nostr " + base64.StdEncoding.EncodeToString([]byte("nope")), testURL, "POST", body, now, ErrInvalid},
		{"other method", good, testURL, "DELETE", body, now, ErrInvalid},
		{"other path", good, "https://watcher.example/v1/info", "POST", body, now, ErrInvalid},
		{"other host", good, "https://evil.example/v1/accounts", "POST", body, now, ErrInvalid},
		{"other body", good, testURL, "POST", []byte(`{"a":2}`), now, ErrInvalid},
		{"body missing", good, testURL, "POST", nil, now, ErrInvalid},
		{"too old", good, testURL, "POST", body, now + MaxSkew + 1, ErrInvalid},
		{"too new", good, testURL, "POST", body, now - MaxSkew - 1, ErrInvalid},
	}
	for _, c := range cases {
		_, err := Verify(c.header, c.url, c.method, c.body, c.now)
		if !errors.Is(err, c.want) {
			t.Errorf("%s: want %v, got %v", c.name, c.want, err)
		}
	}
}

func TestVerifyRejectsWrongKindAndBadSignature(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	ev := nostr.Event{Kind: 1, CreatedAt: nostr.Timestamp(now), Tags: nostr.Tags{{"u", testURL}, {"method", "DELETE"}}}
	_ = ev.Sign(sk)
	h := "Nostr " + base64.StdEncoding.EncodeToString([]byte(ev.String()))
	if _, err := Verify(h, testURL, "DELETE", nil, now); !errors.Is(err, ErrInvalid) {
		t.Fatalf("wrong kind: %v", err)
	}
	ev = nostr.Event{Kind: 27235, CreatedAt: nostr.Timestamp(now), Tags: nostr.Tags{{"u", testURL}, {"method", "DELETE"}}}
	_ = ev.Sign(sk)
	ev.Content = "tampered"
	h = "Nostr " + base64.StdEncoding.EncodeToString([]byte(ev.String()))
	if _, err := Verify(h, testURL, "DELETE", nil, now); !errors.Is(err, ErrInvalid) {
		t.Fatalf("tampered: %v", err)
	}
}

func TestVerifyPayloadTagWithoutBodyIsRejected(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	h, _ := Build(sk, testURL, "DELETE", []byte("x"), now)
	if _, err := Verify(h, testURL, "DELETE", nil, now); !errors.Is(err, ErrInvalid) {
		t.Fatalf("payload without body: %v", err)
	}
}

func TestVerifyRejectsForgedID(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	ev := nostr.Event{Kind: 27235, CreatedAt: nostr.Timestamp(now), Tags: nostr.Tags{{"u", testURL}, {"method", "DELETE"}}}
	if err := ev.Sign(sk); err != nil {
		t.Fatal(err)
	}
	// signature stays valid for the body; only the id field is swapped
	ev.ID = strings.Repeat("0", 64)
	h := "Nostr " + base64.StdEncoding.EncodeToString([]byte(ev.String()))
	if _, err := Verify(h, testURL, "DELETE", nil, now); !errors.Is(err, ErrInvalid) {
		t.Fatalf("forged id accepted: %v", err)
	}
}
