package store

import "database/sql"

type Account struct {
	Pubkey    string
	Address   string
	CreatedAt int64
}

// queryRow is a constraint that matches both *sql.DB and *sql.Tx
type queryRow interface {
	QueryRow(string, ...any) *sql.Row
}

// getAccount queries a single account; works with both *sql.DB and *sql.Tx.
func getAccount(q queryRow, pubkey string) (Account, bool, error) {
	var a Account
	err := q.QueryRow(`SELECT nostr_pubkey, address, created_at FROM accounts WHERE nostr_pubkey = ?`, pubkey).
		Scan(&a.Pubkey, &a.Address, &a.CreatedAt)
	if err == sql.ErrNoRows {
		return Account{}, false, nil
	}
	return a, err == nil, err
}

// PutAccount inserts or replaces the account for a.Pubkey and returns the
// previous row if there was one.
func (s *Store) PutAccount(a Account) (*Account, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	prev, ok, err := getAccount(tx, a.Pubkey)
	if err != nil {
		return nil, err
	}

	_, err = tx.Exec(`INSERT INTO accounts(nostr_pubkey, address, created_at) VALUES(?,?,?)
		ON CONFLICT(nostr_pubkey) DO UPDATE SET address = excluded.address, created_at = excluded.created_at`,
		a.Pubkey, a.Address, a.CreatedAt)
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}

	if ok {
		return &prev, nil
	}
	return nil, nil
}

func (s *Store) GetAccount(pubkey string) (Account, bool, error) {
	return getAccount(s.db, pubkey)
}

func (s *Store) DeleteAccount(pubkey string) (Account, bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return Account{}, false, err
	}
	defer tx.Rollback()

	a, ok, err := getAccount(tx, pubkey)
	if err != nil || !ok {
		return Account{}, false, err
	}

	_, err = tx.Exec(`DELETE FROM accounts WHERE nostr_pubkey = ?`, pubkey)
	if err != nil {
		return Account{}, false, err
	}

	if err := tx.Commit(); err != nil {
		return Account{}, false, err
	}

	return a, true, nil
}

func (s *Store) ListAccounts() ([]Account, error) {
	rows, err := s.db.Query(`SELECT nostr_pubkey, address, created_at FROM accounts ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Account
	for rows.Next() {
		var a Account
		if err := rows.Scan(&a.Pubkey, &a.Address, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
