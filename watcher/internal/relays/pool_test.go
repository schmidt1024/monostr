package relays

import (
	"context"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/relays/relaytest"
)

var nonce atomic.Int64

// signed returns a signed event with a unique content: two events built in
// the same second with identical fields would share an ID, and a relay
// silently treats the second one as a duplicate (accepted, never broadcast).
func signed(t *testing.T, sk string, kind int, tags nostr.Tags) nostr.Event {
	t.Helper()
	ev := nostr.Event{Kind: kind, CreatedAt: nostr.Now(), Tags: tags, Content: fmt.Sprintf("n%d", nonce.Add(1))}
	if err := ev.Sign(sk); err != nil {
		t.Fatal(err)
	}
	return ev
}

func TestPublishReportsPerRelay(t *testing.T) {
	url1, store := relaytest.Start(t)
	url2, _ := relaytest.Start(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	p := NewPool(ctx)
	ev := signed(t, nostr.GeneratePrivateKey(), 9739, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	ok, errs := p.Publish(ctx, []string{url1, url2, "ws://127.0.0.1:1"}, ev)
	if ok != 2 || len(errs) != 1 {
		t.Fatalf("ok %d errs %v", ok, errs)
	}
	ch, _ := store.QueryEvents(ctx, nostr.Filter{IDs: []string{ev.ID}})
	if got := <-ch; got == nil || got.ID != ev.ID {
		t.Fatal("event not stored on relay 1")
	}
	// republishing the same event is not an error
	if ok, _ := p.Publish(ctx, []string{url1}, ev); ok != 1 {
		t.Fatalf("republish ok %d", ok)
	}
}

func TestFetchRelayList(t *testing.T) {
	url, _ := relaytest.Start(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	p := NewPool(ctx)
	sk := nostr.GeneratePrivateKey()
	pk, _ := nostr.GetPublicKey(sk)
	list := signed(t, sk, protocol.KindRelayList, nostr.Tags{{"r", "wss://both.example"}, {"r", "wss://w.example", "write"}, {"r", "wss://r.example", "read"}})
	if ok, _ := p.Publish(ctx, []string{url}, list); ok != 1 {
		t.Fatal("publish list")
	}
	read, write, found := p.FetchRelayList(ctx, []string{url}, pk)
	if !found || len(read) != 2 || len(write) != 2 || write[1] != "wss://w.example" || read[1] != "wss://r.example" {
		t.Fatalf("found %v read %v write %v", found, read, write)
	}
	if _, _, found := p.FetchRelayList(ctx, []string{url}, strings.Repeat("0", 64)); found {
		t.Fatal("unknown pubkey found")
	}
	if _, _, found := p.FetchRelayList(ctx, []string{"ws://127.0.0.1:1"}, pk); found {
		t.Fatal("dead relay found something")
	}
}

func TestFetchReturnsStoredEventsAcrossRelays(t *testing.T) {
	url1, _ := relaytest.Start(t)
	url2, _ := relaytest.Start(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	p := NewPool(ctx)
	sk := nostr.GeneratePrivateKey()

	evA := signed(t, sk, 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	evB := signed(t, sk, 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	other := signed(t, sk, 1, nil)
	if ok, _ := p.Publish(ctx, []string{url1}, evA); ok != 1 {
		t.Fatal("publish evA to relay1")
	}
	if ok, _ := p.Publish(ctx, []string{url1}, evB); ok != 1 {
		t.Fatal("publish evB to relay1")
	}
	if ok, _ := p.Publish(ctx, []string{url2}, evA); ok != 1 {
		t.Fatal("publish evA to relay2")
	}
	if ok, _ := p.Publish(ctx, []string{url1}, other); ok != 1 {
		t.Fatal("publish other to relay1")
	}

	since := nostr.Timestamp(time.Now().Unix() - 3600)
	got := p.Fetch(ctx, []string{url1, url2, "ws://127.0.0.1:1"}, nostr.Filter{Kinds: []int{9738}, Since: &since})
	if len(got) != 2 {
		t.Fatalf("got %d events, want 2: %v", len(got), got)
	}
	ids := map[string]bool{}
	for _, ev := range got {
		ids[ev.ID] = true
	}
	if !ids[evA.ID] || !ids[evB.ID] {
		t.Fatalf("missing expected ids: %v", ids)
	}

	future := nostr.Timestamp(time.Now().Unix() + 3600)
	if got := p.Fetch(ctx, []string{url1, url2}, nostr.Filter{Kinds: []int{9738}, Since: &future}); len(got) != 0 {
		t.Fatalf("future since returned %d events", len(got))
	}
}
