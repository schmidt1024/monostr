package protocol

import (
	"reflect"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

func TestParseRelayList(t *testing.T) {
	ev := &nostr.Event{Kind: KindRelayList, Tags: nostr.Tags{
		{"r", "wss://both.example"},
		{"r", "wss://read.example", "read"},
		{"r", "wss://write.example/", "write"},
		{"r", "WSS://Upper.Example"},
		{"r", "http://not-a-relay.example"},
		{"r"},
		{"x", "wss://ignored.example"},
	}}
	read, write := ParseRelayList(ev)
	wantRead := []string{"wss://both.example", "wss://read.example", "wss://upper.example"}
	wantWrite := []string{"wss://both.example", "wss://write.example", "wss://upper.example"}
	if !reflect.DeepEqual(read, wantRead) || !reflect.DeepEqual(write, wantWrite) {
		t.Fatalf("read %v write %v", read, write)
	}
	if r, w := ParseRelayList(&nostr.Event{Kind: 1}); r != nil || w != nil {
		t.Fatal("wrong kind should yield nil")
	}
}

// TestParseRelayListCapsAndFilters is finding I3: a hostile NIP-65 list
// must not make the watcher connect to many relays or to internal hosts.
func TestParseRelayListCapsAndFilters(t *testing.T) {
	ev := &nostr.Event{Kind: KindRelayList, Tags: nostr.Tags{
		{"r", "ws://localhost:7777"},
		{"r", "ws://127.0.0.1:7777"},
		{"r", "ws://10.0.0.5"},
		{"r", "ws://192.168.1.1"},
		{"r", "ws://169.254.169.254"},
		{"r", "ws://[::1]:7777"},
		{"r", "ws://0.0.0.0"},
		{"r", "wss://user:pass@evil.example"},
		{"r", "wss://"},
		{"r", "wss://r1.example"},
		{"r", "wss://r2.example"},
		{"r", "wss://r3.example"},
		{"r", "wss://r4.example"},
		{"r", "wss://r5.example"},
		{"r", "wss://r6.example"},
		{"r", "wss://r7.example"},
		{"r", "wss://w1.example", "write"},
	}}
	read, write := ParseRelayList(ev)
	wantRead := []string{"wss://r1.example", "wss://r2.example", "wss://r3.example", "wss://r4.example", "wss://r5.example"}
	wantWrite := wantRead
	if !reflect.DeepEqual(read, wantRead) || !reflect.DeepEqual(write, wantWrite) {
		t.Fatalf("read %v write %v", read, write)
	}
	// the write cap is independent of the read cap
	ev = &nostr.Event{Kind: KindRelayList, Tags: nostr.Tags{
		{"r", "wss://r1.example", "read"},
		{"r", "wss://w1.example", "write"},
		{"r", "wss://8.8.8.8", "write"},
	}}
	read, write = ParseRelayList(ev)
	if !reflect.DeepEqual(read, []string{"wss://r1.example"}) || !reflect.DeepEqual(write, []string{"wss://w1.example", "wss://8.8.8.8"}) {
		t.Fatalf("read %v write %v", read, write)
	}
	if MaxRelaysPerList != 5 {
		t.Fatalf("MaxRelaysPerList %d", MaxRelaysPerList)
	}
}
