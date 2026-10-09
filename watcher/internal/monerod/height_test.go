package monerod

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestHeightFetchesAndCaches(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		var req struct {
			Method string `json:"method"`
		}
		json.NewDecoder(r.Body).Decode(&req)
		if r.URL.Path != "/json_rpc" || req.Method != "get_info" {
			w.WriteHeader(404)
			return
		}
		w.Write([]byte(`{"jsonrpc":"2.0","id":"0","result":{"height":3200000,"status":"OK"}}`))
	}))
	defer srv.Close()
	c := NewHeightClient(srv.URL)
	now := time.Unix(1700000000, 0)
	c.now = func() time.Time { return now }
	h, err := c.Height(context.Background())
	if err != nil || h != 3200000 {
		t.Fatalf("%d %v", h, err)
	}
	c.Height(context.Background())
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("not cached: %d calls", calls)
	}
	now = now.Add(61 * time.Second)
	c.Height(context.Background())
	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("cache never expires: %d calls", calls)
	}
}

func TestHeightErrorKeepsLastValue(t *testing.T) {
	up := true
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !up {
			w.WriteHeader(503)
			return
		}
		w.Write([]byte(`{"result":{"height":10}}`))
	}))
	defer srv.Close()
	c := NewHeightClient(srv.URL)
	now := time.Unix(1700000000, 0)
	c.now = func() time.Time { return now }
	if h, _ := c.Height(context.Background()); h != 10 {
		t.Fatal("first fetch")
	}
	up = false
	now = now.Add(2 * time.Minute)
	h, err := c.Height(context.Background())
	if err == nil || h != 10 {
		t.Fatalf("stale value not returned with error: %d %v", h, err)
	}
	dead := NewHeightClient("http://127.0.0.1:1")
	if h, err := dead.Height(context.Background()); err == nil || h != 0 {
		t.Fatalf("dead: %d %v", h, err)
	}
}

func TestHeightRPCErrorKeepsLastValue(t *testing.T) {
	var stage int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch atomic.LoadInt32(&stage) {
		case 0:
			w.Write([]byte(`{"result":{"height":10}}`))
		case 1:
			w.Write([]byte(`{"jsonrpc":"2.0","id":"0","error":{"code":-32601,"message":"Method not found"}}`))
		default:
			w.Write([]byte(`{"result":{}}`))
		}
	}))
	defer srv.Close()
	c := NewHeightClient(srv.URL)
	now := time.Unix(1700000000, 0)
	c.now = func() time.Time { return now }
	if h, err := c.Height(context.Background()); err != nil || h != 10 {
		t.Fatalf("first fetch: %d %v", h, err)
	}
	atomic.StoreInt32(&stage, 1)
	now = now.Add(2 * time.Minute)
	h, err := c.Height(context.Background())
	if h != 10 || err == nil || !strings.Contains(err.Error(), "rpc error") {
		t.Fatalf("rpc error not surfaced: %d %v", h, err)
	}
	atomic.StoreInt32(&stage, 2)
	now = now.Add(2 * time.Minute)
	h, err = c.Height(context.Background())
	if h != 10 || err == nil {
		t.Fatalf("missing height not surfaced: %d %v", h, err)
	}
}
