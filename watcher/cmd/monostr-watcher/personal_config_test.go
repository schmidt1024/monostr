package main

import (
	"strings"
	"testing"
)

const (
	testAddr     = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
	testStageAdr = "52zucA3UF6NTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQf9eTdc"
	testViewKey  = "cea3c5dfea43f31197bd4b7166da59f90af44b4afac2b0732d9fcbe2b7fa0c06"
	testNpub     = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
)

func setPersonalEnv(t *testing.T) {
	t.Setenv("WATCHER_SECRET_KEY", strings.Repeat("1", 64))
	t.Setenv("WATCHER_NETWORK", "mainnet")
	t.Setenv("WATCHER_RECIPIENT", testNpub)
	t.Setenv("WATCHER_ADDRESS", testAddr)
	t.Setenv("WATCHER_VIEW_KEY", testViewKey)
	t.Setenv("WATCHER_RELAYS", "wss://relay.example")
	t.Setenv("WATCHER_NODES", "")
	t.Setenv("WATCHER_PROXY", "")
}

func TestPersonalConfigDefaults(t *testing.T) {
	setPersonalEnv(t)
	c, err := loadPersonalConfig()
	if err != nil {
		t.Fatal(err)
	}
	if c.recipient != "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e" {
		t.Fatalf("recipient %s", c.recipient)
	}
	if len(c.nodes) != 4 || c.nodes[0] != defaultMainnetNodes[0] {
		t.Fatalf("nodes %v", c.nodes)
	}
	if c.walletRPC != "http://wallet-rpc:18083/json_rpc" || c.proxy != nil {
		t.Fatalf("walletRPC %q proxy %v", c.walletRPC, c.proxy)
	}
}

func TestPersonalConfigProxy(t *testing.T) {
	setPersonalEnv(t)
	t.Setenv("WATCHER_PROXY", "socks5://tor:9050")
	c, err := loadPersonalConfig()
	if err != nil || proxyHostPort(c.proxy) != "tor:9050" {
		t.Fatalf("proxy %v err %v", c.proxy, err)
	}
	if proxyHostPort(nil) != "" {
		t.Fatal("nil proxy")
	}
}

func TestPersonalConfigRejects(t *testing.T) {
	for name, tc := range map[string]struct{ key, value, wantVar string }{
		"address of other network": {"WATCHER_ADDRESS", testStageAdr, "WATCHER_ADDRESS"},
		"view key mismatch":        {"WATCHER_VIEW_KEY", strings.Repeat("a", 64), "WATCHER_VIEW_KEY"},
		"two recipients":           {"WATCHER_RECIPIENT", testNpub + "," + testNpub, "WATCHER_RECIPIENT"},
		"bad recipient":            {"WATCHER_RECIPIENT", "nobody", "WATCHER_RECIPIENT"},
		"relay not wss":            {"WATCHER_RELAYS", "https://relay.example", "WATCHER_RELAYS"},
		"five relays":              {"WATCHER_RELAYS", "wss://a,wss://b,wss://c,wss://d,wss://e", "WATCHER_RELAYS"},
		"one node":                 {"WATCHER_NODES", "https://n1.example", "WATCHER_NODES"},
		"node not http":            {"WATCHER_NODES", "https://n1.example,ftp://n2.example", "WATCHER_NODES"},
		"proxy not socks5":         {"WATCHER_PROXY", "http://tor:9050", "WATCHER_PROXY"},
	} {
		t.Run(name, func(t *testing.T) {
			setPersonalEnv(t)
			t.Setenv(tc.key, tc.value)
			_, err := loadPersonalConfig()
			if err == nil || !strings.Contains(err.Error(), tc.wantVar) {
				t.Fatalf("err %v", err)
			}
		})
	}
}

func TestPersonalConfigStagenetNeedsNodes(t *testing.T) {
	setPersonalEnv(t)
	t.Setenv("WATCHER_NETWORK", "stagenet")
	t.Setenv("WATCHER_ADDRESS", testStageAdr)
	if _, err := loadPersonalConfig(); err == nil || !strings.Contains(err.Error(), "WATCHER_NODES") {
		t.Fatalf("err %v", err)
	}
}

func TestPersonalConfigRejectsDuplicateNodes(t *testing.T) {
	for _, nodes := range []string{
		"https://n1.example,https://n1.example",
		"https://N1.Example:18081/,HTTPS://n1.example:18081",
	} {
		setPersonalEnv(t)
		t.Setenv("WATCHER_NODES", nodes)
		_, err := loadPersonalConfig()
		if err == nil || !strings.Contains(err.Error(), "WATCHER_NODES") || !strings.Contains(err.Error(), "duplicate") {
			t.Fatalf("%s: err %v", nodes, err)
		}
	}
}

func TestPersonalConfigRejectsNodeWithCredentials(t *testing.T) {
	setPersonalEnv(t)
	t.Setenv("WATCHER_NODES", "https://user:s3cret@n1.example,https://n2.example")
	_, err := loadPersonalConfig()
	if err == nil || !strings.Contains(err.Error(), "WATCHER_NODES") || strings.Contains(err.Error(), "s3cret") {
		t.Fatalf("err %v", err)
	}
}

func TestPersonalConfigCheckWalletRPC(t *testing.T) {
	setPersonalEnv(t)
	t.Setenv("WALLET_RPC_URL", "")
	t.Setenv("WALLET_RPC_CHECK_URL", "")
	c, err := loadPersonalConfig()
	if err != nil || c.walletCheckRPC != "http://wallet-rpc-check:18083/json_rpc" {
		t.Fatalf("walletCheckRPC %q err %v", c.walletCheckRPC, err)
	}
	for _, same := range []string{"http://wallet-rpc:18083/json_rpc", "HTTP://Wallet-RPC:18083/json_rpc/"} {
		t.Setenv("WALLET_RPC_CHECK_URL", same)
		_, err = loadPersonalConfig()
		if err == nil || !strings.Contains(err.Error(), "WALLET_RPC_URL") || !strings.Contains(err.Error(), "WALLET_RPC_CHECK_URL") {
			t.Fatalf("%s: same wallet-rpc for both wallets: err %v", same, err)
		}
	}
	t.Setenv("WALLET_RPC_URL", "http://127.0.0.1:80/json_rpc")
	t.Setenv("WALLET_RPC_CHECK_URL", "http://127.0.0.1/json_rpc")
	if _, err = loadPersonalConfig(); err == nil {
		t.Fatal("default port not normalized")
	}
}

func TestPersonalConfigNodeErrorsDoNotEchoURL(t *testing.T) {
	for _, nodes := range []string{
		"https://n1.example,ftp://hidden-node.example",
		"https://hidden-node.example,https://hidden-node.example",
	} {
		setPersonalEnv(t)
		t.Setenv("WATCHER_NODES", nodes)
		_, err := loadPersonalConfig()
		if err == nil || !strings.Contains(err.Error(), "WATCHER_NODES") || strings.Contains(err.Error(), "hidden-node") {
			t.Fatalf("%s: err %v", nodes, err)
		}
	}
}

func TestPersonalConfigDuplicateNodesWithDefaultPort(t *testing.T) {
	for _, nodes := range []string{
		"https://n1.example:443,https://n1.example",
		"http://n1.example,http://N1.example:80/",
	} {
		setPersonalEnv(t)
		t.Setenv("WATCHER_NODES", nodes)
		_, err := loadPersonalConfig()
		if err == nil || !strings.Contains(err.Error(), "duplicate") {
			t.Fatalf("%s: err %v", nodes, err)
		}
	}
	setPersonalEnv(t)
	t.Setenv("WATCHER_NODES", "https://n1.example:18081,https://n1.example:18089,http://n1.example:443")
	if _, err := loadPersonalConfig(); err != nil {
		t.Fatalf("distinct ports rejected: %v", err)
	}
}

func TestPersonalConfigProxyNeedsPort(t *testing.T) {
	setPersonalEnv(t)
	t.Setenv("WATCHER_PROXY", "socks5://tor")
	if _, err := loadPersonalConfig(); err == nil || !strings.Contains(err.Error(), "WATCHER_PROXY") {
		t.Fatalf("err %v", err)
	}
}
