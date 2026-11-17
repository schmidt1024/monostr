package watcher

import (
	"context"
	"strconv"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/store"
)

// HandleIntent validates, stores and hooks a kind 9738 event. Anything
// that must be ignored (invalid, unknown recipient, duplicate, over limit)
// returns nil; only storage failures are errors.
func (s *Service) HandleIntent(ctx context.Context, ev *nostr.Event) error {
	now := s.cfg.Now()
	in, err := protocol.ParseIntent(ev, now)
	if err != nil {
		s.log.Debug("intent rejected", "err", err.Error())
		return nil
	}
	account, ok, err := s.deps.Store.GetAccount(in.Recipient)
	if err != nil {
		return err
	}
	if !ok {
		return nil
	}
	if _, exists, err := s.deps.Store.GetIntent(in.ID); err != nil || exists {
		return err
	}
	open, err := s.deps.Store.CountOpenIntents(in.Recipient)
	if err != nil {
		return err
	}
	if open >= MaxOpenIntentsPerRecipient {
		s.log.Warn("open intents cap", "recipient", in.Recipient)
		return nil
	}
	// one sender (throwaway keys are free, but each needs its own events)
	// must not fill the recipient's whole cap
	fromSender, err := s.deps.Store.CountOpenIntentsFrom(in.Recipient, in.Sender)
	if err != nil {
		return err
	}
	if fromSender >= MaxOpenIntentsPerSender {
		s.log.Warn("open intents per sender cap", "recipient", in.Recipient, "sender", in.Sender)
		return nil
	}
	// fail closed: without any chain height there is no payment bound, so the
	// intent is not stored now; Backfill fetches it again on a later run
	bound := s.paymentBoundHeight(ctx)
	if bound == 0 {
		s.log.Warn("no chain height, intent left for a later backfill", "intent", in.ID)
		return nil
	}
	// recorded last: an intent turned away by the cheap checks above costs
	// the sender nothing and the limiter no memory
	if !s.deps.IntentLimit.Allow(in.Sender, timeOf(now)) {
		s.log.Warn("intent rate limit", "sender", in.Sender)
		return nil
	}
	inserted, err := s.deps.Store.InsertIntentAt(in, now, bound)
	if err != nil {
		return err
	}
	if !inserted && in.Bound {
		// tips 0.6: someone copied this sender's pid into an unbound intent and
		// got here first; the bound one takes the pid while that one is unpaid
		old, replaced, err := s.deps.Store.ReplaceUnboundIntent(in, now, bound)
		if err != nil {
			return err
		}
		if replaced {
			var hooks []string
			for _, r := range old {
				s.log.Warn("unbound intent replaced", "old", r.ID, "new", in.ID, "recipient", in.Recipient)
				if r.LWSEventID != "" {
					hooks = append(hooks, r.LWSEventID)
				}
			}
			// an old webhook carries the old intent id as its token: its hook would
			// find no intent. One LWS could not remove now is retried by the sweep.
			if len(hooks) > 0 {
				if err := s.deps.LWS.WebhookDeleteUUID(ctx, hooks); err != nil {
					s.log.Warn("old webhooks not removed, sweep retries", "event_ids", hooks, "err", err.Error())
					if err := s.deps.Store.AddOrphanWebhooks(hooks); err != nil {
						s.log.Warn("orphan webhooks not recorded", "err", err.Error())
					}
				}
			}
			inserted = true
		}
	}
	if !inserted {
		// the id was checked above, so the (recipient, pid) pair is taken:
		// one payment must never satisfy two intents
		s.log.Warn("duplicate pid for recipient", "intent", in.ID, "recipient", in.Recipient)
		return nil
	}
	s.bumpLastSeen(in.CreatedAt)
	s.registerWebhook(ctx, store.IntentRow{Intent: in}, account.Address)
	return nil
}

func (s *Service) bumpLastSeen(ts int64) {
	if v, ok, _ := s.deps.Store.GetMeta(metaLastSeenIntent); ok {
		if cur, err := strconv.ParseInt(v, 10, 64); err == nil && cur >= ts {
			return
		}
	}
	if err := s.deps.Store.SetMeta(metaLastSeenIntent, strconv.FormatInt(ts, 10)); err != nil {
		s.log.Error("set last seen", "err", err.Error())
	}
}

// registerWebhook asks LWS for a tx-confirmation hook (confirmations = 1 →
// callbacks at 0 and 1). Failures are logged; Sweep/Recover retry.
func (s *Service) registerWebhook(ctx context.Context, row store.IntentRow, address string) {
	eventID, err := s.deps.LWS.WebhookAdd(ctx, address, row.PID, row.ID, s.cfg.HookURL, 1)
	if err != nil {
		s.log.Error("lws webhook_add failed", "intent", row.ID, "err", err.Error())
		return
	}
	if err := s.deps.Store.SetIntentWebhook(row.ID, eventID); err != nil {
		s.log.Error("store webhook id", "intent", row.ID, "err", err.Error())
	}
}

// Backfill re-fetches intents from the subscription relays since the last
// seen intent minus the lookback and feeds them through HandleIntent.
// Duplicates are dropped by HandleIntent's GetIntent check. It closes the
// gap a live subscription leaves after a reconnect (since resets to now).
func (s *Service) Backfill(ctx context.Context) error {
	events := s.deps.Relays.Fetch(ctx, s.SubscriptionRelays(), s.IntentFilter())
	for _, ev := range events {
		if err := s.HandleIntent(ctx, ev); err != nil {
			return err
		}
	}
	return nil
}
