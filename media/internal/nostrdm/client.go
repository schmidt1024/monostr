package nostrdm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/keyer"
	"github.com/nbd-wtf/go-nostr/nip17"
	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/review"
)

const (
	// IndexerRelay is asked for the operator's DM relay list and receives
	// the server's profile, so clients can find both.
	IndexerRelay = "wss://purplepag.es"
	// FallbackRelay is used when the operator has not published any DM relays.
	FallbackRelay = "wss://relay.monostr.com"

	dmRelayTTL       = time.Hour
	dmFallbackTTL    = 5 * time.Minute // a lookup that found nothing may just have timed out
	subscriptionTTL  = 10 * time.Minute
	announceRetry    = time.Hour
	resubscribeIn    = 30 * time.Second
	authProbeTimeout = 15 * time.Second
	listenLookback   = 2 * 86400 // seconds; gift wrap timestamps are randomised into the past
)

// Client sends review notices to the operator and receives their replies.
type Client struct {
	kr        nostr.Keyer
	pub       string
	operator  string
	ownRelays []string
	publicURL string
	pool      *nostr.SimplePool
	log       *slog.Logger

	mu          sync.Mutex
	dmRelays    []string
	dmRelaysAt  time.Time
	dmRelaysTTL time.Duration
}

var _ review.Messenger = (*Client)(nil)

// New builds a client for the server key nsec (nsec1... or 64 hex). operator
// is the operator's hex pubkey. Nothing connects until first use.
func New(ctx context.Context, nsec, operator string, ownRelays []string, publicURL string, logger *slog.Logger) (*Client, error) {
	// Listen has nothing to subscribe to without an own relay, and would
	// block forever instead of failing.
	if len(ownRelays) == 0 {
		return nil, errors.New("at least one own relay is required")
	}
	sec, err := secretHex(nsec)
	if err != nil {
		return nil, err
	}
	if !nostr.IsValid32ByteHex(operator) {
		return nil, errors.New("operator pubkey must be 64 hex characters")
	}
	kr, err := keyer.NewPlainKeySigner(sec)
	if err != nil {
		return nil, fmt.Errorf("server key: %w", err)
	}
	pub, err := nostr.GetPublicKey(sec)
	if err != nil {
		return nil, fmt.Errorf("server key: %w", err)
	}
	if pub == operator {
		// The server would answer its own replies in an endless loop.
		return nil, errors.New("server key and operator are the same pubkey")
	}
	if logger == nil {
		logger = slog.Default()
	}
	// The AUTH handler signs with the server key: relay.monostr.com only
	// hands out gift wraps (kind 1059) to the pubkey they are addressed to
	// after NIP-42 authentication.
	pool := nostr.NewSimplePool(ctx, nostr.WithAuthHandler(func(ctx context.Context, ev nostr.RelayEvent) error {
		return kr.SignEvent(ctx, ev.Event)
	}))
	return &Client{
		kr: kr, pub: pub, operator: operator,
		// go-nostr normalizes relay slices in place, so keep our own copy.
		ownRelays: slices.Clone(ownRelays),
		publicURL: publicURL,
		pool:      pool, log: logger,
	}, nil
}

func secretHex(s string) (string, error) {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "nsec1") {
		_, v, err := nip19.Decode(s)
		if err != nil {
			return "", fmt.Errorf("server nsec: %w", err)
		}
		hex, ok := v.(string)
		if !ok {
			return "", errors.New("server nsec: not a private key")
		}
		return hex, nil
	}
	if !nostr.IsValid32ByteHex(s) {
		return "", errors.New("server key must be an nsec or 64 hex characters")
	}
	return s, nil
}

// PublicKey returns the server's hex pubkey.
func (c *Client) PublicKey() string { return c.pub }

// Send delivers text to the operator's DM relays. It succeeds as soon as one
// relay has accepted the wrap.
func (c *Client) Send(ctx context.Context, text string) error {
	wrap, err := wrapFor(ctx, c.kr, c.operator, text)
	if err != nil {
		return err
	}
	return c.publish(ctx, c.operatorRelays(ctx), wrap)
}

// Announce publishes the server's profile (kind 0) and DM relay list (kind
// 10050) so the operator's client shows who the notices come from and the
// operator can reply to the relays the server actually listens on.
func (c *Client) Announce(ctx context.Context) error {
	profile, err := c.profileContent()
	if err != nil {
		return err
	}
	relayTags := make(nostr.Tags, 0, len(c.ownRelays))
	for _, r := range c.ownRelays {
		relayTags = append(relayTags, nostr.Tag{"relay", r})
	}
	targets := dedupe(append(append([]string{IndexerRelay}, c.ownRelays...), c.operatorRelays(ctx)...))

	var errs []error
	for _, ev := range []nostr.Event{
		{Kind: nostr.KindProfileMetadata, Content: string(profile), CreatedAt: nostr.Now()},
		{Kind: nostr.KindDMRelayList, Tags: relayTags, CreatedAt: nostr.Now()},
	} {
		if err := c.kr.SignEvent(ctx, &ev); err != nil {
			return err
		}
		if err := c.publish(ctx, targets, ev); err != nil {
			errs = append(errs, fmt.Errorf("kind %d: %w", ev.Kind, err))
		}
	}
	return errors.Join(errs...)
}

// profileContent is the JSON content of the kind 0 event.
func (c *Client) profileContent() ([]byte, error) {
	return json.Marshal(map[string]string{
		"name":  "monostr media",
		"about": "Meldungen von " + c.publicURL,
	})
}

// AnnounceUntilDone calls Announce now and, while it keeps failing, hourly
// until it succeeds or ctx ends. Relays being down at start must not leave
// the server unannounced for good.
func (c *Client) AnnounceUntilDone(ctx context.Context) {
	for {
		err := c.Announce(ctx)
		if err == nil {
			return
		}
		c.log.Warn("announce failed, retrying", "err", err, "in", announceRetry)
		select {
		case <-ctx.Done():
			return
		case <-time.After(announceRetry):
		}
	}
}

// Listen subscribes to gift wraps for the server on its own relays and hands
// every readable one to handle. It blocks until ctx ends. since returns the
// created_at of the newest wrap handled so far (0 for none); the subscription
// starts two days earlier because wrap timestamps are randomised, and the
// reviewer drops wraps it has already executed.
func (c *Client) Listen(ctx context.Context, since func() int64, handle func(context.Context, review.Inbound)) {
	for ctx.Err() == nil {
		ts := nostr.Timestamp(lookback(since(), time.Now().Unix()))
		// go-nostr's subscription reconnects on its own but then rewrites
		// Since to now, which would lose gift wraps whose timestamps are
		// randomised into the past. So each subscription lives only ten
		// minutes and is then replaced by one with a fresh Since.
		sctx, cancel := context.WithTimeout(ctx, subscriptionTTL)
		filter := nostr.Filter{
			Kinds: []int{nostr.KindGiftWrap},
			Tags:  nostr.TagMap{"p": []string{c.pub}},
			Since: &ts,
		}
		for _, url := range c.ownRelays {
			c.authenticate(sctx, url, filter)
		}
		events := c.pool.SubscribeMany(sctx, slices.Clone(c.ownRelays), filter)
		closed := false
	read:
		for {
			select {
			case <-sctx.Done():
				break read
			case re, ok := <-events:
				if !ok {
					closed = true
					break read
				}
				in, err := unwrap(ctx, c.kr, *re.Event)
				if err != nil {
					c.log.Debug("dropping unreadable gift wrap", "relay", re.Relay.URL, "err", err)
					continue
				}
				handle(ctx, in)
			}
		}
		cancel()
		if !closed {
			continue
		}
		// The channel closes when every relay has closed the subscription;
		// do not hammer them.
		select {
		case <-ctx.Done():
			return
		case <-time.After(resubscribeIn):
		}
	}
}

// authenticate makes sure the pool's connection to url is NIP-42
// authenticated before Listen subscribes on it. go-nostr only answers a
// CLOSED whose reason starts with "auth-required:", but strfry (which runs
// relay.monostr.com) sends "ERROR: auth-required: …", so the pool would never
// authenticate and the server would never read its gift wraps. A probe
// subscription draws the relay's challenge; when it is refused for missing
// authentication, the server authenticates on that same connection, which
// SubscribeMany then reuses. A relay that answers without asking is left alone.
func (c *Client) authenticate(ctx context.Context, url string, filter nostr.Filter) {
	actx, cancel := context.WithTimeout(ctx, authProbeTimeout)
	defer cancel()
	r, err := c.pool.EnsureRelay(url)
	if err != nil {
		c.log.Warn("relay unreachable", "relay", url, "err", err)
		return
	}
	sub, err := r.Subscribe(actx, nostr.Filters{filter})
	if err != nil {
		c.log.Warn("probe subscription failed", "relay", url, "err", err)
		return
	}
	defer sub.Unsub()
	select {
	case reason := <-sub.ClosedReason:
		if !strings.Contains(reason, "auth-required:") {
			c.log.Warn("relay refused the subscription", "relay", url, "reason", reason)
			return
		}
		if err := r.Auth(actx, func(ae *nostr.Event) error { return c.kr.SignEvent(actx, ae) }); err != nil {
			c.log.Warn("relay authentication failed", "relay", url, "err", err)
		}
	case <-sub.EndOfStoredEvents:
	case <-actx.Done():
	}
}

// operatorRelays returns where the operator wants DMs, cached for an hour so
// a burst of notices costs one lookup.
func (c *Client) operatorRelays(ctx context.Context) []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.dmRelays != nil && time.Since(c.dmRelaysAt) < c.dmRelaysTTL {
		return c.dmRelays
	}
	query := dedupe(append([]string{FallbackRelay, IndexerRelay}, c.ownRelays...))
	qctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	relays := nip17.GetDMRelays(qctx, c.operator, c.pool, query)
	ttl := dmRelayTTL
	if len(relays) == 0 {
		relays, ttl = []string{FallbackRelay}, dmFallbackTTL
	}
	c.dmRelays, c.dmRelaysAt, c.dmRelaysTTL = relays, time.Now(), ttl
	return relays
}

// publish sends ev to all urls and succeeds when at least one relay answered
// OK. A relay that wants NIP-42 AUTH first gets one authenticated retry, as
// nip17.PublishMessage does.
func (c *Client) publish(ctx context.Context, urls []string, ev nostr.Event) error {
	type result struct {
		url string
		err error
	}
	ch := make(chan result, len(urls))
	for _, u := range urls {
		go func() { ch <- result{u, c.publishOne(ctx, u, ev)} }()
	}
	var errs []error
	ok := false
	for range urls {
		r := <-ch
		if r.err == nil {
			ok = true
			continue
		}
		c.log.Debug("publish failed", "relay", r.url, "kind", ev.Kind, "err", r.err)
		errs = append(errs, fmt.Errorf("%s: %w", r.url, r.err))
	}
	if ok {
		return nil
	}
	return fmt.Errorf("no relay accepted kind %d: %w", ev.Kind, errors.Join(errs...))
}

func (c *Client) publishOne(ctx context.Context, url string, ev nostr.Event) error {
	pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	r, err := c.pool.EnsureRelay(url)
	if err != nil {
		return err
	}
	err = r.Publish(pctx, ev)
	if err != nil && strings.Contains(err.Error(), "auth-required:") {
		if authErr := r.Auth(pctx, func(ae *nostr.Event) error { return c.kr.SignEvent(pctx, ae) }); authErr != nil {
			return fmt.Errorf("auth: %w", authErr)
		}
		err = r.Publish(pctx, ev)
	}
	return err
}

func dedupe(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s != "" && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

// lookback is where a subscription starts: two days before the newest wrap
// handled, but never earlier than nine days ago, because the reviewer drops
// messages older than a week anyway. With no wrap handled yet (0) it starts
// as if the newest one were seven days old.
func lookback(last, now int64) int64 {
	const week = 7 * 86400
	if last < now-week {
		last = now - week
	}
	return last - listenLookback
}
