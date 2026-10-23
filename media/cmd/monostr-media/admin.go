package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/blossom"
	"monostr.com/media/internal/imagecheck"
	"monostr.com/media/internal/limits"
	"monostr.com/media/internal/review"
	"monostr.com/media/internal/storage"
	"monostr.com/media/internal/store"
)

const adminUsage = `usage: monostr-media admin <command>

  delete <sha256>                  remove a picture and every ownership of it
  ban-hash <sha256> [note]         remove a picture and refuse it for good
  unban-hash <sha256>
  ban-pubkey <npub|hex> [--purge] [note]
                                   refuse uploads by this account; --purge also
                                   removes its pictures that nobody else owns
  unban-pubkey <npub|hex>
  log <npub|hex> [n]               the account's last n uploads and refusals (default 50)
  stats                            totals, today's numbers, largest accounts
  near <score> [n]                 stored pictures scored between <score> and the
                                   threshold (MEDIA_NSFW_THRESHOLD), newest first
                                   (default 50); review cases: see review
  review [--all] [n]               pictures waiting for a look, newest first (default
                                   50): code, score, notice (sent, pending, summary),
                                   time, address, account; --all adds closed cases
                                   with their outcome
  gc                               remove objects in the bucket that no row names, and
                                   forget review messages older than 30 days
`

// gcGrace protects an object that an upload has put into the bucket but not
// yet written to the database.
const gcGrace = time.Hour

// wrapKeep is how long a seen gift wrap is remembered. The reviewer subscribes
// from two days before the newest wrap and ignores messages older than seven
// days, so a month is far more than replay protection needs.
const wrapKeep = 30 * 24 * time.Hour

type admin struct {
	store     *store.Store
	bucket    storage.Bucket
	publicURL string
	threshold float64
	out       io.Writer
	now       func() time.Time
}

var errUsage = errors.New(adminUsage)

func parseHash(s string) (string, error) {
	s = strings.ToLower(s)
	if !blossom.IsHex64(s) {
		return "", fmt.Errorf("%q is not a SHA-256 (64 hex characters)", s)
	}
	return s, nil
}

// parsePubkey accepts an npub or 64 hex characters.
func parsePubkey(s string) (string, error) {
	if strings.HasPrefix(s, "npub1") {
		prefix, value, err := nip19.Decode(s)
		hex, ok := value.(string)
		if err != nil || prefix != "npub" || !ok {
			return "", fmt.Errorf("%q is not a valid npub", s)
		}
		return hex, nil
	}
	s = strings.ToLower(s)
	if !blossom.IsHex64(s) {
		return "", fmt.Errorf("%q is neither an npub nor 64 hex characters", s)
	}
	return s, nil
}

func (a admin) url(u store.Upload) string {
	return a.publicURL + "/" + u.SHA256 + "." + imagecheck.Ext(u.Type)
}

func stamp(unix int64) string { return time.Unix(unix, 0).UTC().Format("2006-01-02 15:04:05") }

func (a admin) run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errUsage
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "delete":
		if len(rest) != 1 {
			return errUsage
		}
		hash, err := parseHash(rest[0])
		if err != nil {
			return err
		}
		return a.deleteBlob(ctx, hash)
	case "ban-hash":
		if len(rest) < 1 {
			return errUsage
		}
		hash, err := parseHash(rest[0])
		if err != nil {
			return err
		}
		if err := a.store.BanHash(hash, strings.Join(rest[1:], " "), a.now().Unix()); err != nil {
			return err
		}
		fmt.Fprintln(a.out, "banned", hash)
		return a.deleteBlob(ctx, hash)
	case "unban-hash":
		if len(rest) != 1 {
			return errUsage
		}
		hash, err := parseHash(rest[0])
		if err != nil {
			return err
		}
		was, err := a.store.UnbanHash(hash)
		if err != nil {
			return err
		}
		if !was {
			return fmt.Errorf("%s was not banned", hash)
		}
		fmt.Fprintln(a.out, "unbanned", hash)
		return nil
	case "ban-pubkey":
		if len(rest) < 1 {
			return errUsage
		}
		pubkey, err := parsePubkey(rest[0])
		if err != nil {
			return err
		}
		purge := false
		var note []string
		for _, arg := range rest[1:] {
			if arg == "--purge" {
				purge = true
			} else {
				note = append(note, arg)
			}
		}
		if err := a.store.BanPubkey(pubkey, strings.Join(note, " "), a.now().Unix()); err != nil {
			return err
		}
		fmt.Fprintln(a.out, "banned", pubkey)
		if !purge {
			return nil
		}
		gone, err := a.store.PurgePubkey(pubkey)
		if err != nil {
			return err
		}
		for _, hash := range gone {
			if err := a.bucket.Delete(ctx, hash); err != nil {
				return fmt.Errorf("delete %s from the bucket: %w (run gc later)", hash, err)
			}
		}
		fmt.Fprintf(a.out, "purged %d pictures\n", len(gone))
		return nil
	case "unban-pubkey":
		if len(rest) != 1 {
			return errUsage
		}
		pubkey, err := parsePubkey(rest[0])
		if err != nil {
			return err
		}
		was, err := a.store.UnbanPubkey(pubkey)
		if err != nil {
			return err
		}
		if !was {
			return fmt.Errorf("%s was not banned", pubkey)
		}
		fmt.Fprintln(a.out, "unbanned", pubkey)
		return nil
	case "log":
		if len(rest) < 1 || len(rest) > 2 {
			return errUsage
		}
		pubkey, err := parsePubkey(rest[0])
		if err != nil {
			return err
		}
		limit, err := optionalCount(rest[1:])
		if err != nil {
			return err
		}
		lines, err := a.store.Log(pubkey, limit)
		if err != nil {
			return err
		}
		for _, u := range lines {
			fmt.Fprintf(a.out, "%s  %-9s  %.4f  %8d  %s  %s\n", stamp(u.At), u.Outcome, u.Score, u.Size, u.Type, u.SHA256)
		}
		fmt.Fprintf(a.out, "%d lines\n", len(lines))
		return nil
	case "stats":
		if len(rest) != 0 {
			return errUsage
		}
		st, err := a.store.Stats(limits.DayStart(a.now()))
		if err != nil {
			return err
		}
		fmt.Fprintf(a.out, "pictures        %d\nbytes           %d\naccounts        %d\nstored today    %d (%d bytes)\nrefused today   %d\n",
			st.Blobs, st.Bytes, st.Owners, st.StoredToday, st.BytesToday, st.RejectedToday)
		fmt.Fprintf(a.out, "open reviews: %d\n", st.OpenReviews)
		for _, u := range st.Top {
			fmt.Fprintf(a.out, "  %s  %5d pictures  %12d bytes\n", u.Pubkey, u.Blobs, u.Bytes)
		}
		return nil
	case "near":
		if len(rest) < 1 || len(rest) > 2 {
			return errUsage
		}
		from, err := strconv.ParseFloat(rest[0], 64)
		if err != nil || from < 0 || from >= a.threshold {
			return fmt.Errorf("the score must be a number from 0 to below the threshold %.2f", a.threshold)
		}
		limit, err := optionalCount(rest[1:])
		if err != nil {
			return err
		}
		lines, err := a.store.Near(from, a.threshold, limit)
		if err != nil {
			return err
		}
		for _, u := range lines {
			fmt.Fprintf(a.out, "%.4f  %s  %s  %s\n", u.Score, stamp(u.At), a.url(u), u.Pubkey)
		}
		fmt.Fprintf(a.out, "%d pictures scored from %.2f to below %.2f\n", len(lines), from, a.threshold)
		return nil
	case "review":
		all := false
		if len(rest) > 0 && rest[0] == "--all" {
			all, rest = true, rest[1:]
		}
		if len(rest) > 1 {
			return errUsage
		}
		limit, err := optionalCount(rest)
		if err != nil {
			return err
		}
		return a.review(all, limit)
	case "gc":
		if len(rest) != 0 {
			return errUsage
		}
		return a.gc(ctx)
	default:
		return errUsage
	}
}

func optionalCount(args []string) (int, error) {
	if len(args) == 0 {
		return 50, nil
	}
	n, err := strconv.Atoi(args[0])
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("%q is not a positive count", args[0])
	}
	return n, nil
}

// deleteBlob removes the row first and the object second: the database never
// names a missing object.
func (a admin) deleteBlob(ctx context.Context, hash string) error {
	existed, err := a.store.DeleteBlob(hash)
	if err != nil {
		return err
	}
	if err := a.bucket.Delete(ctx, hash); err != nil {
		return fmt.Errorf("delete %s from the bucket: %w (run gc later)", hash, err)
	}
	if existed {
		fmt.Fprintln(a.out, "deleted", hash)
	} else {
		fmt.Fprintln(a.out, "no such picture", hash)
	}
	return nil
}

// review lists the cases newest first. The state column says how the operator
// heard of an open case; a closed one shows how it ended.
func (a admin) review(all bool, limit int) error {
	open, err := a.store.OpenReviews(1 << 30)
	if err != nil {
		return err
	}
	hashes := make([]string, len(open))
	for i, c := range open {
		hashes[i] = c.SHA256
	}
	var cases []store.ReviewCase
	if all {
		cases, err = a.store.Reviews(limit)
	} else if len(open) > limit {
		cases = open[:limit]
	} else {
		cases = open
	}
	if err != nil {
		return err
	}
	for _, c := range cases {
		state := c.Outcome
		if c.Closed == 0 {
			switch {
			case c.Delivery == store.DeliverySummary:
				state = "summary"
			case c.Notified > 0:
				state = "sent"
			default:
				state = "pending"
			}
		}
		url := a.url(store.Upload{SHA256: c.SHA256, Type: c.Type})
		fmt.Fprintf(a.out, "%s  %.2f  %s  %s  %s  %s\n", review.Code(c.SHA256, hashes), c.Score, state, stamp(c.At), url, c.Pubkey)
	}
	return nil
}

func (a admin) gc(ctx context.Context) error {
	pruned, err := a.store.PruneWraps(a.now().Add(-wrapKeep).Unix())
	if err != nil {
		return err
	}
	fmt.Fprintf(a.out, "%d seen messages forgotten\n", pruned)
	objects, err := a.bucket.List(ctx)
	if err != nil {
		return err
	}
	cutoff := a.now().Add(-gcGrace)
	removed := 0
	for _, o := range objects {
		if o.Modified.After(cutoff) {
			continue
		}
		if _, known, err := a.store.Blob(o.Key); err != nil {
			return err
		} else if known {
			continue
		}
		if err := a.bucket.Delete(ctx, o.Key); err != nil {
			return err
		}
		fmt.Fprintln(a.out, "removed", o.Key)
		removed++
	}
	fmt.Fprintf(a.out, "%d objects, %d removed\n", len(objects), removed)
	return nil
}
