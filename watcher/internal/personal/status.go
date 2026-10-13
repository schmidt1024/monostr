package personal

import (
	"context"
	"errors"
	"log/slog"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/protocol"
)

type Publisher interface {
	Publish(ctx context.Context, urls []string, ev nostr.Event) (int, []error)
}

// StatusPublisher publishes the watcher status (kind 10038) on the watcher relays.
type StatusPublisher struct {
	SecretKey string
	Recipient string
	Address   string
	Network   string
	Version   string
	Relays    []string
	Pool      Publisher
	Height    func(context.Context) (uint64, error)
	Now       func() int64
	Log       *slog.Logger
}

// Publish sends one status. A height error is logged and the height returned
// alongside it (normally 0) is sent.
func (s *StatusPublisher) Publish(ctx context.Context) error {
	height, err := s.Height(ctx)
	if err != nil {
		s.Log.Warn("status: height unavailable", "err", err.Error())
	}
	ev := protocol.BuildStatus(s.Recipient, s.Address, s.Network, height, s.Version, s.Now())
	if err := ev.Sign(s.SecretKey); err != nil {
		return err
	}
	ok, errs := s.Pool.Publish(ctx, s.Relays, ev)
	for _, e := range errs {
		s.Log.Warn("status publish", "err", e.Error())
	}
	if ok == 0 {
		return errors.New("status not accepted by any relay")
	}
	return nil
}
