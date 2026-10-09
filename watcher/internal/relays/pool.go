// Package relays wraps go-nostr's SimplePool: publish to many relays with a
// per-relay verdict, fetch NIP-65 relay lists, keep long-lived subscriptions.
package relays

import (
	"context"
	"fmt"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/protocol"
)

type Pool struct {
	pool *nostr.SimplePool
}

func NewPool(ctx context.Context) *Pool {
	return &Pool{pool: nostr.NewSimplePool(ctx)}
}

const publishTimeout = 8 * time.Second

// Publish sends ev to every url and returns how many relays accepted it.
// A relay that already has the event counts as accepted.
func (p *Pool) Publish(ctx context.Context, urls []string, ev nostr.Event) (int, []error) {
	if len(urls) == 0 {
		return 0, nil
	}
	ctx, cancel := context.WithTimeout(ctx, publishTimeout)
	defer cancel()
	ok := 0
	var errs []error
	for res := range p.pool.PublishMany(ctx, urls, ev) {
		if res.Error != nil {
			errs = append(errs, fmt.Errorf("%s: %w", res.RelayURL, res.Error))
			continue
		}
		ok++
	}
	return ok, errs
}

const fetchTimeout = 6 * time.Second

// MaxFetchEvents caps how many events one Fetch collects; the remaining
// subscriptions are cancelled once it is reached so a hostile relay cannot
// flood the watcher.
const MaxFetchEvents = 500

// Fetch returns the stored events matching filter on urls, deduplicated by
// id, and ends when every reachable relay sent EOSE, the timeout hits or
// MaxFetchEvents events were collected.
// It is the backfill primitive: a live subscription resumes with
// since = now after a reconnect, so anything published during the outage
// is only recovered by fetching it again.
func (p *Pool) Fetch(ctx context.Context, urls []string, filter nostr.Filter) []*nostr.Event {
	if len(urls) == 0 {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	seen := map[string]bool{}
	var out []*nostr.Event
	for ev := range p.pool.FetchMany(ctx, urls, filter) {
		if ev.Event == nil || seen[ev.ID] {
			continue
		}
		seen[ev.ID] = true
		e := *ev.Event
		out = append(out, &e)
		if len(out) >= MaxFetchEvents {
			cancel()
			break
		}
	}
	return out
}

// FetchRelayList returns the newest kind 10002 of pubkey found on urls.
func (p *Pool) FetchRelayList(ctx context.Context, urls []string, pubkey string) (read, write []string, found bool) {
	var newest *nostr.Event
	for _, ev := range p.Fetch(ctx, urls, nostr.Filter{Kinds: []int{protocol.KindRelayList}, Authors: []string{pubkey}, Limit: 1}) {
		if ev.PubKey != pubkey {
			continue
		}
		if newest == nil || ev.CreatedAt > newest.CreatedAt {
			newest = ev
		}
	}
	if newest == nil {
		return nil, nil, false
	}
	read, write = protocol.ParseRelayList(newest)
	return read, write, true
}
