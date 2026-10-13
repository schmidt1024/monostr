// Package walletrpc drives monero-wallet-rpc for the personal watcher: a
// view-only wallet of the recipient, scanned against public nodes.
package walletrpc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	url  string
	http *http.Client
}

// New takes the JSON-RPC URL (…/json_rpc). The client has its own transport:
// wallet-rpc is a local container and never goes through WATCHER_PROXY.
func New(url string) *Client {
	return &Client{url: url, http: &http.Client{Timeout: 120 * time.Second, Transport: &http.Transport{}}}
}

type RPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (e *RPCError) Error() string { return fmt.Sprintf("wallet-rpc error %d: %s", e.Code, e.Message) }

// call never puts params into an error: generate_from_keys carries the view key.
func (c *Client) call(ctx context.Context, method string, params, out any) error {
	body, err := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": "0", "method": method, "params": params})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("wallet-rpc %s: %w", method, err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return fmt.Errorf("wallet-rpc %s: read: %w", method, err)
	}
	if resp.StatusCode != 200 {
		return fmt.Errorf("wallet-rpc %s: HTTP %d", method, resp.StatusCode)
	}
	var envelope struct {
		Result json.RawMessage `json:"result"`
		Error  *RPCError       `json:"error"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return fmt.Errorf("wallet-rpc %s: decode: %w", method, err)
	}
	if envelope.Error != nil {
		return envelope.Error
	}
	if out != nil && len(envelope.Result) > 0 {
		if err := json.Unmarshal(envelope.Result, out); err != nil {
			return fmt.Errorf("wallet-rpc %s: decode result: %w", method, err)
		}
	}
	return nil
}

func (c *Client) OpenWallet(ctx context.Context, filename string) error {
	return c.call(ctx, "open_wallet", map[string]any{"filename": filename, "password": ""}, nil)
}

// GenerateFromKeys creates the view-only wallet. If wallet-rpc echoes the
// view key in its error message, the key is replaced before the error leaves.
func (c *Client) GenerateFromKeys(ctx context.Context, filename, address, viewKey string, restoreHeight uint64) error {
	err := c.call(ctx, "generate_from_keys", map[string]any{
		"filename": filename, "address": address, "viewkey": viewKey, "spendkey": "",
		"password": "", "restore_height": restoreHeight, "autosave_current": true,
	}, nil)
	var rpcErr *RPCError
	if viewKey != "" && errors.As(err, &rpcErr) {
		return &RPCError{Code: rpcErr.Code, Message: strings.ReplaceAll(rpcErr.Message, viewKey, "[view key]")}
	}
	return err
}

// SetDaemon points the open wallet at a node. proxy is "host:port" or empty;
// its host is resolved to an IP first (see ResolveProxy).
func (c *Client) SetDaemon(ctx context.Context, address, proxy string) error {
	proxy, err := ResolveProxy(ctx, proxy)
	if err != nil {
		return err
	}
	return c.call(ctx, "set_daemon", map[string]any{"address": address, "trusted": false, "proxy": proxy}, nil)
}

// ResolveProxy turns "host:port" into "ip:port" (IPv4 preferred); "" stays "".
// wallet-rpc's set_daemon proxy only accepts an IP literal, and the tor
// container's IP changes when it is recreated, so callers resolve every time.
func ResolveProxy(ctx context.Context, hostport string) (string, error) {
	if hostport == "" {
		return "", nil
	}
	host, port, err := net.SplitHostPort(hostport)
	if err != nil {
		return "", fmt.Errorf("proxy %q: %w", hostport, err)
	}
	addrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return "", fmt.Errorf("resolve proxy %q: %w", host, err)
	}
	if len(addrs) == 0 {
		return "", fmt.Errorf("resolve proxy %q: no address", host)
	}
	ip := addrs[0].IP
	for _, a := range addrs {
		if a.IP.To4() != nil {
			ip = a.IP
			break
		}
	}
	return net.JoinHostPort(ip.String(), port), nil
}

// Height is the wallet's own chain height: how far it has synced.
func (c *Client) Height(ctx context.Context) (uint64, error) {
	var out struct {
		Height uint64 `json:"height"`
	}
	if err := c.call(ctx, "get_height", map[string]any{}, &out); err != nil {
		return 0, err
	}
	return out.Height, nil
}

type Transfer struct {
	TxID          string `json:"txid"`
	PaymentID     string `json:"payment_id"`
	Amount        uint64 `json:"amount"`
	Height        uint64 `json:"height"`
	Confirmations uint64 `json:"confirmations"`
	Pool          bool   `json:"-"` // listed under "pool" (not yet in a block)
}

// IncomingTransfers returns confirmed incoming transfers and those still in the pool.
func (c *Client) IncomingTransfers(ctx context.Context) ([]Transfer, error) {
	var out struct {
		In   []Transfer `json:"in"`
		Pool []Transfer `json:"pool"`
	}
	if err := c.call(ctx, "get_transfers", map[string]any{"in": true, "pool": true}, &out); err != nil {
		return nil, err
	}
	for i := range out.Pool {
		out.Pool[i].Confirmations, out.Pool[i].Pool = 0, true
	}
	return append(out.In, out.Pool...), nil
}
