package blossom_test

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/media/internal/blossom"
	"monostr.com/media/internal/blossom/blossomtest"
)

const (
	host = "media.example"
	now  = int64(1_800_000_000)
)

var hash = strings.Repeat("ab", 32)

func keys(t *testing.T) (sk, pk string) {
	t.Helper()
	sk = nostr.GeneratePrivateKey()
	pk, err := nostr.GetPublicKey(sk)
	if err != nil {
		t.Fatal(err)
	}
	return sk, pk
}

func exp(delta int64) nostr.Tag { return nostr.Tag{"expiration", strconv.FormatInt(now+delta, 10)} }

func TestVerifyAcceptsAWellFormedUploadAuthorization(t *testing.T) {
	sk, pk := keys(t)
	auth, err := blossom.Verify(blossomtest.Auth(sk, "upload", hash, now), blossom.ActionUpload, host, now)
	if err != nil {
		t.Fatal(err)
	}
	if auth.Pubkey != pk || !auth.Covers(hash) || auth.Covers(strings.Repeat("cd", 32)) {
		t.Fatalf("auth: %+v", auth)
	}
}

func TestVerifyAcceptsEveryBase64Form(t *testing.T) {
	sk, _ := keys(t)
	// the content makes the JSON contain bytes that differ between the two alphabets
	ev := nostr.Event{Kind: 24242, CreatedAt: nostr.Timestamp(now), Content: "Upload ??>>~~", Tags: nostr.Tags{{"t", "upload"}, {"x", hash}, exp(300)}}
	if err := ev.Sign(sk); err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(ev)
	forms := map[string]string{
		"standard padded":   base64.StdEncoding.EncodeToString(raw),
		"standard unpadded": base64.RawStdEncoding.EncodeToString(raw),
		"url padded":        base64.URLEncoding.EncodeToString(raw),
		"url unpadded":      base64.RawURLEncoding.EncodeToString(raw),
	}
	if forms["standard padded"] == forms["url padded"] {
		t.Fatal("test event does not exercise the alphabet difference")
	}
	for name, token := range forms {
		if _, err := blossom.Verify("Nostr "+token, blossom.ActionUpload, host, now); err != nil {
			t.Errorf("%s: %v", name, err)
		}
	}
	// the scheme is matched without regard to case
	if _, err := blossom.Verify("nostr "+forms["standard padded"], blossom.ActionUpload, host, now); err != nil {
		t.Errorf("lowercase scheme: %v", err)
	}
}

func TestVerifyAcceptsAnExpirationInMilliseconds(t *testing.T) {
	sk, _ := keys(t)
	ev := blossomtest.Event(sk, now, nostr.Tags{{"t", "upload"}, {"x", hash}, {"expiration", strconv.FormatInt((now+300)*1000, 10)}})
	if _, err := blossom.Verify(blossomtest.Header(ev), blossom.ActionUpload, host, now); err != nil {
		t.Fatal(err)
	}
}

func TestVerifyServerTag(t *testing.T) {
	sk, _ := keys(t)
	own := blossomtest.Event(sk, now, nostr.Tags{{"t", "upload"}, {"x", hash}, exp(300), {"server", "other.example"}, {"server", "MEDIA.example"}})
	if _, err := blossom.Verify(blossomtest.Header(own), blossom.ActionUpload, host, now); err != nil {
		t.Fatalf("own server among several: %v", err)
	}
	foreign := blossomtest.Event(sk, now, nostr.Tags{{"t", "upload"}, {"x", hash}, exp(300), {"server", "other.example"}})
	if _, err := blossom.Verify(blossomtest.Header(foreign), blossom.ActionUpload, host, now); !errors.Is(err, blossom.ErrInvalid) {
		t.Fatalf("foreign server: %v", err)
	}
}

func TestVerifyCollectsSeveralHashes(t *testing.T) {
	sk, _ := keys(t)
	other := strings.Repeat("cd", 32)
	ev := blossomtest.Event(sk, now, nostr.Tags{{"t", "delete"}, {"x", hash}, {"x", other}, {"x", "not-a-hash"}, exp(300)})
	auth, err := blossom.Verify(blossomtest.Header(ev), blossom.ActionDelete, host, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(auth.Hashes) != 2 || !auth.Covers(hash) || !auth.Covers(other) {
		t.Fatalf("hashes: %v", auth.Hashes)
	}
}

func TestVerifyRejects(t *testing.T) {
	sk, _ := keys(t)
	good := nostr.Tags{{"t", "upload"}, {"x", hash}, exp(300)}
	signed := func(createdAt int64, tags nostr.Tags) string {
		return blossomtest.Header(blossomtest.Event(sk, createdAt, tags))
	}
	wrongKind := func() string {
		ev := nostr.Event{Kind: 27235, CreatedAt: nostr.Timestamp(now), Tags: good}
		ev.Sign(sk)
		return blossomtest.Header(ev)
	}
	tampered := func() string {
		ev := blossomtest.Event(sk, now, good)
		ev.Tags = nostr.Tags{{"t", "upload"}, {"x", strings.Repeat("cd", 32)}, exp(300)}
		return blossomtest.Header(ev)
	}
	badSig := func() string {
		ev := blossomtest.Event(sk, now, good)
		ev.Sig = strings.Repeat("0", 128)
		return blossomtest.Header(ev)
	}
	cases := map[string]string{
		"not base64":              "Nostr !!!",
		"not json":                "Nostr " + base64.StdEncoding.EncodeToString([]byte("hello")),
		"wrong kind":              wrongKind(),
		"tampered tags":           tampered(),
		"bad signature":           badSig(),
		"created in the future":   signed(now+61, good),
		"expired":                 signed(now-600, nostr.Tags{{"t", "upload"}, {"x", hash}, exp(-1)}),
		"expires exactly now":     signed(now, nostr.Tags{{"t", "upload"}, {"x", hash}, exp(0)}),
		"no expiration":           signed(now, nostr.Tags{{"t", "upload"}, {"x", hash}}),
		"expiration not a number": signed(now, nostr.Tags{{"t", "upload"}, {"x", hash}, {"expiration", "soon"}}),
		"wrong action":            signed(now, nostr.Tags{{"t", "delete"}, {"x", hash}, exp(300)}),
		"two actions":             signed(now, nostr.Tags{{"t", "upload"}, {"t", "delete"}, {"x", hash}, exp(300)}),
		"no action":               signed(now, nostr.Tags{{"x", hash}, exp(300)}),
		"no x":                    signed(now, nostr.Tags{{"t", "upload"}, exp(300)}),
		"x not a hash":            signed(now, nostr.Tags{{"t", "upload"}, {"x", strings.ToUpper(hash)}, exp(300)}),
	}
	for name, header := range cases {
		if _, err := blossom.Verify(header, blossom.ActionUpload, host, now); !errors.Is(err, blossom.ErrInvalid) {
			t.Errorf("%s: got %v, want ErrInvalid", name, err)
		}
	}
	for name, header := range map[string]string{"empty": "", "other scheme": "Bearer abc", "scheme only": "Nostr"} {
		if _, err := blossom.Verify(header, blossom.ActionUpload, host, now); !errors.Is(err, blossom.ErrMissing) {
			t.Errorf("%s: got %v, want ErrMissing", name, err)
		}
	}
}

func TestVerifyAcceptsCreatedAtSlightlyAhead(t *testing.T) {
	sk, _ := keys(t)
	ev := blossomtest.Event(sk, now+60, nostr.Tags{{"t", "upload"}, {"x", hash}, exp(300)})
	if _, err := blossom.Verify(blossomtest.Header(ev), blossom.ActionUpload, host, now); err != nil {
		t.Fatal(err)
	}
}
