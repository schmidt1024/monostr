package store

import "database/sql"

// Blob is one stored picture.
type Blob struct {
	SHA256    string
	Size      int64
	Type      string
	Width     int
	Height    int
	CreatedAt int64
}

// Blob looks a picture up by hash.
func (s *Store) Blob(sha256 string) (Blob, bool, error) {
	var b Blob
	err := s.db.QueryRow(`SELECT sha256, size, type, width, height, created_at FROM blobs WHERE sha256 = ?`, sha256).
		Scan(&b.SHA256, &b.Size, &b.Type, &b.Width, &b.Height, &b.CreatedAt)
	if err == sql.ErrNoRows {
		return Blob{}, false, nil
	}
	return b, err == nil, err
}

// AddStored records a newly stored picture: the blob, its first owner and the
// log line, in one transaction.
func (s *Store) AddStored(b Blob, pubkey string, score float64) error {
	return s.tx(func(tx *sql.Tx) error {
		if _, err := tx.Exec(`INSERT INTO blobs(sha256, size, type, width, height, created_at) VALUES(?,?,?,?,?,?)`,
			b.SHA256, b.Size, b.Type, b.Width, b.Height, b.CreatedAt); err != nil {
			return err
		}
		if _, err := tx.Exec(`INSERT INTO owners(pubkey, sha256, created_at) VALUES(?,?,?)`, pubkey, b.SHA256, b.CreatedAt); err != nil {
			return err
		}
		_, err := tx.Exec(`INSERT INTO uploads(pubkey, sha256, size, type, at, outcome, score) VALUES(?,?,?,?,?,?,?)`,
			pubkey, b.SHA256, b.Size, b.Type, b.CreatedAt, OutcomeStored, score)
		return err
	})
}

// AddDuplicate records an upload of a picture the server already holds. A
// pubkey that did not own it becomes an owner and the log gets a line (added
// is true). A pubkey that owns it already changes nothing: one authorization
// can be replayed until it expires, and a replay must not grow the log.
func (s *Store) AddDuplicate(b Blob, pubkey string, now int64) (added bool, err error) {
	err = s.tx(func(tx *sql.Tx) error {
		res, err := tx.Exec(`INSERT OR IGNORE INTO owners(pubkey, sha256, created_at) VALUES(?,?,?)`, pubkey, b.SHA256, now)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return nil
		}
		added = true
		_, err = tx.Exec(`INSERT INTO uploads(pubkey, sha256, size, type, at, outcome) VALUES(?,?,?,?,?,?)`,
			pubkey, b.SHA256, b.Size, b.Type, now, OutcomeDuplicate)
		return err
	})
	return added, err
}

// IsOwner reports whether the pubkey owns the picture.
func (s *Store) IsOwner(pubkey, sha256 string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM owners WHERE pubkey = ? AND sha256 = ?`, pubkey, sha256).Scan(&n)
	return n > 0, err
}

// LogRejected records an upload the classifier refused. Nothing else is kept of it.
func (s *Store) LogRejected(pubkey, sha256 string, size int64, mediaType string, score float64, now int64) error {
	_, err := s.db.Exec(`INSERT INTO uploads(pubkey, sha256, size, type, at, outcome, score) VALUES(?,?,?,?,?,?,?)`,
		pubkey, sha256, size, mediaType, now, OutcomeNSFW, score)
	return err
}

// RemoveOwner takes the pubkey's ownership of a picture away. wasOwner is
// false when there was none; blobGone is true when that was the last owner
// and the blob row went with it (the caller then deletes the object).
func (s *Store) RemoveOwner(pubkey, sha256 string) (wasOwner, blobGone bool, err error) {
	err = s.tx(func(tx *sql.Tx) error {
		res, err := tx.Exec(`DELETE FROM owners WHERE pubkey = ? AND sha256 = ?`, pubkey, sha256)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return nil
		}
		wasOwner = true
		var left int
		if err := tx.QueryRow(`SELECT COUNT(*) FROM owners WHERE sha256 = ?`, sha256).Scan(&left); err != nil {
			return err
		}
		if left > 0 {
			return nil
		}
		blobGone = true
		_, err = tx.Exec(`DELETE FROM blobs WHERE sha256 = ?`, sha256)
		return err
	})
	return wasOwner, blobGone, err
}

// DeleteBlob removes a picture and every ownership of it.
func (s *Store) DeleteBlob(sha256 string) (existed bool, err error) {
	res, err := s.db.Exec(`DELETE FROM blobs WHERE sha256 = ?`, sha256)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// PurgePubkey removes every ownership of the pubkey and returns the hashes of
// the pictures that had no other owner; their blob rows are gone, the caller
// deletes the objects.
func (s *Store) PurgePubkey(pubkey string) (gone []string, err error) {
	err = s.tx(func(tx *sql.Tx) error {
		rows, err := tx.Query(`SELECT sha256 FROM owners o WHERE pubkey = ?
			AND NOT EXISTS (SELECT 1 FROM owners x WHERE x.sha256 = o.sha256 AND x.pubkey <> o.pubkey)`, pubkey)
		if err != nil {
			return err
		}
		for rows.Next() {
			var h string
			if err := rows.Scan(&h); err != nil {
				rows.Close()
				return err
			}
			gone = append(gone, h)
		}
		if err := rows.Close(); err != nil {
			return err
		}
		if _, err := tx.Exec(`DELETE FROM owners WHERE pubkey = ?`, pubkey); err != nil {
			return err
		}
		for _, h := range gone {
			if _, err := tx.Exec(`DELETE FROM blobs WHERE sha256 = ?`, h); err != nil {
				return err
			}
		}
		return nil
	})
	return gone, err
}

// Usage is the summed size of the pictures the pubkey owns.
func (s *Store) Usage(pubkey string) (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COALESCE(SUM(b.size), 0) FROM owners o JOIN blobs b ON b.sha256 = o.sha256 WHERE o.pubkey = ?`, pubkey).Scan(&n)
	return n, err
}

// UploadsSince counts the pubkey's uploads that count against the daily limit
// (stored ones and those the classifier refused) at or after since.
func (s *Store) UploadsSince(pubkey string, since int64) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM uploads WHERE pubkey = ? AND at >= ? AND outcome IN (?, ?)`,
		pubkey, since, OutcomeStored, OutcomeNSFW).Scan(&n)
	return n, err
}

// StoredBytesSince is the size of everything newly stored at or after since.
func (s *Store) StoredBytesSince(since int64) (int64, error) {
	var n int64
	err := s.db.QueryRow(`SELECT COALESCE(SUM(size), 0) FROM uploads WHERE at >= ? AND outcome = ?`, since, OutcomeStored).Scan(&n)
	return n, err
}
