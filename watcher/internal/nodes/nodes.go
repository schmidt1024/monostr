// Package nodes talks to public monerod nodes over restricted RPC for the
// personal watcher (spec 0.4 §3.4): chain height of the scan node, failover
// to the next node, and the node the check wallet syncs through.
package nodes

import (
	"context"
	"errors"
	"net"
	"net/url"
	"strings"
	"sync"

	"monostr.com/watcher/internal/monerod"
)

// failoverAfter is the number of failed scan-node polls in a row that moves the scan to the next node.
const failoverAfter = 3

// Nodes holds WATCHER_NODES split into two fixed groups (spec 0.4 §3.4): the
// scan wallet uses positions 1, 3, 5, … and the check wallet 2, 4, 6, …; each
// fails over within its own group only, so no node ever serves both wallets.
type Nodes struct {
	mu    sync.Mutex
	scan  group
	check group
}

type group struct {
	urls    []string
	heights []*monerod.HeightClient
	cur     int
	fails   int
}

// New takes at least two node base URLs (distinct, enforced by the config);
// even indices form the scan group, odd indices the check group.
// Requests go through http.DefaultTransport (and so through WATCHER_PROXY).
func New(urls []string) (*Nodes, error) {
	if len(urls) < 2 {
		return nil, errors.New("at least two nodes are required")
	}
	n := &Nodes{}
	for i, u := range urls {
		u = strings.TrimRight(u, "/")
		g := &n.scan
		if i%2 == 1 {
			g = &n.check
		}
		g.urls = append(g.urls, u)
		g.heights = append(g.heights, monerod.NewHeightClient(u))
	}
	return n, nil
}

// Normalize returns the comparable form of a node URL: lower-case scheme and
// host, no default port (http 80, https 443), no trailing slash, no query.
// Two nodes with the same normalized form are the same node.
func Normalize(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return strings.TrimRight(raw, "/")
	}
	scheme := strings.ToLower(u.Scheme)
	host, port := strings.ToLower(u.Hostname()), u.Port()
	if (scheme == "http" && port == "80") || (scheme == "https" && port == "443") {
		port = ""
	}
	if port != "" {
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]" // IPv6 literal
	}
	return scheme + "://" + host + strings.TrimRight(u.Path, "/")
}

// ScanGroup is the normalized node list of the scan wallet; it names the scan wallet file.
func (n *Nodes) ScanGroup() []string { return normalized(n.scan.urls) }

// CheckGroup is the normalized node list of the check wallet; it names the check wallet file.
func (n *Nodes) CheckGroup() []string { return normalized(n.check.urls) }

func normalized(urls []string) []string {
	out := make([]string, len(urls))
	for i, u := range urls {
		out[i] = Normalize(u)
	}
	return out
}

// Current is the node the scan wallet syncs through.
func (n *Nodes) Current() string { return n.node(&n.scan) }

// CheckNode is the node the check wallet syncs through.
func (n *Nodes) CheckNode() string { return n.node(&n.check) }

// Height is the chain height reported by the scan node.
func (n *Nodes) Height(ctx context.Context) (uint64, error) { return n.height(ctx, &n.scan) }

// CheckHeight is the chain height reported by the check node.
func (n *Nodes) CheckHeight(ctx context.Context) (uint64, error) { return n.height(ctx, &n.check) }

func (n *Nodes) ReportSuccess()      { n.success(&n.scan) }
func (n *Nodes) ReportCheckSuccess() { n.success(&n.check) }

// ReportFailure counts a failed poll of the scan node. After failoverAfter
// failures in a row the next node of the scan group becomes the scan node.
func (n *Nodes) ReportFailure() (next string, switched bool) { return n.failure(&n.scan) }

// ReportCheckFailure is ReportFailure for the check group.
func (n *Nodes) ReportCheckFailure() (next string, switched bool) { return n.failure(&n.check) }

// Advance moves the scan wallet to the next node of its group and returns it.
func (n *Nodes) Advance() string { return n.advance(&n.scan) }

// AdvanceCheck moves the check wallet to the next node of its group and returns it.
func (n *Nodes) AdvanceCheck() string { return n.advance(&n.check) }

func (n *Nodes) advance(g *group) string {
	n.mu.Lock()
	defer n.mu.Unlock()
	g.cur = (g.cur + 1) % len(g.urls)
	g.fails = 0
	return g.urls[g.cur]
}

func (n *Nodes) node(g *group) string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return g.urls[g.cur]
}

func (n *Nodes) height(ctx context.Context, g *group) (uint64, error) {
	n.mu.Lock()
	hc := g.heights[g.cur]
	n.mu.Unlock()
	return hc.Height(ctx)
}

func (n *Nodes) success(g *group) {
	n.mu.Lock()
	g.fails = 0
	n.mu.Unlock()
}

func (n *Nodes) failure(g *group) (string, bool) {
	n.mu.Lock()
	defer n.mu.Unlock()
	g.fails++
	if g.fails < failoverAfter {
		return g.urls[g.cur], false
	}
	g.fails = 0
	if len(g.urls) == 1 {
		return g.urls[0], false // nowhere to go within the group
	}
	g.cur = (g.cur + 1) % len(g.urls)
	return g.urls[g.cur], true
}
