// Package nip98 builds and verifies NIP-98 HTTP Auth headers
// ("Authorization: Nostr <base64(event)>").
package nip98

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/protocol"
)

const (
	scheme = "Nostr "
	// MaxSkew is the accepted |now - created_at| in seconds.
	MaxSkew int64 = 60
)

var (
	ErrMissing = errors.New("nip98: missing authorization")
	ErrInvalid = errors.New("nip98: invalid authorization")
)

func payloadHash(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

// Build signs a kind 27235 event for url/method/body and returns the header value.
func Build(secretKey, url, method string, body []byte, createdAt int64) (string, error) {
	ev := nostr.Event{
		Kind:      protocol.KindHTTPAuth,
		CreatedAt: nostr.Timestamp(createdAt),
		Content:   "",
		Tags:      nostr.Tags{{"u", url}, {"method", strings.ToUpper(method)}},
	}
	if len(body) > 0 {
		ev.Tags = append(ev.Tags, nostr.Tag{"payload", payloadHash(body)})
	}
	if err := ev.Sign(secretKey); err != nil {
		return "", err
	}
	raw, err := json.Marshal(ev)
	if err != nil {
		return "", err
	}
	return scheme + base64.StdEncoding.EncodeToString(raw), nil
}

func invalid(reason string) error { return fmt.Errorf("%w: %s", ErrInvalid, reason) }

// Verify checks header against the request (url = public URL incl. path,
// method, raw body) and returns the signing pubkey.
func Verify(header, url, method string, body []byte, now int64) (string, error) {
	if !strings.HasPrefix(header, scheme) {
		return "", ErrMissing
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(header[len(scheme):]))
	if err != nil {
		return "", invalid("base64")
	}
	var ev nostr.Event
	if err := json.Unmarshal(raw, &ev); err != nil {
		return "", invalid("json")
	}
	if ev.Kind != protocol.KindHTTPAuth {
		return "", invalid("kind")
	}
	if !protocol.IsHex64(ev.PubKey) || !ev.CheckID() {
		return "", invalid("id")
	}
	if ok, err := ev.CheckSignature(); err != nil || !ok {
		return "", invalid("signature")
	}
	delta := now - int64(ev.CreatedAt)
	if delta > MaxSkew || delta < -MaxSkew {
		return "", invalid("created_at outside window")
	}
	if u := ev.Tags.Find("u"); u == nil || len(u) < 2 || u[1] != url {
		return "", invalid("u tag")
	}
	if m := ev.Tags.Find("method"); m == nil || len(m) < 2 || !strings.EqualFold(m[1], method) {
		return "", invalid("method tag")
	}
	p := ev.Tags.Find("payload")
	switch {
	case len(body) == 0 && p == nil:
	case len(body) == 0 && p != nil:
		return "", invalid("payload tag without body")
	case p == nil || len(p) < 2 || p[1] != payloadHash(body):
		return "", invalid("payload hash")
	}
	return ev.PubKey, nil
}
