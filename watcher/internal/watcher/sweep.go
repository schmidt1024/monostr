package watcher

import (
	"context"
	"strconv"

	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/store"
)

// Sweep is the periodic job: drop stale receipts, retry unpublished ones,
// register missing webhooks, expire intents, remember the chain height and
// refresh old relay lists.
func (s *Service) Sweep(ctx context.Context) error {
	now := s.cfg.Now()
	if err := s.dropStaleReceipts(ctx, now); err != nil {
		return err
	}
	if err := s.retryUnpublished(ctx); err != nil {
		return err
	}
	if err := s.registerMissingWebhooks(ctx, now); err != nil {
		return err
	}
	if err := s.expireIntents(ctx, now); err != nil {
		return err
	}
	if err := s.runPendingRescan(ctx); err != nil {
		return err
	}
	if err := s.recordHeight(ctx); err != nil {
		return err
	}
	s.refreshStaleRelayLists(ctx, now)
	return nil
}

func (s *Service) dropStaleReceipts(ctx context.Context, now int64) error {
	stale, err := s.deps.Store.StaleSeenReceipts(now - ReceiptTimeoutSeconds)
	if err != nil {
		return err
	}
	for _, r := range stale {
		row, ok, err := s.deps.Store.GetIntent(r.IntentID)
		if err != nil {
			return err
		}
		if !ok {
			continue
		}
		del := protocol.BuildDeletion(r.ID, now)
		if err := s.sign(&del); err != nil {
			return err
		}
		if okCount, errs := s.deps.Relays.Publish(ctx, s.publishRelays(row.Recipient), del); okCount == 0 {
			s.log.Error("deletion not published", "receipt", r.ID, "errs", len(errs))
			continue // retry next sweep; receipt stays 'seen'
		}
		dropped, err := s.deps.Store.DropReceiptIfSeen(r.ID)
		if err != nil {
			return err
		}
		if !dropped {
			// I4: the confirmation landed while the deletion was being
			// published. The retracted event cannot be revived, so the
			// confirmed receipt goes out again under a fresh event.
			current, found, err := s.deps.Store.GetReceipt(r.ID)
			if err != nil {
				return err
			}
			if found && current.State == store.ReceiptConfirmed {
				if err := s.resurrectReceipt(ctx, row, current, current.Amount); err != nil {
					return err
				}
			}
		}
		if _, err := s.deps.Store.RecomputeIntentState(row.ID); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) retryUnpublished(ctx context.Context) error {
	pending, err := s.deps.Store.UnpublishedReceipts()
	if err != nil {
		return err
	}
	for _, r := range pending {
		row, ok, err := s.deps.Store.GetIntent(r.IntentID)
		if err != nil {
			return err
		}
		if !ok {
			continue
		}
		ev := protocol.BuildReceipt(row.Intent, r.Amount, r.CreatedAt)
		if err := s.sign(&ev); err != nil {
			return err
		}
		if ev.ID != r.ID {
			s.log.Error("rebuilt receipt id differs", "receipt", r.ID, "rebuilt", ev.ID)
			continue
		}
		s.publishReceipt(ctx, r.ID, row.Recipient, ev)
	}
	return nil
}

func (s *Service) registerMissingWebhooks(ctx context.Context, now int64) error {
	rows, err := s.deps.Store.IntentsWithoutWebhook(now)
	if err != nil {
		return err
	}
	for _, row := range rows {
		account, ok, err := s.deps.Store.GetAccount(row.Recipient)
		if err != nil {
			return err
		}
		if !ok {
			continue
		}
		s.registerWebhook(ctx, row, account.Address)
	}
	return nil
}

func (s *Service) expireIntents(ctx context.Context, now int64) error {
	rows, err := s.deps.Store.ExpiredIntents(now)
	if err != nil {
		return err
	}
	for _, row := range rows {
		if row.State == store.StateSeen {
			// F1b: a payment was seen close to expiry; keep the webhook so
			// LWS can still deliver the confirmation. The intent is
			// re-evaluated on a later sweep once the receipt confirms
			// (state moves to confirmed) or is dropped (state is recomputed
			// from the remaining receipts).
			continue
		}
		if row.LWSEventID != "" {
			if err := s.deps.LWS.WebhookDeleteUUID(ctx, []string{row.LWSEventID}); err != nil {
				s.log.Error("lws webhook delete failed", "intent", row.ID, "err", err.Error())
				continue
			}
			if err := s.deps.Store.ClearIntentWebhook(row.ID); err != nil {
				return err
			}
		}
		if row.State == store.StateOpen {
			// conditional (I4): a hook may have added a live receipt since
			// the row was listed; then the row is kept
			deleted, err := s.deps.Store.DeleteIntentIfOpen(row.ID)
			if err != nil {
				return err
			}
			if !deleted {
				s.log.Warn("expired intent kept, it has a live receipt", "intent", row.ID)
			}
		}
	}
	return nil
}

const relayRefreshBatch = 10

func (s *Service) refreshStaleRelayLists(ctx context.Context, now int64) {
	accounts, err := s.deps.Store.ListAccounts()
	if err != nil {
		return
	}
	n := 0
	for _, a := range accounts {
		_, fetchedAt, ok, _ := s.deps.Store.GetRelayCache(a.Pubkey + ":read")
		if ok && now-fetchedAt < RelayCacheTTLSeconds {
			continue
		}
		s.RefreshRelayList(ctx, a.Pubkey)
		if n++; n >= relayRefreshBatch {
			return
		}
	}
}

// recordHeight stores the current chain height as last_height, unless a
// rescan is still pending: the pending height is the restart point, and
// moving last_height past it would lose payments if the watcher restarted
// before the rescan succeeds.
func (s *Service) recordHeight(ctx context.Context) error {
	if _, pending, err := s.deps.Store.GetMeta(metaPendingRescan); err != nil || pending {
		return err
	}
	height, err := s.deps.Height.Height(ctx)
	if err != nil {
		s.log.Warn("height unavailable, last_height not updated", "err", err.Error())
		return nil
	}
	if height == 0 {
		return nil
	}
	return s.deps.Store.SetMeta(metaLastHeight, strconv.FormatUint(height, 10))
}

// Recover runs once at startup, after Backfill has loaded the intents
// published during the downtime: it registers webhooks that never made it
// to LWS and schedules a rescan of every address with live intents from
// the last known height, so payments made while the watcher was down are
// reported. The rescan is recorded as pending_rescan_height and retried by
// Sweep until LWS accepts it.
func (s *Service) Recover(ctx context.Context) error {
	now := s.cfg.Now()
	if err := s.registerMissingWebhooks(ctx, now); err != nil {
		return err
	}
	if _, pending, err := s.deps.Store.GetMeta(metaPendingRescan); err != nil {
		return err
	} else if !pending {
		v, ok, err := s.deps.Store.GetMeta(metaLastHeight)
		if err != nil {
			return err
		}
		if ok {
			if err := s.deps.Store.SetMeta(metaPendingRescan, v); err != nil {
				return err
			}
		}
	}
	return s.runPendingRescan(ctx)
}

// rescanRetryStep is how far below a failed rescan height the one in-sweep retry starts.
const rescanRetryStep = 10

// runPendingRescan issues the LWS rescan recorded in pending_rescan_height
// for every address with live (non-confirmed, non-expired) intents. On
// success, or when there is nothing to rescan, the pending marker is
// cleared; on failure it is kept for the next Sweep.
func (s *Service) runPendingRescan(ctx context.Context) error {
	v, ok, err := s.deps.Store.GetMeta(metaPendingRescan)
	if err != nil || !ok {
		return err
	}
	height, err := strconv.ParseUint(v, 10, 64)
	if err != nil || height == 0 {
		return s.deps.Store.DeleteMeta(metaPendingRescan)
	}
	now := s.cfg.Now()
	accounts, err := s.deps.Store.ListAccounts()
	if err != nil {
		return err
	}
	var addresses []string
	for _, a := range accounts {
		rows, err := s.deps.Store.IntentsForRecipient(a.Pubkey)
		if err != nil {
			return err
		}
		for _, row := range rows {
			if row.State != store.StateConfirmed && row.Expiration > now {
				addresses = append(addresses, a.Address)
				break
			}
		}
	}
	if len(addresses) == 0 {
		return s.deps.Store.DeleteMeta(metaPendingRescan)
	}
	if daemon, err := s.deps.Height.Height(ctx); err == nil && daemon > 1 && height >= daemon {
		height = daemon - 1 // LWS rejects a rescan at or above its own scan height
	}
	if err := s.deps.LWS.Rescan(ctx, height, addresses); err != nil {
		retry := uint64(0)
		if height > rescanRetryStep {
			retry = height - rescanRetryStep
		}
		if err2 := s.deps.LWS.Rescan(ctx, retry, addresses); err2 != nil {
			s.log.Error("lws rescan failed, retrying on next sweep", "height", height, "err", err.Error())
			return nil
		}
	}
	return s.deps.Store.DeleteMeta(metaPendingRescan)
}
