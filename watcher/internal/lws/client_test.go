package lws

import (
	"context"
	"errors"
	"strings"
	"testing"

	"monostr.com/watcher/internal/lws/lwstest"
)

const (
	authKey = "f50922f5fcd186eaa4bd7070b8072b66fea4fd736f06bd82df702e2314187d09"
	addr    = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
	viewKey = "cea3c5dfea43f31197bd4b7166da59f90af44b4afac2b0732d9fcbe2b7fa0c06"
)

func TestWebhookAddAndDelete(t *testing.T) {
	f := lwstest.NewFake(t, authKey)
	c := New(f.URL, authKey)
	ctx := context.Background()
	f.NextEventID = "fa10a4db485145f1a24dc09c19a79d43"
	id, err := c.WebhookAdd(ctx, addr, "0123456789abcdef", strings.Repeat("d", 64), "http://127.0.0.1:8080/internal/lws-hook", 1)
	if err != nil || id != "fa10a4db485145f1a24dc09c19a79d43" {
		t.Fatalf("add: %q %v", id, err)
	}
	calls := f.CallsFor("webhook_add")
	if len(calls) != 1 {
		t.Fatalf("calls: %v", f.Calls)
	}
	p := calls[0].Params
	if p["type"] != "tx-confirmation" || p["address"] != addr || p["payment_id"] != "0123456789abcdef" ||
		p["token"] != strings.Repeat("d", 64) || p["url"] != "http://127.0.0.1:8080/internal/lws-hook" || p["confirmations"] != float64(1) {
		t.Fatalf("params: %v", p)
	}
	if err := c.WebhookDeleteUUID(ctx, []string{id}); err != nil {
		t.Fatal(err)
	}
	del := f.CallsFor("webhook_delete_uuid")
	if len(del) != 1 || del[0].Params["event_ids"].([]any)[0] != id {
		t.Fatalf("delete: %v", del)
	}
}

func TestEnsureAccountAddsUnknownAndActivatesKnown(t *testing.T) {
	f := lwstest.NewFake(t, authKey)
	c := New(f.URL, authKey)
	ctx := context.Background()
	if err := c.EnsureAccount(ctx, addr, viewKey); err != nil {
		t.Fatal(err)
	}
	if n := len(f.CallsFor("add_account")); n != 1 {
		t.Fatalf("add_account calls %d", n)
	}
	if f.CallsFor("add_account")[0].Params["key"] != viewKey {
		t.Fatal("view key not forwarded")
	}
	// second time: account is now known (inactive) → modify_account_status active, no add_account
	f.Active, f.Inactive = nil, []string{addr}
	if err := c.EnsureAccount(ctx, addr, viewKey); err != nil {
		t.Fatal(err)
	}
	if n := len(f.CallsFor("add_account")); n != 1 {
		t.Fatalf("add_account called again (%d)", n)
	}
	mod := f.CallsFor("modify_account_status")
	if len(mod) != 1 || mod[0].Params["status"] != "active" {
		t.Fatalf("modify: %v", mod)
	}
	// already active → nothing
	f.Active, f.Inactive = []string{addr}, nil
	c.EnsureAccount(ctx, addr, viewKey)
	if n := len(f.CallsFor("modify_account_status")); n != 1 {
		t.Fatalf("modify called for active account (%d)", n)
	}
}

func TestErrorsCarryStatusButNeverTheViewKey(t *testing.T) {
	f := lwstest.NewFake(t, authKey)
	c := New(f.URL, authKey)
	f.FailAddAccount = true
	err := c.AddAccount(context.Background(), addr, viewKey)
	var lerr *Error
	if !errors.As(err, &lerr) || lerr.Status != 500 || lerr.Command != "add_account" {
		t.Fatalf("err: %v", err)
	}
	if strings.Contains(err.Error(), viewKey) {
		t.Fatal("view key leaked into error")
	}
	// wrong auth → 403
	bad := New(f.URL, strings.Repeat("0", 64))
	err = bad.Rescan(context.Background(), 10, []string{addr})
	if !errors.As(err, &lerr) || lerr.Status != 403 {
		t.Fatalf("auth: %v", err)
	}
	// unreachable server → plain error, not *Error
	dead := New("http://127.0.0.1:1", authKey)
	if err := dead.WebhookDeleteUUID(context.Background(), []string{"x"}); err == nil || errors.As(err, &lerr) {
		t.Fatalf("dead: %v", err)
	}
}

func TestRescanParams(t *testing.T) {
	f := lwstest.NewFake(t, authKey)
	c := New(f.URL, authKey)
	if err := c.Rescan(context.Background(), 2192100, []string{addr}); err != nil {
		t.Fatal(err)
	}
	p := f.CallsFor("rescan")[0].Params
	if p["height"] != float64(2192100) || p["addresses"].([]any)[0] != addr {
		t.Fatalf("params %v", p)
	}
}
