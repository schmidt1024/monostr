package store

import "database/sql"

// How the operator hears of a review case: one message of its own, or a line
// in the next summary once the day's cap of single messages is used up.
const (
	DeliverySingle  = "single"
	DeliverySummary = "summary"
)

// How a review case ended: the operator answered «weg», or the picture went
// away by another path (owner deleted it, ban, purge).
const (
	ClosedWeg     = "weg"
	ClosedRemoved = "removed"
)

// A picture can have several cases over time (removed, then uploaded again),
// but at most one open one: a unique index on the open rows enforces it.

// ReviewCase is a stored picture in the grey zone that waits for a look. Times
// are unix seconds, 0 means not set. Type is kept here because the blob row is
// gone by the time a closed case is listed.
type ReviewCase struct {
	SHA256, Pubkey, Type, Delivery, Outcome string
	Score                                   float64
	At, Notified, NextTry, Closed           int64
	Attempts                                int
}

const reviewColumns = `sha256, pubkey, type, delivery, outcome, score, at, notified, next_try, closed, attempts`

func scanReviews(rows *sql.Rows, err error) ([]ReviewCase, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ReviewCase
	for rows.Next() {
		var c ReviewCase
		if err := rows.Scan(&c.SHA256, &c.Pubkey, &c.Type, &c.Delivery, &c.Outcome, &c.Score,
			&c.At, &c.Notified, &c.NextTry, &c.Closed, &c.Attempts); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// AddStoredReview records a picture that is stored but waits for review: what
// AddStored writes, with the outcome "review", plus the case. The delivery is
// "single" while fewer than singlesPerDay single cases were created since
// dayStart, otherwise "summary"; counting and inserting share one transaction
// so concurrent uploads cannot overshoot the cap.
func (s *Store) AddStoredReview(b Blob, pubkey string, score float64, dayStart int64, singlesPerDay int) (delivery string, err error) {
	err = s.tx(func(tx *sql.Tx) error {
		var singles int
		if err := tx.QueryRow(`SELECT COUNT(*) FROM review WHERE delivery = ? AND at >= ?`, DeliverySingle, dayStart).Scan(&singles); err != nil {
			return err
		}
		delivery = DeliverySummary
		if singles < singlesPerDay {
			delivery = DeliverySingle
		}
		if err := insertStored(tx, b, pubkey, score, OutcomeReview); err != nil {
			return err
		}
		_, err := tx.Exec(`INSERT INTO review(sha256, score, pubkey, type, at, delivery) VALUES(?,?,?,?,?,?)`,
			b.SHA256, score, pubkey, b.Type, b.CreatedAt, delivery)
		return err
	})
	if err != nil {
		delivery = ""
	}
	return delivery, err
}

// DueNotices returns the single cases whose message is still to be sent and
// whose retry time has come, oldest first.
func (s *Store) DueNotices(now int64) ([]ReviewCase, error) {
	return scanReviews(s.db.Query(`SELECT `+reviewColumns+` FROM review
		WHERE delivery = ? AND notified = 0 AND closed = 0 AND next_try <= ? ORDER BY at, id`, DeliverySingle, now))
}

// NoticeSent records that the single message went out.
func (s *Store) NoticeSent(sha256 string, at int64) error {
	_, err := s.db.Exec(`UPDATE review SET notified = ? WHERE closed = 0 AND sha256 = ?`, at, sha256)
	return err
}

// NoticeFailed counts a failed attempt and schedules the next one; the
// schedule is stored so a restart does not forget it.
func (s *Store) NoticeFailed(sha256 string, nextTry int64) error {
	_, err := s.db.Exec(`UPDATE review SET attempts = attempts + 1, next_try = ? WHERE closed = 0 AND sha256 = ?`, nextTry, sha256)
	return err
}

// Unsummarized returns the summary cases not yet reported, oldest first.
func (s *Store) Unsummarized() ([]ReviewCase, error) {
	return scanReviews(s.db.Query(`SELECT `+reviewColumns+` FROM review
		WHERE delivery = ? AND notified = 0 AND closed = 0 ORDER BY at, id`, DeliverySummary))
}

// MarkSummarized records that the cases were reported in a summary.
func (s *Store) MarkSummarized(sha256s []string, at int64) error {
	return s.tx(func(tx *sql.Tx) error {
		for _, h := range sha256s {
			if _, err := tx.Exec(`UPDATE review SET notified = ? WHERE closed = 0 AND sha256 = ?`, at, h); err != nil {
				return err
			}
		}
		return nil
	})
}

// OpenReviews returns the cases still waiting, newest first.
func (s *Store) OpenReviews(limit int) ([]ReviewCase, error) {
	return scanReviews(s.db.Query(`SELECT `+reviewColumns+` FROM review
		WHERE closed = 0 ORDER BY at DESC, id DESC LIMIT ?`, limit))
}

// Reviews returns all cases, open and closed, newest first.
func (s *Store) Reviews(limit int) ([]ReviewCase, error) {
	return scanReviews(s.db.Query(`SELECT `+reviewColumns+` FROM review
		ORDER BY at DESC, id DESC LIMIT ?`, limit))
}

// CloseReview closes an open case with the outcome; closed is false when
// there was no open case (unknown hash or answered before).
func (s *Store) CloseReview(sha256, outcome string, at int64) (closed bool, err error) {
	res, err := s.db.Exec(`UPDATE review SET closed = ?, outcome = ? WHERE closed = 0 AND sha256 = ?`, at, outcome, sha256)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// WrapSeen remembers a gift wrap id; fresh is false when it was seen before,
// so a message the relay delivers twice is acted on once.
func (s *Store) WrapSeen(id string, at int64) (fresh bool, err error) {
	res, err := s.db.Exec(`INSERT OR IGNORE INTO seen_wraps(id, at) VALUES(?,?)`, id, at)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// LastWrapAt is the time of the newest remembered wrap, 0 when there is none.
func (s *Store) LastWrapAt() (int64, error) {
	var at int64
	err := s.db.QueryRow(`SELECT COALESCE(MAX(at), 0) FROM seen_wraps`).Scan(&at)
	return at, err
}

// RemoveForReview carries out the operator's «weg»: in one transaction it
// closes the open case as "weg", bans the hash and deletes the blob row, so a
// failure half way leaves nothing changed and the answer can be repeated.
// closed is false, with nothing changed, when there is no open case (already
// answered, or removed by an admin in the meantime).
func (s *Store) RemoveForReview(sha256 string, at int64) (closed bool, err error) {
	err = s.tx(func(tx *sql.Tx) error {
		res, err := tx.Exec(`UPDATE review SET closed = ?, outcome = ? WHERE closed = 0 AND sha256 = ?`, at, ClosedWeg, sha256)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return nil
		}
		closed = true
		if _, err := tx.Exec(`INSERT INTO banned_hashes(sha256, at, note) VALUES(?,?,?)
			ON CONFLICT(sha256) DO UPDATE SET note = excluded.note`, sha256, at, "weg per DM"); err != nil {
			return err
		}
		_, err = tx.Exec(`DELETE FROM blobs WHERE sha256 = ?`, sha256)
		return err
	})
	if err != nil {
		closed = false
	}
	return closed, err
}

// PruneWraps forgets wraps remembered before the given time and returns how
// many went.
func (s *Store) PruneWraps(before int64) (int64, error) {
	res, err := s.db.Exec(`DELETE FROM seen_wraps WHERE at < ?`, before)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
