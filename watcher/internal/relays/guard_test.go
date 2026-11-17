package relays

import (
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

func TestGuardTransportRefusesUntrustedInternalHosts(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {}))
	defer srv.Close()
	_, port, _ := net.SplitHostPort(srv.Listener.Addr().String())
	get := func(trusted []string, url string) error {
		tr := &http.Transport{}
		GuardTransport(tr, trusted)
		resp, err := (&http.Client{Transport: tr}).Get(url)
		if err == nil {
			resp.Body.Close()
		}
		return err
	}
	// a user-supplied host that resolves to loopback
	if err := get(nil, "http://localhost:"+port); !errors.Is(err, ErrNonPublicAddress) {
		t.Fatalf("untrusted loopback name: %v", err)
	}
	if err := get(nil, srv.URL); !errors.Is(err, ErrNonPublicAddress) {
		t.Fatalf("untrusted loopback literal: %v", err)
	}
	// configured hosts stay reachable
	if err := get(TrustedHosts(srv.URL), srv.URL); err != nil {
		t.Fatalf("trusted host refused: %v", err)
	}
	if err := get(TrustedHosts("ws://LOCALHOST:7777"), "http://localhost:"+port); err != nil {
		t.Fatalf("trusted name refused: %v", err)
	}
}

func TestPublicOnlyAllowsPublicAddresses(t *testing.T) {
	if err := publicOnly("tcp", "93.184.216.34:443", nil); err != nil {
		t.Fatal(err)
	}
	for _, addr := range []string{"127.0.0.1:1", "10.0.0.1:1", "100.64.0.1:1", "169.254.169.254:80", "[fd00::1]:1", "[::1]:1", "0.0.0.0:1"} {
		if err := publicOnly("tcp", addr, nil); err == nil {
			t.Errorf("%s allowed", addr)
		}
	}
}

func TestTrustedHosts(t *testing.T) {
	got := TrustedHosts("wss://Relay.Example", "http://lws:8443/admin", "socks5://tor:9050", "monerod:18089", "")
	if want := []string{"relay.example", "lws", "tor", "monerod"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("%v", got)
	}
}
