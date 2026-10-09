// Package watcher is the orchestration core: registration, intents, hooks,
// the periodic sweep and startup recovery. It talks to LWS, relays and the
// chain only through small interfaces so tests can use fakes.
package watcher

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/api"
	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/ratelimit"
	"monostr.com/watcher/internal/store"
)

type LWSClient interface {
	EnsureAccount(ctx context.Context, address, viewKeyHex string) error
	ModifyAccountStatus(ctx context.Context, status string, addresses []string) error
	WebhookAdd(ctx context.Context, address, paymentID, token, url string, confirmations uint32) (string, error)
	WebhookDeleteUUID(ctx context.Context, eventIDs []string) error
	Rescan(ctx context.Context, height uint64, addresses []string) error
}

// RelayPool is the subset of relays.Pool the watcher needs. Fetch is the
// EOSE-bounded backfill primitive (Task 8 addendum, Ruling A): a live
// subscription resumes with since = now after a reconnect, so intents
// published during an outage must be re-fetched explicitly.
type RelayPool interface {
	Publish(ctx context.Context, urls []string, ev nostr.Event) (int, []error)
	FetchRelayList(ctx context.Context, urls []string, pubkey string) (read, write []string, found bool)
	Fetch(ctx context.Context, urls []string, filter nostr.Filter) []*nostr.Event
}

type HeightSource interface {
	Height(ctx context.Context) (uint64, error)
}

type Config struct {
	SecretKey       string
	Network         monero.Network
	Relays          []string
	PublicRelays    []string // advertised by Info; Relays when empty
	RelayListRelays []string
	HookURL         string
	Version         string
	Now             func() int64
}

type Deps struct {
	Store       *store.Store
	LWS         LWSClient
	Relays      RelayPool
	Height      HeightSource
	IntentLimit *ratelimit.Window
	Logger      *slog.Logger
}

const (
	MaxOpenIntentsPerRecipient = 500
	ReceiptTimeoutSeconds      = 3600
	RelayCacheTTLSeconds       = 86400
	IntentLookbackSeconds      = 3600
	intentsPerSenderPerHour    = 60

	metaLastSeenIntent = "last_seen_intent_ts"
	metaLastHeight     = "last_height"
	metaPendingRescan  = "pending_rescan_height"
)

type Service struct {
	cfg    Config
	deps   Deps
	pubkey string
	log    *slog.Logger
}

func New(cfg Config, deps Deps) (*Service, error) {
	pubkey, err := nostr.GetPublicKey(cfg.SecretKey)
	if err != nil {
		return nil, fmt.Errorf("watcher secret key: %w", err)
	}
	if cfg.Now == nil {
		cfg.Now = func() int64 { return time.Now().Unix() }
	}
	if deps.IntentLimit == nil {
		deps.IntentLimit = ratelimit.New(intentsPerSenderPerHour, time.Hour)
	}
	if deps.Logger == nil {
		deps.Logger = slog.Default()
	}
	if deps.Store == nil || deps.LWS == nil || deps.Relays == nil || deps.Height == nil {
		return nil, errors.New("watcher: missing dependency")
	}
	return &Service{cfg: cfg, deps: deps, pubkey: pubkey, log: deps.Logger}, nil
}

func (s *Service) Pubkey() string { return s.pubkey }

func (s *Service) Info(ctx context.Context) api.InfoResponse {
	height, err := s.deps.Height.Height(ctx)
	if err != nil {
		s.log.Warn("height unavailable", "err", err.Error())
	}
	relays := s.cfg.Relays
	if len(s.cfg.PublicRelays) > 0 {
		relays = s.cfg.PublicRelays
	}
	return api.InfoResponse{Pubkey: s.pubkey, Relays: relays, Network: s.cfg.Network.String(), Height: height, Version: s.cfg.Version}
}

func badRequest(msg string) error { return &api.HTTPError{Status: 400, Message: msg} }

// Register validates address and view key locally, makes the LWS account
// active and stores the mapping. The view key is forwarded once and never
// kept.
func (s *Service) Register(ctx context.Context, pubkey, address, viewKeyHex string) error {
	addr, err := monero.ParseStandardAddress(address)
	if errors.Is(err, monero.ErrNotStandardAddress) {
		return badRequest("address must be a standard (primary) address")
	}
	if err != nil {
		return badRequest("invalid address")
	}
	if addr.Network != s.cfg.Network {
		return badRequest("address network does not match watcher network " + s.cfg.Network.String())
	}
	viewKey, err := hex.DecodeString(viewKeyHex)
	if err != nil || !monero.ViewKeyMatches(viewKey, addr.View) {
		return badRequest("view key does not match address")
	}
	if err := s.deps.LWS.EnsureAccount(ctx, address, viewKeyHex); err != nil {
		s.log.Error("lws ensure account failed", "err", err.Error())
		return &api.HTTPError{Status: 502, Message: "watcher backend unavailable"}
	}
	prev, err := s.deps.Store.PutAccount(store.Account{Pubkey: pubkey, Address: address, CreatedAt: s.cfg.Now()})
	if err != nil {
		return err
	}
	if prev != nil && prev.Address != address {
		s.retireAddress(ctx, pubkey, prev.Address)
	}
	go s.RefreshRelayList(context.Background(), pubkey)
	return nil
}

// Unregister removes the account, deactivates it at LWS and drops the
// recipient's open intents and webhooks. It is idempotent.
func (s *Service) Unregister(ctx context.Context, pubkey string) error {
	acc, ok, err := s.deps.Store.DeleteAccount(pubkey)
	if err != nil {
		return err
	}
	if !ok {
		return nil
	}
	s.retireAddress(ctx, pubkey, acc.Address)
	return nil
}

// retireAddress deactivates an address at LWS and removes the recipient's
// webhooks; open intents are deleted, seen/confirmed rows are kept.
func (s *Service) retireAddress(ctx context.Context, pubkey, address string) {
	if err := s.deps.LWS.ModifyAccountStatus(ctx, "inactive", []string{address}); err != nil {
		s.log.Error("lws deactivate failed", "err", err.Error())
	}
	rows, err := s.deps.Store.IntentsForRecipient(pubkey)
	if err != nil {
		s.log.Error("list intents failed", "err", err.Error())
		return
	}
	for _, row := range rows {
		if row.LWSEventID != "" {
			if err := s.deps.LWS.WebhookDeleteUUID(ctx, []string{row.LWSEventID}); err != nil {
				s.log.Error("lws webhook delete failed", "intent", row.ID, "err", err.Error())
				continue
			}
			if err := s.deps.Store.ClearIntentWebhook(row.ID); err != nil {
				s.log.Error("clear webhook failed", "intent", row.ID, "err", err.Error())
			}
		}
		if row.State == store.StateOpen {
			// conditional (I4): an intent that just got a live receipt is kept
			if _, err := s.deps.Store.DeleteIntentIfOpen(row.ID); err != nil {
				s.log.Error("delete intent failed", "intent", row.ID, "err", err.Error())
			}
		}
	}
}

// RefreshRelayList fetches the recipient's NIP-65 list and caches it. A
// failed fetch (found == false) never overwrites an existing cache entry
// (F3): a transient relay outage must not drop the recipient's relays for
// RelayCacheTTLSeconds. A recipient with no NIP-65 list at all still gets
// an empty cache written once, so it isn't retried on every sweep.
func (s *Service) RefreshRelayList(ctx context.Context, pubkey string) bool {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	urls := append(append([]string{}, s.cfg.RelayListRelays...), s.cfg.Relays...)
	read, write, found := s.deps.Relays.FetchRelayList(ctx, urls, pubkey)
	if !found {
		_, _, readExists, _ := s.deps.Store.GetRelayCache(pubkey + ":read")
		_, _, writeExists, _ := s.deps.Store.GetRelayCache(pubkey + ":write")
		if readExists || writeExists {
			return false
		}
	}
	now := s.cfg.Now()
	if err := s.deps.Store.PutRelayCache(pubkey+":read", read, now); err != nil {
		s.log.Error("relay cache", "err", err.Error())
	}
	if err := s.deps.Store.PutRelayCache(pubkey+":write", write, now); err != nil {
		s.log.Error("relay cache", "err", err.Error())
	}
	return found
}

func (s *Service) cachedRelays(pubkey, kind string) []string {
	relays, _, ok, err := s.deps.Store.GetRelayCache(pubkey + ":" + kind)
	if err != nil || !ok {
		return nil
	}
	return relays
}

func union(base []string, more ...[]string) []string {
	seen := map[string]bool{}
	var out []string
	add := func(u string) {
		if u == "" || seen[u] {
			return
		}
		seen[u] = true
		out = append(out, u)
	}
	for _, u := range base {
		add(u)
	}
	for _, list := range more {
		for _, u := range list {
			add(u)
		}
	}
	return out
}

// publishRelays are the watcher relays plus the recipient's write relays.
func (s *Service) publishRelays(recipient string) []string {
	return union(s.cfg.Relays, s.cachedRelays(recipient, "write"))
}

// SubscriptionRelays are the watcher relays plus every recipient's read relays.
func (s *Service) SubscriptionRelays() []string {
	accounts, err := s.deps.Store.ListAccounts()
	if err != nil {
		s.log.Error("list accounts", "err", err.Error())
		return s.cfg.Relays
	}
	lists := make([][]string, 0, len(accounts))
	for _, a := range accounts {
		lists = append(lists, s.cachedRelays(a.Pubkey, "read"))
	}
	return union(s.cfg.Relays, lists...)
}

// IntentFilter is the relay subscription: kind 9738 since the last seen
// intent minus one hour (or one day back on a fresh database).
func (s *Service) IntentFilter() nostr.Filter {
	since := s.cfg.Now() - 86400
	if v, ok, _ := s.deps.Store.GetMeta(metaLastSeenIntent); ok {
		if ts, err := strconv.ParseInt(v, 10, 64); err == nil {
			since = ts - IntentLookbackSeconds
		}
	}
	ts := nostr.Timestamp(since)
	return nostr.Filter{Kinds: []int{protocol.KindTipIntent}, Since: &ts}
}

func (s *Service) sign(ev *nostr.Event) error { return ev.Sign(s.cfg.SecretKey) }

var _ api.Service = (*Service)(nil)
