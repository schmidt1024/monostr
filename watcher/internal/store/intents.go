package store

import (
	"database/sql"

	"monostr.com/watcher/internal/protocol"
)

type IntentState string

const (
	StateOpen      IntentState = "open"
	StateSeen      IntentState = "seen"
	StateConfirmed IntentState = "confirmed"
)

type IntentRow struct {
	protocol.Intent
	State      IntentState
	LWSEventID string
	StoredAt   int64
	// ChainHeight is the chain height the watcher observed when it stored the
	// intent (0 = unknown, e.g. rows from before the column existed); see
	// MinPaymentBlock.
	ChainHeight uint64
}

// PaymentSlackBlocks (about an hour) covers a cached chain height, an intent
// that reached the watcher late through relay backfill, and reorgs.
const PaymentSlackBlocks = 30

// MinPaymentBlock is the lowest block a payment for the intent may be in. A
// payment in an older block was made before the intent existed (an old,
// uncredited payment whose pid a new intent re-uses) and is not credited.
// It depends only on ChainHeight, never on the sender-chosen created_at.
// 0 means no bound.
func (r IntentRow) MinPaymentBlock() uint64 {
	if r.ChainHeight <= PaymentSlackBlocks {
		return 0
	}
	return r.ChainHeight - PaymentSlackBlocks
}

const intentColumns = `id, sender, recipient, note_id, pid, amount, type, created_at, expiration, state, lws_event_id, stored_at, anon, chain_height, bound`

func scanIntent(sc interface{ Scan(...any) error }) (IntentRow, error) {
	var r IntentRow
	var anon, bound int64
	err := sc.Scan(&r.ID, &r.Sender, &r.Recipient, &r.NoteID, &r.PID, &r.Amount, &r.Type,
		&r.CreatedAt, &r.Expiration, &r.State, &r.LWSEventID, &r.StoredAt, &anon, &r.ChainHeight, &bound)
	r.Anon = anon != 0
	r.Bound = bound != 0
	return r, err
}

func (s *Store) queryIntents(query string, args ...any) ([]IntentRow, error) {
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []IntentRow
	for rows.Next() {
		r, err := scanIntent(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// InsertIntent stores a new open intent with an unknown chain height; see
// InsertIntentAt.
func (s *Store) InsertIntent(in protocol.Intent, storedAt int64) (bool, error) {
	return s.InsertIntentAt(in, storedAt, 0)
}

// InsertIntentAt stores a new open intent together with the chain height at
// storage time; returns false if the id or the (recipient, pid) pair already
// exists, or the pid is taken by an intent of another account registered
// with the same address.
func (s *Store) InsertIntentAt(in protocol.Intent, storedAt int64, chainHeight uint64) (bool, error) {
	anon, bound := boolInt(in.Anon), boolInt(in.Bound)
	// LWS webhooks are keyed by (address, pid): two accounts registered with
	// the same address would each get the other's payments for a shared pid,
	// so the pid must be unique per address, not only per recipient
	res, err := s.db.Exec(`INSERT OR IGNORE INTO intents(`+intentColumns+`)
		SELECT ?,?,?,?,?,?,?,?,?,'open','',?,?,?,?
		WHERE NOT EXISTS (SELECT 1 FROM intents i JOIN accounts a ON a.nostr_pubkey = i.recipient
			WHERE i.pid = ? AND a.address = (SELECT address FROM accounts WHERE nostr_pubkey = ?))`,
		in.ID, in.Sender, in.Recipient, in.NoteID, in.PID, in.Amount, in.Type, in.CreatedAt, in.Expiration, storedAt, anon, chainHeight, bound,
		in.PID, in.Recipient)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// ReplaceUnboundIntent lets a bound intent (tips 0.6) take the pid of stored
// unbound ones: someone who copied a sender's pid into an intent under their
// own key got there first. The conflicts are looked up in the same scope
// InsertIntentAt refuses (the recipient, or any account registered with the
// recipient's address). They are replaced only if every one of them is
// unbound, still open and without a receipt; otherwise nothing changes and the
// new intent is not stored (a quiet refusal, not an error). Returns the removed
// rows; their LWS webhooks (token = their ids) are the caller's to remove.
func (s *Store) ReplaceUnboundIntent(in protocol.Intent, storedAt int64, chainHeight uint64) ([]IntentRow, bool, error) {
	if !in.Bound {
		return nil, false, nil
	}
	tx, err := s.db.Begin()
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback()
	rows, err := tx.Query(`SELECT `+intentColumns+`, EXISTS (SELECT 1 FROM receipts r WHERE r.intent_id = intents.id)
		FROM intents WHERE pid = ? AND (recipient = ?
		OR recipient IN (SELECT nostr_pubkey FROM accounts WHERE address = (SELECT address FROM accounts WHERE nostr_pubkey = ?)))`,
		in.PID, in.Recipient, in.Recipient)
	if err != nil {
		return nil, false, err
	}
	var conflicts []IntentRow
	replaceable := true
	for rows.Next() {
		var paid bool
		r, err := scanIntent(scanWith(rows, &paid))
		if err != nil {
			rows.Close()
			return nil, false, err
		}
		if r.Bound || r.State != StateOpen || paid {
			replaceable = false
		}
		conflicts = append(conflicts, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	if len(conflicts) == 0 || !replaceable {
		return nil, false, nil
	}
	for _, r := range conflicts {
		res, err := tx.Exec(`DELETE FROM intents WHERE id = ? AND bound = 0 AND state = 'open'
			AND NOT EXISTS (SELECT 1 FROM receipts WHERE intent_id = ?)`, r.ID, r.ID)
		if err != nil {
			return nil, false, err
		}
		if n, _ := res.RowsAffected(); n != 1 {
			return nil, false, nil // changed under us: leave everything as it was
		}
	}
	if _, err := tx.Exec(`INSERT INTO intents(`+intentColumns+`) VALUES (?,?,?,?,?,?,?,?,?,'open','',?,?,?,1)`,
		in.ID, in.Sender, in.Recipient, in.NoteID, in.PID, in.Amount, in.Type, in.CreatedAt, in.Expiration, storedAt, boolInt(in.Anon), chainHeight); err != nil {
		return nil, false, err
	}
	return conflicts, true, tx.Commit()
}

// scanWith scans the intent columns into the row and the extra trailing columns into extra.
func scanWith(rows *sql.Rows, extra ...any) interface{ Scan(...any) error } {
	return scannerFunc(func(dest ...any) error { return rows.Scan(append(dest, extra...)...) })
}

type scannerFunc func(dest ...any) error

func (f scannerFunc) Scan(dest ...any) error { return f(dest...) }

func (s *Store) GetIntent(id string) (IntentRow, bool, error) {
	r, err := scanIntent(s.db.QueryRow(`SELECT `+intentColumns+` FROM intents WHERE id = ?`, id))
	if err == sql.ErrNoRows {
		return IntentRow{}, false, nil
	}
	return r, err == nil, err
}

func (s *Store) SetIntentWebhook(id, lwsEventID string) error {
	_, err := s.db.Exec(`UPDATE intents SET lws_event_id = ? WHERE id = ?`, lwsEventID, id)
	return err
}

func (s *Store) ClearIntentWebhook(id string) error { return s.SetIntentWebhook(id, "") }

func (s *Store) SetIntentState(id string, st IntentState) error {
	_, err := s.db.Exec(`UPDATE intents SET state = ? WHERE id = ?`, string(st), id)
	return err
}

func (s *Store) DeleteIntent(id string) error {
	_, err := s.db.Exec(`DELETE FROM intents WHERE id = ?`, id)
	return err
}

// DeleteIntentIfOpen deletes an intent only while it is 'open' and has no
// live (non-dropped) receipt; false means the row was kept.
func (s *Store) DeleteIntentIfOpen(id string) (bool, error) {
	return s.execAffected(`DELETE FROM intents WHERE id = ? AND state = 'open'
		AND NOT EXISTS (SELECT 1 FROM receipts WHERE intent_id = ? AND state != 'dropped')`, id, id)
}

// RecomputeIntentState derives the intent state from its receipts
// (confirmed if any receipt is confirmed, else seen if any is seen, else
// open), stores it and returns it. A missing intent yields "" and no error.
func (s *Store) RecomputeIntentState(id string) (IntentState, error) {
	var st IntentState
	err := s.db.QueryRow(`UPDATE intents SET state = CASE
		WHEN EXISTS (SELECT 1 FROM receipts WHERE intent_id = intents.id AND state = 'confirmed') THEN 'confirmed'
		WHEN EXISTS (SELECT 1 FROM receipts WHERE intent_id = intents.id AND state = 'seen') THEN 'seen'
		ELSE 'open' END
		WHERE id = ? RETURNING state`, id).Scan(&st)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return st, err
}

// IntentsWithoutWebhook lists live (not yet expired) intents that still need a webhook.
func (s *Store) IntentsWithoutWebhook(now int64) ([]IntentRow, error) {
	return s.queryIntents(`SELECT `+intentColumns+` FROM intents
		WHERE lws_event_id = '' AND expiration > ? ORDER BY stored_at`, now)
}

// ExpiredIntents lists intents past expiration that still need work:
// open ones (to delete) or ones that still hold a webhook (to delete it).
func (s *Store) ExpiredIntents(now int64) ([]IntentRow, error) {
	return s.queryIntents(`SELECT `+intentColumns+` FROM intents
		WHERE expiration <= ? AND (state = 'open' OR lws_event_id != '') ORDER BY expiration`, now)
}

func (s *Store) CountOpenIntents(recipient string) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM intents WHERE recipient = ? AND state = 'open'`, recipient).Scan(&n)
	return n, err
}

// IntentsWithWebhook lists every intent that holds an LWS webhook.
func (s *Store) IntentsWithWebhook() ([]IntentRow, error) {
	return s.queryIntents(`SELECT ` + intentColumns + ` FROM intents WHERE lws_event_id != '' ORDER BY stored_at`)
}

// CountOpenIntentsFrom counts the recipient's open intents by one sender.
func (s *Store) CountOpenIntentsFrom(recipient, sender string) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM intents WHERE recipient = ? AND state = 'open' AND sender = ?`, recipient, sender).Scan(&n)
	return n, err
}

func (s *Store) IntentsForRecipient(pubkey string) ([]IntentRow, error) {
	return s.queryIntents(`SELECT `+intentColumns+` FROM intents WHERE recipient = ? ORDER BY stored_at`, pubkey)
}
