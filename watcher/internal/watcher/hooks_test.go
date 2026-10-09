package watcher

import (
	"context"
	"strconv"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/store"
)

type hookEnv struct {
	*env
	rpk, sender string
	intent      *nostr.Event
}

func newHookEnv(t *testing.T) *hookEnv {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk, _ := nostr.GetPublicKey(rsk)
	e.relays.lists[rpk] = [2][]string{{"wss://r.example"}, {"wss://write.example"}}
	e.register(t, rsk)
	eventually(t, "cache", func() bool { _, _, ok, _ := e.st.GetRelayCache(rpk + ":write"); return ok })
	sender := nostr.GeneratePrivateKey()
	ev := intentEvent(t, sender, rpk, "0123456789abcdef", 5000, t0)
	if err := e.svc.HandleIntent(context.Background(), ev); err != nil {
		t.Fatal(err)
	}
	senderPK, _ := nostr.GetPublicKey(sender)
	return &hookEnv{env: e, rpk: rpk, sender: senderPK, intent: ev}
}

func TestHookPublishesReceiptOnZeroConf(t *testing.T) {
	h := newHookEnv(t)
	h.now = t0 + 30
	err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 0, 4949))
	if err != nil {
		t.Fatal(err)
	}
	pubs := h.relays.published
	if len(pubs) != 1 {
		t.Fatalf("published %d", len(pubs))
	}
	ev := pubs[0].ev
	if ev.Kind != 9739 || ev.PubKey != h.pk || ev.CreatedAt != nostr.Timestamp(t0+30) {
		t.Fatalf("event %+v", ev)
	}
	if ok, _ := ev.CheckSignature(); !ok {
		t.Fatal("receipt not signed by watcher")
	}
	tags := ev.Tags
	if tags[0][1] != strings.Repeat("b", 64) || tags[1][1] != h.rpk || tags[2][1] != h.sender ||
		tags[3][1] != "4949" || tags[4][1] != h.intent.ID || tags[5][1] != "like" {
		t.Fatalf("tags %v", tags)
	}
	urls := strings.Join(pubs[0].urls, " ")
	if urls != "wss://w1.example wss://w2.example wss://write.example" {
		t.Fatalf("urls %q", urls)
	}
	r, ok, _ := h.st.ReceiptByTx(h.intent.ID, strings.Repeat("7", 64))
	if !ok || r.ID != ev.ID || r.State != store.ReceiptSeen || !r.Published || r.Amount != 4949 || r.SeenAt != t0+30 {
		t.Fatalf("receipt %+v %v", r, ok)
	}
	row, _, _ := h.st.GetIntent(h.intent.ID)
	if row.State != store.StateSeen {
		t.Fatalf("intent state %s", row.State)
	}
}

func TestHookConfirmationMarksConfirmed(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10))
	if err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 1, 10)); err != nil {
		t.Fatal(err)
	}
	if len(h.relays.published) != 1 {
		t.Fatal("confirmation published something")
	}
	r, _, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	row, _, _ := h.st.GetIntent(h.intent.ID)
	if r.State != store.ReceiptConfirmed || row.State != store.StateConfirmed {
		t.Fatalf("states %s %s", r.State, row.State)
	}
	// webhook stays until expiration (second payment must still be seen)
	if len(h.lws.deleted) != 0 {
		t.Fatal("webhook deleted on confirmation")
	}
}

func TestHookConfirmationWithoutPriorSeenCreatesConfirmedReceipt(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	if err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 1, 10)); err != nil {
		t.Fatal(err)
	}
	if len(h.relays.published) != 1 {
		t.Fatal("receipt not published")
	}
	r, _, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	if r.State != store.ReceiptConfirmed {
		t.Fatalf("state %s", r.State)
	}
}

func TestHookRejectsMismatch(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	cases := map[string][3]string{
		"unknown token":  {strings.Repeat("0", 64), "0123456789abcdef", "event1"},
		"wrong pid":      {h.intent.ID, "ffffffffffffffff", "event1"},
		"wrong event id": {h.intent.ID, "0123456789abcdef", "event9"},
	}
	for name, c := range cases {
		if err := h.svc.HandleHook(context.Background(), hook(c[0], c[1], c[2], tx, 0, 10)); err != nil {
			t.Errorf("%s: %v", name, err)
		}
	}
	if len(h.relays.published) != 0 {
		t.Fatal("mismatching hook produced a receipt")
	}
	if rows, _ := h.st.UnpublishedReceipts(); len(rows) != 0 {
		t.Fatal("receipt stored")
	}
}

func TestHookDuplicateTxIsIgnored(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	for i := 0; i < 3; i++ {
		h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10))
	}
	if len(h.relays.published) != 1 {
		t.Fatalf("published %d", len(h.relays.published))
	}
}

func TestHookTwoPaymentsTwoReceipts(t *testing.T) {
	h := newHookEnv(t)
	h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 0, 10))
	h.now = t0 + 60
	h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("8", 64), 0, 20))
	evs := h.relays.events()
	if len(evs) != 2 || evs[0].ID == evs[1].ID || evs[1].Tags[3][1] != "20" || evs[1].Tags[4][1] != h.intent.ID {
		t.Fatalf("events %v", evs)
	}
}

func TestHookPublishFailureKeepsReceiptUnpublished(t *testing.T) {
	h := newHookEnv(t)
	h.relays.fail = true
	if err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 0, 10)); err != nil {
		t.Fatalf("publish failure must not fail the hook: %v", err)
	}
	r, ok, _ := h.st.ReceiptByTx(h.intent.ID, strings.Repeat("7", 64))
	if !ok || r.Published {
		t.Fatalf("receipt %+v %v", r, ok)
	}
}

// TestHookLateConfirmationResurrectsDroppedReceipt is finding F1a: a tx
// confirming more than ReceiptTimeoutSeconds after first sighting must not
// be lost just because Sweep already dropped the stale 'seen' receipt.
func TestHookLateConfirmationResurrectsDroppedReceipt(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	if err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10)); err != nil {
		t.Fatal(err)
	}
	first := h.relays.published[0].ev

	h.now = t0 + ReceiptTimeoutSeconds + 1
	if err := h.svc.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(h.relays.published) != 2 {
		t.Fatalf("deletion not published: %v", h.relays.published)
	}
	r, _, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	row, _, _ := h.st.GetIntent(h.intent.ID)
	if r.State != store.ReceiptDropped || row.State != store.StateOpen {
		t.Fatalf("precondition: not dropped: %s %s", r.State, row.State)
	}

	// the confirmation arrives after the drop
	h.now = t0 + ReceiptTimeoutSeconds + 100
	if err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 1, 10)); err != nil {
		t.Fatal(err)
	}
	pubs := h.relays.published
	if len(pubs) != 3 {
		t.Fatalf("resurrection not published: %v", pubs)
	}
	resurrected := pubs[2].ev
	if resurrected.Kind != 9739 || resurrected.ID == first.ID || resurrected.CreatedAt != nostr.Timestamp(h.now) || resurrected.PubKey != h.pk {
		t.Fatalf("resurrected event %+v", resurrected)
	}
	if ok, _ := resurrected.CheckSignature(); !ok {
		t.Fatal("resurrected receipt not signed")
	}
	if len(resurrected.Tags) != len(first.Tags) {
		t.Fatalf("tags changed: %v vs %v", resurrected.Tags, first.Tags)
	}
	for i := range first.Tags {
		if resurrected.Tags[i][0] != first.Tags[i][0] || resurrected.Tags[i][1] != first.Tags[i][1] {
			t.Fatalf("tag %d changed: %v vs %v", i, resurrected.Tags[i], first.Tags[i])
		}
	}
	newReceipt, ok, _ := h.st.GetReceipt(resurrected.ID)
	if !ok || newReceipt.State != store.ReceiptConfirmed || !newReceipt.Published {
		t.Fatalf("new receipt row: %+v %v", newReceipt, ok)
	}
	if _, ok, _ := h.st.GetReceipt(first.ID); ok {
		t.Fatal("old receipt row still present")
	}
	row, _, _ = h.st.GetIntent(h.intent.ID)
	if row.State != store.StateConfirmed {
		t.Fatalf("intent not confirmed: %s", row.State)
	}

	// no further drop on a later sweep
	if err := h.svc.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(h.relays.published) != 3 {
		t.Fatal("resurrected receipt dropped again")
	}
}

// TestApplyToExistingUpgradesSeen exercises the shared found-path helper
// directly (finding F4): a 'seen' receipt plus a confirming hook must
// upgrade the receipt and the intent to confirmed without publishing again.
// This is the same logic HandleHook's !inserted branch falls back to when
// it loses the insert race against a concurrent hook for the same tx; that
// race is not reproducible deterministically through the store (SQLite
// writes are serialized on the single connection), so the helper is
// exercised directly here instead.
func TestApplyToExistingUpgradesSeen(t *testing.T) {
	h := newHookEnv(t)
	tx := strings.Repeat("7", 64)
	if err := h.svc.HandleHook(context.Background(), hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10)); err != nil {
		t.Fatal(err)
	}
	row, _, _ := h.st.GetIntent(h.intent.ID)
	existing, ok, _ := h.st.ReceiptByTx(h.intent.ID, tx)
	if !ok {
		t.Fatal("precondition: receipt missing")
	}
	confirming := hook(h.intent.ID, "0123456789abcdef", "event1", tx, 1, 10)
	if err := h.svc.applyToExisting(context.Background(), row, existing, confirming); err != nil {
		t.Fatal(err)
	}
	got, _, _ := h.st.GetReceipt(existing.ID)
	if got.State != store.ReceiptConfirmed {
		t.Fatalf("receipt state %s", got.State)
	}
	row, _, _ = h.st.GetIntent(h.intent.ID)
	if row.State != store.StateConfirmed {
		t.Fatalf("intent state %s", row.State)
	}
	if len(h.relays.published) != 1 {
		t.Fatal("upgrade must not publish again")
	}
}

// TestHookIgnoresTxAlreadyCreditedToAnotherIntent is finding I1: a tx that
// already produced a receipt for one intent is never credited to a second
// intent.
func TestHookIgnoresTxAlreadyCreditedToAnotherIntent(t *testing.T) {
	h := newHookEnv(t)
	ctx := context.Background()
	other := intentEvent(t, nostr.GeneratePrivateKey(), h.rpk, "0123456789abcdee", 5000, t0)
	if err := h.svc.HandleIntent(ctx, other); err != nil {
		t.Fatal(err)
	}
	tx := strings.Repeat("7", 64)
	if err := h.svc.HandleHook(ctx, hook(h.intent.ID, "0123456789abcdef", "event1", tx, 0, 10)); err != nil {
		t.Fatal(err)
	}
	if err := h.svc.HandleHook(ctx, hook(other.ID, "0123456789abcdee", "event2", tx, 0, 10)); err != nil {
		t.Fatal(err)
	}
	if len(h.relays.published) != 1 {
		t.Fatalf("tx credited twice: %d receipts", len(h.relays.published))
	}
	if _, ok, _ := h.st.ReceiptByTx(other.ID, tx); ok {
		t.Fatal("second receipt stored for the same tx")
	}
	if row, _, _ := h.st.GetIntent(other.ID); row.State != store.StateOpen {
		t.Fatalf("second intent state %s", row.State)
	}
}

func TestHookAnonymousProfileTipReceiptNamesNeitherNoteNorSender(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk, _ := nostr.GetPublicKey(rsk)
	e.relays.lists[rpk] = [2][]string{{"wss://r.example"}, {"wss://write.example"}}
	e.register(t, rsk)
	eventually(t, "cache", func() bool { _, _, ok, _ := e.st.GetRelayCache(rpk + ":write"); return ok })
	ev := &nostr.Event{
		Kind: protocol.KindTipIntent, CreatedAt: nostr.Timestamp(t0), Content: "",
		Tags: nostr.Tags{
			{"p", rpk}, {"amount", "5000"}, {"pid", "0123456789abcdef"}, {"type", "tip"},
			{"expiration", strconv.FormatInt(t0+86400, 10)}, {"anon"},
		},
	}
	if err := ev.Sign(nostr.GeneratePrivateKey()); err != nil { // a one-time key
		t.Fatal(err)
	}
	if err := e.svc.HandleIntent(context.Background(), ev); err != nil {
		t.Fatal(err)
	}
	row, ok, _ := e.st.GetIntent(ev.ID)
	if !ok || row.NoteID != "" || !row.Anon {
		t.Fatalf("stored intent %+v %v", row, ok)
	}
	if err := e.svc.HandleHook(context.Background(), hook(ev.ID, "0123456789abcdef", "event1", strings.Repeat("7", 64), 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if len(e.relays.published) != 1 {
		t.Fatalf("published %d", len(e.relays.published))
	}
	receipt := e.relays.published[0].ev
	var names []string
	for _, tag := range receipt.Tags {
		names = append(names, tag[0])
	}
	if strings.Join(names, ",") != "p,amount,intent,type" {
		t.Fatalf("tags %v", receipt.Tags)
	}
	if strings.Contains(receipt.String(), ev.PubKey) {
		t.Fatal("the one-time sender key must not appear in the receipt")
	}
}
