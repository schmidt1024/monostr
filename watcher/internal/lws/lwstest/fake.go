// Package lwstest is an in-process stand-in for the monero-lws admin API
// that records every call.
package lwstest

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

type Call struct {
	Command string
	Params  map[string]any
}

type Fake struct {
	*httptest.Server
	mu             sync.Mutex
	Calls          []Call
	Active         []string // addresses reported by list_accounts
	Inactive       []string
	Hidden         []string
	NextEventID    string // returned by webhook_add; default "00000000000000000000000000000001"
	FailAddAccount bool   // add_account answers 500
	FailWebhookAdd bool   // webhook_add answers 500
	authKey        string
}

func NewFake(t testing.TB, authKey string) *Fake {
	f := &Fake{authKey: authKey, NextEventID: "00000000000000000000000000000001"}
	f.Server = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.Server.Close)
	return f
}

func (f *Fake) CallsFor(command string) []Call {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []Call
	for _, c := range f.Calls {
		if c.Command == command {
			out = append(out, c)
		}
	}
	return out
}

func accounts(addrs []string) []map[string]any {
	out := []map[string]any{}
	for _, a := range addrs {
		out = append(out, map[string]any{"address": a, "scan_height": 0, "access_time": 0})
	}
	return out
}

func (f *Fake) handle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(405)
		return
	}
	var req struct {
		Auth   string         `json:"auth"`
		Params map[string]any `json:"params"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(400)
		return
	}
	if f.authKey != "" && req.Auth != f.authKey {
		w.WriteHeader(403)
		return
	}
	command := strings.TrimPrefix(r.URL.Path, "/")
	f.mu.Lock()
	f.Calls = append(f.Calls, Call{Command: command, Params: req.Params})
	active, inactive, hidden := f.Active, f.Inactive, f.Hidden
	eventID, failAdd, failHook := f.NextEventID, f.FailAddAccount, f.FailWebhookAdd
	f.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	switch command {
	case "add_account":
		if failAdd {
			w.WriteHeader(500)
			w.Write([]byte(`invalid key/address pair`))
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"updated": []string{req.Params["address"].(string)}})
	case "list_accounts":
		json.NewEncoder(w).Encode(map[string]any{"active": accounts(active), "inactive": accounts(inactive), "hidden": accounts(hidden)})
	case "modify_account_status", "rescan":
		json.NewEncoder(w).Encode(map[string]any{"updated": req.Params["addresses"]})
	case "webhook_add":
		if failHook {
			w.WriteHeader(500)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{
			"event_id": eventID, "payment_id": req.Params["payment_id"], "token": req.Params["token"],
			"confirmations": req.Params["confirmations"], "url": req.Params["url"],
		})
	case "webhook_delete_uuid":
		w.Write([]byte(`{}`))
	default:
		w.WriteHeader(404)
	}
}
