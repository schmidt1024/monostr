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

// failureTTL is how long a failed fetch is remembered: while monerod is down,
// GET / and /v1/info must not each wait for a fresh request (that would let
// any visitor queue requests behind the client's mutex). It stays below the
// personal poller's 15 s interval, so node failover counts real failures only.
const failureTTL = 10 * time.Second

type HeightClient struct {
	url  string
	http *http.Client
	now  func() time.Time
	mu   sync.Mutex
	last uint64
	at   time.Time
	// failure of the last fetch and when it happened (zero: none)
	failErr error
	failAt  time.Time
}

func NewHeightClient(rpcURL string) *HeightClient {
	return &HeightClient{
		url:  strings.TrimRight(rpcURL, "/") + "/json_rpc",
		http: &http.Client{Timeout: 10 * time.Second},
		now:  time.Now,
	}
}

// Height returns the cached height (60 s) or fetches it. On failure it
// returns the last known value together with the error; a failure is
// remembered for failureTTL (unless the caller's context ended).
func (c *HeightClient) Height(ctx context.Context) (uint64, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if !c.at.IsZero() && now.Sub(c.at) < cacheTTL {
		return c.last, nil
	}
	if c.failErr != nil && now.Sub(c.failAt) < failureTTL {
		return c.last, c.failErr
	}
	h, err := c.fetch(ctx)
	if err != nil {
		if ctx.Err() == nil {
			c.failErr, c.failAt = err, now
		}
		return c.last, err
	}
	c.failErr = nil
	c.last, c.at = h, now
	return c.last, nil
}

func (c *HeightClient) fetch(ctx context.Context) (uint64, error) {
	body := []byte(`{"jsonrpc":"2.0","id":"0","method":"get_info"}`)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.url, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return 0, fmt.Errorf("monerod get_info: HTTP %d", resp.StatusCode)
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
		return 0, err
	}
	if out.Error != nil {
		return 0, fmt.Errorf("monerod get_info: rpc error %d: %s", out.Error.Code, out.Error.Message)
	}
	if out.Result.Height == 0 {
		return 0, errors.New("monerod get_info: no height in response")
	}
	return out.Result.Height, nil
}
