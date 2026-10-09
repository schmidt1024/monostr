// Package store is the watcher's SQLite persistence (accounts, intents,
// receipts, relay cache, meta). One writer, WAL mode, no ORM.
package store

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS accounts (
  nostr_pubkey TEXT PRIMARY KEY,
  address      TEXT NOT NULL,
  created_at   INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS intents (
  id           TEXT PRIMARY KEY,
  sender       TEXT NOT NULL,
  recipient    TEXT NOT NULL,
  note_id      TEXT NOT NULL,
  pid          TEXT NOT NULL,
  amount       INTEGER NOT NULL,
  type         TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  expiration   INTEGER NOT NULL,
  state        TEXT NOT NULL DEFAULT 'open',
  lws_event_id TEXT NOT NULL DEFAULT '',
  stored_at    INTEGER NOT NULL,
  anon         INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS intents_recipient ON intents(recipient, state);
CREATE INDEX IF NOT EXISTS intents_expiration ON intents(expiration);
CREATE UNIQUE INDEX IF NOT EXISTS intents_recipient_pid ON intents(recipient, pid);
CREATE TABLE IF NOT EXISTS receipts (
  id         TEXT PRIMARY KEY,
  intent_id  TEXT NOT NULL REFERENCES intents(id) ON DELETE CASCADE,
  tx_hash    TEXT NOT NULL,
  amount     INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  seen_at    INTEGER NOT NULL,
  state      TEXT NOT NULL DEFAULT 'seen',
  published  INTEGER NOT NULL DEFAULT 0,
  UNIQUE(intent_id, tx_hash)
);
CREATE TABLE IF NOT EXISTS relay_cache (
  pubkey      TEXT PRIMARY KEY,
  relays_json TEXT NOT NULL,
  fetched_at  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
`

// Open creates or opens the database file and applies the schema.
func Open(path string) (*Store, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("apply schema: %w", err)
	}
	if err := migrate(db); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

// migrate brings a database created by an older watcher up to the current
// schema. Each step is idempotent.
func migrate(db *sql.DB) error {
	has, err := hasColumn(db, "intents", "anon")
	if err != nil || has {
		return err
	}
	_, err = db.Exec(`ALTER TABLE intents ADD COLUMN anon INTEGER NOT NULL DEFAULT 0`)
	return err
}

func hasColumn(db *sql.DB, table, column string) (bool, error) {
	var n int
	err := db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?`, table, column).Scan(&n)
	return n > 0, err
}

func (s *Store) Close() error { return s.db.Close() }
