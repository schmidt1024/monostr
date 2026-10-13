package nodes

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeNode answers get_info with height.
type fakeNode struct {
	height uint64
}

func (f *fakeNode) server(t *testing.T) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/json_rpc":
			json.NewEncoder(w).Encode(map[string]any{"result": map[string]any{"height": f.height, "status": "OK"}})
		default:
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

func TestNewNeedsTwoNodes(t *testing.T) {
	if _, err := New([]string{"https://a.example"}); err == nil {
		t.Fatal("one node accepted")
	}
}

func TestHeightOfScanNode(t *testing.T) {
	a, b := &fakeNode{height: 100}, &fakeNode{height: 200}
	n, _ := New([]string{a.server(t), b.server(t)})
	if h, err := n.Height(context.Background()); err != nil || h != 100 {
		t.Fatalf("height %d %v", h, err)
	}
}

func TestCheckHeightOfCheckNode(t *testing.T) {
	a, b := &fakeNode{height: 100}, &fakeNode{height: 200}
	n, _ := New([]string{a.server(t), b.server(t)})
	if h, err := n.CheckHeight(context.Background()); err != nil || h != 200 {
		t.Fatalf("height %d %v", h, err)
	}
}

func TestFailoverAfterThreeFailures(t *testing.T) {
	urls := []string{"https://a", "https://b", "https://c", "https://d"}
	n, _ := New(urls)
	for i := 0; i < 2; i++ {
		if _, switched := n.ReportFailure(); switched {
			t.Fatalf("switched after %d failures", i+1)
		}
	}
	n.ReportSuccess() // resets the count
	n.ReportFailure()
	n.ReportFailure()
	next, switched := n.ReportFailure()
	if !switched || next != urls[2] || n.Current() != urls[2] {
		t.Fatalf("next %q switched %v current %q", next, switched, n.Current())
	}
	if n.CheckNode() != urls[1] {
		t.Fatalf("scan failover moved the check node to %q", n.CheckNode())
	}
}

func TestCheckFailoverAfterThreeFailures(t *testing.T) {
	urls := []string{"https://a", "https://b", "https://c", "https://d"}
	n, _ := New(urls)
	n.ReportCheckFailure()
	n.ReportCheckFailure()
	n.ReportCheckSuccess() // resets the count
	n.ReportCheckFailure()
	n.ReportCheckFailure()
	next, switched := n.ReportCheckFailure()
	if !switched || next != urls[3] || n.CheckNode() != urls[3] {
		t.Fatalf("next %q switched %v check %q", next, switched, n.CheckNode())
	}
	if n.Current() != urls[0] {
		t.Fatalf("check failover moved the scan node to %q", n.Current())
	}
}

// TestGroupsNeverShareANode walks both rotations for 2, 3 and 4 nodes: the scan
// wallet only sees even indices, the check wallet only odd ones.
func TestGroupsNeverShareANode(t *testing.T) {
	all := []string{"https://n0", "https://n1", "https://n2", "https://n3"}
	for size := 2; size <= 4; size++ {
		urls := all[:size]
		n, _ := New(urls)
		scanSeen, checkSeen := map[string]bool{}, map[string]bool{}
		for round := 0; round < 4*size; round++ {
			if n.Current() == n.CheckNode() {
				t.Fatalf("%d nodes: both wallets on %q", size, n.Current())
			}
			scanSeen[n.Current()], checkSeen[n.CheckNode()] = true, true
			for i := 0; i < 3; i++ {
				if round%2 == 0 {
					n.ReportFailure()
				} else {
					n.ReportCheckFailure()
				}
			}
		}
		for i, u := range urls {
			if i%2 == 0 && (!scanSeen[u] || checkSeen[u]) || i%2 == 1 && (!checkSeen[u] || scanSeen[u]) {
				t.Fatalf("%d nodes: node %d scan %v check %v", size, i, scanSeen[u], checkSeen[u])
			}
		}
	}
}

func TestSingleNodeGroupDoesNotSwitch(t *testing.T) {
	n, _ := New([]string{"https://a", "https://b"})
	for i := 0; i < 3; i++ {
		if next, switched := n.ReportCheckFailure(); switched || next != "https://b" {
			t.Fatalf("next %q switched %v", next, switched)
		}
	}
}

func TestNormalize(t *testing.T) {
	for in, want := range map[string]string{
		"https://N1.Example:443/":       "https://n1.example",
		"HTTP://n1.example:80":          "http://n1.example",
		"https://n1.example:18081/":     "https://n1.example:18081",
		"http://n1.example:443":         "http://n1.example:443",
		"https://[2001:DB8::1]:443/rpc": "https://[2001:db8::1]/rpc",
		"https://[2001:db8::1]:18089":   "https://[2001:db8::1]:18089",
	} {
		if got := Normalize(in); got != want {
			t.Errorf("Normalize(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestGroupsAreNormalizedAndFixed(t *testing.T) {
	n, _ := New([]string{"https://A.example:443/", "https://b.example", "https://c.example:18081", "https://d.example"})
	if got := strings.Join(n.ScanGroup(), ","); got != "https://a.example,https://c.example:18081" {
		t.Fatalf("scan group %s", got)
	}
	if got := strings.Join(n.CheckGroup(), ","); got != "https://b.example,https://d.example" {
		t.Fatalf("check group %s", got)
	}
	n.ReportFailure()
	n.ReportFailure()
	n.ReportFailure()
	if got := strings.Join(n.ScanGroup(), ","); got != "https://a.example,https://c.example:18081" {
		t.Fatalf("scan group after failover %s", got)
	}
}

func TestAdvanceStaysInGroup(t *testing.T) {
	n, _ := New([]string{"https://a", "https://b", "https://c", "https://d", "https://e"})
	n.ReportFailure()
	n.ReportFailure()
	if got := n.Advance(); got != "https://c" || n.Current() != "https://c" {
		t.Fatalf("advance %s current %s", got, n.Current())
	}
	if _, switched := n.ReportFailure(); switched {
		t.Fatal("failure count not reset by advance")
	}
	n.Advance()
	if got := n.Advance(); got != "https://a" {
		t.Fatalf("no wrap within the scan group: %s", got)
	}
	if n.CheckNode() != "https://b" {
		t.Fatalf("check node moved: %s", n.CheckNode())
	}
	if got := n.AdvanceCheck(); got != "https://d" || n.CheckNode() != "https://d" || n.Current() != "https://a" {
		t.Fatalf("advance check %s, check %s, scan %s", got, n.CheckNode(), n.Current())
	}
}
