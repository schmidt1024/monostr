// Package monerod reads the chain height from monerod's restricted JSON-RPC.
package monerod

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

const cacheTTL = 60 * time.Second

type HeightClient struct {
	url  string
	http *http.Client
	now  func() time.Time
	mu   sync.Mutex
	last uint64
	at   time.Time
}

func NewHeightClient(rpcURL string) *HeightClient {
	return &HeightClient{
		url:  strings.TrimRight(rpcURL, "/") + "/json_rpc",
		http: &http.Client{Timeout: 10 * time.Second},
		now:  time.Now,
	}
}

// Height returns the cached height (60 s) or fetches it. On failure it
// returns the last known value together with the error.
func (c *HeightClient) Height(ctx context.Context) (uint64, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if !c.at.IsZero() && now.Sub(c.at) < cacheTTL {
		return c.last, nil
	}
	body := []byte(`{"jsonrpc":"2.0","id":"0","method":"get_info"}`)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(body))
	if err != nil {
		return c.last, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return c.last, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return c.last, fmt.Errorf("monerod get_info: HTTP %d", resp.StatusCode)
	}
	var out struct {
		Result struct {
			Height uint64 `json:"height"`
		} `json:"result"`
		Error *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return c.last, err
	}
	if out.Error != nil {
		return c.last, fmt.Errorf("monerod get_info: rpc error %d: %s", out.Error.Code, out.Error.Message)
	}
	if out.Result.Height == 0 {
		return c.last, errors.New("monerod get_info: no height in response")
	}
	c.last, c.at = out.Result.Height, now
	return c.last, nil
}
