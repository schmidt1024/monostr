package watcher

import (
	"context"
	"sort"
	"strconv"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/store"
)

func timeOf(unix int64) time.Time { return time.Unix(unix, 0) }

// maxBackfillBlocks is how far below the current height a payment bound may
// start: an intent lives at most protocol.MaxIntentTTL (120 s blocks), so a
// genuine payment is never older than that, however long the watcher was down.
const maxBackfillBlocks = uint64(protocol.MaxIntentTTL / 120)

// paymentBoundHeight is the chain height an intent's payment bound starts
// from (store.IntentRow.MinPaymentBlock), taken only from what the watcher
// itself observed, never from the sender's created_at: the current height,
// or the height last seen by the sweep if that is lower (after downtime it
// dates from before the outage, so payments made while the watcher was down
// still count), but never more than maxBackfillBlocks below the current
// height, so a seen height that stopped advancing cannot open the bound.
// Without a current height the last seen one is used; 0 = no height at all.
func (s *Service) paymentBoundHeight(ctx context.Context) uint64 {
	seen := s.lastSeenHeight()
	cur, err := s.deps.Height.Height(ctx)
	if err != nil || cur == 0 {
		return seen
	}
	if seen == 0 || seen >= cur {
		return cur
	}
	return max(seen, cur-min(cur, maxBackfillBlocks))
}

// lastSeenHeight is the height the sweep saw last (last_seen_height, which
// advances even while a rescan is pending), or last_height on a database
// from before that key existed; 0 = none.
func (s *Service) lastSeenHeight() uint64 {
	for _, key := range []string{metaLastSeenHeight, metaLastHeight} {
		if v, ok, _ := s.deps.Store.GetMeta(key); ok {
			if h, err := strconv.ParseUint(v, 10, 64); err == nil && h > 0 {
				return h
			}
		}
	}
	return 0
}

// HandleHook turns an LWS tx-confirmation callback into a receipt (first
// sighting) or a confirmation. Hooks that do not match a stored intent are
// ignored with nil.
func (s *Service) HandleHook(ctx context.Context, h lws.Hook) error {
	row, ok, err := s.deps.Store.GetIntent(h.Token)
	if err != nil {
		return err
	}
	// the hook must come from the webhook this intent holds: an intent
	// without one (deleted, not yet registered) accepts no hook
	if !ok || row.PID != h.PaymentID || h.EventID == "" || row.LWSEventID != h.EventID {
		s.log.Warn("hook ignored", "token", h.Token)
		return nil
	}
	// Block 0: still in the pool (or no height reported), so made after the intent anyway
	if minBlock := row.MinPaymentBlock(); h.Block != 0 && h.Block < minBlock {
		s.log.Warn("hook ignored, payment older than the intent", "token", h.Token, "block", h.Block, "min_block", minBlock)
		return nil
	}
	existing, found, err := s.deps.Store.ReceiptByTx(row.ID, h.TxHash)
	if err != nil {
		return err
	}
	if found {
		return s.applyToExisting(ctx, row, existing, h)
	}
	if other, credited, err := s.deps.Store.ReceiptByTxHash(h.TxHash); err != nil {
		return err
	} else if credited && other.IntentID != row.ID {
		s.log.Warn("tx already credited to another intent", "token", h.Token, "intent", other.IntentID)
		return nil
	}
	if adopted, err := s.adoptPublished(ctx, row, h); err != nil || adopted {
		return err
	}
	now := s.cfg.Now()
	ev := protocol.BuildReceipt(row.Intent, h.Amount, now)
	if err := s.sign(&ev); err != nil {
		return err
	}
	state := store.ReceiptSeen
	if h.Confirmations >= 1 {
		state = store.ReceiptConfirmed
	}
	inserted, err := s.deps.Store.InsertReceipt(store.Receipt{
		ID: ev.ID, IntentID: row.ID, TxHash: h.TxHash, Amount: h.Amount, CreatedAt: now, SeenAt: now, State: state,
	})
	if err != nil {
		return err
	}
	if !inserted {
		// F4: a concurrent hook for the same tx won the insert race between
		// our ReceiptByTx lookup and this insert; fall back to the
		// found-path against whatever it stored.
		existing, found, err := s.deps.Store.ReceiptByTx(row.ID, h.TxHash)
		if err != nil {
			return err
		}
		if !found {
			return nil
		}
		return s.applyToExisting(ctx, row, existing, h)
	}
	if _, err := s.deps.Store.RecomputeIntentState(row.ID); err != nil {
		return err
	}
	s.publishReceipt(ctx, ev.ID, row.Recipient, ev)
	return nil
}

// applyToExisting handles a hook for a tx that already has a receipt row:
// a 'seen' receipt is upgraded to 'confirmed' on a confirming hook, and a
// 'dropped' receipt (F1a: confirmed more than ReceiptTimeoutSeconds after
// first sighting, already swept away) is resurrected under a fresh event.
// Anything else (duplicate 0-conf, already confirmed) is a no-op. The
// upgrade is conditional (I4): if Sweep dropped the receipt between our
// read and the update, the drop is detected and the receipt resurrected.
func (s *Service) applyToExisting(ctx context.Context, row store.IntentRow, existing store.Receipt, h lws.Hook) error {
	if h.Confirmations < 1 {
		return nil
	}
	switch existing.State {
	case store.ReceiptSeen:
		confirmed, err := s.deps.Store.ConfirmReceiptIfSeen(existing.ID)
		if err != nil {
			return err
		}
		if !confirmed {
			current, found, err := s.deps.Store.ReceiptByTx(row.ID, existing.TxHash)
			if err != nil || !found || current.State != store.ReceiptDropped {
				return err
			}
			return s.resurrectReceipt(ctx, row, current, h.Amount)
		}
		_, err = s.deps.Store.RecomputeIntentState(row.ID)
		return err
	case store.ReceiptDropped:
		return s.resurrectReceipt(ctx, row, existing, h.Amount)
	default:
		return nil
	}
}

// resurrectReceipt rebuilds the receipt as a fresh Nostr event (the old
// event's deletion has already been published, so it cannot be reused),
// moves the existing row onto it as 'confirmed' and republishes. If a
// concurrent resurrection already moved the row, nothing happens.
func (s *Service) resurrectReceipt(ctx context.Context, row store.IntentRow, existing store.Receipt, amount int64) error {
	now := s.cfg.Now()
	ev := protocol.BuildReceipt(row.Intent, amount, now)
	if err := s.sign(&ev); err != nil {
		return err
	}
	moved, err := s.deps.Store.ReplaceReceiptEvent(existing.ID, ev.ID, now, store.ReceiptConfirmed)
	if err != nil || !moved {
		return err
	}
	if _, err := s.deps.Store.RecomputeIntentState(row.ID); err != nil {
		return err
	}
	s.publishReceipt(ctx, ev.ID, row.Recipient, ev)
	return nil
}

// adoptPublished takes over a receipt this watcher published for the intent in an earlier
// run whose database is gone: Backfill brings the intent back and the wallet the payment, and
// a second receipt with a new id would make clients count the tip twice. Only intents from
// before this start (with the clock skew intents may have) are looked up, so live tips cost
// no relay round trip. Each receipt is taken over once (oldest first, same amount or both
// without one); deleted receipts, receipts by other keys and events whose id does not match
// their content do not count. Without an answer from the relays the caller publishes as
// before: a missing receipt would be worse than a rare duplicate.
func (s *Service) adoptPublished(ctx context.Context, row store.IntentRow, h lws.Hook) (bool, error) {
	if row.CreatedAt >= s.startedAt+protocol.MaxFutureSkew {
		return false, nil
	}
	relays := s.publishRelays(row.Recipient)
	since := nostr.Timestamp(row.CreatedAt - protocol.MaxFutureSkew)
	var candidates []*nostr.Event
	for _, ev := range s.deps.Relays.Fetch(ctx, relays, nostr.Filter{
		Kinds: []int{protocol.KindTipReceipt}, Authors: []string{s.pubkey}, Since: &since,
		Tags: nostr.TagMap{"p": []string{row.Recipient}},
	}) {
		if s.ownReceiptFor(ev, row, h.Amount) {
			candidates = append(candidates, ev)
		}
	}
	if len(candidates) == 0 {
		return false, nil
	}
	ids := make([]string, len(candidates))
	for i, ev := range candidates {
		ids[i] = ev.ID
	}
	deleted := map[string]bool{}
	for _, ev := range s.deps.Relays.Fetch(ctx, relays, nostr.Filter{
		Kinds: []int{protocol.KindDeletion}, Authors: []string{s.pubkey}, Tags: nostr.TagMap{"e": ids},
	}) {
		if ev.Kind != protocol.KindDeletion || ev.PubKey != s.pubkey || !ev.CheckID() {
			continue
		}
		if ok, err := ev.CheckSignature(); err != nil || !ok {
			continue
		}
		for _, tag := range ev.Tags {
			if len(tag) >= 2 && tag[0] == "e" {
				deleted[tag[1]] = true
			}
		}
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].CreatedAt < candidates[j].CreatedAt })
	state := store.ReceiptSeen
	if h.Confirmations >= 1 {
		state = store.ReceiptConfirmed
	}
	for _, ev := range candidates {
		if deleted[ev.ID] {
			continue
		}
		if _, taken, err := s.deps.Store.GetReceipt(ev.ID); err != nil {
			return false, err
		} else if taken {
			continue
		}
		inserted, err := s.deps.Store.InsertReceipt(store.Receipt{
			ID: ev.ID, IntentID: row.ID, TxHash: h.TxHash, Amount: h.Amount,
			CreatedAt: int64(ev.CreatedAt), SeenAt: s.cfg.Now(), State: state,
		})
		if err != nil {
			return false, err
		}
		if !inserted {
			// a concurrent hook stored this tx (then this hook may still confirm it, as in
			// HandleHook's own fallback), or took this receipt for another payment
			existing, found, err := s.deps.Store.ReceiptByTx(row.ID, h.TxHash)
			if err != nil {
				return false, err
			}
			if found {
				return true, s.applyToExisting(ctx, row, existing, h)
			}
			continue
		}
		// already on the relays: the retry of unpublished receipts must leave it alone
		if err := s.deps.Store.SetReceiptPublished(ev.ID, true); err != nil {
			return true, err
		}
		if _, err := s.deps.Store.RecomputeIntentState(row.ID); err != nil {
			return true, err
		}
		s.log.Info("receipt taken over from the relays", "intent", row.ID, "receipt", ev.ID)
		return true, nil
	}
	return false, nil
}

// ownReceiptFor: a genuine receipt of this watcher for the intent, with the amount this
// payment's receipt would carry.
func (s *Service) ownReceiptFor(ev *nostr.Event, row store.IntentRow, received int64) bool {
	if ev.Kind != protocol.KindTipReceipt || ev.PubKey != s.pubkey || !ev.CheckID() {
		return false
	}
	if intent := ev.Tags.GetFirst([]string{"intent"}); intent == nil || len(*intent) < 2 || (*intent)[1] != row.ID {
		return false
	}
	amount := ev.Tags.GetFirst([]string{"amount"})
	if row.Amount > 0 {
		if amount == nil || len(*amount) < 2 || (*amount)[1] != strconv.FormatInt(received, 10) {
			return false
		}
	} else if amount != nil {
		return false
	}
	ok, err := ev.CheckSignature()
	return err == nil && ok
}

func (s *Service) publishReceipt(ctx context.Context, receiptID, recipient string, ev nostr.Event) {
	urls := s.publishRelays(recipient)
	ok, errs := s.deps.Relays.Publish(ctx, urls, ev)
	for _, err := range errs {
		s.log.Warn("publish", "receipt", receiptID, "err", err.Error())
	}
	if ok == 0 {
		s.log.Error("receipt not published to any relay", "receipt", receiptID)
		return
	}
	if err := s.deps.Store.SetReceiptPublished(receiptID, true); err != nil {
		s.log.Error("mark published", "receipt", receiptID, "err", err.Error())
	}
}
