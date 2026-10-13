// Package personal runs the personal watcher (spec 0.4 §3): it turns the
// incoming transfers of the recipient's view-only wallet into the hooks the
// watcher service already understands, and publishes the watcher status.
package personal

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/store"
	"monostr.com/watcher/internal/walletrpc"
)

type Wallet interface {
	IncomingTransfers(ctx context.Context) ([]walletrpc.Transfer, error)
	SetDaemon(ctx context.Context, address, proxy string) error
	Height(ctx context.Context) (uint64, error) // how far the wallet has synced
}

type Nodes interface {
	Height(ctx context.Context) (uint64, error)
	ReportFailure() (next string, switched bool)
	ReportSuccess()
	CheckHeight(ctx context.Context) (uint64, error)
	ReportCheckFailure() (next string, switched bool)
	ReportCheckSuccess()
}

type Intents interface {
	IntentsForRecipient(pubkey string) ([]store.IntentRow, error)
}

type HookHandler interface {
	HandleHook(ctx context.Context, h lws.Hook) error
}

// healthyWithin is how recent the last successful poll of each wallet must be for the watcher to count as healthy.
const healthyWithin = 5 * time.Minute

// A wallet more than stallBehind blocks behind its node whose height has not
// grown for stallPolls polls in a row (about 2 minutes) is stalled: its proxy
// IP may be stale or its node serves no blocks.
const (
	stallBehind = 10
	stallPolls  = 8
)

// holdWarnAfter is how long a hook may wait for the check wallet before a warning is logged.
const holdWarnAfter = time.Hour

const (
	levelPool      = 1
	levelConfirmed = 2
)

// Poller reads two view-only wallets of the same address: the scan wallet
// syncs through the scan node group, the check wallet through the disjoint
// check node group (spec 0.4 §3.4). A payment is handed on only when both report it alike.
type Poller struct {
	Wallet      Wallet // scan wallet
	CheckWallet Wallet
	Nodes       Nodes
	Intents     Intents
	Handler     HookHandler
	Recipient   string
	Proxy       string
	Log         *slog.Logger
	Now         func() time.Time
	// Ensure reopens the scan wallet and points it at the scan node, CheckEnsure
	// the check wallet at the check node. Poll calls them when wallet-rpc answers
	// with an error (e.g. no wallet open after a restart).
	Ensure      func(ctx context.Context) error
	CheckEnsure func(ctx context.Context) error

	scan, check walletState

	forwarded map[string]int       // txid → highest level handed to Handler
	heldSince map[string]time.Time // txid → first time the check wallet did not back it
	warned    map[string]bool
	disagreed map[string]bool // txid → mismatch already logged
	checkDown bool            // check wallet outage already logged
}

type walletState struct {
	pendingDaemon string    // node set_daemon failed for, retried each poll
	needsEnsure   bool      // reopening failed (maybe after open_wallet): retried each poll, wallet unread meanwhile
	lastGood      time.Time // last successful get_transfers

	height     uint64 // wallet height at the last poll
	heightSeen bool
	flatPolls  int  // polls in a row behind the node without progress
	stalled    bool // until the wallet's height grows or it reaches its node
}

// NormalizePID lower-cases a payment id and cuts the 48 zero chars wallet-rpc may pad a short id with.
func NormalizePID(s string) string {
	s = strings.ToLower(s)
	if len(s) == 64 && strings.Trim(s[16:], "0") == "" {
		return s[:16]
	}
	return s
}

// Poll runs one round: scan-node health and failover, then every incoming
// transfer of the scan wallet whose pid belongs to an intent of the recipient
// is handed on as a hook, once per txid and level, when the check wallet
// reports the same txid with the same pid and amount.
func (p *Poller) Poll(ctx context.Context) error {
	if p.CheckWallet == nil {
		return errors.New("no check wallet")
	}
	if p.forwarded == nil {
		p.forwarded, p.heldSince = map[string]int{}, map[string]time.Time{}
		p.warned, p.disagreed = map[string]bool{}, map[string]bool{}
	}
	// each wallet fails over within its own node group only; a node whose
	// wallet is stalled gets no success, so stall failures add up to a failover
	scanNode, scanNodeErr := p.Nodes.Height(ctx)
	if scanNodeErr != nil {
		if next, switched := p.Nodes.ReportFailure(); switched {
			p.Log.Warn("scan node unreachable, switching", "next", next)
			p.scan.pendingDaemon = next
		}
	} else if !p.scan.stalled {
		p.Nodes.ReportSuccess()
	}
	checkNode, checkNodeErr := p.Nodes.CheckHeight(ctx)
	if checkNodeErr != nil {
		if next, switched := p.Nodes.ReportCheckFailure(); switched {
			p.Log.Warn("check node unreachable, switching", "next", next)
			p.check.pendingDaemon = next
		}
	} else if !p.check.stalled {
		p.Nodes.ReportCheckSuccess()
	}
	transfers, scanErr := p.read(ctx, "scan", p.Wallet, &p.scan, p.Ensure)
	checkTransfers, checkErr := p.read(ctx, "check", p.CheckWallet, &p.check, p.CheckEnsure)
	if scanErr == nil && scanNodeErr == nil {
		p.watchStall(ctx, "scan", p.Wallet, &p.scan, scanNode, p.Nodes.ReportFailure)
	}
	if checkErr == nil && checkNodeErr == nil {
		p.watchStall(ctx, "check", p.CheckWallet, &p.check, checkNode, p.Nodes.ReportCheckFailure)
	}
	if checkErr != nil && !p.checkDown {
		p.checkDown = true
		p.Log.Warn("check wallet unreadable, holding payments", "err", checkErr.Error())
	} else if checkErr == nil && p.checkDown {
		p.checkDown = false
		p.Log.Info("check wallet readable again")
	}
	if scanErr != nil {
		return scanErr
	}
	rows, err := p.Intents.IntentsForRecipient(p.Recipient)
	if err != nil {
		return err
	}
	byPID := map[string]store.IntentRow{}
	for _, r := range rows {
		if r.LWSEventID != "" {
			byPID[r.PID] = r
		}
	}
	checkByTx := map[string]walletrpc.Transfer{}
	for _, ct := range perTx(checkTransfers) {
		checkByTx[ct.TxID] = ct
	}
	for _, tr := range perTx(transfers) {
		pid := NormalizePID(tr.PaymentID)
		row, ok := byPID[pid]
		if !ok {
			continue
		}
		want := levelPool
		if tr.Confirmations >= 1 {
			want = levelConfirmed
		}
		if p.forwarded[tr.TxID] >= want {
			continue
		}
		ct, ok := checkByTx[tr.TxID]
		if checkErr != nil || !ok {
			p.hold(tr.TxID)
			continue
		}
		if NormalizePID(ct.PaymentID) != pid || ct.Amount != tr.Amount {
			if !p.disagreed[tr.TxID] {
				p.disagreed[tr.TxID] = true
				p.Log.Warn("check wallet disagrees", "tx", tr.TxID, "amount", tr.Amount, "check_amount", ct.Amount)
			}
			p.hold(tr.TxID)
			continue
		}
		hook := lws.Hook{PaymentID: pid, Token: row.ID, EventID: row.LWSEventID, TxHash: tr.TxID, Amount: int64(tr.Amount)}
		level := levelPool
		if want == levelConfirmed && ct.Confirmations >= 1 {
			level = levelConfirmed
			hook.Confirmations, hook.Block = uint32(min(tr.Confirmations, ct.Confirmations, 1000)), tr.Height
		} else if p.forwarded[tr.TxID] >= levelPool {
			// only the scan wallet sees a confirmation: wait for the check wallet
			p.hold(tr.TxID)
			continue
		}
		if err := p.Handler.HandleHook(ctx, hook); err != nil {
			p.Log.Error("handle hook", "intent", row.ID, "err", err.Error())
			continue
		}
		p.forwarded[tr.TxID] = level
		delete(p.heldSince, tr.TxID)
		delete(p.warned, tr.TxID)
		delete(p.disagreed, tr.TxID)
	}
	return nil
}

// watchStall compares the wallet's height with its node's. When the wallet
// is stalled it points the wallet at its group's node again (resolving the
// proxy anew) and counts a failure for the group, so a node that answers
// get_info but serves no blocks is left after failoverAfter stalls.
func (p *Poller) watchStall(ctx context.Context, name string, w Wallet, st *walletState, node uint64, reportFailure func() (string, bool)) {
	height, err := w.Height(ctx)
	if err != nil {
		return
	}
	behind := node > height+stallBehind
	if !st.heightSeen || height > st.height || !behind {
		if st.stalled {
			p.Log.Info("wallet syncing again", "wallet", name, "height", height)
		}
		st.flatPolls, st.stalled = 0, false
	} else {
		st.flatPolls++
	}
	st.height, st.heightSeen = height, true
	if st.flatPolls < stallPolls {
		return
	}
	st.flatPolls, st.stalled = 0, true
	next, switched := reportFailure()
	p.Log.Warn("wallet stalled, setting its node again", "wallet", name, "height", height, "node_height", node, "switched", switched)
	if err := w.SetDaemon(ctx, next, p.Proxy); err != nil {
		p.Log.Error("set_daemon failed, retrying next poll", "wallet", name, "err", err.Error())
		st.pendingDaemon = next
		return
	}
	st.pendingDaemon = ""
}

// perTx folds the transfers into one per txid, in first-seen order. wallet2
// lists one entry per receiving subaddress, so the amounts of a tx are summed;
// a tx listed both as confirmed and (stale) in the pool counts once, as
// confirmed, with the most confirmations.
func perTx(transfers []walletrpc.Transfer) []walletrpc.Transfer {
	type key struct {
		txid string
		pool bool
	}
	sums := map[key]walletrpc.Transfer{}
	var order []string
	seen := map[string]bool{}
	for _, t := range transfers {
		k := key{t.TxID, t.Pool}
		if s, ok := sums[k]; ok {
			s.Amount += t.Amount
			if t.Confirmations > s.Confirmations {
				s.Confirmations, s.Height = t.Confirmations, t.Height
			}
			sums[k] = s
		} else {
			sums[k] = t
		}
		if !seen[t.TxID] {
			seen[t.TxID] = true
			order = append(order, t.TxID)
		}
	}
	out := make([]walletrpc.Transfer, 0, len(order))
	for _, txid := range order {
		if t, ok := sums[key{txid, false}]; ok {
			out = append(out, t)
		} else {
			out = append(out, sums[key{txid, true}])
		}
	}
	return out
}

// read retries a failed ensure or a pending set_daemon of one wallet and
// returns its incoming transfers. On a wallet-rpc error it calls ensure to
// reopen the wallet. A wallet whose ensure failed counts as unread until an
// ensure succeeds: it may be open but still on wallet-rpc's default daemon.
func (p *Poller) read(ctx context.Context, name string, w Wallet, st *walletState, ensure func(context.Context) error) ([]walletrpc.Transfer, error) {
	if st.needsEnsure && ensure != nil {
		if err := ensure(ctx); err != nil {
			p.Log.Error("setting up the wallet failed, retrying next poll", "wallet", name, "err", err.Error())
			return nil, fmt.Errorf("%s wallet not set up: %w", name, err)
		}
		p.Log.Info("wallet set up again", "wallet", name)
		st.needsEnsure, st.pendingDaemon = false, ""
	}
	if st.pendingDaemon != "" {
		if err := w.SetDaemon(ctx, st.pendingDaemon, p.Proxy); err != nil {
			p.Log.Error("set_daemon failed, retrying next poll", "wallet", name, "node", st.pendingDaemon, "err", err.Error())
		} else {
			st.pendingDaemon = ""
		}
	}
	transfers, err := w.IncomingTransfers(ctx)
	if err != nil {
		var rpcErr *walletrpc.RPCError
		if ensure != nil && errors.As(err, &rpcErr) {
			if ensureErr := ensure(ctx); ensureErr != nil {
				p.Log.Error("reopening the wallet failed, retrying next poll", "wallet", name, "err", ensureErr.Error())
				st.needsEnsure = true
			} else {
				p.Log.Warn("wallet reopened after a wallet-rpc error", "wallet", name, "err", err.Error())
				st.pendingDaemon = "" // ensure pointed it at its node
			}
		}
		return nil, err
	}
	st.lastGood = p.Now()
	return transfers, nil
}

// Healthy reports whether both wallets were polled successfully within the
// last 5 minutes and neither waits for a retried ensure or is stalled.
func (p *Poller) Healthy(now time.Time) bool {
	for _, st := range []walletState{p.scan, p.check} {
		if st.needsEnsure || st.stalled || st.lastGood.IsZero() || now.Sub(st.lastGood) > healthyWithin {
			return false
		}
	}
	return true
}

func (p *Poller) hold(txid string) {
	first, ok := p.heldSince[txid]
	if !ok {
		p.heldSince[txid] = p.Now()
		return
	}
	if !p.warned[txid] && p.Now().Sub(first) > holdWarnAfter {
		p.warned[txid] = true
		p.Log.Warn("check wallet has not backed tx for an hour", "tx", txid)
	}
}
