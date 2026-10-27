// Package review decides what the operator is told about pictures in the NSFW
// grey zone and what an answer does. It knows nothing about Nostr: a Messenger
// sends the texts, and whoever receives direct messages calls Handle.
package review

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"monostr.com/media/internal/storage"
	"monostr.com/media/internal/store"
)

// Messenger delivers one text to the operator.
type Messenger interface {
	Send(ctx context.Context, text string) error
}

// Inbound is one received direct message, already unwrapped.
type Inbound struct {
	WrapID  string // rumor id, the exactly-once key; the wrap id is relay-supplied and unchecked
	WrapAt  int64  // gift wrap created_at
	SentAt  int64  // rumor created_at, inside the seal the sender signed
	Sender  string // hex pubkey of the seal signer
	Kind    int    // rumor kind
	Content string
}

// Config names where pictures live and who may answer.
type Config struct {
	PublicURL string
	Operator  string // hex pubkey
	// StorageAlertBytes: the operator hears once when all stored bytes pass
	// it (spec 2026-10-27 video §6); 0 never.
	StorageAlertBytes int64
}

// Deps are the collaborators of a Reviewer.
type Deps struct {
	Store     *store.Store
	Bucket    storage.Bucket
	Messenger Messenger
	Logger    *slog.Logger
	Now       func() time.Time
}

// summaryEvery keeps the summary to one message per hour however many
// pictures arrive.
const summaryEvery = time.Hour

// dmKind is the rumor kind of a NIP-17 chat message.
const dmKind = 14

// listLimit is far above any realistic number of open cases.
const listLimit = 100000

// Reviewer sends review notices and carries out the operator's answers.
type Reviewer struct {
	cfg  Config
	deps Deps

	mu          sync.Mutex // Flush and Handle run on different goroutines
	lastSummary time.Time
	lastStorage time.Time
}

// New returns a Reviewer; a nil Logger or Now get defaults.
func New(cfg Config, deps Deps) *Reviewer {
	if deps.Logger == nil {
		deps.Logger = slog.Default()
	}
	if deps.Now == nil {
		deps.Now = time.Now
	}
	return &Reviewer{cfg: cfg, deps: deps}
}

// maxMessageAge is how old an operator message may be and still be acted on.
const maxMessageAge = 7 * 24 * time.Hour

// clockSkew is how far phone and server clocks may differ, in seconds; a bare
// «weg» next to a notice closer than this is not guessed.
const clockSkew = 60

// retryDelay is the wait after the n-th failed send of one notice.
func retryDelay(n int) time.Duration {
	switch n {
	case 1:
		return time.Minute
	case 2:
		return 5 * time.Minute
	case 3:
		return 30 * time.Minute
	}
	return time.Hour
}

// Flush sends the notices that are due, then the summary if one is owed.
func (r *Reviewer) Flush(ctx context.Context) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.flushNotices(ctx)
	r.flushSummary(ctx)
	r.flushStorage(ctx)
}

// storageEvery: the stored total is looked at once an hour.
const storageEvery = time.Hour

func (r *Reviewer) flushStorage(ctx context.Context) {
	now := r.deps.Now()
	if r.cfg.StorageAlertBytes <= 0 || (!r.lastStorage.IsZero() && now.Sub(r.lastStorage) < storageEvery) {
		return
	}
	r.lastStorage = now
	due, total, err := r.deps.Store.StorageAlert(r.cfg.StorageAlertBytes)
	if err != nil {
		r.deps.Logger.Error("review: storage alert", "err", err)
		return
	}
	if !due {
		return
	}
	if err := r.deps.Messenger.Send(ctx, storageText(r.cfg.PublicURL, total, r.cfg.StorageAlertBytes)); err != nil {
		// not marked: the next hour tries again
		r.deps.Logger.Warn("review: storage alert not sent", "err", err)
		return
	}
	if err := r.deps.Store.MarkStorageAlerted(now.Unix()); err != nil {
		r.deps.Logger.Error("review: storage alert mark", "err", err)
	}
}

func (r *Reviewer) flushNotices(ctx context.Context) {
	now := r.deps.Now()
	due, err := r.deps.Store.DueNotices(now.Unix())
	if err != nil {
		r.deps.Logger.Error("review: due notices", "err", err)
		return
	}
	if len(due) == 0 {
		return
	}
	shas := r.openHashes()
	for _, c := range due {
		if ctx.Err() != nil {
			return
		}
		if err := r.deps.Messenger.Send(ctx, noticeText(r.cfg.PublicURL, Code(c.SHA256, shas), c)); err != nil {
			next := now.Add(retryDelay(c.Attempts + 1)).Unix()
			r.deps.Logger.Warn("review: notice not sent", "sha256", c.SHA256, "attempts", c.Attempts+1, "err", err)
			if err := r.deps.Store.NoticeFailed(c.SHA256, next); err != nil {
				r.deps.Logger.Error("review: notice failed mark", "err", err)
			}
			continue
		}
		if err := r.deps.Store.NoticeSent(c.SHA256, now.Unix()); err != nil {
			r.deps.Logger.Error("review: notice sent mark", "err", err)
		}
	}
}

func (r *Reviewer) flushSummary(ctx context.Context) {
	now := r.deps.Now()
	if !r.lastSummary.IsZero() && now.Sub(r.lastSummary) < summaryEvery {
		return
	}
	cases, err := r.deps.Store.Unsummarized()
	if err != nil {
		r.deps.Logger.Error("review: unsummarized", "err", err)
		return
	}
	if len(cases) == 0 {
		return
	}
	if err := r.deps.Messenger.Send(ctx, summaryText(len(cases), time.Unix(cases[0].At, 0), now)); err != nil {
		// The time is not recorded, so the next flush tries again.
		r.deps.Logger.Warn("review: summary not sent", "err", err)
		return
	}
	r.lastSummary = now
	shas := make([]string, len(cases))
	for i, c := range cases {
		shas[i] = c.SHA256
	}
	if err := r.deps.Store.MarkSummarized(shas, now.Unix()); err != nil {
		r.deps.Logger.Error("review: summary mark", "err", err)
	}
}

// openHashes lists the hashes of the cases open right now; codes are only
// made unique among these.
func (r *Reviewer) openHashes() []string {
	open, err := r.deps.Store.OpenReviews(listLimit)
	if err != nil {
		r.deps.Logger.Error("review: open cases", "err", err)
	}
	shas := make([]string, len(open))
	for i, c := range open {
		shas[i] = c.SHA256
	}
	return shas
}

// Handle acts on one received message. Only the operator's chat messages
// count, and each gift wrap is acted on once; everything else is dropped
// without a reply.
func (r *Reviewer) Handle(ctx context.Context, in Inbound) {
	if in.Sender != r.cfg.Operator || in.Kind != dmKind {
		return
	}
	// A relay can replay an old «weg»; seen_wraps is pruned after 30 days, so
	// anything older than a week is refused here. A bare «weg» only considers
	// cases announced before the message was written (see answer).
	if in.SentAt < r.deps.Now().Add(-maxMessageAge).Unix() {
		return
	}
	fresh, err := r.deps.Store.WrapSeen(in.WrapID, in.WrapAt)
	if err != nil {
		r.deps.Logger.Error("review: wrap seen", "err", err)
		return
	}
	if !fresh {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if err := r.deps.Messenger.Send(ctx, r.answer(in.Content, in.SentAt)); err != nil {
		r.deps.Logger.Warn("review: reply not sent", "err", err)
	}
}

// answer runs the command in a message and returns the reply text. sentAt is
// when the operator wrote it: a bare «weg» must not hit a picture whose notice
// went out after that, because they never saw it.
func (r *Reviewer) answer(content string, sentAt int64) string {
	prefix, ok := ParseCommand(content)
	if !ok {
		return unknownText
	}
	open, err := r.deps.Store.OpenReviews(listLimit)
	if err != nil {
		r.deps.Logger.Error("review: open cases", "err", err)
		return failedText
	}
	var target store.ReviewCase
	if prefix == "" {
		// The newest case whose notice was sent before the operator wrote;
		// a summary case or one announced later is never hit by a bare «weg».
		for _, c := range open {
			if c.Delivery == store.DeliverySingle && c.Notified > 0 && c.Notified <= sentAt &&
				(target.SHA256 == "" || c.Notified > target.Notified) {
				target = c
			}
		}
		if target.SHA256 == "" {
			return noneText
		}
		// Another notice close to the message: phone and server clocks differ,
		// so it is unclear which one the operator meant. Delete nothing.
		for _, c := range open {
			if c.SHA256 != target.SHA256 && c.Delivery == store.DeliverySingle && c.Notified > 0 &&
				c.Notified > sentAt-clockSkew && c.Notified < sentAt+clockSkew {
				return manyNewText
			}
		}
	} else {
		var matches []store.ReviewCase
		for _, c := range open {
			if strings.HasPrefix(c.SHA256, prefix) {
				matches = append(matches, c)
			}
		}
		switch len(matches) {
		case 0:
			return fmt.Sprintf(notFoundFmt, prefix)
		case 1:
			target = matches[0]
		default:
			return fmt.Sprintf(ambigFmt, prefix)
		}
	}
	shas := make([]string, len(open))
	for i, c := range open {
		shas[i] = c.SHA256
	}
	code := Code(target.SHA256, shas) // before closing, while the case still counts
	closed, err := r.remove(target.SHA256)
	if err != nil {
		r.deps.Logger.Error("review: remove", "sha256", target.SHA256, "err", err)
		return fmt.Sprintf(failedFmt, code, code)
	}
	if !closed {
		return fmt.Sprintf(notFoundFmt, code)
	}
	return fmt.Sprintf(doneFmt, code)
}

// remove carries out «weg». The store does the case, ban and blob row in one
// transaction, so an error leaves the case open and the answer can be sent
// again. Only the bucket object is outside it; an error there is only logged
// because "admin gc" finds the orphan.
func (r *Reviewer) remove(sha256 string) (closed bool, err error) {
	closed, err = r.deps.Store.RemoveForReview(sha256, r.deps.Now().Unix())
	if err != nil || !closed {
		return closed, err
	}
	if err := r.deps.Bucket.Delete(context.Background(), sha256); err != nil {
		r.deps.Logger.Error("review: delete object", "sha256", sha256, "err", err)
	}
	return true, nil
}

// Run flushes once at start (notices left unsent by a restart), then on every
// poke and every minute until ctx ends.
func (r *Reviewer) Run(ctx context.Context, poke <-chan struct{}) {
	r.Flush(ctx)
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-poke:
		case <-t.C:
		}
		r.Flush(ctx)
	}
}
