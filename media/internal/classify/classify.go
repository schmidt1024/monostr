// Package classify asks the NSFW scorer beside the server (container "nsfw")
// how a picture rates.
package classify

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

var (
	// ErrUnreadable: the scorer could not read the bytes as a picture.
	ErrUnreadable = errors.New("classify: unreadable picture")
	// ErrUnavailable: the scorer did not answer, or not in a usable way.
	ErrUnavailable = errors.New("classify: scorer unavailable")
)

// Client talks to one scorer.
type Client struct {
	base string
	http *http.Client
}

// New builds a client for the scorer at base (http://nsfw:8081); every call
// gives up after timeout.
func New(base string, timeout time.Duration) *Client {
	return &Client{base: strings.TrimRight(base, "/"), http: &http.Client{Timeout: timeout}}
}

// Score returns the NSFW probability (0..1) of the picture.
func (c *Client) Score(ctx context.Context, data []byte) (float64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/classify", bytes.NewReader(data))
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	res, err := c.http.Do(req)
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 4<<10))
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	switch {
	case res.StatusCode == http.StatusUnprocessableEntity:
		return 0, ErrUnreadable
	case res.StatusCode != http.StatusOK:
		return 0, fmt.Errorf("%w: status %d", ErrUnavailable, res.StatusCode)
	}
	var out struct {
		Score *float64 `json:"score"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.Score == nil || *out.Score < 0 || *out.Score > 1 {
		return 0, fmt.Errorf("%w: bad answer", ErrUnavailable)
	}
	return *out.Score, nil
}

// Health reports whether the scorer is up.
func (c *Client) Health(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+"/healthz", nil)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: status %d", ErrUnavailable, res.StatusCode)
	}
	return nil
}
