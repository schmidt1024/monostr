package watcher

import (
	"context"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/store"
)

// adoptEnv is a watcher that lost its database: the intent dates from before this start
// (t0) and came back through Backfill; receipts it published back then may still be on
// the relays.
type adoptEnv struct {
	*env
	intent *nostr.Event
	parsed protocol.Intent
}

func newAdoptEnv(t *testing.T, intentAmount, intentAt int64) *adoptEnv {
	t.Helper()
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk, _ := nostr.GetPublicKey(rsk)
	e.relays.lists[rpk] = [2][]string{{"wss://r.example"}, {"wss://write.example"}}
	e.register(t, rsk)
	eventually(t, "cache", func() bool { _, _, ok, _ := e.st.GetRelayCache(rpk + ":write"); return ok })
	ev := intentEvent(t, nostr.GeneratePrivateKey(), rpk, "0123456789abcdef", intentAmount, intentAt)
	if intentAt > e.now {
		e.now = intentAt // a live intent arrives while the watcher runs
	}
	if err := e.svc.HandleIntent(context.Background(), ev); err != nil {
		t.Fatal(err)
	}
	in, err := protocol.ParseIntent(ev, e.now)
	if err != nil {
		t.Fatal(err)
	}
	return &adoptEnv{env: e, intent: ev, parsed: in}
}

// earlier is a receipt this watcher published for the intent before it lost its database.
func (a *adoptEnv) earlier(t *testing.T, amount, at int64) *nostr.Event {
	t.Helper()
	ev := protocol.BuildReceipt(a.parsed, amount, at)
	if err := ev.Sign(a.sk); err != nil {
		t.Fatal(err)
	}
	return &ev
}

func (a *adoptEnv) deletion(t *testing.T, receiptID string, at int64) *nostr.Event {
	t.Helper()
	ev := protocol.BuildDeletion(receiptID, at)
	if err := ev.Sign(a.sk); err != nil {
		t.Fatal(err)
	}
	return &ev
}

var (
	txA = strings.Repeat("7", 64)
	txB = strings.Repeat("8", 64)
)

func receiptQueries(f *fakeRelays) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, flt := range f.fetchedFilters {
		for _, k := range flt.Kinds {
			if k == protocol.KindTipReceipt {
				n++
			}
		}
	}
	return n
}

func TestHookAdoptsReceiptPublishedBeforeLostDatabase(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	old := a.earlier(t, 4949, t0-500)
	a.relays.fetch = []*nostr.Event{old}
	a.now = t0 + 30
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 1, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 0 {
		t.Fatalf("published %d events, want the old receipt taken over", n)
	}
	r, ok, _ := a.st.ReceiptByTx(a.intent.ID, txA)
	if !ok || r.ID != old.ID || r.CreatedAt != t0-500 || r.State != store.ReceiptConfirmed || !r.Published || r.Amount != 4949 {
		t.Fatalf("receipt %+v %v", r, ok)
	}
}

func TestHookAdoptsReceiptWithoutAmount(t *testing.T) {
	a := newAdoptEnv(t, 0, t0-600) // hidden amount: intent and receipt without amount
	old := a.earlier(t, 4949, t0-500)
	if old.Tags.GetFirst([]string{"amount"}) != nil {
		t.Fatal("test setup: receipt should carry no amount")
	}
	a.relays.fetch = []*nostr.Event{old}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 0 {
		t.Fatalf("published %d", n)
	}
	if r, ok, _ := a.st.ReceiptByTx(a.intent.ID, txA); !ok || r.ID != old.ID {
		t.Fatalf("receipt %+v %v", r, ok)
	}
}

func TestHookIgnoresDeletedOrForeignOrOtherAmountReceipts(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	deleted := a.earlier(t, 4949, t0-500)
	otherAmount := a.earlier(t, 1234, t0-400)
	foreign := protocol.BuildReceipt(a.parsed, 4949, t0-300)
	if err := foreign.Sign(nostr.GeneratePrivateKey()); err != nil {
		t.Fatal(err)
	}
	a.relays.fetch = []*nostr.Event{deleted, a.deletion(t, deleted.ID, t0-200), otherAmount, &foreign}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	pubs := a.relays.events()
	if len(pubs) != 1 || pubs[0].Kind != protocol.KindTipReceipt {
		t.Fatalf("want one new receipt, got %d", len(pubs))
	}
	if r, _, _ := a.st.ReceiptByTx(a.intent.ID, txA); r.ID != pubs[0].ID {
		t.Fatalf("stored %s, published %s", r.ID, pubs[0].ID)
	}
}

func TestHookAdoptsOneReceiptPerPayment(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	first := a.earlier(t, 4949, t0-500)
	second := a.earlier(t, 4949, t0-400)
	a.relays.fetch = []*nostr.Event{second, first}
	for _, tx := range []string{txA, txB} {
		if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", tx, 0, 4949)); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(a.relays.events()); n != 0 {
		t.Fatalf("published %d", n)
	}
	ra, _, _ := a.st.ReceiptByTx(a.intent.ID, txA)
	rb, _, _ := a.st.ReceiptByTx(a.intent.ID, txB)
	if ra.ID != first.ID || rb.ID != second.ID {
		t.Fatalf("adopted %s and %s, want oldest first", ra.ID, rb.ID)
	}
}

func TestHookPublishesWhenRelaysHaveNoReceipt(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 1 {
		t.Fatalf("published %d", n)
	}
	if receiptQueries(a.relays) != 1 {
		t.Fatal("relays were not asked")
	}
}

func TestHookForIntentAfterStartDoesNotAskRelays(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0+protocol.MaxFutureSkew+10)
	a.now += 10
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if receiptQueries(a.relays) != 0 {
		t.Fatal("asked the relays for a live intent")
	}
	if n := len(a.relays.events()); n != 1 {
		t.Fatalf("published %d", n)
	}
}

func TestHookRejectsReceiptUnderForgedID(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	forged := *a.earlier(t, 4949, t0-500)
	forged.ID = strings.Repeat("f", 64) // a relay serves a genuine receipt under a made-up id
	a.relays.fetch = []*nostr.Event{&forged}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 1 {
		t.Fatalf("published %d, want a new receipt", n)
	}
}

func TestHookDoesNotAdoptReceiptWithAmountForHiddenIntent(t *testing.T) {
	a := newAdoptEnv(t, 0, t0-600)
	withAmount := protocol.BuildReceipt(protocol.Intent{ID: a.parsed.ID, Recipient: a.parsed.Recipient, Type: a.parsed.Type, Amount: 1}, 4949, t0-500)
	if err := withAmount.Sign(a.sk); err != nil {
		t.Fatal(err)
	}
	a.relays.fetch = []*nostr.Event{&withAmount}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 1 {
		t.Fatalf("published %d", n)
	}
}

func TestHookAsksForTheRecipientsReceiptsWithSkew(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	a.relays.mu.Lock()
	defer a.relays.mu.Unlock()
	var got *nostr.Filter
	for i, f := range a.relays.fetchedFilters {
		if len(f.Kinds) == 1 && f.Kinds[0] == protocol.KindTipReceipt {
			got = &a.relays.fetchedFilters[i]
		}
	}
	if got == nil || len(got.Tags["p"]) != 1 || got.Tags["p"][0] != a.parsed.Recipient || got.Authors[0] != a.pk ||
		got.Since == nil || int64(*got.Since) != t0-600-protocol.MaxFutureSkew {
		t.Fatalf("receipt filter %+v", got)
	}
}

func TestHookIntentSlightlyInTheFutureStillLooksUp(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0+protocol.MaxFutureSkew-1) // client clock ahead, intent ingested before the restart
	a.now = t0 + 30
	old := a.earlier(t, 4949, t0-10)
	a.relays.fetch = []*nostr.Event{old}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 0 {
		t.Fatalf("published %d", n)
	}
}

func TestHookTakesTheNextReceiptWhenOneWasTakenMeanwhile(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	first := a.earlier(t, 4949, t0-500)
	second := a.earlier(t, 4949, t0-400)
	a.relays.fetch = []*nostr.Event{first, second}
	// a concurrent hook for another payment stores the first receipt between our lookup and insert
	a.relays.onFetch = func() {
		a.relays.onFetch = nil
		if _, err := a.st.InsertReceipt(store.Receipt{ID: first.ID, IntentID: a.intent.ID, TxHash: txB, Amount: 4949, CreatedAt: t0 - 500, SeenAt: t0, State: store.ReceiptSeen}); err != nil {
			t.Error(err)
		}
	}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 0, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 0 {
		t.Fatalf("published %d", n)
	}
	if r, ok, _ := a.st.ReceiptByTx(a.intent.ID, txA); !ok || r.ID != second.ID {
		t.Fatalf("receipt %+v %v", r, ok)
	}
}

func TestHookLosingTheTakeoverToItsOwnTxStillConfirms(t *testing.T) {
	a := newAdoptEnv(t, 5000, t0-600)
	old := a.earlier(t, 4949, t0-500)
	a.relays.fetch = []*nostr.Event{old}
	// a concurrent 0-conf hook for the same tx stores its receipt while this confirming hook
	// waits for the relays: the takeover insert collides on (intent, tx)
	a.relays.onFetch = func() {
		a.relays.onFetch = nil
		if _, err := a.st.InsertReceipt(store.Receipt{ID: strings.Repeat("c", 64), IntentID: a.intent.ID, TxHash: txA, Amount: 4949, CreatedAt: t0, SeenAt: t0, State: store.ReceiptSeen}); err != nil {
			t.Error(err)
		}
	}
	if err := a.svc.HandleHook(context.Background(), hook(a.intent.ID, "0123456789abcdef", "event1", txA, 1, 4949)); err != nil {
		t.Fatal(err)
	}
	if n := len(a.relays.events()); n != 0 {
		t.Fatalf("published %d", n)
	}
	if r, ok, _ := a.st.ReceiptByTx(a.intent.ID, txA); !ok || r.State != store.ReceiptConfirmed {
		t.Fatalf("receipt %+v %v, want confirmed", r, ok)
	}
}
