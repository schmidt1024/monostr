package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/nip98"
	"monostr.com/watcher/internal/ratelimit"
)

const publicURL = "https://watcher.example"

type fakeService struct {
	registered   []string
	unregistered []string
	hooks        []lws.Hook
	registerErr  error
	hookErr      error
}

func (f *fakeService) Info(context.Context) InfoResponse {
	return InfoResponse{Pubkey: strings.Repeat("a", 64), Relays: []string{"wss://r.example"}, Network: "stagenet", Height: 42, Version: "test"}
}
func (f *fakeService) Register(_ context.Context, pubkey, address, viewKey string) error {
	if f.registerErr != nil {
		return f.registerErr
	}
	f.registered = append(f.registered, pubkey+" "+address+" "+viewKey)
	return nil
}
func (f *fakeService) Unregister(_ context.Context, pubkey string) error {
	f.unregistered = append(f.unregistered, pubkey)
	return nil
}
func (f *fakeService) HandleHook(_ context.Context, h lws.Hook) error {
	f.hooks = append(f.hooks, h)
	return f.hookErr
}

type env struct {
	svc *fakeService
	srv *httptest.Server
	log *bytes.Buffer
	sk  string
	pk  string
}

func newEnv(t *testing.T) *env {
	t.Helper()
	e := &env{svc: &fakeService{}, log: &bytes.Buffer{}}
	e.sk = nostr.GeneratePrivateKey()
	e.pk, _ = nostr.GetPublicKey(e.sk)
	logger := slog.New(slog.NewTextHandler(e.log, nil))
	h := New(e.svc, publicURL, ratelimit.New(2, time.Hour), logger)
	e.srv = httptest.NewServer(h)
	t.Cleanup(e.srv.Close)
	return e
}

func (e *env) do(t *testing.T, method, path string, body []byte, auth bool, headers map[string]string) (*http.Response, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, bytes.NewReader(body))
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if auth {
		h, err := nip98.Build(e.sk, publicURL+path, method, body, time.Now().Unix())
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", h)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	var out map[string]any
	if len(raw) > 0 {
		json.Unmarshal(raw, &out)
	}
	return resp, out
}

func TestInfo(t *testing.T) {
	e := newEnv(t)
	resp, out := e.do(t, "GET", "/v1/info", nil, false, nil)
	if resp.StatusCode != 200 || out["pubkey"] != strings.Repeat("a", 64) || out["network"] != "stagenet" || out["height"] != float64(42) {
		t.Fatalf("%d %v", resp.StatusCode, out)
	}
	if resp.Header.Get("Content-Type") != "application/json" {
		t.Fatalf("content-type %q", resp.Header.Get("Content-Type"))
	}
}

func TestRegisterRequiresValidNip98(t *testing.T) {
	e := newEnv(t)
	body := []byte(`{"address":"4A","view_key":"` + strings.Repeat("c", 64) + `"}`)
	resp, out := e.do(t, "POST", "/v1/accounts", body, false, nil)
	if resp.StatusCode != 401 || out["error"] == nil {
		t.Fatalf("no auth: %d %v", resp.StatusCode, out)
	}
	// header for another path
	h, _ := nip98.Build(e.sk, publicURL+"/v1/info", "POST", body, time.Now().Unix())
	resp, _ = e.do(t, "POST", "/v1/accounts", body, false, map[string]string{"Authorization": h})
	if resp.StatusCode != 401 {
		t.Fatalf("wrong u: %d", resp.StatusCode)
	}
	// expired header
	h, _ = nip98.Build(e.sk, publicURL+"/v1/accounts", "POST", body, time.Now().Unix()-120)
	resp, _ = e.do(t, "POST", "/v1/accounts", body, false, map[string]string{"Authorization": h})
	if resp.StatusCode != 401 {
		t.Fatalf("expired: %d", resp.StatusCode)
	}
	// header signed over a different body
	h, _ = nip98.Build(e.sk, publicURL+"/v1/accounts", "POST", []byte(`{}`), time.Now().Unix())
	resp, _ = e.do(t, "POST", "/v1/accounts", body, false, map[string]string{"Authorization": h})
	if resp.StatusCode != 401 {
		t.Fatalf("body mismatch: %d", resp.StatusCode)
	}
	if len(e.svc.registered) != 0 {
		t.Fatal("service called without valid auth")
	}
	resp, out = e.do(t, "POST", "/v1/accounts", body, true, nil)
	if resp.StatusCode != 200 || out["watcher_pubkey"] != strings.Repeat("a", 64) {
		t.Fatalf("valid: %d %v", resp.StatusCode, out)
	}
	if len(e.svc.registered) != 1 || e.svc.registered[0] != e.pk+" 4A "+strings.Repeat("c", 64) {
		t.Fatalf("registered %v", e.svc.registered)
	}
}

func TestRegisterValidatesBodyAndMapsServiceErrors(t *testing.T) {
	e := newEnv(t)
	for name, body := range map[string]string{
		"not json":       `nope`,
		"missing key":    `{"address":"4A"}`,
		"key not hex":    `{"address":"4A","view_key":"zz"}`,
		"key wrong size": `{"address":"4A","view_key":"abcd"}`,
		"empty address":  `{"address":"","view_key":"` + strings.Repeat("c", 64) + `"}`,
	} {
		resp, out := e.do(t, "POST", "/v1/accounts", []byte(body), true, nil)
		if resp.StatusCode != 400 || out["error"] == nil {
			t.Errorf("%s: %d %v", name, resp.StatusCode, out)
		}
	}
	e.svc.registerErr = &HTTPError{Status: 400, Message: "view key does not match address"}
	resp, out := e.do(t, "POST", "/v1/accounts", []byte(`{"address":"4A","view_key":"`+strings.Repeat("c", 64)+`"}`), true, nil)
	if resp.StatusCode != 400 || out["error"] != "view key does not match address" {
		t.Fatalf("mapped: %d %v", resp.StatusCode, out)
	}
	e.svc.registerErr = io.ErrUnexpectedEOF
	resp, out = e.do(t, "POST", "/v1/accounts", []byte(`{"address":"4A","view_key":"`+strings.Repeat("c", 64)+`"}`), true, nil)
	if resp.StatusCode != 500 || out["error"] != "internal error" {
		t.Fatalf("internal: %d %v", resp.StatusCode, out)
	}
	if strings.Contains(e.log.String(), strings.Repeat("c", 64)) {
		t.Fatal("view key in log")
	}
	// oversized body
	big := []byte(`{"address":"` + strings.Repeat("4", 5000) + `","view_key":"` + strings.Repeat("c", 64) + `"}`)
	resp, _ = e.do(t, "POST", "/v1/accounts", big, true, nil)
	if resp.StatusCode != 413 {
		t.Fatalf("oversized: %d", resp.StatusCode)
	}
}

func TestRegisterRateLimitPerIP(t *testing.T) {
	e := newEnv(t)
	body := []byte(`{"address":"4A","view_key":"` + strings.Repeat("c", 64) + `"}`)
	for i := 0; i < 2; i++ {
		if resp, _ := e.do(t, "POST", "/v1/accounts", body, true, map[string]string{"X-Forwarded-For": "203.0.113.5"}); resp.StatusCode != 200 {
			t.Fatalf("call %d: %d", i, resp.StatusCode)
		}
	}
	resp, out := e.do(t, "POST", "/v1/accounts", body, true, map[string]string{"X-Forwarded-For": "203.0.113.5"})
	if resp.StatusCode != 429 || out["error"] == nil {
		t.Fatalf("limit: %d %v", resp.StatusCode, out)
	}
	if resp, _ := e.do(t, "POST", "/v1/accounts", body, true, map[string]string{"X-Forwarded-For": "203.0.113.6, 10.0.0.1"}); resp.StatusCode != 200 {
		t.Fatalf("other ip: %d", resp.StatusCode)
	}
}

func TestUnregister(t *testing.T) {
	e := newEnv(t)
	resp, _ := e.do(t, "DELETE", "/v1/accounts", nil, false, nil)
	if resp.StatusCode != 401 {
		t.Fatalf("no auth: %d", resp.StatusCode)
	}
	resp, _ = e.do(t, "DELETE", "/v1/accounts", nil, true, nil)
	if resp.StatusCode != 204 {
		t.Fatalf("delete: %d", resp.StatusCode)
	}
	if len(e.svc.unregistered) != 1 || e.svc.unregistered[0] != e.pk {
		t.Fatalf("unregistered %v", e.svc.unregistered)
	}
}

func TestHookOnlyFromInternalNetwork(t *testing.T) {
	e := newEnv(t)
	hook := []byte(`{"event":"tx-confirmation","payment_id":"a","token":"t","confirmations":0,"id":"x","tx_info":{"amount":5,"tx_hash":"h"}}`)
	// httptest client connects from 127.0.0.1 → allowed
	resp, _ := e.do(t, "POST", "/internal/lws-hook", hook, false, nil)
	if resp.StatusCode != 200 || len(e.svc.hooks) != 1 || e.svc.hooks[0].Amount != 5 {
		t.Fatalf("hook: %d %v", resp.StatusCode, e.svc.hooks)
	}
	// X-Forwarded-For must NOT grant or revoke access; source address decides
	resp, _ = e.do(t, "POST", "/internal/lws-hook", hook, false, map[string]string{"X-Forwarded-For": "203.0.113.5"})
	if resp.StatusCode != 200 {
		t.Fatalf("xff ignored: %d", resp.StatusCode)
	}
	resp, _ = e.do(t, "POST", "/internal/lws-hook", []byte(`garbage`), false, nil)
	if resp.StatusCode != 400 {
		t.Fatalf("bad payload: %d", resp.StatusCode)
	}
	if !isInternal("203.0.113.5:1234") && isInternal("10.1.2.3:80") && isInternal("[::1]:80") && isInternal("172.16.0.9:1") && isInternal("192.168.1.1:1") && isInternal("[fd00::1]:1") {
		return
	}
	t.Fatal("isInternal classification")
}

func TestHookServiceErrorIs500ButPayloadNotLogged(t *testing.T) {
	e := newEnv(t)
	e.svc.hookErr = io.ErrUnexpectedEOF
	hook := []byte(`{"event":"tx-confirmation","payment_id":"a","token":"secret-token","confirmations":0,"id":"x","tx_info":{"amount":5,"tx_hash":"h"}}`)
	resp, _ := e.do(t, "POST", "/internal/lws-hook", hook, false, nil)
	if resp.StatusCode != 500 {
		t.Fatalf("%d", resp.StatusCode)
	}
	if !strings.Contains(e.log.String(), "unexpected EOF") {
		t.Fatal("error not logged")
	}
}

func TestUnknownRouteAndMethod(t *testing.T) {
	e := newEnv(t)
	if resp, _ := e.do(t, "GET", "/v1/nope", nil, false, nil); resp.StatusCode != 404 {
		t.Fatalf("404: %d", resp.StatusCode)
	}
	if resp, _ := e.do(t, "PUT", "/v1/accounts", []byte(`{}`), true, nil); resp.StatusCode != 405 {
		t.Fatalf("405: %d", resp.StatusCode)
	}
}
