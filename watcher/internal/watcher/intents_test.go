package watcher

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"testing"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/store"
)

func TestHandleIntentStoresAndRegistersWebhook(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	sender := nostr.GeneratePrivateKey()
	ev := intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0-10)
	if err := e.svc.HandleIntent(context.Background(), ev); err != nil {
		t.Fatal(err)
	}
	row, ok, _ := e.st.GetIntent(ev.ID)
	if !ok || row.State != store.StateOpen || row.LWSEventID != "event1" || row.StoredAt != t0 {
		t.Fatalf("row %+v %v", row, ok)
	}
	if len(e.lws.hooks) != 1 || e.lws.hooks[0] != mainnetAddr+" 0123456789abcdef "+ev.ID+" "+hookURL+" 1" {
		t.Fatalf("hooks %v", e.lws.hooks)
	}
	if v, _, _ := e.st.GetMeta(metaLastSeenIntent); v != strconv.FormatInt(t0-10, 10) {
		t.Fatalf("last seen %q", v)
	}
	// older intent does not move last_seen backwards
	e.svc.HandleIntent(context.Background(), intentEvent(t, sender, rpk, "0123456789abcdee", 5, t0-100))
	if v, _, _ := e.st.GetMeta(metaLastSeenIntent); v != strconv.FormatInt(t0-10, 10) {
		t.Fatalf("last seen moved backwards %q", v)
	}
}

func TestHandleIntentIgnores(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	sender := nostr.GeneratePrivateKey()
	ctx := context.Background()
	// unregistered recipient
	other, _ := nostr.GetPublicKey(nostr.GeneratePrivateKey())
	if err := e.svc.HandleIntent(ctx, intentEvent(t, sender, other, "0123456789abcdef", 5, t0)); err != nil {
		t.Fatal(err)
	}
	// invalid (expired)
	if err := e.svc.HandleIntent(ctx, intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0-90000)); err != nil {
		t.Fatal(err)
	}
	// wrong kind
	bad := intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0)
	bad.Kind = 1
	bad.Sign(sender)
	e.svc.HandleIntent(ctx, bad)
	if len(e.lws.hooks) != 0 {
		t.Fatalf("hooks for ignored intents: %v", e.lws.hooks)
	}
	// duplicate id
	ev := intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0)
	e.svc.HandleIntent(ctx, ev)
	e.svc.HandleIntent(ctx, ev)
	if len(e.lws.hooks) != 1 {
		t.Fatalf("duplicate registered twice: %v", e.lws.hooks)
	}
	// sender rate limit (env limit is 3/h): 2 more allowed, 4th dropped
	e.svc.HandleIntent(ctx, intentEvent(t, sender, rpk, "0123456789abcde1", 5, t0))
	e.svc.HandleIntent(ctx, intentEvent(t, sender, rpk, "0123456789abcde2", 5, t0))
	e.svc.HandleIntent(ctx, intentEvent(t, sender, rpk, "0123456789abcde3", 5, t0))
	if len(e.lws.hooks) != 3 {
		t.Fatalf("rate limit: %d hooks", len(e.lws.hooks))
	}
	// duplicate ids don't count against the limit (checked before)
}

func TestHandleIntentRecipientCap(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	for i := 0; i < MaxOpenIntentsPerRecipient; i++ {
		pid := fmt.Sprintf("%016x", i)
		if err := e.svc.HandleIntent(ctx, intentEvent(t, nostr.GeneratePrivateKey(), rpk, pid, 5, t0)); err != nil {
			t.Fatal(err)
		}
	}
	e.svc.HandleIntent(ctx, intentEvent(t, nostr.GeneratePrivateKey(), rpk, "ffffffffffffffff", 5, t0))
	if n, _ := e.st.CountOpenIntents(rpk); n != MaxOpenIntentsPerRecipient {
		t.Fatalf("cap not enforced: %d", n)
	}
}

func TestHandleIntentWebhookFailureIsRetriedBySweep(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	e.lws.failHook = errors.New("lws down")
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	if err := e.svc.HandleIntent(context.Background(), ev); err != nil {
		t.Fatalf("intent must be stored even if lws fails: %v", err)
	}
	row, _, _ := e.st.GetIntent(ev.ID)
	if row.LWSEventID != "" {
		t.Fatal("event id set despite failure")
	}
	e.lws.failHook = nil
	if err := e.svc.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	row, _, _ = e.st.GetIntent(ev.ID)
	if row.LWSEventID != "event1" {
		t.Fatalf("webhook not registered by sweep: %+v", row)
	}
}

// TestBackfillFeedsFetchedIntentsAndSkipsDuplicates is Ruling A of the task-8
// addendum: Backfill re-fetches intents since the last seen intent minus the
// lookback, using the current subscription relays and filter, and feeds them
// through HandleIntent (which itself drops duplicates and unknown recipients).
func TestBackfillFeedsFetchedIntentsAndSkipsDuplicates(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	sender := nostr.GeneratePrivateKey()

	// already handled directly (not via Backfill)
	preHandled := intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0)
	if err := e.svc.HandleIntent(ctx, preHandled); err != nil {
		t.Fatal(err)
	}

	// two new, valid intents for the registered recipient
	newOne := intentEvent(t, sender, rpk, "0123456789abcde1", 5, t0)
	newTwo := intentEvent(t, sender, rpk, "0123456789abcde2", 5, t0)

	// one for an unregistered recipient
	other, _ := nostr.GetPublicKey(nostr.GeneratePrivateKey())
	unregistered := intentEvent(t, sender, other, "0123456789abcde3", 5, t0)

	e.relays.fetch = []*nostr.Event{preHandled, newOne, newTwo, unregistered}

	wantURLs := e.svc.SubscriptionRelays()
	wantSince := e.svc.IntentFilter().Since

	if err := e.svc.Backfill(ctx); err != nil {
		t.Fatal(err)
	}

	if len(e.lws.hooks) != 3 {
		t.Fatalf("hooks after backfill: %v", e.lws.hooks)
	}
	if len(e.relays.fetchedURLs) != 1 {
		t.Fatalf("fetch calls: %d", len(e.relays.fetchedURLs))
	}
	gotURLs := e.relays.fetchedURLs[0]
	if len(gotURLs) != len(wantURLs) {
		t.Fatalf("fetch urls %v, want %v", gotURLs, wantURLs)
	}
	for i := range wantURLs {
		if gotURLs[i] != wantURLs[i] {
			t.Fatalf("fetch urls %v, want %v", gotURLs, wantURLs)
		}
	}
	f := e.relays.fetchedFilters[0]
	if len(f.Kinds) != 1 || f.Kinds[0] != 9738 || f.Since == nil || wantSince == nil || *f.Since != *wantSince {
		t.Fatalf("fetch filter %+v", f)
	}
}

// TestHandleIntentRejectsDuplicatePidForRecipient is finding I1: another
// sender reusing a recipient's pid must not get a second intent (and
// webhook) for the same payment.
func TestHandleIntentRejectsDuplicatePidForRecipient(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	first := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	if err := e.svc.HandleIntent(ctx, first); err != nil {
		t.Fatal(err)
	}
	spoof := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	if err := e.svc.HandleIntent(ctx, spoof); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.hooks) != 1 {
		t.Fatalf("second webhook for a duplicate pid: %v", e.lws.hooks)
	}
	if _, ok, _ := e.st.GetIntent(spoof.ID); ok {
		t.Fatal("duplicate-pid intent stored")
	}
}
