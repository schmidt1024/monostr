// Package blossom reads and checks Blossom authorization events
// (kind 24242, BUD-11), sent as "Authorization: Nostr <base64(event)>".
package blossom

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/nbd-wtf/go-nostr"
)

const (
	// KindAuth is the Blossom authorization event kind.
	KindAuth = 24242
	// MaxFuture is how far created_at may lie ahead of the server clock, in seconds.
	MaxFuture int64 = 60

	ActionUpload = "upload"
	ActionDelete = "delete"

	scheme = "nostr "
)

var (
	ErrMissing = errors.New("blossom: missing authorization")
	ErrInvalid = errors.New("blossom: invalid authorization")
)

// Auth is a verified authorization: who signed it and which blobs it covers.
type Auth struct {
	Pubkey string
	Hashes []string
}

// Covers reports whether the authorization names the blob hash.
func (a Auth) Covers(hash string) bool {
	for _, h := range a.Hashes {
		if h == hash {
			return true
		}
	}
	return false
}

func invalid(reason string) error { return fmt.Errorf("%w: %s", ErrInvalid, reason) }

// IsHex64 reports whether s is 64 lowercase hex characters.
func IsHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

// decode accepts standard base64 and base64url, each with or without padding:
// the specification asks for base64url without padding, deployed clients
// (Amethyst, Primal) send padded standard base64.
func decode(token string) ([]byte, error) {
	token = strings.TrimRight(strings.TrimSpace(token), "=")
	token = strings.NewReplacer("-", "+", "_", "/").Replace(token)
	return base64.RawStdEncoding.DecodeString(token)
}

// Verify checks the header for one action ("upload" or "delete") on this
// server (host is the bare lowercase domain) at time now (unix seconds).
func Verify(header, action, host string, now int64) (Auth, error) {
	if len(header) < len(scheme) || !strings.EqualFold(header[:len(scheme)], scheme) {
		return Auth{}, ErrMissing
	}
	raw, err := decode(header[len(scheme):])
	if err != nil {
		return Auth{}, invalid("base64")
	}
	var ev nostr.Event
	if err := json.Unmarshal(raw, &ev); err != nil {
		return Auth{}, invalid("json")
	}
	if ev.Kind != KindAuth {
		return Auth{}, invalid("kind")
	}
	if !IsHex64(ev.PubKey) || !ev.CheckID() {
		return Auth{}, invalid("id")
	}
	if ok, err := ev.CheckSignature(); err != nil || !ok {
		return Auth{}, invalid("signature")
	}
	if int64(ev.CreatedAt) > now+MaxFuture {
		return Auth{}, invalid("created_at in the future")
	}
	var (
		hashes     []string
		servers    []string
		actions    []string
		expiration int64 = -1
	)
	for _, tag := range ev.Tags {
		if len(tag) < 2 {
			continue
		}
		switch tag[0] {
		case "t":
			actions = append(actions, tag[1])
		case "x":
			if IsHex64(tag[1]) {
				hashes = append(hashes, tag[1])
			}
		case "server":
			servers = append(servers, strings.ToLower(tag[1]))
		case "expiration":
			// Primal sends milliseconds; such a value is simply far in the future
			if v, err := strconv.ParseInt(tag[1], 10, 64); err == nil {
				expiration = v
			}
		}
	}
	if expiration <= now {
		return Auth{}, invalid("expired or without expiration")
	}
	if len(actions) != 1 || actions[0] != action {
		return Auth{}, invalid("t tag")
	}
	if len(hashes) == 0 {
		return Auth{}, invalid("x tag")
	}
	if len(servers) > 0 {
		ok := false
		for _, s := range servers {
			if s == host {
				ok = true
			}
		}
		if !ok {
			return Auth{}, invalid("server tag names another server")
		}
	}
	return Auth{Pubkey: ev.PubKey, Hashes: hashes}, nil
}
