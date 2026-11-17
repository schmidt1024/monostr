package api

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/nip98"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/ratelimit"
)

const (
	maxRegisterBody = 4 << 10
	maxHookBody     = 64 << 10
)

type server struct {
	svc       Service
	publicURL string
	regLimit  *ratelimit.Window
	replay    *nip98.ReplayCache
	allowed   map[string]bool // empty: anyone may register
	hookPath  string          // HookPath(secret); "" disables the hook endpoint
	log       *slog.Logger
}

// HookPathPrefix is where LWS posts its callbacks; the secret follows it.
const HookPathPrefix = "/internal/lws-hook/"

// HookPath is the callback path for hookSecret.
func HookPath(hookSecret string) string { return HookPathPrefix + hookSecret }

// New builds the HTTP handler. allowedPubkeys (hex) limits who may register;
// empty means anyone. LWS callbacks are accepted only on HookPath(hookSecret)
// from an internal address; an empty hookSecret disables them.
func New(svc Service, publicURL string, registerLimit *ratelimit.Window, allowedPubkeys []string, hookSecret string, logger *slog.Logger) http.Handler {
	s := &server{svc: svc, publicURL: strings.TrimRight(publicURL, "/"), regLimit: registerLimit, allowed: map[string]bool{}, log: logger,
		replay: nip98.NewReplayCache(nip98.DefaultReplayCapacity)}
	if hookSecret != "" {
		s.hookPath = HookPath(hookSecret)
	}
	for _, pk := range allowedPubkeys {
		s.allowed[pk] = true
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.home)
	mux.HandleFunc("GET /v1/info", s.info)
	mux.HandleFunc("POST /v1/accounts", s.register)
	mux.HandleFunc("DELETE /v1/accounts", s.unregister)
	mux.HandleFunc("POST "+HookPathPrefix+"{secret}", s.hook)
	return s.logging(mux)
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

// logging records method, path, status and duration. Never bodies, never headers.
func (s *server) logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		path := r.URL.Path
		if strings.HasPrefix(path, HookPathPrefix) {
			path = HookPathPrefix + "…" // never log the hook secret
		}
		s.log.Info("http", "method", r.Method, "path", path, "status", sw.status, "ms", time.Since(start).Milliseconds())
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func (s *server) writeServiceError(w http.ResponseWriter, err error) {
	var herr *HTTPError
	if errors.As(err, &herr) {
		writeError(w, herr.Status, herr.Message)
		return
	}
	s.log.Error("service error", "err", err.Error())
	writeError(w, 500, "internal error")
}

func (s *server) info(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.svc.Info(r.Context()))
}

func isInternal(remoteAddr string) bool {
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate())
}

// clientIP is the address the client connected from. Behind the reverse
// proxy (an internal source address) it is the rightmost X-Forwarded-For
// entry that is not itself internal: entries left of it were sent by the
// client and can be anything (Caddy replaces a client's header anyway; a
// proxy that appends instead still adds the real address on the right).
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !isInternal(r.RemoteAddr) {
		return host
	}
	hops := strings.Split(strings.Join(r.Header.Values("X-Forwarded-For"), ","), ",")
	for i := len(hops) - 1; i >= 0; i-- {
		hop := strings.TrimSpace(hops[i])
		ip := net.ParseIP(hop)
		if ip == nil {
			break // garbage: stop trusting the header
		}
		if !ip.IsLoopback() && !ip.IsPrivate() {
			return hop
		}
	}
	return host
}

// limitKey buckets an IPv6 client by its /64 (one subscriber usually owns a
// whole /64, so per-address keys would be free to mint); IPv4 stays per address.
func limitKey(ip string) string {
	parsed := net.ParseIP(ip)
	if parsed == nil || parsed.To4() != nil {
		return ip
	}
	return parsed.Mask(net.CIDRMask(64, 128)).String() + "/64"
}

// authenticate verifies NIP-98 against the public URL and returns the pubkey.
func (s *server) authenticate(w http.ResponseWriter, r *http.Request, body []byte) (string, bool) {
	now := time.Now().Unix()
	pubkey, id, err := nip98.VerifyEvent(r.Header.Get("Authorization"), s.publicURL+r.URL.Path, r.Method, body, now)
	if err != nil {
		writeError(w, 401, "invalid or missing NIP-98 authorization")
		return "", false
	}
	// a captured header (e.g. of DELETE /v1/accounts) must not work twice
	if !s.replay.Add(id, now) {
		writeError(w, 401, "NIP-98 authorization already used")
		return "", false
	}
	return pubkey, true
}

func readBody(w http.ResponseWriter, r *http.Request, limit int64) ([]byte, bool) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, 413, "body too large")
		} else {
			writeError(w, 400, "cannot read body")
		}
		return nil, false
	}
	return body, true
}

func (s *server) register(w http.ResponseWriter, r *http.Request) {
	body, ok := readBody(w, r, maxRegisterBody)
	if !ok {
		return
	}
	pubkey, ok := s.authenticate(w, r, body)
	if !ok {
		return
	}
	if len(s.allowed) > 0 && !s.allowed[pubkey] {
		writeError(w, 403, "this watcher only accepts accounts of its operator")
		return
	}
	var req struct {
		Address string `json:"address"`
		ViewKey string `json:"view_key"`
	}
	if err := json.Unmarshal(body, &req); err != nil || req.Address == "" || !protocol.IsHex64(strings.ToLower(req.ViewKey)) {
		writeError(w, 400, "body must be {\"address\": string, \"view_key\": 64 hex chars}")
		return
	}
	// only well-formed, authenticated attempts count against the limit
	if !s.regLimit.Allow(limitKey(clientIP(r)), time.Now()) {
		writeError(w, 429, "too many registrations, try again later")
		return
	}
	if err := s.svc.Register(r.Context(), pubkey, req.Address, strings.ToLower(req.ViewKey)); err != nil {
		s.writeServiceError(w, err)
		return
	}
	writeJSON(w, 200, map[string]string{"watcher_pubkey": s.svc.Info(r.Context()).Pubkey})
}

func (s *server) unregister(w http.ResponseWriter, r *http.Request) {
	pubkey, ok := s.authenticate(w, r, nil)
	if !ok {
		return
	}
	if err := s.svc.Unregister(r.Context(), pubkey); err != nil {
		s.writeServiceError(w, err)
		return
	}
	w.WriteHeader(204)
}

func (s *server) hook(w http.ResponseWriter, r *http.Request) {
	// constant-time: the path carries the secret
	if s.hookPath == "" || subtle.ConstantTimeCompare([]byte(r.URL.Path), []byte(s.hookPath)) != 1 || !isInternal(r.RemoteAddr) {
		writeError(w, 403, "forbidden")
		return
	}
	body, ok := readBody(w, r, maxHookBody)
	if !ok {
		return
	}
	h, err := lws.ParseHook(body)
	if err != nil {
		writeError(w, 400, "bad hook payload")
		return
	}
	if err := s.svc.HandleHook(r.Context(), h); err != nil {
		s.writeServiceError(w, err)
		return
	}
	w.WriteHeader(200)
}
