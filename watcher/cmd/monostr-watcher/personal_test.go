package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"monostr.com/watcher/internal/nodes"
	"monostr.com/watcher/internal/store"
	"monostr.com/watcher/internal/watcher"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestRegisterWithRetry(t *testing.T) {
	calls := 0
	err := registerWithRetry(context.Background(), func(context.Context) error {
		calls++
		if calls < 3 {
			return errors.New("wallet-rpc not up")
		}
		return nil
	}, time.Millisecond, discardLogger())
	if err != nil || calls != 3 {
		t.Fatalf("calls %d err %v", calls, err)
	}
}

func TestRegisterWithRetryStopsOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	err := registerWithRetry(ctx, func(context.Context) error { return errors.New("down") }, time.Hour, discardLogger())
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err %v", err)
	}
}

// heightNode is a monerod stand-in answering get_info with height.
func heightNode(t *testing.T, height uint64) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"result": map[string]any{"height": height, "status": "OK"}})
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

func TestWalletSyncsFollowTheirGroups(t *testing.T) {
	urls := []string{heightNode(t, 100), heightNode(t, 200), heightNode(t, 300)}
	nd, err := nodes.New(urls)
	if err != nil {
		t.Fatal(err)
	}
	scan, check := scanSync(nd), checkSync(nd)
	if scan.Role != "scan" || scan.Node() != urls[0] || strings.Join(scan.Group, ",") != strings.Join(nd.ScanGroup(), ",") {
		t.Fatalf("scan %s %s %v", scan.Role, scan.Node(), scan.Group)
	}
	if check.Role != "check" || check.Node() != urls[1] || strings.Join(check.Group, ",") != strings.Join(nd.CheckGroup(), ",") {
		t.Fatalf("check %s %s %v", check.Role, check.Node(), check.Group)
	}
	if h, err := scan.Height(context.Background()); err != nil || h != 100 {
		t.Fatalf("scan height %d %v", h, err)
	}
	if h, err := check.Height(context.Background()); err != nil || h != 200 {
		t.Fatalf("check restore height must come from the check node: %d %v", h, err)
	}
	if scan.Advance(); scan.Node() != urls[2] || check.Node() != urls[1] {
		t.Fatalf("scan advance: scan %s check %s", scan.Node(), check.Node())
	}
	if check.Advance(); check.Node() != urls[1] || scan.Node() != urls[2] { // one-node check group
		t.Fatalf("check advance: scan %s check %s", scan.Node(), check.Node())
	}
}

func TestRegisterBothRegistersOnce(t *testing.T) {
	registers, ensures := 0, 0
	register := func(context.Context) error {
		registers++
		if registers == 1 {
			return errors.New("wallet-rpc not ready")
		}
		return nil
	}
	ensureCheck := func(context.Context) error {
		ensures++
		if ensures < 3 {
			return errors.New("wallet-rpc-check not ready")
		}
		return nil
	}
	both := registerBoth(register, ensureCheck)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if both(ctx) == nil {
			t.Fatalf("attempt %d succeeded early", i+1)
		}
	}
	if err := both(ctx); err != nil {
		t.Fatal(err)
	}
	if registers != 2 || ensures != 3 {
		t.Fatalf("registers %d ensures %d", registers, ensures)
	}
}

func TestLastRecordedHeight(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "w.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	last := lastRecordedHeight(st, discardLogger())
	if h := last(); h != 0 {
		t.Fatalf("no recorded height: %d", h)
	}
	st.SetMeta(watcher.MetaLastHeight, "3200000")
	if h := last(); h != 3200000 {
		t.Fatalf("recorded height: %d", h)
	}
	st.SetMeta(watcher.MetaLastHeight, "garbage")
	if h := last(); h != 0 {
		t.Fatalf("unparsable height: %d", h)
	}
}
