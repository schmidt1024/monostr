package api

import (
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
	log       *slog.Logger
}

func New(svc Service, publicURL string, registerLimit *ratelimit.Window, logger *slog.Logger) http.Handler {
	s := &server{svc: svc, publicURL: strings.TrimRight(publicURL, "/"), regLimit: registerLimit, log: logger}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/info", s.info)
	mux.HandleFunc("POST /v1/accounts", s.register)
	mux.HandleFunc("DELETE /v1/accounts", s.unregister)
	mux.HandleFunc("POST /internal/lws-hook", s.hook)
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
		s.log.Info("http", "method", r.Method, "path", r.URL.Path, "status", sw.status, "ms", time.Since(start).Milliseconds())
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

// authenticate verifies NIP-98 against the public URL and returns the pubkey.
func (s *server) authenticate(w http.ResponseWriter, r *http.Request, body []byte) (string, bool) {
	pubkey, err := nip98.Verify(r.Header.Get("Authorization"), s.publicURL+r.URL.Path, r.Method, body, time.Now().Unix())
	if err != nil {
		writeError(w, 401, "invalid or missing NIP-98 authorization")
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
	var req struct {
		Address string `json:"address"`
		ViewKey string `json:"view_key"`
	}
	if err := json.Unmarshal(body, &req); err != nil || req.Address == "" || !protocol.IsHex64(strings.ToLower(req.ViewKey)) {
		writeError(w, 400, "body must be {\"address\": string, \"view_key\": 64 hex chars}")
		return
	}
	// only well-formed, authenticated attempts count against the limit
	if !s.regLimit.Allow(clientIP(r), time.Now()) {
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
	if !isInternal(r.RemoteAddr) {
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
