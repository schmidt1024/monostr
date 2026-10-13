package walletrpc

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type rpcCall struct {
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}

// fakeRPC records calls and answers each method from results (a JSON result) or errs.
func fakeRPC(t *testing.T, results map[string]string, errs map[string]string) (*Client, *[]rpcCall) {
	var calls []rpcCall
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var c rpcCall
		json.NewDecoder(r.Body).Decode(&c)
		calls = append(calls, c)
		if msg, ok := errs[c.Method]; ok {
			w.Write([]byte(`{"jsonrpc":"2.0","id":"0","error":{"code":-1,"message":"` + msg + `"}}`))
			return
		}
		res := results[c.Method]
		if res == "" {
			res = "{}"
		}
		w.Write([]byte(`{"jsonrpc":"2.0","id":"0","result":` + res + `}`))
	}))
	t.Cleanup(srv.Close)
	return New(srv.URL + "/json_rpc"), &calls
}

func TestIncomingTransfers(t *testing.T) {
	c, calls := fakeRPC(t, map[string]string{"get_transfers": `{
		"in":[{"txid":"aa","payment_id":"1111111111111111","amount":5,"height":10,"confirmations":3}],
		"pool":[{"txid":"bb","payment_id":"2222222222222222","amount":7,"height":0,"confirmations":0}]}`}, nil)
	got, err := c.IncomingTransfers(context.Background())
	if err != nil || len(got) != 2 {
		t.Fatalf("got %v %v", got, err)
	}
	if got[0] != (Transfer{TxID: "aa", PaymentID: "1111111111111111", Amount: 5, Height: 10, Confirmations: 3}) {
		t.Fatalf("in %+v", got[0])
	}
	if got[1].TxID != "bb" || got[1].Confirmations != 0 || !got[1].Pool || got[0].Pool {
		t.Fatalf("pool %+v", got[1])
	}
	var p map[string]bool
	json.Unmarshal((*calls)[0].Params, &p)
	if !p["in"] || !p["pool"] || p["out"] {
		t.Fatalf("params %s", (*calls)[0].Params)
	}
}

func TestRPCErrorIsTyped(t *testing.T) {
	c, _ := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	err := c.OpenWallet(context.Background(), "monostr-x")
	var rpcErr *RPCError
	if !errors.As(err, &rpcErr) || rpcErr.Message != "Failed to open wallet" {
		t.Fatalf("err %v", err)
	}
}

func TestGenerateFromKeysNeverEchoesViewKey(t *testing.T) {
	viewKey := strings.Repeat("c", 64)
	c, calls := fakeRPC(t, nil, map[string]string{"generate_from_keys": "Wallet already exists."})
	err := c.GenerateFromKeys(context.Background(), "monostr-x", "4abc", viewKey, 99)
	if err == nil || strings.Contains(err.Error(), viewKey) {
		t.Fatalf("err %v", err)
	}
	var p map[string]any
	json.Unmarshal((*calls)[0].Params, &p)
	if p["viewkey"] != viewKey || p["address"] != "4abc" || p["restore_height"] != float64(99) || p["spendkey"] != "" {
		t.Fatalf("params %s", (*calls)[0].Params)
	}
}

func TestSetDaemonUntrustedWithProxy(t *testing.T) {
	c, calls := fakeRPC(t, nil, nil)
	if err := c.SetDaemon(context.Background(), "https://node.example:18089", "localhost:9050"); err != nil {
		t.Fatal(err)
	}
	var p map[string]any
	json.Unmarshal((*calls)[0].Params, &p)
	if p["address"] != "https://node.example:18089" || p["trusted"] != false || p["proxy"] != "127.0.0.1:9050" {
		t.Fatalf("params %s", (*calls)[0].Params)
	}
}

func TestClientNeverUsesProxy(t *testing.T) {
	tr, ok := New("http://wallet-rpc:18083/json_rpc").http.Transport.(*http.Transport)
	if !ok || tr.Proxy != nil {
		t.Fatal("wallet-rpc client must use its own transport without proxy")
	}
}

// wallet-rpc's set_daemon proxy only accepts an IP literal, so the host is resolved first.
func TestResolveProxy(t *testing.T) {
	for in, want := range map[string]string{"": "", "127.0.0.1:9050": "127.0.0.1:9050", "localhost:9050": "127.0.0.1:9050"} {
		got, err := ResolveProxy(context.Background(), in)
		if err != nil || got != want {
			t.Fatalf("%q: got %q err %v", in, got, err)
		}
	}
	if _, err := ResolveProxy(context.Background(), "no-such-host.invalid:9050"); err == nil {
		t.Fatal("unresolvable host accepted")
	}
}

func TestSetDaemonFailsWhenProxyDoesNotResolve(t *testing.T) {
	c, calls := fakeRPC(t, nil, nil)
	if err := c.SetDaemon(context.Background(), "https://node.example:18089", "no-such-host.invalid:9050"); err == nil {
		t.Fatal("no error")
	}
	if len(*calls) != 0 {
		t.Fatal("set_daemon sent with an unresolved proxy")
	}
}

func TestGenerateFromKeysRedactsEchoedViewKey(t *testing.T) {
	viewKey := strings.Repeat("d", 64)
	c, _ := fakeRPC(t, nil, map[string]string{"generate_from_keys": "invalid view key " + viewKey})
	err := c.GenerateFromKeys(context.Background(), "monostr-x", "4abc", viewKey, 99)
	var rpcErr *RPCError
	if !errors.As(err, &rpcErr) || strings.Contains(err.Error(), viewKey) || rpcErr.Message != "invalid view key [view key]" {
		t.Fatalf("err %v", err)
	}
}

func TestHeight(t *testing.T) {
	c, calls := fakeRPC(t, map[string]string{"get_height": `{"height":3200123}`}, nil)
	h, err := c.Height(context.Background())
	if err != nil || h != 3200123 || methods(*calls) != "get_height" {
		t.Fatalf("height %d err %v calls %s", h, err, methods(*calls))
	}
	c, _ = fakeRPC(t, nil, map[string]string{"get_height": "No wallet file"})
	if _, err := c.Height(context.Background()); err == nil {
		t.Fatal("no error")
	}
}
