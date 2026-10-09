// Package api is the HTTP surface of the media server: Blossom upload,
// retrieval and deletion (BUD-01, BUD-02, BUD-06, BUD-11, delete from BUD-12),
// a page with the rules and a health check.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"monostr.com/media/internal/blossom"
	"monostr.com/media/internal/limits"
	"monostr.com/media/internal/storage"
	"monostr.com/media/internal/store"
)

// Reason codes sent in X-Monostr-Reason (spec section 4.4). X-Reason carries
// text for people, which clients must not parse; these codes are for the app.
const (
	CodeAuth          = "auth"
	CodeBanned        = "banned"
	CodeBannedContent = "banned-content"
	CodeNSFW          = "nsfw"
	CodeHashMismatch  = "hash-mismatch"
	CodeLength        = "length"
	CodeTooLarge      = "too-large"
	CodeQuota         = "quota"
	CodeType          = "type"
	CodeRate          = "rate"
	CodeUnavailable   = "unavailable"
	CodeNotFound      = "not-found"
	CodeBadRequest    = "bad-request"
	CodeInternal      = "internal"
)

// Classifier scores a picture; classify.Client is the real one.
type Classifier interface {
	Score(ctx context.Context, data []byte) (float64, error)
	Health(ctx context.Context) error
}

// Config is what the operator sets.
type Config struct {
	PublicURL   string // https://media.monostr.com
	Limits      limits.Limits
	Threshold   float64 // an NSFW score at or above it refuses the upload
	ContactNpub string
	Version     string
	// MaxInFlight bounds the uploads worked on at once; each holds its picture
	// in memory. 0 means DefaultMaxInFlight.
	MaxInFlight int
	// BucketTimeout bounds one write or delete at the bucket. 0 means DefaultBucketTimeout.
	BucketTimeout time.Duration
}

const (
	DefaultMaxInFlight   = 16
	DefaultBucketTimeout = 60 * time.Second
	// maxInFlightPerAddress keeps one address from holding every slot.
	maxInFlightPerAddress = 4
)

// Deps are the server's collaborators. Now may be nil (time.Now).
type Deps struct {
	Store      *store.Store
	Bucket     storage.Bucket
	Classifier Classifier
	Logger     *slog.Logger
	Now        func() time.Time
}

type server struct {
	cfg        Config
	host       string // bare lowercase domain of PublicURL, as a "server" tag names it
	store      *store.Store
	bucket     storage.Bucket
	classifier Classifier
	log        *slog.Logger
	now        func() time.Time
	ips        *limits.Daily
	// one lock per blob: storing it and deleting its last owner must not
	// interleave, or the bucket and the database part ways
	blobs keyLocks

	maxInFlight   int
	bucketTimeout time.Duration
	// mu guards pending: what uploads in flight hold against the limits
	mu      sync.Mutex
	pending pending
}

// New builds the handler.
func New(cfg Config, deps Deps) (http.Handler, error) {
	cfg.PublicURL = strings.TrimRight(cfg.PublicURL, "/")
	u, err := url.Parse(cfg.PublicURL)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, errors.New("api: PublicURL must be an absolute http(s) URL")
	}
	s := &server{
		cfg: cfg, host: strings.ToLower(u.Hostname()),
		store: deps.Store, bucket: deps.Bucket, classifier: deps.Classifier, log: deps.Logger,
		now: deps.Now, ips: limits.NewDaily(),
		maxInFlight: cfg.MaxInFlight, bucketTimeout: cfg.BucketTimeout,
		pending: pending{byAddress: map[string]int{}, byPubkey: map[string]*reservation{}},
	}
	if s.now == nil {
		s.now = time.Now
	}
	if s.maxInFlight <= 0 {
		s.maxInFlight = DefaultMaxInFlight
	}
	if s.bucketTimeout <= 0 {
		s.bucketTimeout = DefaultBucketTimeout
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.page)
	mux.HandleFunc("GET /healthz", s.healthz)
	mux.HandleFunc("PUT /upload", s.upload)
	mux.HandleFunc("HEAD /upload", s.preflight)
	mux.HandleFunc("GET /{blob}", s.serve) // GET patterns also match HEAD
	mux.HandleFunc("DELETE /{blob}", s.remove)
	return s.logging(cors(mux)), nil
}

// keyLocks is a mutex per key that exists only while somebody holds or waits
// for it. Uploads of different pictures never wait for each other.
type keyLocks struct {
	mu    sync.Mutex
	locks map[string]*keyLock
}

type keyLock struct {
	mu      sync.Mutex
	holders int // holding or waiting
}

// lock takes the key's mutex and returns the func that gives it back.
func (k *keyLocks) lock(key string) (unlock func()) {
	k.mu.Lock()
	if k.locks == nil {
		k.locks = map[string]*keyLock{}
	}
	l := k.locks[key]
	if l == nil {
		l = &keyLock{}
		k.locks[key] = l
	}
	l.holders++
	k.mu.Unlock()
	l.mu.Lock()
	return func() {
		l.mu.Unlock()
		k.mu.Lock()
		if l.holders--; l.holders == 0 {
			delete(k.locks, key)
		}
		k.mu.Unlock()
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

// logging records method, path, status and duration. Never an address, never
// a header, never a body.
func (s *server) logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		s.log.Info("http", "method", r.Method, "path", r.URL.Path, "status", sw.status, "ms", time.Since(start).Milliseconds())
	})
}

// cors opens every endpoint to web clients (BUD-01) and answers preflights.
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Access-Control-Allow-Origin", "*")
		h.Set("Access-Control-Expose-Headers", "X-Reason, X-Monostr-Reason")
		if r.Method == http.MethodOptions {
			h.Set("Access-Control-Allow-Headers", "Authorization, *")
			h.Set("Access-Control-Allow-Methods", "GET, HEAD, PUT, DELETE")
			h.Set("Access-Control-Max-Age", "86400")
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// reject answers with the status, the reason for people (X-Reason) and the
// code for the app (X-Monostr-Reason).
func reject(w http.ResponseWriter, status int, code, reason string) {
	w.Header().Set("X-Reason", reason)
	w.Header().Set("X-Monostr-Reason", code)
	writeJSON(w, status, map[string]string{"message": reason})
}

func (s *server) internal(w http.ResponseWriter, what string, err error) {
	s.log.Error(what, "err", err.Error())
	reject(w, http.StatusInternalServerError, CodeInternal, "internal error")
}

func isInternal(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate())
}

// clientIP is the address the limits count. The server is reachable through
// Caddy only, which sets X-Forwarded-For; the header is believed only when
// the direct peer is on a private network.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if isInternal(r.RemoteAddr) {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			if first := strings.TrimSpace(strings.Split(xff, ",")[0]); first != "" {
				return first
			}
		}
	}
	return host
}

// authorize verifies the Blossom authorization for one action.
func (s *server) authorize(w http.ResponseWriter, r *http.Request, action string, now time.Time) (blossom.Auth, bool) {
	auth, err := blossom.Verify(r.Header.Get("Authorization"), action, s.host, now.Unix())
	if err != nil {
		reject(w, http.StatusUnauthorized, CodeAuth, err.Error())
		return blossom.Auth{}, false
	}
	return auth, true
}

// blobHash reads the hash from a path segment like "<sha256>" or
// "<sha256>.jpg"; the extension is free.
func blobHash(segment string) (string, bool) {
	if i := strings.IndexByte(segment, '.'); i >= 0 {
		segment = segment[:i]
	}
	segment = strings.ToLower(segment)
	return segment, blossom.IsHex64(segment)
}
