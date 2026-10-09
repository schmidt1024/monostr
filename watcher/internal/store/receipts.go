package store

import "database/sql"

type ReceiptState string

const (
	ReceiptSeen      ReceiptState = "seen"
	ReceiptConfirmed ReceiptState = "confirmed"
	ReceiptDropped   ReceiptState = "dropped"
)

type Receipt struct {
	ID        string
	IntentID  string
	TxHash    string
	Amount    int64
	CreatedAt int64
	SeenAt    int64
	State     ReceiptState
	Published bool
}

const receiptColumns = `id, intent_id, tx_hash, amount, created_at, seen_at, state, published`

func scanReceipt(sc interface{ Scan(...any) error }) (Receipt, error) {
	var r Receipt
	var published int
	err := sc.Scan(&r.ID, &r.IntentID, &r.TxHash, &r.Amount, &r.CreatedAt, &r.SeenAt, &r.State, &published)
	r.Published = published == 1
	return r, err
}

func (s *Store) queryReceipts(query string, args ...any) ([]Receipt, error) {
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Receipt
	for rows.Next() {
		r, err := scanReceipt(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// InsertReceipt stores a receipt; returns false when (intent_id, tx_hash) exists.
func (s *Store) InsertReceipt(r Receipt) (bool, error) {
	res, err := s.db.Exec(`INSERT OR IGNORE INTO receipts(`+receiptColumns+`) VALUES(?,?,?,?,?,?,?,0)`,
		r.ID, r.IntentID, r.TxHash, r.Amount, r.CreatedAt, r.SeenAt, string(r.State))
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

func (s *Store) ReceiptByTx(intentID, txHash string) (Receipt, bool, error) {
	r, err := scanReceipt(s.db.QueryRow(`SELECT `+receiptColumns+` FROM receipts WHERE intent_id = ? AND tx_hash = ?`, intentID, txHash))
	if err == sql.ErrNoRows {
		return Receipt{}, false, nil
	}
	return r, err == nil, err
}

// ReceiptByTxHash returns any receipt for txHash, regardless of intent.
func (s *Store) ReceiptByTxHash(txHash string) (Receipt, bool, error) {
	r, err := scanReceipt(s.db.QueryRow(`SELECT `+receiptColumns+` FROM receipts WHERE tx_hash = ? ORDER BY seen_at LIMIT 1`, txHash))
	if err == sql.ErrNoRows {
		return Receipt{}, false, nil
	}
	return r, err == nil, err
}

func (s *Store) GetReceipt(id string) (Receipt, bool, error) {
	r, err := scanReceipt(s.db.QueryRow(`SELECT `+receiptColumns+` FROM receipts WHERE id = ?`, id))
	if err == sql.ErrNoRows {
		return Receipt{}, false, nil
	}
	return r, err == nil, err
}

func (s *Store) SetReceiptState(id string, st ReceiptState) error {
	_, err := s.db.Exec(`UPDATE receipts SET state = ? WHERE id = ?`, string(st), id)
	return err
}

func (s *Store) SetReceiptPublished(id string, published bool) error {
	v := 0
	if published {
		v = 1
	}
	_, err := s.db.Exec(`UPDATE receipts SET published = ? WHERE id = ?`, v, id)
	return err
}

// ReplaceReceiptEvent moves a receipt row to a new Nostr event (new id and
// created_at), resets published and sets the state. Used when a retracted
// receipt is resurrected by a confirmation. It reports false when no row
// has oldID any more (a concurrent resurrection already moved it).
func (s *Store) ReplaceReceiptEvent(oldID, newID string, createdAt int64, state ReceiptState) (bool, error) {
	return s.execAffected(`UPDATE receipts SET id = ?, created_at = ?, published = 0, state = ? WHERE id = ?`,
		newID, createdAt, string(state), oldID)
}

// DropReceiptIfSeen moves a receipt from 'seen' to 'dropped'; false means
// it was not 'seen' (already confirmed, dropped or gone).
func (s *Store) DropReceiptIfSeen(id string) (bool, error) {
	return s.execAffected(`UPDATE receipts SET state = 'dropped' WHERE id = ? AND state = 'seen'`, id)
}

// ConfirmReceiptIfSeen moves a receipt from 'seen' to 'confirmed'; false
// means it was not 'seen'.
func (s *Store) ConfirmReceiptIfSeen(id string) (bool, error) {
	return s.execAffected(`UPDATE receipts SET state = 'confirmed' WHERE id = ? AND state = 'seen'`, id)
}

func (s *Store) execAffected(query string, args ...any) (bool, error) {
	res, err := s.db.Exec(query, args...)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	return n == 1, err
}

// StaleSeenReceipts lists receipts still 'seen' whose seen_at is before the cutoff.
func (s *Store) StaleSeenReceipts(seenBefore int64) ([]Receipt, error) {
	return s.queryReceipts(`SELECT `+receiptColumns+` FROM receipts WHERE state = 'seen' AND seen_at < ? ORDER BY seen_at`, seenBefore)
}

func (s *Store) UnpublishedReceipts() ([]Receipt, error) {
	return s.queryReceipts(`SELECT ` + receiptColumns + ` FROM receipts WHERE published = 0 AND state != 'dropped' ORDER BY seen_at`)
}
