package store

import (
	"database/sql"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"monostr.com/watcher/internal/protocol"
)

func openTemp(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "w.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func intent(id string) protocol.Intent {
	return protocol.Intent{
		ID: strings.Repeat(id, 64), Sender: strings.Repeat("e", 64), Recipient: strings.Repeat("c", 64),
		NoteID: strings.Repeat("b", 64), PID: strings.Repeat(id, 16), Type: "like", Amount: 7,
		CreatedAt: 1000, Expiration: 2000,
	}
}

func TestProfileAndAnonymousIntentsRoundTrip(t *testing.T) {
	s := openTemp(t)
	profile := intent("2")
	profile.NoteID = ""
	profile.Anon = true
	if ins, err := s.InsertIntent(profile, 1001); err != nil || !ins {
		t.Fatalf("insert: %v %v", ins, err)
	}
	row, ok, err := s.GetIntent(profile.ID)
	if err != nil || !ok || row.NoteID != "" || !row.Anon {
		t.Fatalf("profile row: %+v %v %v", row, ok, err)
	}
	public := intent("3")
	if ins, err := s.InsertIntent(public, 1001); err != nil || !ins {
		t.Fatalf("insert: %v %v", ins, err)
	}
	if row, _, _ := s.GetIntent(public.ID); row.Anon || row.NoteID != strings.Repeat("b", 64) {
		t.Fatalf("public row: %+v", row)
	}
}

func TestOpenAddsAnonColumnToAnOlderDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		t.Fatal(err)
	}
	// the intents table as the watcher created it before protocol 0.2
	if _, err := db.Exec(`CREATE TABLE intents (
		id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL, note_id TEXT NOT NULL,
		pid TEXT NOT NULL, amount INTEGER NOT NULL, type TEXT NOT NULL, created_at INTEGER NOT NULL,
		expiration INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'open',
		lws_event_id TEXT NOT NULL DEFAULT '', stored_at INTEGER NOT NULL)`); err != nil {
		t.Fatal(err)
	}
	old := intent("1")
	if _, err := db.Exec(`INSERT INTO intents(id, sender, recipient, note_id, pid, amount, type, created_at, expiration, stored_at)
		VALUES(?,?,?,?,?,?,?,?,?,?)`, old.ID, old.Sender, old.Recipient, old.NoteID, old.PID, old.Amount, old.Type, old.CreatedAt, old.Expiration, 1001); err != nil {
		t.Fatal(err)
	}
	db.Close()
	for i := 0; i < 2; i++ { // the second Open finds the column and changes nothing
		s, err := Open(path)
		if err != nil {
			t.Fatalf("open %d: %v", i, err)
		}
		row, ok, err := s.GetIntent(old.ID)
		if err != nil || !ok || row.Anon || row.NoteID != old.NoteID || row.Amount != 7 || row.StoredAt != 1001 {
			t.Fatalf("open %d: row %+v %v %v", i, row, ok, err)
		}
		s.Close()
	}
}

func TestAccounts(t *testing.T) {
	s := openTemp(t)
	prev, err := s.PutAccount(Account{Pubkey: strings.Repeat("c", 64), Address: "4A", CreatedAt: 1})
	if err != nil || prev != nil {
		t.Fatalf("first put: %v %v", prev, err)
	}
	prev, err = s.PutAccount(Account{Pubkey: strings.Repeat("c", 64), Address: "4B", CreatedAt: 2})
	if err != nil || prev == nil || prev.Address != "4A" {
		t.Fatalf("replace: %v %v", prev, err)
	}
	a, ok, err := s.GetAccount(strings.Repeat("c", 64))
	if err != nil || !ok || a.Address != "4B" || a.CreatedAt != 2 {
		t.Fatalf("get: %+v %v %v", a, ok, err)
	}
	list, _ := s.ListAccounts()
	if len(list) != 1 {
		t.Fatalf("list: %v", list)
	}
	if _, ok, _ := s.GetAccount(strings.Repeat("d", 64)); ok {
		t.Fatal("unknown account found")
	}
	del, ok, err := s.DeleteAccount(strings.Repeat("c", 64))
	if err != nil || !ok || del.Address != "4B" {
		t.Fatalf("delete: %+v %v %v", del, ok, err)
	}
	if _, ok, _ := s.DeleteAccount(strings.Repeat("c", 64)); ok {
		t.Fatal("second delete reported ok")
	}
}

func TestIntentsLifecycle(t *testing.T) {
	s := openTemp(t)
	in := intent("1")
	if ins, err := s.InsertIntent(in, 1001); err != nil || !ins {
		t.Fatalf("insert: %v %v", ins, err)
	}
	if ins, err := s.InsertIntent(in, 1002); err != nil || ins {
		t.Fatalf("duplicate insert: %v %v", ins, err)
	}
	row, ok, _ := s.GetIntent(in.ID)
	if !ok || row.State != StateOpen || row.LWSEventID != "" || row.StoredAt != 1001 || row.Amount != 7 {
		t.Fatalf("row: %+v", row)
	}
	missing, _ := s.IntentsWithoutWebhook(1500)
	if len(missing) != 1 {
		t.Fatalf("without webhook: %v", missing)
	}
	if err := s.SetIntentWebhook(in.ID, "aa11"); err != nil {
		t.Fatal(err)
	}
	if missing, _ = s.IntentsWithoutWebhook(1500); len(missing) != 0 {
		t.Fatalf("after webhook: %v", missing)
	}
	if n, _ := s.CountOpenIntents(in.Recipient); n != 1 {
		t.Fatalf("open count %d", n)
	}
	if err := s.SetIntentState(in.ID, StateSeen); err != nil {
		t.Fatal(err)
	}
	if n, _ := s.CountOpenIntents(in.Recipient); n != 0 {
		t.Fatalf("open count after seen %d", n)
	}
	// not expired yet
	if exp, _ := s.ExpiredIntents(1999); len(exp) != 0 {
		t.Fatalf("expired early: %v", exp)
	}
	exp, _ := s.ExpiredIntents(2000)
	if len(exp) != 1 || exp[0].LWSEventID != "aa11" {
		t.Fatalf("expired: %v", exp)
	}
	if err := s.ClearIntentWebhook(in.ID); err != nil {
		t.Fatal(err)
	}
	// seen intent without webhook is no longer reported as expired work
	if exp, _ = s.ExpiredIntents(2000); len(exp) != 0 {
		t.Fatalf("expired after clear: %v", exp)
	}
	// an open intent past expiration is always reported (row must be deleted)
	open := intent("2")
	s.InsertIntent(open, 1001)
	if exp, _ = s.ExpiredIntents(2000); len(exp) != 1 || exp[0].ID != open.ID {
		t.Fatalf("open expired: %v", exp)
	}
	forRecipient, _ := s.IntentsForRecipient(in.Recipient)
	if len(forRecipient) != 2 {
		t.Fatalf("for recipient: %v", forRecipient)
	}
	if err := s.DeleteIntent(open.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := s.GetIntent(open.ID); ok {
		t.Fatal("deleted intent still present")
	}
	// expired-but-without-webhook rows are excluded from IntentsWithoutWebhook
	late := intent("3")
	late.Expiration = 500
	s.InsertIntent(late, 1001)
	if missing, _ = s.IntentsWithoutWebhook(2000); len(missing) != 0 {
		t.Fatalf("expired intent offered for webhook: %v", missing)
	}
}

// TestIntentPidUniquePerRecipient is finding I1: a second intent with the
// same (recipient, pid) must not be stored, otherwise one payment would
// satisfy two intents and produce two receipts.
func TestIntentPidUniquePerRecipient(t *testing.T) {
	s := openTemp(t)
	first := intent("1")
	if ins, err := s.InsertIntent(first, 1001); err != nil || !ins {
		t.Fatalf("first insert: %v %v", ins, err)
	}
	spoof := intent("2")
	spoof.PID = first.PID
	if ins, err := s.InsertIntent(spoof, 1002); err != nil || ins {
		t.Fatalf("same recipient and pid accepted: %v %v", ins, err)
	}
	if _, ok, _ := s.GetIntent(spoof.ID); ok {
		t.Fatal("spoofed intent stored")
	}
	other := intent("3")
	other.PID = first.PID
	other.Recipient = strings.Repeat("d", 64)
	if ins, err := s.InsertIntent(other, 1003); err != nil || !ins {
		t.Fatalf("same pid for another recipient rejected: %v %v", ins, err)
	}
}

func TestReceiptByTxHash(t *testing.T) {
	s := openTemp(t)
	in := intent("1")
	s.InsertIntent(in, 1001)
	tx := strings.Repeat("7", 64)
	if _, ok, err := s.ReceiptByTxHash(tx); err != nil || ok {
		t.Fatalf("empty: %v %v", ok, err)
	}
	r := Receipt{ID: strings.Repeat("9", 64), IntentID: in.ID, TxHash: tx, Amount: 5, CreatedAt: 1500, SeenAt: 1500, State: ReceiptSeen}
	s.InsertReceipt(r)
	got, ok, err := s.ReceiptByTxHash(tx)
	if err != nil || !ok || got.ID != r.ID || got.IntentID != in.ID {
		t.Fatalf("by tx hash: %+v %v %v", got, ok, err)
	}
}

func TestReceipts(t *testing.T) {
	s := openTemp(t)
	in := intent("1")
	s.InsertIntent(in, 1001)
	r := Receipt{ID: strings.Repeat("9", 64), IntentID: in.ID, TxHash: strings.Repeat("7", 64), Amount: 5, CreatedAt: 1500, SeenAt: 1500, State: ReceiptSeen}
	if ins, err := s.InsertReceipt(r); err != nil || !ins {
		t.Fatalf("insert: %v %v", ins, err)
	}
	dup := r
	dup.ID = strings.Repeat("8", 64)
	if ins, err := s.InsertReceipt(dup); err != nil || ins {
		t.Fatalf("duplicate tx accepted: %v %v", ins, err)
	}
	got, ok, _ := s.ReceiptByTx(in.ID, r.TxHash)
	if !ok || got.ID != r.ID || got.Published {
		t.Fatalf("by tx: %+v %v", got, ok)
	}
	if got, ok, _ := s.GetReceipt(r.ID); !ok || got.Amount != 5 || got.CreatedAt != 1500 {
		t.Fatalf("get: %+v %v", got, ok)
	}
	unpub, _ := s.UnpublishedReceipts()
	if len(unpub) != 1 {
		t.Fatalf("unpublished: %v", unpub)
	}
	s.SetReceiptPublished(r.ID, true)
	if unpub, _ = s.UnpublishedReceipts(); len(unpub) != 0 {
		t.Fatalf("still unpublished: %v", unpub)
	}
	if stale, _ := s.StaleSeenReceipts(1500); len(stale) != 0 {
		t.Fatalf("stale too early: %v", stale)
	}
	stale, _ := s.StaleSeenReceipts(1501)
	if len(stale) != 1 {
		t.Fatalf("stale: %v", stale)
	}
	s.SetReceiptState(r.ID, ReceiptConfirmed)
	if stale, _ = s.StaleSeenReceipts(9999); len(stale) != 0 {
		t.Fatalf("confirmed reported stale: %v", stale)
	}
	// deleting the intent cascades to its receipts
	s.DeleteIntent(in.ID)
	if _, ok, _ := s.GetReceipt(r.ID); ok {
		t.Fatal("receipt survived intent deletion")
	}
}

func TestReplaceReceiptEvent(t *testing.T) {
	s := openTemp(t)
	in := intent("2")
	s.InsertIntent(in, 1001)
	r := Receipt{ID: strings.Repeat("9", 64), IntentID: in.ID, TxHash: strings.Repeat("7", 64), Amount: 5, CreatedAt: 1500, SeenAt: 1500, State: ReceiptDropped}
	if ins, err := s.InsertReceipt(r); err != nil || !ins {
		t.Fatalf("insert: %v %v", ins, err)
	}
	s.SetReceiptPublished(r.ID, true)
	newID := strings.Repeat("a", 64)
	if ok, err := s.ReplaceReceiptEvent(r.ID, newID, 5000, ReceiptConfirmed); err != nil || !ok {
		t.Fatalf("replace: %v %v", ok, err)
	}
	// a second replacement of the old id (concurrent resurrection) is a no-op
	if ok, err := s.ReplaceReceiptEvent(r.ID, strings.Repeat("b", 64), 6000, ReceiptConfirmed); err != nil || ok {
		t.Fatalf("second replace: %v %v", ok, err)
	}
	if _, ok, _ := s.GetReceipt(r.ID); ok {
		t.Fatal("row still found under the old id")
	}
	got, ok, err := s.GetReceipt(newID)
	if err != nil || !ok {
		t.Fatalf("row not found under the new id: %v %v", ok, err)
	}
	if got.State != ReceiptConfirmed || got.Published || got.CreatedAt != 5000 {
		t.Fatalf("row not updated: %+v", got)
	}
	byTx, ok, _ := s.ReceiptByTx(in.ID, r.TxHash)
	if !ok || byTx.ID != newID {
		t.Fatalf("by tx after replace: %+v %v", byTx, ok)
	}
}

func TestRelayCacheAndMeta(t *testing.T) {
	s := openTemp(t)
	if _, _, ok, _ := s.GetRelayCache("x"); ok {
		t.Fatal("empty cache hit")
	}
	s.PutRelayCache("x", []string{"wss://a", "wss://b"}, 42)
	relays, at, ok, err := s.GetRelayCache("x")
	if err != nil || !ok || at != 42 || len(relays) != 2 || relays[1] != "wss://b" {
		t.Fatalf("cache: %v %d %v %v", relays, at, ok, err)
	}
	s.PutRelayCache("x", nil, 43)
	relays, at, ok, _ = s.GetRelayCache("x")
	if !ok || at != 43 || len(relays) != 0 {
		t.Fatalf("empty list: %v %d %v", relays, at, ok)
	}
	if _, ok, _ := s.GetMeta("k"); ok {
		t.Fatal("meta hit")
	}
	s.SetMeta("k", "1")
	s.SetMeta("k", "2")
	if v, ok, _ := s.GetMeta("k"); !ok || v != "2" {
		t.Fatalf("meta: %q %v", v, ok)
	}
	if err := s.DeleteMeta("k"); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := s.GetMeta("k"); ok {
		t.Fatal("meta survived delete")
	}
}

func TestOpenIsIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "w.db")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	s.SetMeta("k", "v")
	s.Close()
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if v, ok, _ := s.GetMeta("k"); !ok || v != "v" {
		t.Fatal("data lost across reopen")
	}
}

func TestPutAccountConcurrentPreviousIsExact(t *testing.T) {
	s := openTemp(t)
	pubkey := strings.Repeat("p", 64)

	var wg sync.WaitGroup
	prevResults := make([]*Account, 20)
	var mu sync.Mutex

	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			addr := string(rune(65 + idx)) // 'A', 'B', 'C', ...
			prev, err := s.PutAccount(Account{Pubkey: pubkey, Address: addr, CreatedAt: int64(idx)})
			if err != nil {
				t.Errorf("PutAccount error: %v", err)
				return
			}
			mu.Lock()
			prevResults[idx] = prev
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	// Exactly one call should get previous == nil
	nilCount := 0
	for _, prev := range prevResults {
		if prev == nil {
			nilCount++
		}
	}
	if nilCount != 1 {
		t.Fatalf("expected exactly 1 nil previous, got %d", nilCount)
	}

	// Verify one account exists with one of the written addresses
	final, ok, err := s.GetAccount(pubkey)
	if err != nil || !ok {
		t.Fatalf("final get failed: %v %v", ok, err)
	}
	// The final address should be one of A-T
	if final.Address < "A" || final.Address > "T" {
		t.Fatalf("unexpected final address: %q", final.Address)
	}
}

func receiptFor(intentID, id, tx string, st ReceiptState) Receipt {
	return Receipt{ID: strings.Repeat(id, 64), IntentID: intentID, TxHash: strings.Repeat(tx, 64), Amount: 5, CreatedAt: 1500, SeenAt: 1500, State: st}
}

// TestConditionalReceiptTransitions is finding I4: seen → dropped and
// seen → confirmed only happen from 'seen', so a confirmation racing a
// drop (or the reverse) is detected instead of silently overwritten.
func TestConditionalReceiptTransitions(t *testing.T) {
	s := openTemp(t)
	in := intent("1")
	s.InsertIntent(in, 1001)
	a := receiptFor(in.ID, "9", "7", ReceiptSeen)
	b := receiptFor(in.ID, "8", "6", ReceiptSeen)
	s.InsertReceipt(a)
	s.InsertReceipt(b)

	if ok, err := s.ConfirmReceiptIfSeen(a.ID); err != nil || !ok {
		t.Fatalf("confirm seen: %v %v", ok, err)
	}
	if ok, err := s.ConfirmReceiptIfSeen(a.ID); err != nil || ok {
		t.Fatalf("confirm confirmed: %v %v", ok, err)
	}
	if ok, err := s.DropReceiptIfSeen(a.ID); err != nil || ok {
		t.Fatalf("drop confirmed: %v %v", ok, err)
	}
	if got, _, _ := s.GetReceipt(a.ID); got.State != ReceiptConfirmed {
		t.Fatalf("confirmed receipt changed to %s", got.State)
	}
	if ok, err := s.DropReceiptIfSeen(b.ID); err != nil || !ok {
		t.Fatalf("drop seen: %v %v", ok, err)
	}
	if ok, err := s.DropReceiptIfSeen(b.ID); err != nil || ok {
		t.Fatalf("drop dropped: %v %v", ok, err)
	}
	if ok, err := s.ConfirmReceiptIfSeen(b.ID); err != nil || ok {
		t.Fatalf("confirm dropped: %v %v", ok, err)
	}
	if got, _, _ := s.GetReceipt(b.ID); got.State != ReceiptDropped {
		t.Fatalf("dropped receipt changed to %s", got.State)
	}
	if ok, err := s.ConfirmReceiptIfSeen(strings.Repeat("0", 64)); err != nil || ok {
		t.Fatalf("confirm unknown: %v %v", ok, err)
	}
}

func TestDeleteIntentIfOpen(t *testing.T) {
	s := openTemp(t)
	open := intent("1")
	s.InsertIntent(open, 1001)
	if ok, err := s.DeleteIntentIfOpen(open.ID); err != nil || !ok {
		t.Fatalf("delete open: %v %v", ok, err)
	}
	if ok, err := s.DeleteIntentIfOpen(open.ID); err != nil || ok {
		t.Fatalf("delete missing: %v %v", ok, err)
	}

	seen := intent("2")
	s.InsertIntent(seen, 1001)
	s.SetIntentState(seen.ID, StateSeen)
	if ok, err := s.DeleteIntentIfOpen(seen.ID); err != nil || ok {
		t.Fatalf("delete seen: %v %v", ok, err)
	}

	// state still 'open' but a live receipt landed: keep it
	racing := intent("3")
	s.InsertIntent(racing, 1001)
	s.InsertReceipt(receiptFor(racing.ID, "9", "7", ReceiptSeen))
	if ok, err := s.DeleteIntentIfOpen(racing.ID); err != nil || ok {
		t.Fatalf("delete open with live receipt: %v %v", ok, err)
	}
	if _, ok, _ := s.GetIntent(racing.ID); !ok {
		t.Fatal("intent with live receipt deleted")
	}

	// only dropped receipts: deletable
	dropped := intent("4")
	s.InsertIntent(dropped, 1001)
	s.InsertReceipt(receiptFor(dropped.ID, "8", "6", ReceiptDropped))
	if ok, err := s.DeleteIntentIfOpen(dropped.ID); err != nil || !ok {
		t.Fatalf("delete open with dropped receipt: %v %v", ok, err)
	}
}

func TestRecomputeIntentState(t *testing.T) {
	cases := []struct {
		name     string
		receipts []ReceiptState
		want     IntentState
	}{
		{"no receipts", nil, StateOpen},
		{"one seen", []ReceiptState{ReceiptSeen}, StateSeen},
		{"one dropped", []ReceiptState{ReceiptDropped}, StateOpen},
		{"one confirmed", []ReceiptState{ReceiptConfirmed}, StateConfirmed},
		{"confirmed and seen", []ReceiptState{ReceiptConfirmed, ReceiptSeen}, StateConfirmed},
		{"confirmed and dropped", []ReceiptState{ReceiptDropped, ReceiptConfirmed}, StateConfirmed},
		{"two seen one dropped", []ReceiptState{ReceiptSeen, ReceiptDropped, ReceiptSeen}, StateSeen},
		{"two dropped", []ReceiptState{ReceiptDropped, ReceiptDropped}, StateOpen},
	}
	ids := []string{"a", "b", "c"}
	for _, c := range cases {
		s := openTemp(t)
		in := intent("1")
		s.InsertIntent(in, 1001)
		s.SetIntentState(in.ID, StateSeen) // start from a wrong state on purpose
		for i, st := range c.receipts {
			s.InsertReceipt(receiptFor(in.ID, ids[i], ids[i], st))
		}
		got, err := s.RecomputeIntentState(in.ID)
		if err != nil || got != c.want {
			t.Errorf("%s: got %q %v, want %q", c.name, got, err, c.want)
			continue
		}
		if row, _, _ := s.GetIntent(in.ID); row.State != c.want {
			t.Errorf("%s: stored %q, want %q", c.name, row.State, c.want)
		}
	}
}
