package store

import (
	"database/sql"
	"encoding/json"
	"sort"
	"strings"
)

func (s *Store) GetRelayCache(pubkey string) ([]string, int64, bool, error) {
	var raw string
	var at int64
	err := s.db.QueryRow(`SELECT relays_json, fetched_at FROM relay_cache WHERE pubkey = ?`, pubkey).Scan(&raw, &at)
	if err == sql.ErrNoRows {
		return nil, 0, false, nil
	}
	if err != nil {
		return nil, 0, false, err
	}
	var relays []string
	if err := json.Unmarshal([]byte(raw), &relays); err != nil {
		return nil, 0, false, err
	}
	return relays, at, true, nil
}

func (s *Store) PutRelayCache(pubkey string, relays []string, fetchedAt int64) error {
	if relays == nil {
		relays = []string{}
	}
	raw, err := json.Marshal(relays)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`INSERT INTO relay_cache(pubkey, relays_json, fetched_at) VALUES(?,?,?)
		ON CONFLICT(pubkey) DO UPDATE SET relays_json = excluded.relays_json, fetched_at = excluded.fetched_at`,
		pubkey, string(raw), fetchedAt)
	return err
}

func (s *Store) GetMeta(key string) (string, bool, error) {
	var v string
	err := s.db.QueryRow(`SELECT value FROM meta WHERE key = ?`, key).Scan(&v)
	if err == sql.ErrNoRows {
		return "", false, nil
	}
	return v, err == nil, err
}

func (s *Store) SetMeta(key, value string) error {
	_, err := s.db.Exec(`INSERT INTO meta(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

func (s *Store) DeleteMeta(key string) error {
	_, err := s.db.Exec(`DELETE FROM meta WHERE key = ?`, key)
	return err
}

const metaOrphanWebhooks = "orphan_webhooks"

// AddOrphanWebhooks records LWS webhook event ids whose removal failed (see
// ReplaceUnboundIntent); the sweep retries them. Read and write in one
// transaction: intents and the sweep run on different goroutines.
func (s *Store) AddOrphanWebhooks(ids []string) error {
	return s.editOrphans(func(set map[string]bool) {
		for _, id := range ids {
			set[id] = true
		}
	})
}

// ForgetOrphanWebhooks drops ids that are gone at LWS.
func (s *Store) ForgetOrphanWebhooks(ids []string) error {
	return s.editOrphans(func(set map[string]bool) {
		for _, id := range ids {
			delete(set, id)
		}
	})
}

// OrphanWebhooks lists the recorded ids, sorted.
func (s *Store) OrphanWebhooks() ([]string, error) {
	v, ok, err := s.GetMeta(metaOrphanWebhooks)
	if err != nil || !ok || v == "" {
		return nil, err
	}
	return strings.Split(v, ","), nil
}

func (s *Store) editOrphans(change func(map[string]bool)) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var v string
	err = tx.QueryRow(`SELECT value FROM meta WHERE key = ?`, metaOrphanWebhooks).Scan(&v)
	if err != nil && err != sql.ErrNoRows {
		return err
	}
	set := map[string]bool{}
	for _, id := range strings.Split(v, ",") {
		if id != "" {
			set[id] = true
		}
	}
	change(set)
	ids := make([]string, 0, len(set))
	for id := range set {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	if _, err := tx.Exec(`INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`,
		metaOrphanWebhooks, strings.Join(ids, ",")); err != nil {
		return err
	}
	return tx.Commit()
}
