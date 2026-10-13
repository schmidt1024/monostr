package main

import (
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"

	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/nodes"
	"monostr.com/watcher/internal/protocol"
)

// defaultMainnetNodes are independent public nodes (none run by monostr), checked 2026-10-13.
var defaultMainnetNodes = []string{
	"https://xmr-node.cakewallet.com:18081",
	"https://node.monerodevs.org:18089",
	"https://monero.stackwallet.com:18081",
	"https://xmr.cryptostorm.is:18081",
}

type personalConfig struct {
	secretKey       string
	network         monero.Network
	recipient       string
	address         string
	viewKey         string
	nodes           []string
	relays          []string
	relayListRelays []string
	proxy           *url.URL
	walletRPC       string
	walletCheckRPC  string
	dbPath          string
	sweepInterval   time.Duration
}

func loadPersonalConfig() (personalConfig, error) {
	var c personalConfig
	c.secretKey = env("WATCHER_SECRET_KEY", "")
	if !protocol.IsHex64(c.secretKey) {
		return c, errors.New("WATCHER_SECRET_KEY must be 64 lowercase hex chars")
	}
	net, err := monero.ParseNetwork(env("WATCHER_NETWORK", ""))
	if err != nil {
		return c, fmt.Errorf("WATCHER_NETWORK: %w", err)
	}
	c.network = net
	recipients, err := parsePubkeys(env("WATCHER_RECIPIENT", ""))
	if err != nil || len(recipients) != 1 {
		return c, errors.New("WATCHER_RECIPIENT must be exactly one npub or 64-char hex pubkey")
	}
	c.recipient = recipients[0]
	c.address = env("WATCHER_ADDRESS", "")
	addr, err := monero.ParseStandardAddress(c.address)
	if err != nil {
		return c, fmt.Errorf("WATCHER_ADDRESS: must be a primary address: %w", err)
	}
	if addr.Network != c.network {
		return c, fmt.Errorf("WATCHER_ADDRESS is not a %s address", c.network)
	}
	c.viewKey = strings.ToLower(env("WATCHER_VIEW_KEY", ""))
	key, err := hex.DecodeString(c.viewKey)
	if err != nil || !protocol.IsHex64(c.viewKey) || !monero.ViewKeyMatches(key, addr.View) {
		return c, errors.New("WATCHER_VIEW_KEY does not match WATCHER_ADDRESS")
	}
	c.nodes = splitList(env("WATCHER_NODES", ""))
	if len(c.nodes) == 0 && c.network == monero.Mainnet {
		c.nodes = defaultMainnetNodes
	}
	seen := map[string]bool{}
	for i, n := range c.nodes {
		u, err := url.Parse(n)
		if err != nil {
			return c, fmt.Errorf("WATCHER_NODES: node %d is not a valid URL", i+1)
		}
		if u.User != nil {
			// not echoed: the URL carries credentials
			return c, fmt.Errorf("WATCHER_NODES: node %d must not contain user:password", i+1)
		}
		if (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return c, fmt.Errorf("WATCHER_NODES: node %d is not an http(s) URL", i+1)
		}
		key := nodes.Normalize(n)
		if seen[key] {
			return c, fmt.Errorf("WATCHER_NODES: node %d is a duplicate; the cross-check needs a different node", i+1)
		}
		seen[key] = true
	}
	if len(c.nodes) < 2 {
		return c, errors.New("WATCHER_NODES must list at least two nodes")
	}
	c.relays = splitList(env("WATCHER_RELAYS", ""))
	if len(c.relays) < 1 || len(c.relays) > 4 {
		return c, errors.New("WATCHER_RELAYS must list 1 to 4 relays")
	}
	for _, r := range c.relays {
		if u, err := url.Parse(r); err != nil || u.Scheme != "wss" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return c, fmt.Errorf("WATCHER_RELAYS: %q is not a plain wss:// URL", r)
		}
	}
	c.relayListRelays = splitList(env("WATCHER_RELAY_LIST_RELAYS", "wss://purplepag.es,wss://relay.damus.io"))
	if p := env("WATCHER_PROXY", ""); p != "" {
		u, err := url.Parse(p)
		if err != nil || u.Scheme != "socks5" || u.Hostname() == "" || u.Port() == "" {
			return c, errors.New("WATCHER_PROXY must look like socks5://host:port")
		}
		c.proxy = u
	}
	c.walletRPC = env("WALLET_RPC_URL", "http://wallet-rpc:18083/json_rpc")
	c.walletCheckRPC = env("WALLET_RPC_CHECK_URL", "http://wallet-rpc-check:18083/json_rpc")
	if nodes.Normalize(c.walletRPC) == nodes.Normalize(c.walletCheckRPC) {
		return c, errors.New("WALLET_RPC_URL and WALLET_RPC_CHECK_URL must point to two different wallet-rpc instances")
	}
	c.dbPath = env("WATCHER_DB", "/data/watcher.db")
	c.sweepInterval, err = time.ParseDuration(env("WATCHER_SWEEP_INTERVAL", "60s"))
	if err != nil || c.sweepInterval < time.Second {
		return c, errors.New("WATCHER_SWEEP_INTERVAL must be a duration >= 1s")
	}
	return c, nil
}

// proxyHostPort is the host:port of the proxy ("" without proxy); walletrpc.Client.SetDaemon
// resolves the host to an IP on every call, as wallet-rpc accepts only IP literals.
func proxyHostPort(u *url.URL) string {
	if u == nil {
		return ""
	}
	return u.Host
}
