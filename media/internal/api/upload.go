package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"monostr.com/media/internal/blossom"
	"monostr.com/media/internal/classify"
	"monostr.com/media/internal/imagecheck"
	"monostr.com/media/internal/limits"
	"monostr.com/media/internal/store"
)

// descriptor is the Blossom blob descriptor (BUD-02).
type descriptor struct {
	URL      string `json:"url"`
	SHA256   string `json:"sha256"`
	Size     int64  `json:"size"`
	Type     string `json:"type"`
	Uploaded int64  `json:"uploaded"`
}

func (s *server) describe(b store.Blob) descriptor {
	return descriptor{
		URL:    s.cfg.PublicURL + "/" + b.SHA256 + "." + imagecheck.Ext(b.Type),
		SHA256: b.SHA256, Size: b.Size, Type: b.Type, Uploaded: b.CreatedAt,
	}
}

// reservation is what one pubkey's uploads in flight hold against its limits.
type reservation struct {
	count int   // uploads that will count for the day
	bytes int64 // bytes that will count against the quota
}

// pending is what the uploads in flight have reserved. A limit is checked and
// reserved under server.mu in one step, so that requests arriving together
// cannot all pass a check that each of them alone would pass. A finished
// upload is for a moment counted twice (in the database and here), which errs
// on the strict side.
type pending struct {
	inFlight  int
	byAddress map[string]int
	byPubkey  map[string]*reservation
	newBytes  int64 // against the day's global cap
}

// admission is one upload as far as it is known before its bytes are read.
type admission struct {
	pubkey      string
	length      int64
	contentType string
	// candidate is the hash the client says it uploads ("" when it names
	// several): a picture the server already has costs no new storage, and one
	// the pubkey already owns costs it nothing at all - a retry after a lost
	// response must not run into the limits its first attempt used up.
	candidate string
	now       time.Time
	// reserve is false for the preflight, which only asks.
	reserve bool
}

// candidateHash is the hash an upload announces: X-SHA-256 when the
// authorization covers it, else the authorization's only x tag.
func candidateHash(auth blossom.Auth, r *http.Request) string {
	if claimed := strings.ToLower(r.Header.Get("X-SHA-256")); claimed != "" {
		if blossom.IsHex64(claimed) && auth.Covers(claimed) {
			return claimed
		}
		return ""
	}
	if len(auth.Hashes) == 1 {
		return auth.Hashes[0]
	}
	return ""
}

// admit runs every check that needs no picture bytes (spec 4.1, step 2):
// banned pubkey, length, type, the bound on uploads in flight, the daily
// limits and the quota. It writes the rejection itself. On success it returns
// the normalised media type and, for a reserving admission, a release func
// the caller must call when the upload is over, however it ends.
func (s *server) admit(w http.ResponseWriter, r *http.Request, a admission) (string, func(), bool) {
	lim := s.cfg.Limits
	none := func() {}
	banned, err := s.store.PubkeyBanned(a.pubkey)
	if err != nil {
		s.internal(w, "pubkey ban lookup", err)
		return "", none, false
	}
	if banned {
		reject(w, http.StatusForbidden, CodeBanned, "this pubkey may not upload here")
		return "", none, false
	}
	if a.length <= 0 {
		reject(w, http.StatusLengthRequired, CodeLength, "Content-Length is required")
		return "", none, false
	}
	if a.length > lim.MaxBytes {
		reject(w, http.StatusRequestEntityTooLarge, CodeTooLarge, fmt.Sprintf("file larger than %d bytes", lim.MaxBytes))
		return "", none, false
	}
	mediaType, ok := imagecheck.Normalize(a.contentType)
	if !ok {
		reject(w, http.StatusUnsupportedMediaType, CodeType, "only JPEG, PNG, WebP and GIF pictures are accepted")
		return "", none, false
	}

	// known: the server has the picture, nothing new will be stored;
	// owner: this pubkey already holds it, so the upload is a repeat
	known, owner := false, false
	if a.candidate != "" {
		_, found, err := s.store.Blob(a.candidate)
		if err != nil {
			s.internal(w, "blob lookup", err)
			return "", none, false
		}
		if found {
			known = true
			if owner, err = s.store.IsOwner(a.pubkey, a.candidate); err != nil {
				s.internal(w, "owner lookup", err)
				return "", none, false
			}
		}
	}

	day := limits.DayStart(a.now)
	address := limits.IPKey(clientIP(r))
	s.mu.Lock()
	defer s.mu.Unlock()
	if a.reserve {
		if s.pending.inFlight >= s.maxInFlight {
			w.Header().Set("Retry-After", "5")
			reject(w, http.StatusServiceUnavailable, CodeUnavailable, "the server is busy, try again in a moment")
			return "", none, false
		}
		if s.pending.byAddress[address] >= maxInFlightPerAddress {
			reject(w, http.StatusTooManyRequests, CodeRate, "too many uploads at once from this address")
			return "", none, false
		}
	}
	mine := s.pending.byPubkey[a.pubkey]
	if mine == nil {
		mine = &reservation{}
	}
	if !known {
		uploads, err := s.store.UploadsSince(a.pubkey, day)
		if err != nil {
			s.internal(w, "upload count", err)
			return "", none, false
		}
		if uploads+mine.count >= lim.PubkeyPerDay {
			reject(w, http.StatusTooManyRequests, CodeRate, "daily upload limit of this pubkey reached")
			return "", none, false
		}
	}
	if s.ips.Count(address, a.now)+s.pending.byAddress[address] >= lim.IPPerDay {
		reject(w, http.StatusTooManyRequests, CodeRate, "daily upload limit of this address reached")
		return "", none, false
	}
	if !owner {
		usage, err := s.store.Usage(a.pubkey)
		if err != nil {
			s.internal(w, "usage", err)
			return "", none, false
		}
		if usage+mine.bytes+a.length > lim.PubkeyQuota {
			reject(w, http.StatusRequestEntityTooLarge, CodeQuota, fmt.Sprintf("storage quota of %d bytes is full", lim.PubkeyQuota))
			return "", none, false
		}
	}
	if !known {
		stored, err := s.store.StoredBytesSince(day)
		if err != nil {
			s.internal(w, "stored bytes", err)
			return "", none, false
		}
		if stored+s.pending.newBytes+a.length > lim.GlobalBytesPerDay {
			reject(w, http.StatusTooManyRequests, CodeRate, "the server takes no more uploads today")
			return "", none, false
		}
	}
	if !a.reserve {
		return mediaType, none, true
	}

	held := reservation{}
	var newBytes int64
	if !known {
		held.count, newBytes = 1, a.length
	}
	if !owner {
		held.bytes = a.length
	}
	s.pending.inFlight++
	s.pending.byAddress[address]++
	s.pending.newBytes += newBytes
	mine.count += held.count
	mine.bytes += held.bytes
	s.pending.byPubkey[a.pubkey] = mine
	release := func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.pending.inFlight--
		if s.pending.byAddress[address]--; s.pending.byAddress[address] <= 0 {
			delete(s.pending.byAddress, address)
		}
		s.pending.newBytes -= newBytes
		if m := s.pending.byPubkey[a.pubkey]; m != nil {
			m.count -= held.count
			m.bytes -= held.bytes
			if m.count <= 0 && m.bytes <= 0 {
				delete(s.pending.byPubkey, a.pubkey)
			}
		}
	}
	return mediaType, release, true
}

// upload is PUT /upload (spec 4.1).
func (s *server) upload(w http.ResponseWriter, r *http.Request) {
	now := s.now()
	auth, ok := s.authorize(w, r, blossom.ActionUpload, now)
	if !ok {
		return
	}
	mediaType, release, ok := s.admit(w, r, admission{
		pubkey: auth.Pubkey, length: r.ContentLength, contentType: r.Header.Get("Content-Type"),
		candidate: candidateHash(auth, r), now: now, reserve: true,
	})
	if !ok {
		return
	}
	defer release()
	address := limits.IPKey(clientIP(r))

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.cfg.Limits.MaxBytes))
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			reject(w, http.StatusRequestEntityTooLarge, CodeTooLarge, fmt.Sprintf("file larger than %d bytes", s.cfg.Limits.MaxBytes))
		} else {
			reject(w, http.StatusBadRequest, CodeBadRequest, "cannot read the body")
		}
		return
	}
	sum := sha256.Sum256(body)
	hash := hex.EncodeToString(sum[:])
	if !auth.Covers(hash) {
		reject(w, http.StatusConflict, CodeHashMismatch, "the authorization does not name the uploaded bytes")
		return
	}
	if claimed := r.Header.Get("X-SHA-256"); claimed != "" && strings.ToLower(claimed) != hash {
		reject(w, http.StatusConflict, CodeHashMismatch, "X-SHA-256 does not match the uploaded bytes")
		return
	}
	banned, err := s.store.HashBanned(hash)
	if err != nil {
		s.internal(w, "hash ban lookup", err)
		return
	}
	if banned {
		reject(w, http.StatusForbidden, CodeBannedContent, "this file may not be stored here")
		return
	}

	defer s.blobs.lock(hash)()

	existing, found, err := s.store.Blob(hash)
	if err != nil {
		s.internal(w, "blob lookup", err)
		return
	}
	if found {
		if _, err := s.store.AddDuplicate(existing, auth.Pubkey, now.Unix()); err != nil {
			s.internal(w, "add duplicate", err)
			return
		}
		// a replay stores nothing, but it is not free either: it counts for the address
		s.ips.Add(address, now)
		writeJSON(w, http.StatusOK, s.describe(existing))
		return
	}

	info, err := imagecheck.Inspect(body, mediaType)
	if errors.Is(err, imagecheck.ErrTooManyPixels) {
		reject(w, http.StatusRequestEntityTooLarge, CodeTooLarge, fmt.Sprintf(
			"picture larger than %d pixels a side or %d pixels in all, or an animation of more than %d frame pixels",
			imagecheck.MaxSide, imagecheck.MaxPixels, imagecheck.MaxAnimationPixels))
		return
	}
	if err != nil {
		reject(w, http.StatusUnsupportedMediaType, CodeType, "the bytes are not a picture of the declared type")
		return
	}

	score, err := s.classifier.Score(r.Context(), body)
	if err != nil {
		// a content check that fails costs work: it counts for the address (never
		// for the pubkey), so that failing on purpose is not free
		s.ips.Add(address, now)
		if errors.Is(err, classify.ErrUnreadable) {
			reject(w, http.StatusUnsupportedMediaType, CodeType, "the picture cannot be read")
			return
		}
		s.log.Error("classifier", "err", err.Error())
		reject(w, http.StatusServiceUnavailable, CodeUnavailable, "the content check is unavailable, try again later")
		return
	}
	if score >= s.cfg.Threshold {
		if err := s.store.LogRejected(auth.Pubkey, hash, int64(len(body)), info.Type, score, now.Unix()); err != nil {
			s.log.Error("log rejected", "err", err.Error())
		}
		s.ips.Add(address, now)
		reject(w, http.StatusForbidden, CodeNSFW, "this server does not accept adult content")
		return
	}

	// bucket first, database second: the database never names a missing object
	putCtx, cancel := context.WithTimeout(r.Context(), s.bucketTimeout)
	err = s.bucket.Put(putCtx, hash, body, info.Type)
	cancel()
	if err != nil {
		s.log.Error("bucket put", "err", err.Error())
		reject(w, http.StatusServiceUnavailable, CodeUnavailable, "storage is unavailable, try again later")
		return
	}
	blob := store.Blob{SHA256: hash, Size: int64(len(body)), Type: info.Type, Width: info.Width, Height: info.Height, CreatedAt: now.Unix()}
	if err := s.store.AddStored(blob, auth.Pubkey, score); err != nil {
		// the object stays behind without a row; "admin gc" removes it
		s.internal(w, "add stored", err)
		return
	}
	s.ips.Add(address, now)
	writeJSON(w, http.StatusCreated, s.describe(blob))
}

// preflight is HEAD /upload (BUD-06): would this upload be taken? It counts
// and reserves nothing and cannot foresee the picture and content checks.
func (s *server) preflight(w http.ResponseWriter, r *http.Request) {
	now := s.now()
	auth, ok := s.authorize(w, r, blossom.ActionUpload, now)
	if !ok {
		return
	}
	hash := strings.ToLower(r.Header.Get("X-SHA-256"))
	if !blossom.IsHex64(hash) {
		reject(w, http.StatusBadRequest, CodeBadRequest, "X-SHA-256 is required")
		return
	}
	if !auth.Covers(hash) {
		reject(w, http.StatusConflict, CodeHashMismatch, "the authorization does not name X-SHA-256")
		return
	}
	length, err := strconv.ParseInt(r.Header.Get("X-Content-Length"), 10, 64)
	if err != nil {
		length = 0 // admit answers 411
	}
	if _, _, ok := s.admit(w, r, admission{
		pubkey: auth.Pubkey, length: length, contentType: r.Header.Get("X-Content-Type"), candidate: hash, now: now,
	}); !ok {
		return
	}
	banned, err := s.store.HashBanned(hash)
	if err != nil {
		s.internal(w, "hash ban lookup", err)
		return
	}
	if banned {
		reject(w, http.StatusForbidden, CodeBannedContent, "this file may not be stored here")
		return
	}
	w.WriteHeader(http.StatusOK)
}
