package watcher

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/ratelimit"
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

// TestHandleIntentSenderCap: one sender cannot fill the recipient's cap.
func TestHandleIntentSenderCap(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	e.svc.deps.IntentLimit = ratelimit.New(1000, time.Hour)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	spammer := nostr.GeneratePrivateKey()
	spk, _ := nostr.GetPublicKey(spammer)
	for i := 0; i < MaxOpenIntentsPerSender+5; i++ {
		if err := e.svc.HandleIntent(ctx, intentEvent(t, spammer, rpk, fmt.Sprintf("%016x", i), 5, t0)); err != nil {
			t.Fatal(err)
		}
	}
	if n, _ := e.st.CountOpenIntentsFrom(rpk, spk); n != MaxOpenIntentsPerSender {
		t.Fatalf("sender cap not enforced: %d", n)
	}
	// intents turned away by the cap did not count against the rate limit
	limited := ratelimit.New(MaxOpenIntentsPerSender+1, time.Hour)
	e.svc.deps.IntentLimit = limited
	for i := 0; i < MaxOpenIntentsPerSender; i++ {
		limited.Allow(spk, timeOf(t0))
	}
	e.svc.HandleIntent(ctx, intentEvent(t, spammer, rpk, "eeeeeeeeeeeeeeee", 5, t0))
	if !limited.Allowed(spk, timeOf(t0)) {
		t.Fatal("capped intent recorded by the rate limiter")
	}
	// another sender still gets through
	if err := e.svc.HandleIntent(ctx, intentEvent(t, nostr.GeneratePrivateKey(), rpk, "ffffffffffffffff", 5, t0)); err != nil {
		t.Fatal(err)
	}
	if n, _ := e.st.CountOpenIntents(rpk); n != MaxOpenIntentsPerSender+1 {
		t.Fatalf("open %d", n)
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

// boundIntentEvent is an intent whose pid is bound to senderSK (tips 0.6); it returns the event and its pid.
func boundIntentEvent(t *testing.T, senderSK, recipientPK string, createdAt int64) (*nostr.Event, string) {
	t.Helper()
	pub, _ := nostr.GetPublicKey(senderSK)
	nonce := make([]byte, protocol.PIDNonceSize)
	nonce[0] = 7
	pid, err := protocol.BoundPID(pub, nonce)
	if err != nil {
		t.Fatal(err)
	}
	ev := intentEvent(t, senderSK, recipientPK, pid, 5, createdAt)
	ev.Tags = append(ev.Tags, nostr.Tag{"pid_nonce", hex.EncodeToString(nonce)})
	if err := ev.Sign(senderSK); err != nil {
		t.Fatal(err)
	}
	return ev, pid
}

func TestBoundIntentTakesOverCopiedPid(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	real, pid := boundIntentEvent(t, nostr.GeneratePrivateKey(), rpk, t0-10)
	// the copier saw the pid and got to the watcher first, unbound
	copied := intentEvent(t, nostr.GeneratePrivateKey(), rpk, pid, 5, t0-9)
	if err := e.svc.HandleIntent(ctx, copied); err != nil {
		t.Fatal(err)
	}
	if err := e.svc.HandleIntent(ctx, real); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := e.st.GetIntent(copied.ID); ok {
		t.Fatal("copied intent kept")
	}
	row, ok, _ := e.st.GetIntent(real.ID)
	if !ok || !row.Bound || row.LWSEventID != "event2" {
		t.Fatalf("bound intent %+v %v", row, ok)
	}
	if len(e.lws.deleted) != 1 || e.lws.deleted[0] != "event1" {
		t.Fatalf("old webhook not removed: %v", e.lws.deleted)
	}
	if len(e.lws.hooks) != 2 || e.lws.hooks[1] != mainnetAddr+" "+pid+" "+real.ID+" "+hookURL+" 1" {
		t.Fatalf("hooks %v", e.lws.hooks)
	}
}

func TestUnboundAfterBoundIsDropped(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	real, pid := boundIntentEvent(t, nostr.GeneratePrivateKey(), rpk, t0-10)
	e.svc.HandleIntent(ctx, real)
	copied := intentEvent(t, nostr.GeneratePrivateKey(), rpk, pid, 5, t0-9)
	e.svc.HandleIntent(ctx, copied)
	if _, ok, _ := e.st.GetIntent(copied.ID); ok {
		t.Fatal("unbound copy stored over a bound intent")
	}
	if row, ok, _ := e.st.GetIntent(real.ID); !ok || !row.Bound {
		t.Fatalf("bound intent lost %+v %v", row, ok)
	}
}

func TestBoundAfterPaidUnboundIsDropped(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	real, pid := boundIntentEvent(t, nostr.GeneratePrivateKey(), rpk, t0-10)
	unbound := intentEvent(t, nostr.GeneratePrivateKey(), rpk, pid, 5, t0-9)
	e.svc.HandleIntent(ctx, unbound)
	e.st.InsertReceipt(store.Receipt{ID: strings.Repeat("9", 64), IntentID: unbound.ID, TxHash: strings.Repeat("7", 64), Amount: 5, CreatedAt: t0, SeenAt: t0, State: store.ReceiptSeen})
	e.svc.HandleIntent(ctx, real)
	if _, ok, _ := e.st.GetIntent(real.ID); ok {
		t.Fatal("bound intent replaced a paid one")
	}
	if len(e.lws.deleted) != 0 {
		t.Fatalf("webhook of the paid intent removed: %v", e.lws.deleted)
	}
}

func TestReplacedWebhookDeleteIsRetriedBySweep(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	real, pid := boundIntentEvent(t, nostr.GeneratePrivateKey(), rpk, t0-10)
	e.svc.HandleIntent(ctx, intentEvent(t, nostr.GeneratePrivateKey(), rpk, pid, 5, t0-9))
	e.lws.failDelete = errors.New("lws busy")
	if err := e.svc.HandleIntent(ctx, real); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.deleted) != 0 {
		t.Fatalf("deleted %v", e.lws.deleted)
	}
	e.lws.failDelete = nil
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.deleted) != 1 || e.lws.deleted[0] != "event1" {
		t.Fatalf("orphan webhook not retried: %v", e.lws.deleted)
	}
	e.svc.Sweep(ctx)
	if len(e.lws.deleted) != 1 {
		t.Fatalf("orphan deleted twice: %v", e.lws.deleted)
	}
}

func TestMalformedNonceThroughHandleIntentIsIgnored(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	sk := nostr.GeneratePrivateKey()
	ev, _ := boundIntentEvent(t, sk, rpk, t0-10)
	for _, tag := range ev.Tags {
		if tag[0] == "pid_nonce" {
			tag[1] = tag[1][:31] // 31 chars: malformed
		}
	}
	ev.Sign(sk)
	if err := e.svc.HandleIntent(context.Background(), ev); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := e.st.GetIntent(ev.ID); ok {
		t.Fatal("intent with a malformed nonce stored")
	}
}
