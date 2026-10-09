package store

// Upload is one line of the upload log.
type Upload struct {
	Pubkey  string
	SHA256  string
	Size    int64
	Type    string
	At      int64
	Outcome string
	Score   float64
}

// Log returns the pubkey's newest log lines, newest first.
func (s *Store) Log(pubkey string, limit int) ([]Upload, error) {
	rows, err := s.db.Query(`SELECT pubkey, sha256, size, type, at, outcome, score FROM uploads
		WHERE pubkey = ? ORDER BY id DESC LIMIT ?`, pubkey, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Upload
	for rows.Next() {
		var u Upload
		if err := rows.Scan(&u.Pubkey, &u.SHA256, &u.Size, &u.Type, &u.At, &u.Outcome, &u.Score); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// Near returns stored pictures that still exist and whose score lies in
// [from, below), newest first: the ones just under the threshold.
func (s *Store) Near(from, below float64, limit int) ([]Upload, error) {
	rows, err := s.db.Query(`SELECT u.pubkey, u.sha256, u.size, u.type, u.at, u.outcome, u.score FROM uploads u
		JOIN blobs b ON b.sha256 = u.sha256
		WHERE u.outcome = ? AND u.score >= ? AND u.score < ? ORDER BY u.id DESC LIMIT ?`, OutcomeStored, from, below, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Upload
	for rows.Next() {
		var u Upload
		if err := rows.Scan(&u.Pubkey, &u.SHA256, &u.Size, &u.Type, &u.At, &u.Outcome, &u.Score); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// Uploader is one pubkey with what it holds.
type Uploader struct {
	Pubkey string
	Blobs  int
	Bytes  int64
}

// Stats is the operator's overview.
type Stats struct {
	Blobs         int
	Bytes         int64
	Owners        int
	StoredToday   int
	BytesToday    int64
	RejectedToday int
	Top           []Uploader
}

// Stats summarises the store; "today" starts at dayStart.
func (s *Store) Stats(dayStart int64) (Stats, error) {
	var st Stats
	if err := s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(size), 0) FROM blobs`).Scan(&st.Blobs, &st.Bytes); err != nil {
		return st, err
	}
	if err := s.db.QueryRow(`SELECT COUNT(DISTINCT pubkey) FROM owners`).Scan(&st.Owners); err != nil {
		return st, err
	}
	if err := s.db.QueryRow(`SELECT COUNT(*), COALESCE(SUM(size), 0) FROM uploads WHERE at >= ? AND outcome = ?`, dayStart, OutcomeStored).
		Scan(&st.StoredToday, &st.BytesToday); err != nil {
		return st, err
	}
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM uploads WHERE at >= ? AND outcome = ?`, dayStart, OutcomeNSFW).Scan(&st.RejectedToday); err != nil {
		return st, err
	}
	rows, err := s.db.Query(`SELECT o.pubkey, COUNT(*), COALESCE(SUM(b.size), 0) AS bytes FROM owners o JOIN blobs b ON b.sha256 = o.sha256
		GROUP BY o.pubkey ORDER BY bytes DESC, o.pubkey LIMIT 10`)
	if err != nil {
		return st, err
	}
	defer rows.Close()
	for rows.Next() {
		var u Uploader
		if err := rows.Scan(&u.Pubkey, &u.Blobs, &u.Bytes); err != nil {
			return st, err
		}
		st.Top = append(st.Top, u)
	}
	return st, rows.Err()
}
