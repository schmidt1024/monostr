package walletrpc

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"

	"monostr.com/watcher/internal/nodes"
	"monostr.com/watcher/internal/watcher"
)

var _ watcher.LWSClient = (*Backend)(nil)

// scanSync returns a scan Sync over a fixed group whose current node is node.
func scanSync(node string, height uint64, err error) Sync {
	return Sync{
		Role: "scan", Group: []string{"https://n1", "https://n3"},
		Node:   func() string { return node },
		Height: func(context.Context) (uint64, error) { return height, err },
	}
}

const addr = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"

func methods(calls []rpcCall) string {
	var m []string
	for _, c := range calls {
		m = append(m, c.Method)
	}
	return strings.Join(m, ",")
}

func TestEnsureAccountOpensExistingWallet(t *testing.T) {
	c, calls := fakeRPC(t, nil, nil)
	b := NewBackend(c, scanSync("https://n1", 1000, nil), "127.0.0.1:9050")
	if err := b.EnsureAccount(context.Background(), addr, strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	if methods(*calls) != "open_wallet,set_daemon" {
		t.Fatalf("calls %s", methods(*calls))
	}
	var p map[string]any
	json.Unmarshal((*calls)[0].Params, &p)
	if p["filename"] != WalletName(addr, "scan", []string{"https://n1", "https://n3"}) {
		t.Fatalf("filename %v", p["filename"])
	}
	json.Unmarshal((*calls)[1].Params, &p)
	if p["address"] != "https://n1" || p["proxy"] != "127.0.0.1:9050" {
		t.Fatalf("set_daemon %s", (*calls)[1].Params)
	}
}

func TestEnsureAccountGeneratesMissingWallet(t *testing.T) {
	c, calls := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	b := NewBackend(c, scanSync("https://n1", 1000, nil), "")
	if err := b.EnsureAccount(context.Background(), addr, strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	if methods(*calls) != "open_wallet,generate_from_keys,set_daemon" {
		t.Fatalf("calls %s", methods(*calls))
	}
	var p map[string]any
	json.Unmarshal((*calls)[1].Params, &p)
	if p["restore_height"] != float64(1000-RestoreMargin) {
		t.Fatalf("restore height %v", p["restore_height"])
	}
}

func TestEnsureAccountNeedsHeightToGenerate(t *testing.T) {
	c, calls := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	b := NewBackend(c, scanSync("https://n1", 0, errors.New("down")), "")
	if err := b.EnsureAccount(context.Background(), addr, strings.Repeat("c", 64)); err == nil {
		t.Fatal("no error")
	}
	if methods(*calls) != "open_wallet" {
		t.Fatalf("calls %s", methods(*calls))
	}
}

func TestWalletName(t *testing.T) {
	scan := WalletName(addr, "scan", []string{"https://a", "https://c"})
	if !regexp.MustCompile(`^monostr-42nsXK8WbVGT-scan-[0-9a-f]{8}$`).MatchString(scan) {
		t.Fatalf("name %s", scan)
	}
	if WalletName(addr, "scan", []string{"https://a", "https://c"}) != scan {
		t.Fatal("name not stable")
	}
	for _, other := range []string{
		WalletName(addr, "check", []string{"https://a", "https://c"}),
		WalletName(addr, "scan", []string{"https://a", "https://e"}),
		WalletName(addr, "scan", []string{"https://a"}),
	} {
		if other == scan {
			t.Fatalf("%s collides with %s", other, scan)
		}
	}
}

func TestWalletNameIgnoresGroupOrder(t *testing.T) {
	group := []string{"https://c", "https://a", "https://b"}
	if WalletName(addr, "scan", group) != WalletName(addr, "scan", []string{"https://a", "https://b", "https://c"}) {
		t.Fatal("reordered group changed the wallet name")
	}
	if group[0] != "https://c" {
		t.Fatalf("group sorted in place: %v", group)
	}
}

func TestWalletNameFollowsOwnGroupOnly(t *testing.T) {
	a, _ := nodes.New([]string{"https://a", "https://b", "https://c", "https://d"})
	b, _ := nodes.New([]string{"https://A:443/", "https://x", "https://c", "https://y"}) // same scan group, other check group
	c, _ := nodes.New([]string{"https://a", "https://b", "https://z", "https://d"})      // other scan group, same check group
	if WalletName(addr, "scan", a.ScanGroup()) != WalletName(addr, "scan", b.ScanGroup()) {
		t.Fatal("scan wallet name changed with the check group")
	}
	if WalletName(addr, "scan", a.ScanGroup()) == WalletName(addr, "scan", c.ScanGroup()) {
		t.Fatal("scan wallet name kept with a changed scan group")
	}
	if WalletName(addr, "check", a.CheckGroup()) != WalletName(addr, "check", c.CheckGroup()) {
		t.Fatal("check wallet name changed with the scan group")
	}
	if WalletName(addr, "check", a.CheckGroup()) == WalletName(addr, "check", b.CheckGroup()) {
		t.Fatal("check wallet name kept with a changed check group")
	}
}

func TestBackendUsesItsSync(t *testing.T) {
	c, calls := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	check := NewBackend(c, Sync{
		Role: "check", Group: []string{"https://check"},
		Node:   func() string { return "https://check" },
		Height: func(context.Context) (uint64, error) { return 5000, nil },
	}, "")
	if err := check.EnsureAccount(context.Background(), addr, strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	var open, gen, daemon map[string]any
	json.Unmarshal((*calls)[0].Params, &open)
	json.Unmarshal((*calls)[1].Params, &gen)
	json.Unmarshal((*calls)[2].Params, &daemon)
	name := WalletName(addr, "check", []string{"https://check"})
	if open["filename"] != name || gen["filename"] != name {
		t.Fatalf("filenames %v %v", open["filename"], gen["filename"])
	}
	if gen["restore_height"] != float64(5000-RestoreMargin) || daemon["address"] != "https://check" {
		t.Fatalf("restore height %v daemon %v", gen["restore_height"], daemon["address"])
	}
}

func TestWebhookAddIsStateless(t *testing.T) {
	c, calls := fakeRPC(t, nil, nil)
	b := NewBackend(c, scanSync("", 0, nil), "")
	id, err := b.WebhookAdd(context.Background(), addr, "1111111111111111", "token", "", 1)
	if err != nil || id != "wr-1111111111111111" || len(*calls) != 0 {
		t.Fatalf("id %q err %v calls %d", id, err, len(*calls))
	}
	if b.WebhookDeleteUUID(context.Background(), []string{id}) != nil ||
		b.ModifyAccountStatus(context.Background(), "inactive", []string{addr}) != nil ||
		b.Rescan(context.Background(), 5, []string{addr}) != nil || len(*calls) != 0 {
		t.Fatal("no-ops must not call wallet-rpc")
	}
}

func TestRestoreHeight(t *testing.T) {
	const now = 3_000_000
	for _, c := range []struct {
		name      string
		now, last uint64
		want      uint64
	}{
		{"no recorded height", now, 0, now - RestoreMargin},
		{"recorded before the margin", now, now - 1000, now - 1000 - RestoreMargin},
		{"recorded recently", now, now - 100, now - 100 - RestoreMargin},
		{"recorded ahead of the node", now, now + 50, now - RestoreMargin},
		{"recorded long ago: 7 days at most", now, now - 100_000, now - MaxRestoreLookback},
		{"young chain", 500, 300, 0},
		{"young chain, no recorded height", 500, 0, 0},
	} {
		if got := restoreHeight(c.now, c.last); got != c.want {
			t.Errorf("%s: restore %d, want %d", c.name, got, c.want)
		}
	}
}

func TestEnsureAccountRestoresFromLastRecordedHeight(t *testing.T) {
	c, calls := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	s := scanSync("https://n1", 1_000_000, nil)
	s.LastHeight = func() uint64 { return 1_000_000 - 3000 }
	if err := NewBackend(c, s, "").EnsureAccount(context.Background(), addr, strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	var p map[string]any
	json.Unmarshal((*calls)[1].Params, &p)
	if p["restore_height"] != float64(1_000_000-3000-RestoreMargin) {
		t.Fatalf("restore height %v", p["restore_height"])
	}
}

// heightNode answers get_info with height; deadNode refuses connections.
func heightNode(t *testing.T, height uint64) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"result": map[string]any{"height": height, "status": "OK"}})
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

func deadNode() string {
	srv := httptest.NewServer(http.NotFoundHandler())
	srv.Close()
	return srv.URL
}

func TestEnsureAccountMovesPastDeadNodeInGroup(t *testing.T) {
	dead, alive := deadNode(), heightNode(t, 50_000)
	nd, err := nodes.New([]string{dead, heightNode(t, 90_000), alive, heightNode(t, 90_000)})
	if err != nil {
		t.Fatal(err)
	}
	c, calls := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	s := Sync{Role: "scan", Group: nd.ScanGroup(), Node: nd.Current, Height: nd.Height, Advance: nd.Advance}
	if err := NewBackend(c, s, "").EnsureAccount(context.Background(), addr, strings.Repeat("c", 64)); err != nil {
		t.Fatal(err)
	}
	if methods(*calls) != "open_wallet,generate_from_keys,set_daemon" {
		t.Fatalf("calls %s", methods(*calls))
	}
	var gen, daemon map[string]any
	json.Unmarshal((*calls)[1].Params, &gen)
	json.Unmarshal((*calls)[2].Params, &daemon)
	if gen["restore_height"] != float64(50_000-RestoreMargin) || daemon["address"] != alive {
		t.Fatalf("restore height %v daemon %v", gen["restore_height"], daemon["address"])
	}
	if nd.Current() != alive {
		t.Fatalf("scan node %s", nd.Current())
	}
}

func TestEnsureAccountFailsWhenWholeGroupIsDown(t *testing.T) {
	nd, _ := nodes.New([]string{deadNode(), heightNode(t, 90_000), deadNode(), heightNode(t, 90_000)})
	c, calls := fakeRPC(t, nil, map[string]string{"open_wallet": "Failed to open wallet"})
	s := Sync{Role: "scan", Group: nd.ScanGroup(), Node: nd.Current, Height: nd.Height, Advance: nd.Advance}
	err := NewBackend(c, s, "").EnsureAccount(context.Background(), addr, strings.Repeat("c", 64))
	if err == nil || strings.Contains(err.Error(), "127.0.0.1") {
		t.Fatalf("err %v (must not name a node)", err)
	}
	if methods(*calls) != "open_wallet" {
		t.Fatalf("calls %s", methods(*calls))
	}
}
