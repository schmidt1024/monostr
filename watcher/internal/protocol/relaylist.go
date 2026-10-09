package protocol

import (
	"net"
	"net/url"
	"strings"

	"github.com/nbd-wtf/go-nostr"
)

// MaxRelaysPerList caps the read and the write relays taken from one list.
const MaxRelaysPerList = 5

// ParseRelayList reads a NIP-65 kind 10002 event. URLs are lower-cased,
// trailing slashes trimmed, non-websocket URLs dropped. A tag without a
// marker counts for both read and write. The list is foreign input, so
// URLs with userinfo or pointing at localhost or loopback, private,
// link-local or unspecified IP literals are dropped, and at most
// MaxRelaysPerList read and MaxRelaysPerList write relays are kept (first
// wins).
func ParseRelayList(ev *nostr.Event) (read, write []string) {
	if ev == nil || ev.Kind != KindRelayList {
		return nil, nil
	}
	for _, tag := range ev.Tags {
		if len(tag) < 2 || tag[0] != "r" {
			continue
		}
		u := strings.TrimRight(strings.ToLower(strings.TrimSpace(tag[1])), "/")
		if !strings.HasPrefix(u, "wss://") && !strings.HasPrefix(u, "ws://") {
			continue
		}
		if !publicRelayURL(u) {
			continue
		}
		marker := ""
		if len(tag) >= 3 {
			marker = tag[2]
		}
		if (marker == "" || marker == "read") && len(read) < MaxRelaysPerList {
			read = append(read, u)
		}
		if (marker == "" || marker == "write") && len(write) < MaxRelaysPerList {
			write = append(write, u)
		}
	}
	return read, write
}

// publicRelayURL reports whether u may be dialled on a recipient's behalf.
func publicRelayURL(u string) bool {
	parsed, err := url.Parse(u)
	if err != nil || parsed.User != nil {
		return false
	}
	host := parsed.Hostname()
	if host == "" || host == "localhost" || strings.HasSuffix(host, ".localhost") {
		return false
	}
	if ip := net.ParseIP(host); ip != nil {
		if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
			return false
		}
	}
	return true
}
