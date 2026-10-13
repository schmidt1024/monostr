package personal

import (
	"bytes"
	"cmp"
	"context"
	"errors"
	"log/slog"
	"strings"
	"testing"
	"time"

	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/store"
	"monostr.com/watcher/internal/walletrpc"
)

const pid = "1111111111111111"

type fakeWallet struct {
	transfers    []walletrpc.Transfer
	transfersErr error
	daemons      []string // set_daemon attempts
	daemonErr    error
	height       uint64 // get_height
}

func (f *fakeWallet) Height(context.Context) (uint64, error) { return f.height, nil }

func (f *fakeWallet) IncomingTransfers(context.Context) ([]walletrpc.Transfer, error) {
	if f.transfersErr != nil {
		return nil, f.transfersErr
	}
	return f.transfers, nil
}
func (f *fakeWallet) SetDaemon(_ context.Context, a, _ string) error {
	f.daemons = append(f.daemons, a)
	return f.daemonErr
}

// fakeNodes fails over to scan-next / check-next after 3 failures of a group.
type fakeNodes struct {
	heightErr, checkHeightErr error
	failures, checkFailures   int
	scanH, checkH             uint64 // node heights, 100 when 0
}

func (f *fakeNodes) Height(context.Context) (uint64, error) {
	return cmp.Or(f.scanH, 100), f.heightErr
}
func (f *fakeNodes) CheckHeight(context.Context) (uint64, error) {
	return cmp.Or(f.checkH, 100), f.checkHeightErr
}
func (f *fakeNodes) ReportSuccess()      { f.failures = 0 }
func (f *fakeNodes) ReportCheckSuccess() { f.checkFailures = 0 }
func (f *fakeNodes) ReportFailure() (string, bool) {
	f.failures++
	if f.failures == 3 {
		return "https://scan-next", true
	}
	return "https://scan", false
}
func (f *fakeNodes) ReportCheckFailure() (string, bool) {
	f.checkFailures++
	if f.checkFailures == 3 {
		return "https://check-next", true
	}
	return "https://check", false
}

type fakeIntents []store.IntentRow

func (f fakeIntents) IntentsForRecipient(string) ([]store.IntentRow, error) { return f, nil }

type fakeHandler struct{ hooks []lws.Hook }

func (f *fakeHandler) HandleHook(_ context.Context, h lws.Hook) error {
	f.hooks = append(f.hooks, h)
	return nil
}

func intent() store.IntentRow {
	return store.IntentRow{Intent: protocol.Intent{ID: "intent1", PID: pid}, LWSEventID: walletrpc.EventID(pid)}
}

// transfer is a pool transfer of the test pid.
func transfer(txid string, amount uint64) walletrpc.Transfer {
	return walletrpc.Transfer{TxID: txid, PaymentID: pid, Amount: amount}
}

// newPoller returns a poller whose check wallet holds a copy of the scan wallet's transfers.
func newPoller(w *fakeWallet, n *fakeNodes, rows fakeIntents) (*Poller, *fakeWallet, *fakeHandler) {
	h := &fakeHandler{}
	w.height = cmp.Or(w.height, 100) // synced to the fake nodes
	cw := &fakeWallet{transfers: append([]walletrpc.Transfer(nil), w.transfers...), height: 100}
	return &Poller{Wallet: w, CheckWallet: cw, Nodes: n, Intents: rows, Handler: h, Recipient: "r",
		Log: slog.New(slog.DiscardHandler), Now: time.Now}, cw, h
}

func TestNormalizePID(t *testing.T) {
	if got := NormalizePID("1111111111111111" + strings.Repeat("0", 48)); got != pid {
		t.Fatalf("padded: %q", got)
	}
	if got := NormalizePID("ABCDEF0123456789"); got != "abcdef0123456789" {
		t.Fatalf("upper: %q", got)
	}
}

func TestPollerPoolThenConfirmed(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	p.Poll(context.Background())
	p.Poll(context.Background()) // same state: no second hook
	if len(h.hooks) != 1 {
		t.Fatalf("hooks %v", h.hooks)
	}
	want := lws.Hook{PaymentID: pid, Token: "intent1", EventID: "wr-" + pid, TxHash: "tx1", Confirmations: 0, Amount: 5}
	if h.hooks[0] != want {
		t.Fatalf("hook %+v", h.hooks[0])
	}
	w.transfers[0].Confirmations, w.transfers[0].Height = 1, 77
	cw.transfers[0].Confirmations, cw.transfers[0].Height = 1, 77
	p.Poll(context.Background())
	p.Poll(context.Background())
	if len(h.hooks) != 2 || h.hooks[1].Confirmations != 1 || h.hooks[1].Block != 77 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerPaddedPIDMatches(t *testing.T) {
	tr := transfer("tx1", 5)
	tr.PaymentID = pid + strings.Repeat("0", 48)
	w := &fakeWallet{transfers: []walletrpc.Transfer{tr}}
	p, _, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	p.Poll(context.Background())
	if len(h.hooks) != 1 || h.hooks[0].PaymentID != pid {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerNeedsCheckWalletAgreement(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	cw.transfers = nil
	if err := p.Poll(context.Background()); err != nil || len(h.hooks) != 0 {
		t.Fatalf("err %v hooks %+v", err, h.hooks)
	}
	cw.transfers = []walletrpc.Transfer{transfer("tx1", 5)}
	p.Poll(context.Background())
	if len(h.hooks) != 1 || h.hooks[0].Confirmations != 0 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerAmountMismatchHeld(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	cw.transfers[0].Amount = 6
	p.Poll(context.Background())
	p.Poll(context.Background())
	if len(h.hooks) != 0 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerPIDMismatchHeld(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	cw.transfers[0].PaymentID = "2222222222222222"
	p.Poll(context.Background())
	if len(h.hooks) != 0 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerConfirmedOnlyWhenBothConfirm(t *testing.T) {
	tr := transfer("tx1", 5)
	tr.Confirmations, tr.Height = 2, 70
	w := &fakeWallet{transfers: []walletrpc.Transfer{tr}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	cw.transfers[0].Confirmations, cw.transfers[0].Height = 0, 0
	p.Poll(context.Background())
	p.Poll(context.Background())
	if len(h.hooks) != 1 || h.hooks[0].Confirmations != 0 {
		t.Fatalf("expected one 0-conf hook first: %+v", h.hooks)
	}
	cw.transfers[0].Confirmations, cw.transfers[0].Height = 1, 71
	p.Poll(context.Background())
	if len(h.hooks) != 2 || h.hooks[1].Confirmations != 1 || h.hooks[1].Block != 70 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerCheckTransferWithMoreConfirmationsWins(t *testing.T) {
	tr := transfer("tx1", 5)
	tr.Confirmations, tr.Height = 3, 70
	w := &fakeWallet{transfers: []walletrpc.Transfer{tr}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	cw.transfers = []walletrpc.Transfer{tr, transfer("tx1", 5)} // in, then a stale pool entry
	cw.transfers[0].Confirmations, cw.transfers[1].Pool = 5, true
	p.Poll(context.Background())
	if len(h.hooks) != 1 || h.hooks[0].Confirmations != 3 { // the smaller of scan 3 and check 5
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerCheckWalletUnreadHoldsAll(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	p.Now = func() time.Time { return now }
	cw.transfers = nil
	p.Poll(context.Background()) // both read, nothing to agree on
	cw.transfers = []walletrpc.Transfer{transfer("tx1", 5)}
	cw.transfersErr = errors.New("connection refused")
	now = now.Add(time.Minute)
	if err := p.Poll(context.Background()); err != nil || len(h.hooks) != 0 {
		t.Fatalf("err %v hooks %+v", err, h.hooks)
	}
	if !p.Healthy(now.Add(3 * time.Minute)) {
		t.Fatal("unhealthy 4 minutes after the last check-wallet read")
	}
	if p.Healthy(now.Add(5 * time.Minute)) {
		t.Fatal("healthy 6 minutes after the last check-wallet read")
	}
}

func TestPollerIgnoresUnknownPIDAndIntentsWithoutEventID(t *testing.T) {
	row := intent()
	row.LWSEventID = ""
	other := transfer("tx2", 5)
	other.PaymentID = "2222222222222222"
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5), other}}
	p, _, h := newPoller(w, &fakeNodes{}, fakeIntents{row})
	p.Poll(context.Background())
	if len(h.hooks) != 0 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerFailoverPerGroup(t *testing.T) {
	w := &fakeWallet{}
	n := &fakeNodes{heightErr: errors.New("down")}
	p, cw, _ := newPoller(w, n, nil)
	for i := 0; i < 3; i++ {
		p.Poll(context.Background())
	}
	if len(w.daemons) != 1 || w.daemons[0] != "https://scan-next" || len(cw.daemons) != 0 {
		t.Fatalf("scan failover: scan %v check %v", w.daemons, cw.daemons)
	}
	n.heightErr, n.checkHeightErr = nil, errors.New("down")
	for i := 0; i < 3; i++ {
		p.Poll(context.Background())
	}
	if len(w.daemons) != 1 || len(cw.daemons) != 1 || cw.daemons[0] != "https://check-next" {
		t.Fatalf("check failover: scan %v check %v", w.daemons, cw.daemons)
	}
}

// After a failover whose set_daemon fails for the check wallet, each wallet
// is still only ever pointed at nodes of its own group.
func TestPollerFailedCheckSetDaemonStaysInGroup(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	n := &fakeNodes{heightErr: errors.New("down"), checkHeightErr: errors.New("down")}
	p, cw, h := newPoller(w, n, fakeIntents{intent()})
	cw.daemonErr = errors.New("wallet-rpc busy")
	for i := 0; i < 4; i++ {
		p.Poll(context.Background())
	}
	if len(w.daemons) == 0 || len(cw.daemons) < 2 {
		t.Fatalf("no failover: scan %v check %v", w.daemons, cw.daemons)
	}
	for _, d := range w.daemons {
		if !strings.HasPrefix(d, "https://scan") {
			t.Fatalf("scan wallet pointed at %q", d)
		}
	}
	for _, d := range cw.daemons {
		if !strings.HasPrefix(d, "https://check") {
			t.Fatalf("check wallet pointed at %q", d)
		}
	}
	if len(h.hooks) != 1 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerLogsCheckOutageOnce(t *testing.T) {
	var buf bytes.Buffer
	w := &fakeWallet{}
	p, cw, _ := newPoller(w, &fakeNodes{}, nil)
	p.Log = slog.New(slog.NewTextHandler(&buf, nil))
	cw.transfersErr = errors.New("connection refused")
	for i := 0; i < 3; i++ {
		p.Poll(context.Background())
	}
	cw.transfersErr = nil
	p.Poll(context.Background())
	p.Poll(context.Background())
	out := buf.String()
	if strings.Count(out, "check wallet unreadable") != 1 || !strings.Contains(out, "level=WARN") ||
		strings.Count(out, "check wallet readable again") != 1 {
		t.Fatalf("log:\n%s", out)
	}
}

func TestPollerReconcilesWalletAfterRPCError(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, _, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	w.transfersErr = &walletrpc.RPCError{Code: -13, Message: "No wallet file"}
	ensured := 0
	p.Ensure = func(context.Context) error {
		ensured++
		w.transfersErr = nil // wallet reopened
		return nil
	}
	if err := p.Poll(context.Background()); err == nil {
		t.Fatal("no error from the failed poll")
	}
	if ensured != 1 {
		t.Fatalf("ensure called %d times", ensured)
	}
	if err := p.Poll(context.Background()); err != nil || len(h.hooks) != 1 {
		t.Fatalf("err %v hooks %+v", err, h.hooks)
	}
}

func TestPollerReconcilesCheckWallet(t *testing.T) {
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	cw.transfersErr = &walletrpc.RPCError{Code: -13, Message: "No wallet file"}
	ensured := 0
	p.Ensure = func(context.Context) error { t.Fatal("scan wallet reconciled"); return nil }
	p.CheckEnsure = func(context.Context) error {
		ensured++
		cw.transfersErr = nil
		return nil
	}
	if err := p.Poll(context.Background()); err != nil || len(h.hooks) != 0 {
		t.Fatalf("err %v hooks %+v", err, h.hooks)
	}
	if ensured != 1 {
		t.Fatalf("check ensure called %d times", ensured)
	}
	p.Poll(context.Background())
	if len(h.hooks) != 1 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

func TestPollerDoesNotReconcileWhenWalletUnreachable(t *testing.T) {
	w := &fakeWallet{transfersErr: errors.New("connection refused")}
	p, cw, _ := newPoller(w, &fakeNodes{}, nil)
	cw.transfersErr = errors.New("connection refused")
	ensured := 0
	p.Ensure = func(context.Context) error { ensured++; return nil }
	p.CheckEnsure = p.Ensure
	p.Poll(context.Background())
	if ensured != 0 {
		t.Fatalf("ensure called %d times", ensured)
	}
}

func TestPollerRetriesFailedSetDaemon(t *testing.T) {
	w := &fakeWallet{daemonErr: errors.New("wallet-rpc down")}
	n := &fakeNodes{heightErr: errors.New("down")}
	p, cw, _ := newPoller(w, n, nil)
	cw.daemonErr = w.daemonErr
	for i := 0; i < 3; i++ {
		p.Poll(context.Background()) // third poll fails over, set_daemon fails
	}
	n.heightErr = nil
	p.Poll(context.Background()) // still failing: retried
	w.daemonErr, cw.daemonErr = nil, nil
	p.Poll(context.Background()) // succeeds
	p.Poll(context.Background()) // nothing pending
	if len(w.daemons) != 3 || w.daemons[1] != "https://scan-next" || w.daemons[2] != "https://scan-next" {
		t.Fatalf("scan daemons %v", w.daemons)
	}
	if len(cw.daemons) != 0 {
		t.Fatalf("check daemons %v", cw.daemons)
	}
}

func TestPollerHealthy(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{}
	p, _, _ := newPoller(w, &fakeNodes{}, nil)
	p.Now = func() time.Time { return now }
	if p.Healthy(now) {
		t.Fatal("healthy before any poll")
	}
	p.Poll(context.Background())
	if !p.Healthy(now.Add(4 * time.Minute)) {
		t.Fatal("not healthy right after a good poll")
	}
	w.transfersErr = errors.New("down")
	now = now.Add(time.Minute)
	p.Poll(context.Background())
	if p.Healthy(now.Add(5 * time.Minute)) {
		t.Fatal("healthy 6 minutes after the last good poll")
	}
}

func TestPollerNeedsCheckWallet(t *testing.T) {
	p, _, _ := newPoller(&fakeWallet{}, &fakeNodes{}, nil)
	p.CheckWallet = nil
	if err := p.Poll(context.Background()); err == nil {
		t.Fatal("poll without a check wallet")
	}
}

// wallet2 lists one "in" entry per subaddress of the same tx: both sides are
// compared on the sum per txid.
func TestPollerSumsEntriesPerTx(t *testing.T) {
	split := func(confs uint64, amounts ...uint64) []walletrpc.Transfer {
		var out []walletrpc.Transfer
		for _, a := range amounts {
			tr := transfer("tx1", a)
			tr.Confirmations, tr.Height = confs, 70
			out = append(out, tr)
		}
		return out
	}
	for name, sides := range map[string][2][]walletrpc.Transfer{
		"scan split":  {split(2, 3, 2), split(2, 5)},
		"check split": {split(2, 5), split(2, 1, 4)},
		"both split":  {split(2, 3, 2), split(2, 4, 1)},
	} {
		w := &fakeWallet{transfers: sides[0]}
		p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
		cw.transfers = sides[1]
		p.Poll(context.Background())
		p.Poll(context.Background())
		if len(h.hooks) != 1 || h.hooks[0].Amount != 5 || h.hooks[0].Confirmations != 2 {
			t.Fatalf("%s: hooks %+v", name, h.hooks)
		}
	}
}

// A tx still listed in the pool next to its confirmed entries is not counted twice.
func TestPollerPoolEntryNotAddedToConfirmed(t *testing.T) {
	in := transfer("tx1", 5)
	in.Confirmations, in.Height = 1, 70
	stale := transfer("tx1", 5)
	stale.Pool = true
	w := &fakeWallet{transfers: []walletrpc.Transfer{in, stale}}
	p, _, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	p.Poll(context.Background())
	if len(h.hooks) != 1 || h.hooks[0].Amount != 5 || h.hooks[0].Confirmations != 1 {
		t.Fatalf("hooks %+v", h.hooks)
	}
}

// halfEnsure is an Ensure whose open_wallet works (the wallet answers again)
// but whose set_daemon fails failing times.
func halfEnsure(w *fakeWallet, failing int, calls *int) func(context.Context) error {
	return func(context.Context) error {
		*calls++
		w.transfersErr = nil // open_wallet succeeded
		if *calls <= failing {
			return errors.New("set_daemon: resolve proxy")
		}
		return nil
	}
}

func TestPollerRetriesHalfFailedEnsure(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, _, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	p.Now = func() time.Time { return now }
	p.Poll(context.Background()) // both wallets read once
	h.hooks = nil
	p.forwarded = nil
	w.transfersErr = &walletrpc.RPCError{Code: -13, Message: "No wallet file"}
	ensured := 0
	p.Ensure = halfEnsure(w, 2, &ensured)
	p.Poll(context.Background()) // get_transfers fails, ensure 1 fails half-way
	p.Poll(context.Background()) // get_transfers would work: ensure 2 still retried, fails
	if ensured != 2 || len(h.hooks) != 0 {
		t.Fatalf("ensure %d hooks %+v", ensured, h.hooks)
	}
	if p.Healthy(now) {
		t.Fatal("healthy while the scan wallet is not set up")
	}
	p.Poll(context.Background()) // ensure 3 succeeds
	p.Poll(context.Background()) // nothing pending any more
	if ensured != 3 || len(h.hooks) != 1 {
		t.Fatalf("ensure %d hooks %+v", ensured, h.hooks)
	}
	if !p.Healthy(now) {
		t.Fatal("unhealthy after the wallet was set up again")
	}
}

func TestPollerHoldsWhileCheckEnsurePending(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{transfers: []walletrpc.Transfer{transfer("tx1", 5)}}
	p, cw, h := newPoller(w, &fakeNodes{}, fakeIntents{intent()})
	p.Now = func() time.Time { return now }
	cw.transfersErr = &walletrpc.RPCError{Code: -13, Message: "No wallet file"}
	ensured := 0
	p.CheckEnsure = halfEnsure(cw, 2, &ensured)
	p.Poll(context.Background())
	p.Poll(context.Background())
	if ensured != 2 || len(h.hooks) != 0 {
		t.Fatalf("check ensure %d hooks %+v", ensured, h.hooks)
	}
	if p.Healthy(now) {
		t.Fatal("healthy while the check wallet is not set up")
	}
	p.Poll(context.Background())
	if ensured != 3 || len(h.hooks) != 1 {
		t.Fatalf("check ensure %d hooks %+v", ensured, h.hooks)
	}
}

func TestPollerWalletCatchingUpIsNotStalled(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{height: 100}
	n := &fakeNodes{scanH: 5000}
	p, cw, _ := newPoller(w, n, nil)
	p.Now = func() time.Time { return now }
	for i := 0; i < 30; i++ {
		p.Poll(context.Background())
		w.height++ // far behind, but syncing
	}
	if len(w.daemons) != 0 || len(cw.daemons) != 0 || n.failures != 0 || !p.Healthy(now) {
		t.Fatalf("daemons %v %v failures %d healthy %v", w.daemons, cw.daemons, n.failures, p.Healthy(now))
	}
}

func TestPollerWalletAtTipIsNotStalled(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{height: 100}
	n := &fakeNodes{scanH: 110} // 10 blocks: within the tolerance
	p, _, _ := newPoller(w, n, nil)
	p.Now = func() time.Time { return now }
	for i := 0; i < 30; i++ {
		p.Poll(context.Background())
	}
	if len(w.daemons) != 0 || n.failures != 0 || !p.Healthy(now) {
		t.Fatalf("daemons %v failures %d", w.daemons, n.failures)
	}
}

func TestPollerStalledScanWallet(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{height: 100}
	n := &fakeNodes{scanH: 5000}
	p, cw, _ := newPoller(w, n, nil)
	p.Now = func() time.Time { return now }
	for i := 0; i < 8; i++ { // first poll is the baseline, then 7 flat polls
		p.Poll(context.Background())
	}
	if len(w.daemons) != 0 || n.failures != 0 || !p.Healthy(now) {
		t.Fatalf("stalled too early: daemons %v failures %d", w.daemons, n.failures)
	}
	p.Poll(context.Background()) // 8th flat poll
	if len(w.daemons) != 1 || w.daemons[0] != "https://scan" || n.failures != 1 {
		t.Fatalf("stall: daemons %v failures %d", w.daemons, n.failures)
	}
	if p.Healthy(now) {
		t.Fatal("healthy while stalled")
	}
	for i := 0; i < 16; i++ { // two more stalls: the group fails over
		p.Poll(context.Background())
	}
	if len(w.daemons) != 3 || w.daemons[2] != "https://scan-next" || len(cw.daemons) != 0 {
		t.Fatalf("failover: scan %v check %v", w.daemons, cw.daemons)
	}
	w.height++ // syncing again
	p.Poll(context.Background())
	if !p.Healthy(now) {
		t.Fatal("unhealthy after the wallet moved on")
	}
}

func TestPollerStalledCheckWallet(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	w := &fakeWallet{}
	n := &fakeNodes{checkH: 5000}
	p, cw, _ := newPoller(w, n, nil)
	p.Now = func() time.Time { return now }
	for i := 0; i < 9; i++ {
		p.Poll(context.Background())
	}
	if len(cw.daemons) != 1 || cw.daemons[0] != "https://check" || n.checkFailures != 1 || len(w.daemons) != 0 || n.failures != 0 {
		t.Fatalf("check stall: check %v (%d) scan %v (%d)", cw.daemons, n.checkFailures, w.daemons, n.failures)
	}
	if p.Healthy(now) {
		t.Fatal("healthy while the check wallet is stalled")
	}
}
