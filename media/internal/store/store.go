// Package store is the media server's SQLite persistence: which blobs exist,
// who owns them, the upload log and the ban lists. The pictures themselves
// live in the bucket. One writer, WAL mode, no ORM.
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
CREATE TABLE IF NOT EXISTS blobs (
  sha256     TEXT PRIMARY KEY,
  size       INTEGER NOT NULL,
  type       TEXT NOT NULL,
  width      INTEGER NOT NULL,
  height     INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS owners (
  pubkey     TEXT NOT NULL,
  sha256     TEXT NOT NULL REFERENCES blobs(sha256) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (pubkey, sha256)
);
CREATE INDEX IF NOT EXISTS owners_sha256 ON owners(sha256);
CREATE TABLE IF NOT EXISTS uploads (
  id      INTEGER PRIMARY KEY AUTOINCREMENT,
  pubkey  TEXT NOT NULL,
  sha256  TEXT NOT NULL,
  size    INTEGER NOT NULL,
  type    TEXT NOT NULL,
  at      INTEGER NOT NULL,
  outcome TEXT NOT NULL,
  score   REAL NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS uploads_pubkey_at ON uploads(pubkey, at);
CREATE INDEX IF NOT EXISTS uploads_at ON uploads(at);
CREATE TABLE IF NOT EXISTS banned_hashes (
  sha256 TEXT PRIMARY KEY,
  at     INTEGER NOT NULL,
  note   TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS banned_pubkeys (
  pubkey TEXT PRIMARY KEY,
  at     INTEGER NOT NULL,
  note   TEXT NOT NULL DEFAULT ''
);
`

// Upload outcomes in the log.
const (
	OutcomeStored    = "stored"
	OutcomeDuplicate = "duplicate"
	OutcomeNSFW      = "nsfw"
)

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
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// Ping reports whether the database answers.
func (s *Store) Ping() error {
	var one int
	return s.db.QueryRow(`SELECT 1`).Scan(&one)
}

// tx runs fn in a transaction and rolls back on error.
func (s *Store) tx(fn func(*sql.Tx) error) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}
