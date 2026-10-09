// Package api is the watcher's HTTP surface: /v1/info, /v1/accounts and the
// internal monero-lws hook target.
package api

import (
	"context"

	"monostr.com/watcher/internal/lws"
)

type InfoResponse struct {
	Pubkey  string   `json:"pubkey"`
	Relays  []string `json:"relays"`
	Network string   `json:"network"`
	Height  uint64   `json:"height"`
	Version string   `json:"version"`
}

// HTTPError lets the service pick the status code of a client-visible error.
type HTTPError struct {
	Status  int
	Message string
}

func (e *HTTPError) Error() string { return e.Message }

type Service interface {
	Info(ctx context.Context) InfoResponse
	Register(ctx context.Context, pubkey, address, viewKeyHex string) error
	Unregister(ctx context.Context, pubkey string) error
	HandleHook(ctx context.Context, h lws.Hook) error
}
