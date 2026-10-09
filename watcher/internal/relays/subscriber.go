package relays

import (
	"context"
	"sync"

	"github.com/nbd-wtf/go-nostr"
)

// Subscriber keeps one live subscription per relay URL and funnels events
// into out. Reconcile adds and removes relays without touching the others.
type Subscriber struct {
	pool   *Pool
	filter nostr.Filter
	out    chan<- *nostr.Event
	mu     sync.Mutex
	subs   map[string]context.CancelFunc
	closed bool
}

func NewSubscriber(pool *Pool, filter nostr.Filter, out chan<- *nostr.Event) *Subscriber {
	return &Subscriber{pool: pool, filter: filter, out: out, subs: map[string]context.CancelFunc{}}
}

func (s *Subscriber) Reconcile(urls []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	want := map[string]bool{}
	for _, u := range urls {
		want[u] = true
	}
	for u, cancel := range s.subs {
		if !want[u] {
			cancel()
			delete(s.subs, u)
		}
	}
	for u := range want {
		if _, ok := s.subs[u]; ok {
			continue
		}
		ctx, cancel := context.WithCancel(context.Background())
		s.subs[u] = cancel
		go s.run(ctx, u)
	}
}

func (s *Subscriber) run(ctx context.Context, url string) {
	// SimplePool reconnects on its own; the loop ends when ctx is cancelled.
	for ev := range s.pool.pool.SubscribeMany(ctx, []string{url}, s.filter) {
		if ev.Event == nil {
			continue
		}
		select {
		case s.out <- ev.Event:
		case <-ctx.Done():
			return
		}
	}
}

func (s *Subscriber) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	for u, cancel := range s.subs {
		cancel()
		delete(s.subs, u)
	}
}
