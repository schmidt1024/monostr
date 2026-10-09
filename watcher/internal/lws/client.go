package lws

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client is a minimal monero-lws admin REST client. Every command is a POST
// of {"auth": key, "params": {...}} to BaseURL + "/" + command.
type Client struct {
	BaseURL string
	AuthKey string
	HTTP    *http.Client
}

func New(baseURL, authKey string) *Client {
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		AuthKey: authKey,
		HTTP:    &http.Client{Timeout: 15 * time.Second},
	}
}

// Error is a non-2xx answer from the admin API. Body is kept for logging
// except for add_account, whose request/response must never expose keys.
type Error struct {
	Command string
	Status  int
	Body    string
}

func (e *Error) Error() string {
	if e.Body == "" {
		return fmt.Sprintf("lws %s: HTTP %d", e.Command, e.Status)
	}
	return fmt.Sprintf("lws %s: HTTP %d: %s", e.Command, e.Status, e.Body)
}

const maxBody = 1 << 20

func (c *Client) call(ctx context.Context, command string, params any, out any) error {
	req := map[string]any{"params": params}
	if c.AuthKey != "" {
		req["auth"] = c.AuthKey
	}
	body, err := json.Marshal(req)
	if err != nil {
		return err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/"+command, bytes.NewReader(body))
	if err != nil {
		return err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	resp, err := c.HTTP.Do(httpReq)
	if err != nil {
		return fmt.Errorf("lws %s: %w", command, err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if err != nil {
		return fmt.Errorf("lws %s: read: %w", command, err)
	}
	if resp.StatusCode/100 != 2 {
		e := &Error{Command: command, Status: resp.StatusCode}
		if command != "add_account" {
			e.Body = strings.TrimSpace(string(raw))
		}
		return e
	}
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			return fmt.Errorf("lws %s: decode: %w", command, err)
		}
	}
	return nil
}

func (c *Client) AddAccount(ctx context.Context, address, viewKeyHex string) error {
	return c.call(ctx, "add_account", map[string]any{"address": address, "key": viewKeyHex}, nil)
}

type accountEntry struct {
	Address string `json:"address"`
}

func (c *Client) ListAccounts(ctx context.Context) (active, inactive, hidden []string, err error) {
	var out struct {
		Active   []accountEntry `json:"active"`
		Inactive []accountEntry `json:"inactive"`
		Hidden   []accountEntry `json:"hidden"`
	}
	if err := c.call(ctx, "list_accounts", map[string]any{}, &out); err != nil {
		return nil, nil, nil, err
	}
	addrs := func(es []accountEntry) []string {
		var s []string
		for _, e := range es {
			s = append(s, e.Address)
		}
		return s
	}
	return addrs(out.Active), addrs(out.Inactive), addrs(out.Hidden), nil
}

func (c *Client) ModifyAccountStatus(ctx context.Context, status string, addresses []string) error {
	return c.call(ctx, "modify_account_status", map[string]any{"status": status, "addresses": addresses}, nil)
}

// EnsureAccount makes address an active LWS account: known accounts are
// (re)activated, unknown ones added with the view key.
func (c *Client) EnsureAccount(ctx context.Context, address, viewKeyHex string) error {
	active, inactive, hidden, err := c.ListAccounts(ctx)
	if err != nil {
		return err
	}
	for _, a := range active {
		if a == address {
			return nil
		}
	}
	for _, a := range append(inactive, hidden...) {
		if a == address {
			return c.ModifyAccountStatus(ctx, "active", []string{address})
		}
	}
	return c.AddAccount(ctx, address, viewKeyHex)
}

func (c *Client) WebhookAdd(ctx context.Context, address, paymentID, token, url string, confirmations uint32) (string, error) {
	var out struct {
		EventID string `json:"event_id"`
	}
	params := map[string]any{
		"type": "tx-confirmation", "url": url, "address": address,
		"payment_id": paymentID, "token": token, "confirmations": confirmations,
	}
	if err := c.call(ctx, "webhook_add", params, &out); err != nil {
		return "", err
	}
	if out.EventID == "" {
		return "", fmt.Errorf("lws webhook_add: no event_id in response")
	}
	return out.EventID, nil
}

func (c *Client) WebhookDeleteUUID(ctx context.Context, eventIDs []string) error {
	return c.call(ctx, "webhook_delete_uuid", map[string]any{"event_ids": eventIDs}, nil)
}

func (c *Client) Rescan(ctx context.Context, height uint64, addresses []string) error {
	return c.call(ctx, "rescan", map[string]any{"height": height, "addresses": addresses}, nil)
}
