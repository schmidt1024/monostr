package store

// BanHash forbids a picture for good. The caller deletes the blob itself.
func (s *Store) BanHash(sha256, note string, now int64) error {
	_, err := s.db.Exec(`INSERT INTO banned_hashes(sha256, at, note) VALUES(?,?,?)
		ON CONFLICT(sha256) DO UPDATE SET note = excluded.note`, sha256, now, note)
	return err
}

func (s *Store) UnbanHash(sha256 string) (was bool, err error) {
	res, err := s.db.Exec(`DELETE FROM banned_hashes WHERE sha256 = ?`, sha256)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

func (s *Store) HashBanned(sha256 string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM banned_hashes WHERE sha256 = ?`, sha256).Scan(&n)
	return n > 0, err
}

// BanPubkey forbids further uploads by the pubkey.
func (s *Store) BanPubkey(pubkey, note string, now int64) error {
	_, err := s.db.Exec(`INSERT INTO banned_pubkeys(pubkey, at, note) VALUES(?,?,?)
		ON CONFLICT(pubkey) DO UPDATE SET note = excluded.note`, pubkey, now, note)
	return err
}

func (s *Store) UnbanPubkey(pubkey string) (was bool, err error) {
	res, err := s.db.Exec(`DELETE FROM banned_pubkeys WHERE pubkey = ?`, pubkey)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

func (s *Store) PubkeyBanned(pubkey string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM banned_pubkeys WHERE pubkey = ?`, pubkey).Scan(&n)
	return n > 0, err
}
