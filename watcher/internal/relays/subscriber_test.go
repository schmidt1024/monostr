package relays

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/relays/relaytest"
)

// publishExternal sends ev through a second pool, like a separate sender
// would. It deliberately never calls Relay.Close on a bare RelayConnect:
// go-nostr's Relay.close races with its own read loop under -race. The
// pool's connections end with the test's cancellable ctx.
func publishExternal(t *testing.T, ctx context.Context, url string, ev nostr.Event) {
	t.Helper()
	p2 := NewPool(ctx)
	if ok, errs := p2.Publish(ctx, []string{url}, ev); ok != 1 {
		t.Fatalf("external publish: %v", errs)
	}
}

// settle gives the relay time to register the live listener: khatru sends
// stored events before it adds the subscription to its listener table, so a
// publish right after the first stored event could be missed by the test.
func settle() { time.Sleep(300 * time.Millisecond) }

func waitFor(t *testing.T, out <-chan *nostr.Event, id string, what ...string) {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case ev := <-out:
			if ev.ID == id {
				return
			}
		case <-deadline:
			t.Fatalf("event %s not received %v", id, what)
		}
	}
}

func TestSubscriberReceivesLiveAndStoredEvents(t *testing.T) {
	url1, _ := relaytest.Start(t)
	url2, _ := relaytest.Start(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	p := NewPool(ctx)
	sk := nostr.GeneratePrivateKey()
	since := nostr.Timestamp(time.Now().Unix() - 3600)

	stored := signed(t, sk, 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	p.Publish(ctx, []string{url1}, stored)

	out := make(chan *nostr.Event, 16)
	sub := NewSubscriber(p, nostr.Filter{Kinds: []int{9738}, Since: &since}, out)
	defer sub.Close()
	sub.Reconcile([]string{url1})
	waitFor(t, out, stored.ID, "stored")
	settle()

	live := signed(t, sk, 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	publishExternal(t, ctx, url1, live)
	waitFor(t, out, live.ID, "live")

	// add a relay: events from it arrive; remove the first: its events stop
	sub.Reconcile([]string{url2})
	settle()
	onTwo := signed(t, sk, 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	publishExternal(t, ctx, url2, onTwo)
	waitFor(t, out, onTwo.ID, "onTwo")
	onOne := signed(t, sk, 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	publishExternal(t, ctx, url1, onOne)
	select {
	case ev := <-out:
		if ev.ID == onOne.ID {
			t.Fatal("received from removed relay")
		}
	case <-time.After(500 * time.Millisecond):
	}
	// other kinds are filtered by the relay
	other := signed(t, sk, 1, nil)
	publishExternal(t, ctx, url2, other)
	select {
	case ev := <-out:
		if ev.ID == other.ID {
			t.Fatal("received wrong kind")
		}
	case <-time.After(300 * time.Millisecond):
	}
}

func TestSubscriberSurvivesUnreachableRelay(t *testing.T) {
	url, _ := relaytest.Start(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	p := NewPool(ctx)
	out := make(chan *nostr.Event, 4)
	since := nostr.Timestamp(0)
	sub := NewSubscriber(p, nostr.Filter{Kinds: []int{9738}, Since: &since}, out)
	defer sub.Close()
	sub.Reconcile([]string{"ws://127.0.0.1:1", url})
	settle()
	ev := signed(t, nostr.GeneratePrivateKey(), 9738, nostr.Tags{{"p", strings.Repeat("c", 64)}})
	publishExternal(t, ctx, url, ev)
	waitFor(t, out, ev.ID)
}
