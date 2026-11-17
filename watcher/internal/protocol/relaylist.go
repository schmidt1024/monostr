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
// trailing slashes trimmed. A tag without a marker counts for both read and
// write. The list is foreign input, so only wss:// URLs with a public-looking
// host are kept (see publicRelayURL; relays.GuardTransport checks the
// resolved address again at dial time), and at most MaxRelaysPerList read
// and MaxRelaysPerList write relays are kept (first wins).
func ParseRelayList(ev *nostr.Event) (read, write []string) {
	if ev == nil || ev.Kind != KindRelayList {
		return nil, nil
	}
	for _, tag := range ev.Tags {
		if len(tag) < 2 || tag[0] != "r" {
			continue
		}
		u := strings.TrimRight(strings.ToLower(strings.TrimSpace(tag[1])), "/")
		if !strings.HasPrefix(u, "wss://") {
			continue // plain ws:// is neither private nor needed for a public relay
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

// publicRelayURL reports whether u may be dialled on a recipient's behalf:
// no userinfo; an IP literal must be public; a name must have at least two
// labels (single-label names resolve to compose services and LAN hosts) and
// must not end in a local-only suffix.
func publicRelayURL(u string) bool {
	parsed, err := url.Parse(u)
	if err != nil || parsed.User != nil {
		return false
	}
	host := strings.TrimSuffix(parsed.Hostname(), ".")
	if host == "" {
		return false
	}
	if ip := net.ParseIP(host); ip != nil {
		return PublicIP(ip)
	}
	if !strings.Contains(host, ".") {
		return false
	}
	for _, suffix := range []string{".localhost", ".local", ".internal", ".lan", ".home.arpa", ".localdomain"} {
		if strings.HasSuffix(host, suffix) {
			return false
		}
	}
	return true
}

// cgnat is the shared address space of RFC 6598 (carrier-grade NAT, also
// used by some cloud metadata and VPN setups).
var cgnat = &net.IPNet{IP: net.IPv4(100, 64, 0, 0).To4(), Mask: net.CIDRMask(10, 32)}

// thisNetwork is 0.0.0.0/8 ("this host on this network").
var thisNetwork = &net.IPNet{IP: net.IPv4(0, 0, 0, 0).To4(), Mask: net.CIDRMask(8, 32)}

// PublicIP reports whether ip is a globally routable unicast address:
// not loopback, private (incl. IPv6 ULA fc00::/7), link-local, CGNAT
// 100.64.0.0/10, 0.0.0.0/8, unspecified or multicast. IPv4-mapped IPv6
// addresses are judged as IPv4.
func PublicIP(ip net.IP) bool {
	if v4 := ip.To4(); v4 != nil {
		ip = v4
	}
	switch {
	case ip.IsLoopback(), ip.IsPrivate(), ip.IsLinkLocalUnicast(), ip.IsLinkLocalMulticast(),
		ip.IsInterfaceLocalMulticast(), ip.IsMulticast(), ip.IsUnspecified():
		return false
	case cgnat.Contains(ip), thisNetwork.Contains(ip):
		return false
	}
	return true
}
