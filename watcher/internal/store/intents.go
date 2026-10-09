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
}

const intentColumns = `id, sender, recipient, note_id, pid, amount, type, created_at, expiration, state, lws_event_id, stored_at, anon`

func scanIntent(sc interface{ Scan(...any) error }) (IntentRow, error) {
	var r IntentRow
	var anon int64
	err := sc.Scan(&r.ID, &r.Sender, &r.Recipient, &r.NoteID, &r.PID, &r.Amount, &r.Type,
		&r.CreatedAt, &r.Expiration, &r.State, &r.LWSEventID, &r.StoredAt, &anon)
	r.Anon = anon != 0
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

// InsertIntent stores a new open intent; returns false if the id or the
// (recipient, pid) pair already exists.
func (s *Store) InsertIntent(in protocol.Intent, storedAt int64) (bool, error) {
	anon := 0
	if in.Anon {
		anon = 1
	}
	res, err := s.db.Exec(`INSERT OR IGNORE INTO intents(`+intentColumns+`) VALUES(?,?,?,?,?,?,?,?,?,'open','',?,?)`,
		in.ID, in.Sender, in.Recipient, in.NoteID, in.PID, in.Amount, in.Type, in.CreatedAt, in.Expiration, storedAt, anon)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

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

func (s *Store) IntentsForRecipient(pubkey string) ([]IntentRow, error) {
	return s.queryIntents(`SELECT `+intentColumns+` FROM intents WHERE recipient = ? ORDER BY stored_at`, pubkey)
}
