package nostrdm

import (
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"monostr.com/media/internal/review"
)

// TestListenAgainstARealRelay sends a gift wrap from a throwaway operator to a
// throwaway server key on a real relay and expects Listen to hand it over.
// relay.monostr.com only serves kind 1059 after NIP-42 authentication and
// words its refusal "ERROR: auth-required: …", which go-nostr's pool does not
// recognise; this is what proves Listen authenticates anyway.
//
//	NOSTRDM_TEST_RELAY=wss://relay.monostr.com go test ./internal/nostrdm/ -run RealRelay -v
func TestListenAgainstARealRelay(t *testing.T) {
	relay := os.Getenv("NOSTRDM_TEST_RELAY")
	if relay == "" {
		t.Skip("NOSTRDM_TEST_RELAY not set")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	server, operator := newParty(t), newParty(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	c, err := New(ctx, server.sec, operator.pub, []string{relay}, "https://media.example", logger)
	if err != nil {
		t.Fatal(err)
	}
	got := make(chan review.Inbound, 1)
	go c.Listen(ctx, func() int64 { return 0 }, func(_ context.Context, in review.Inbound) {
		select {
		case got <- in:
		default:
		}
	})

	// the operator's side: a plain client publishing the reply to the server
	o, err := New(ctx, operator.sec, server.pub, []string{relay}, "https://media.example", logger)
	if err != nil {
		t.Fatal(err)
	}
	wrap, err := wrapFor(ctx, operator.kr, server.pub, "weg")
	if err != nil {
		t.Fatal(err)
	}
	if err := o.publish(ctx, []string{relay}, wrap); err != nil {
		t.Fatalf("publish: %v", err)
	}

	select {
	case in := <-got:
		if in.Sender != operator.pub || in.Content != "weg" || in.Kind != 14 {
			t.Fatalf("got %+v", in)
		}
	case <-ctx.Done():
		t.Fatal("Listen never handed over the gift wrap")
	}
}
