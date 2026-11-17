package relays

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"syscall"
	"time"

	"monostr.com/watcher/internal/protocol"
)

// ErrNonPublicAddress is returned when a dial to a host that is not
// configured would reach a loopback, private, link-local, CGNAT or other
// non-public address.
var ErrNonPublicAddress = errors.New("refusing to dial a non-public address")

// TrustedHosts returns the lower-cased host names of urls (entries that do
// not parse are skipped); a host:port or bare host is accepted as well.
func TrustedHosts(urls ...string) []string {
	var out []string
	for _, u := range urls {
		if u == "" {
			continue
		}
		host := ""
		if p, err := url.Parse(u); err == nil && p.Host != "" {
			host = p.Hostname()
		} else if h, _, err := net.SplitHostPort(u); err == nil {
			host = h
		} else {
			host = u
		}
		if host != "" {
			out = append(out, strings.ToLower(host))
		}
	}
	return out
}

// GuardTransport makes t refuse connections to non-public addresses unless
// the host being dialled is one of trusted (the operator's configured relays,
// nodes, LWS, monerod, proxy). The check runs on the resolved address at
// connect time (net.Dialer.Control), so a public name that resolves to an
// internal address, a DNS rebind or a redirect to an internal host is
// refused as well. go-nostr dials relays through http.DefaultClient, so
// guarding http.DefaultTransport covers every relay a recipient's NIP-65
// list adds.
func GuardTransport(t *http.Transport, trusted []string) {
	allowed := map[string]bool{}
	for _, h := range trusted {
		allowed[strings.ToLower(h)] = true
	}
	plain := t.DialContext
	if plain == nil {
		d := &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second}
		plain = d.DialContext
	}
	strict := &net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second, Control: publicOnly}
	t.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, _, err := net.SplitHostPort(addr)
		if err != nil {
			host = addr
		}
		if allowed[strings.ToLower(host)] {
			return plain(ctx, network, addr)
		}
		return strict.DialContext(ctx, network, addr)
	}
}

// GuardDefaultTransport applies GuardTransport to http.DefaultTransport.
func GuardDefaultTransport(trusted []string) error {
	t, ok := http.DefaultTransport.(*http.Transport)
	if !ok {
		return errors.New("http.DefaultTransport is not an *http.Transport")
	}
	GuardTransport(t, trusted)
	return nil
}

func publicOnly(_, address string, _ syscall.RawConn) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("%w: %s", ErrNonPublicAddress, address)
	}
	if ip := net.ParseIP(host); ip == nil || !protocol.PublicIP(ip) {
		return fmt.Errorf("%w: %s", ErrNonPublicAddress, host)
	}
	return nil
}
