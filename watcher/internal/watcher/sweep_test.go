package watcher

import (
	"context"
	"errors"
	"reflect"
	"strconv"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/store"
)

func TestSweepDropsStaleReceipt(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10))
	receipt := h.relays.published[0].ev
	h.now = t0 + ReceiptTimeoutSeconds - 1
	h.svc.Sweep(context.Background())
	if len(h.relays.published) != 1 {
		t.Fatal("dropped too early")
	}
	h.now = t0 + ReceiptTimeoutSeconds + 1
	if err := h.svc.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	pubs := h.relays.published
	if len(pubs) != 2 || pubs[1].ev.Kind != 5 || pubs[1].ev.PubKey != h.pk {
		t.Fatalf("no deletion published: %v", pubs)
	}
	if e := pubs[1].ev.Tags.Find("e"); e == nil || e[1] != receipt.ID {
		t.Fatalf("deletion tags %v", pubs[1].ev.Tags)
	}
	if strings.Join(pubs[1].urls, " ") != strings.Join(pubs[0].urls, " ") {
		t.Fatal("deletion not sent to the receipt's relays")
	}
	r, _, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	row, _, _ := h.st.GetIntent(h.intent.ID)
	if r.State != store.ReceiptDropped || row.State != store.StateOpen {
		t.Fatalf("states %s %s", r.State, row.State)
	}
	// a confirmed receipt is never dropped
	h.svc.Sweep(context.Background())
	if len(h.relays.published) != 2 {
		t.Fatal("dropped twice")
	}
}

func TestSweepRetriesUnpublishedReceipt(t *testing.T) {
	h := newHookEnv(t)
	h.relays.fail = true
	h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 0, 10))
	first := h.relays.published[0].ev
	h.relays.fail = false
	h.now = t0 + 120
	if err := h.svc.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	pubs := h.relays.published
	if len(pubs) != 2 || pubs[1].ev.ID != first.ID {
		t.Fatalf("retry must republish the same event id: %v", pubs)
	}
	if ok, _ := pubs[1].ev.CheckSignature(); !ok {
		t.Fatal("retried event not signed")
	}
	r, _, _ := h.st.GetReceipt(first.ID)
	if !r.Published {
		t.Fatal("not marked published")
	}
	h.svc.Sweep(context.Background())
	if len(h.relays.published) != 2 {
		t.Fatal("published again after success")
	}
}

func TestSweepExpiresIntents(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	open := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	seen := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdee", 5, t0)
	e.svc.HandleIntent(ctx, open)
	e.svc.HandleIntent(ctx, seen)
	e.svc.HandleHook(ctx, hook(seen.ID, "0123456789abcdee", "event2", strings.Repeat("7", 64), 1, 5))
	e.now = t0 + 86400
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if strings.Join(e.lws.deleted, " ") != "event1 event2" {
		t.Fatalf("deleted %v", e.lws.deleted)
	}
	if _, ok, _ := e.st.GetIntent(open.ID); ok {
		t.Fatal("expired open intent kept")
	}
	row, ok, _ := e.st.GetIntent(seen.ID)
	if !ok || row.LWSEventID != "" || row.State != store.StateConfirmed {
		t.Fatalf("confirmed intent: %+v %v", row, ok)
	}
	// second sweep: nothing more to delete
	e.svc.Sweep(ctx)
	if len(e.lws.deleted) != 2 {
		t.Fatalf("deleted again: %v", e.lws.deleted)
	}
	if v, _, _ := e.st.GetMeta(metaLastHeight); v != "3200000" {
		t.Fatalf("last height %q", v)
	}
}

// TestSweepKeepsWebhookOfSeenIntentPastExpiry is finding F1b: expiring the
// webhook of a 'seen' intent stops LWS from ever sending the confirmation
// that would let the payment be reported. The webhook must stay until the
// receipt itself confirms or is dropped.
func TestSweepKeepsWebhookOfSeenIntentPastExpiry(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	if err := e.svc.HandleIntent(ctx, ev); err != nil {
		t.Fatal(err)
	}
	tx := strings.Repeat("7", 64)

	// payment seen shortly before expiry, well within the receipt-staleness window
	e.now = t0 + 86400 - 1000
	if err := e.svc.HandleHook(ctx, hook(ev.ID, "0123456789abcdef", "event1", tx, 0, 5)); err != nil {
		t.Fatal(err)
	}
	if row, _, _ := e.st.GetIntent(ev.ID); row.State != store.StateSeen {
		t.Fatalf("precondition: intent not seen: %s", row.State)
	}

	// first sweep: past expiry, but the receipt is not yet stale
	e.now = t0 + 86400 + 10
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.deleted) != 0 {
		t.Fatalf("webhook deleted for a seen intent past expiry: %v", e.lws.deleted)
	}
	row, _, _ := e.st.GetIntent(ev.ID)
	if row.LWSEventID != "event1" {
		t.Fatalf("webhook cleared: %+v", row)
	}

	// the confirmation arrives late, because the webhook was kept alive
	if err := e.svc.HandleHook(ctx, hook(ev.ID, "0123456789abcdef", "event1", tx, 1, 5)); err != nil {
		t.Fatal(err)
	}
	row, _, _ = e.st.GetIntent(ev.ID)
	if row.State != store.StateConfirmed {
		t.Fatalf("not confirmed: %s", row.State)
	}

	// second sweep: now the webhook can be cleaned up, the row is kept
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.deleted) != 1 || e.lws.deleted[0] != "event1" {
		t.Fatalf("webhook not deleted after confirmation: %v", e.lws.deleted)
	}
	row, ok, _ := e.st.GetIntent(ev.ID)
	if !ok || row.State != store.StateConfirmed || row.LWSEventID != "" {
		t.Fatalf("row after cleanup: %+v %v", row, ok)
	}
}

func TestRecoverRegistersMissingWebhooksAndRescans(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	e.lws.failHook = context.DeadlineExceeded
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	e.svc.HandleIntent(ctx, ev)
	e.lws.failHook = nil
	e.st.SetMeta(metaLastHeight, "3199990")
	if err := e.svc.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	row, _, _ := e.st.GetIntent(ev.ID)
	if row.LWSEventID == "" {
		t.Fatal("webhook not registered on recover")
	}
	if len(e.lws.rescans) != 1 || e.lws.rescans[0] != "3199990 "+mainnetAddr {
		t.Fatalf("rescans %v", e.lws.rescans)
	}
	// no last height → no rescan; no live intents → no rescan
	e2 := newEnv(t, monero.Mainnet)
	e2.register(t, nostr.GeneratePrivateKey())
	e2.svc.Recover(ctx)
	if len(e2.lws.rescans) != 0 {
		t.Fatalf("rescan without height: %v", e2.lws.rescans)
	}
	e2.st.SetMeta(metaLastHeight, strconv.Itoa(1))
	e2.svc.Recover(ctx)
	if len(e2.lws.rescans) != 0 {
		t.Fatalf("rescan without live intents: %v", e2.lws.rescans)
	}
}

// TestRecoverRescansAfterBackfillAndRetriesFromSweep is finding C1: the
// rescan must cover intents that only arrived via Backfill (main runs
// Backfill before Recover), a failed rescan is kept as pending and retried
// by Sweep, and last_height does not advance while a rescan is pending.
func TestRecoverRescansAfterBackfillAndRetriesFromSweep(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	if err := e.st.SetMeta(metaLastHeight, "3199990"); err != nil {
		t.Fatal(err)
	}
	// the recipient's only intent was published while the watcher was down
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	e.relays.fetch = []*nostr.Event{ev}
	if err := e.svc.Backfill(ctx); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := e.st.GetIntent(ev.ID); !ok {
		t.Fatal("precondition: backfilled intent not stored")
	}

	e.lws.failRescan = errors.New("lws down")
	if err := e.svc.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	if v, ok, _ := e.st.GetMeta(metaPendingRescan); !ok || v != "3199990" {
		t.Fatalf("pending rescan after failed recover: %q %v", v, ok)
	}

	// still failing: pending kept, last_height untouched
	e.now = t0 + 60
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if v, ok, _ := e.st.GetMeta(metaPendingRescan); !ok || v != "3199990" {
		t.Fatalf("pending rescan after failed sweep: %q %v", v, ok)
	}
	if v, _, _ := e.st.GetMeta(metaLastHeight); v != "3199990" {
		t.Fatalf("last height advanced while rescan pending: %q", v)
	}
	if len(e.lws.rescans) != 0 {
		t.Fatalf("rescans %v", e.lws.rescans)
	}

	// LWS healthy again: the sweep rescans, clears pending, then advances
	e.lws.failRescan = nil
	e.now = t0 + 120
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.rescans) != 1 || e.lws.rescans[0] != "3199990 "+mainnetAddr {
		t.Fatalf("rescans %v", e.lws.rescans)
	}
	if _, ok, _ := e.st.GetMeta(metaPendingRescan); ok {
		t.Fatal("pending rescan not cleared")
	}
	if v, _, _ := e.st.GetMeta(metaLastHeight); v != "3200000" {
		t.Fatalf("last height after rescan: %q", v)
	}
	// no further rescans
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.rescans) != 1 {
		t.Fatalf("rescanned again: %v", e.lws.rescans)
	}
}

// TestDropRacesWithConfirmationResurrects is finding I4: the confirming
// hook lands while Sweep is publishing the kind 5 for a stale 'seen'
// receipt. The receipt must not be marked dropped over the confirmation;
// because the deletion already went out, the receipt is resurrected under a
// fresh event, and the intent ends confirmed.
func TestDropRacesWithConfirmationResurrects(t *testing.T) {
	h := newHookEnv(t)
	ctx := context.Background()
	tx := strings.Repeat("7", 64)
	if err := h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10)); err != nil {
		t.Fatal(err)
	}
	first := h.relays.published[0].ev
	h.relays.onPublish = func(ev nostr.Event) {
		if ev.Kind != 5 {
			return
		}
		h.relays.onPublish = nil
		if err := h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", tx, 1, 10)); err != nil {
			t.Error(err)
		}
	}
	h.now = t0 + ReceiptTimeoutSeconds + 1
	if err := h.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	evs := h.relays.events()
	if len(evs) != 3 || evs[0].ID != first.ID || evs[1].Kind != 5 || evs[2].Kind != 9739 || evs[2].ID == first.ID {
		t.Fatalf("want receipt, kind 5, resurrected receipt; got %v", evs)
	}
	if e := evs[1].Tags.Find("e"); e == nil || e[1] != first.ID {
		t.Fatalf("deletion tags %v", evs[1].Tags)
	}
	if _, ok, _ := h.st.GetReceipt(first.ID); ok {
		t.Fatal("retracted receipt row still present")
	}
	r, ok, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	if !ok || r.ID != evs[2].ID || r.State != store.ReceiptConfirmed || !r.Published {
		t.Fatalf("receipt row %+v %v", r, ok)
	}
	if row, _, _ := h.st.GetIntent(h.intent.ID); row.State != store.StateConfirmed {
		t.Fatalf("intent state %s", row.State)
	}
	// nothing more on later sweeps
	h.svc.Sweep(ctx)
	if len(h.relays.events()) != 3 {
		t.Fatalf("extra events %v", h.relays.events())
	}
}

func TestConfirmedReceiptIsNeverDropped(t *testing.T) {
	h := newHookEnv(t)
	ctx := context.Background()
	tx := strings.Repeat("7", 64)
	h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10))
	h.now = t0 + ReceiptTimeoutSeconds - 10
	h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", tx, 1, 10))
	h.now = t0 + 10*ReceiptTimeoutSeconds
	if err := h.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	for _, ev := range h.relays.events() {
		if ev.Kind == 5 {
			t.Fatal("confirmed receipt retracted")
		}
	}
	r, _, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	row, _, _ := h.st.GetIntent(h.intent.ID)
	if r.State != store.ReceiptConfirmed || row.State != store.StateConfirmed {
		t.Fatalf("states %s %s", r.State, row.State)
	}
}

func TestSecondReceiptOnConfirmedIntentKeepsConfirmed(t *testing.T) {
	h := newHookEnv(t)
	ctx := context.Background()
	h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 1, 10))
	h.now = t0 + 60
	if err := h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("8", 64), 0, 20)); err != nil {
		t.Fatal(err)
	}
	if len(h.relays.events()) != 2 {
		t.Fatalf("second receipt not published: %v", h.relays.events())
	}
	if row, _, _ := h.st.GetIntent(h.intent.ID); row.State != store.StateConfirmed {
		t.Fatalf("intent state %s", row.State)
	}
	// the second receipt going stale does not reopen the intent
	h.now = t0 + 60 + ReceiptTimeoutSeconds + 1
	if err := h.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if row, _, _ := h.st.GetIntent(h.intent.ID); row.State != store.StateConfirmed {
		t.Fatalf("intent state after drop %s", row.State)
	}
}

func TestTwoSeenOneDroppedKeepsIntentSeen(t *testing.T) {
	h := newHookEnv(t)
	ctx := context.Background()
	h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 0, 10))
	h.now = t0 + 2000
	h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("8", 64), 0, 20))
	h.now = t0 + ReceiptTimeoutSeconds + 1
	if err := h.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	first, _, _ := h.st.ReceiptByTx(h.intent.ID, strings.Repeat("7", 64))
	second, _, _ := h.st.ReceiptByTx(h.intent.ID, strings.Repeat("8", 64))
	if first.State != store.ReceiptDropped || second.State != store.ReceiptSeen {
		t.Fatalf("receipts %s %s", first.State, second.State)
	}
	if row, _, _ := h.st.GetIntent(h.intent.ID); row.State != store.StateSeen {
		t.Fatalf("intent state %s, want seen", row.State)
	}
}

// TestExpireKeepsIntentWithLiveReceipt: a hook inserted a receipt after
// ExpiredIntents listed the intent as 'open' (simulated by inserting the
// receipt without touching the intent state); the row and its receipt
// must survive the expiry.
func TestExpireKeepsIntentWithLiveReceipt(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	if err := e.svc.HandleIntent(ctx, ev); err != nil {
		t.Fatal(err)
	}
	e.now = t0 + 86400
	r := store.Receipt{ID: strings.Repeat("9", 64), IntentID: ev.ID, TxHash: strings.Repeat("7", 64), Amount: 5, CreatedAt: e.now, SeenAt: e.now, State: store.ReceiptSeen}
	if ins, err := e.st.InsertReceipt(r); err != nil || !ins {
		t.Fatalf("insert receipt: %v %v", ins, err)
	}
	if err := e.svc.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := e.st.GetIntent(ev.ID); !ok {
		t.Fatal("intent with a live receipt deleted on expiry")
	}
	if _, ok, _ := e.st.GetReceipt(r.ID); !ok {
		t.Fatal("live receipt deleted on expiry")
	}
}

// TestPendingRescanClampsToDaemonHeightAndRetriesOnce: a pending rescan at
// or above the daemon height is clamped to daemon-1 (LWS answers 500 at its
// own scan height); a failure is retried once ten blocks lower in the same
// sweep before giving up until the next one.
func TestPendingRescanClampsToDaemonHeightAndRetriesOnce(t *testing.T) {
	e := newEnv(t, monero.Mainnet) // fakeHeight reports 3200000
	rpk := e.register(t, nostr.GeneratePrivateKey())
	ctx := context.Background()
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", 5, t0)
	e.relays.fetch = []*nostr.Event{ev}
	if err := e.svc.Backfill(ctx); err != nil {
		t.Fatal(err)
	}
	if err := e.st.SetMeta(metaPendingRescan, "3200000"); err != nil {
		t.Fatal(err)
	}
	e.lws.rescanErrs = []error{errors.New("lws rescan: HTTP 500"), nil}
	if err := e.svc.runPendingRescan(ctx); err != nil {
		t.Fatal(err)
	}
	want := []string{"3199999 " + mainnetAddr, "3199989 " + mainnetAddr}
	if !reflect.DeepEqual(e.lws.rescans, want) {
		t.Fatalf("rescans = %v, want %v", e.lws.rescans, want)
	}
	if _, ok, _ := e.st.GetMeta(metaPendingRescan); ok {
		t.Fatal("pending rescan should be cleared after a successful retry")
	}
}
