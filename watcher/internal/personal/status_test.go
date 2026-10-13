package personal

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

type fakePublisher struct {
	urls   []string
	events []nostr.Event
	ok     int
}

func (f *fakePublisher) Publish(_ context.Context, urls []string, ev nostr.Event) (int, []error) {
	f.urls, f.events = urls, append(f.events, ev)
	return f.ok, nil
}

func discardLog() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestStatusPublisherSignsAndPublishes(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	pub := &fakePublisher{ok: 1}
	s := &StatusPublisher{
		SecretKey: sk, Recipient: "rec", Address: "4abc", Network: "mainnet", Version: "0.4.0",
		Relays: []string{"wss://r1"}, Pool: pub, Log: discardLog(),
		Height: func(context.Context) (uint64, error) { return 42, nil },
		Now:    func() int64 { return 1700000000 },
	}
	if err := s.Publish(context.Background()); err != nil {
		t.Fatal(err)
	}
	ev := pub.events[0]
	if ok, _ := ev.CheckSignature(); !ok || ev.Kind != 10038 {
		t.Fatalf("event %+v", ev)
	}
	tag := ev.Tags.GetFirst([]string{"height"})
	if tag == nil || tag.Value() != "42" {
		t.Fatalf("height tag %v", tag)
	}
	if tag := ev.Tags.GetFirst([]string{"protocol"}); tag == nil || tag.Value() != "0.5" {
		t.Fatalf("protocol tag %v", tag)
	}
	if len(pub.urls) != 1 || pub.urls[0] != "wss://r1" {
		t.Fatalf("urls %v", pub.urls)
	}
}

func TestStatusPublisherFailsWhenNoRelayAccepts(t *testing.T) {
	s := &StatusPublisher{
		SecretKey: nostr.GeneratePrivateKey(), Relays: []string{"wss://r1"}, Pool: &fakePublisher{ok: 0}, Log: discardLog(),
		Height: func(context.Context) (uint64, error) { return 0, errors.New("down") },
		Now:    func() int64 { return 1 },
	}
	if err := s.Publish(context.Background()); err == nil {
		t.Fatal("no error")
	}
}
