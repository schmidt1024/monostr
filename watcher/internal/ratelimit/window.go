// Package ratelimit is a tiny in-memory sliding-window limiter.
package ratelimit

import (
	"container/list"
	"sync"
	"time"
)

// DefaultMaxKeys bounds how many keys a Window tracks at once.
const DefaultMaxKeys = 100_000

type entry struct {
	key  string
	hits []time.Time
}

// Window keeps the hits of each key in a list ordered by last recorded hit
// (least recent first): idle keys are pruned from the front and, when the
// map is full, the least recently hit key is evicted for a new one, so a
// flood of fresh keys can never lock out other callers. Every operation is
// O(limit) amortised.
type Window struct {
	limit   int
	per     time.Duration
	maxKeys int
	mu      sync.Mutex
	keys    map[string]*list.Element
	order   *list.List // of *entry, least recently hit first
}

func New(limit int, per time.Duration) *Window {
	return &Window{limit: limit, per: per, maxKeys: DefaultMaxKeys, keys: map[string]*list.Element{}, order: list.New()}
}

// WithMaxKeys sets how many keys are tracked at most; beyond that the least
// recently hit key is forgotten.
func (w *Window) WithMaxKeys(n int) *Window {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.maxKeys = max(n, 1)
	return w
}

// Allow records a hit for key at now and reports whether it is within the limit.
func (w *Window) Allow(key string, now time.Time) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.check(key, now, true)
}

// Allowed reports whether a hit for key at now would be within the limit,
// without recording it.
func (w *Window) Allowed(key string, now time.Time) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.check(key, now, false)
}

func (w *Window) check(key string, now time.Time, record bool) bool {
	cutoff := now.Add(-w.per)
	w.pruneIdle(cutoff)
	el, known := w.keys[key]
	var e *entry
	if known {
		e = el.Value.(*entry)
		kept := e.hits[:0]
		for _, t := range e.hits {
			if t.After(cutoff) {
				kept = append(kept, t)
			}
		}
		e.hits = kept
		if len(kept) >= w.limit {
			return false
		}
	} else if w.limit <= 0 {
		return false
	}
	if !record {
		return true
	}
	if !known {
		for len(w.keys) >= w.maxKeys {
			w.remove(w.order.Front())
		}
		e = &entry{key: key}
		el = w.order.PushBack(e)
		w.keys[key] = el
	} else {
		w.order.MoveToBack(el)
	}
	e.hits = append(e.hits, now)
	return true
}

// pruneIdle drops keys from the front while their last hit is outside the
// window; it stops at the first key still active.
func (w *Window) pruneIdle(cutoff time.Time) {
	for el := w.order.Front(); el != nil; el = w.order.Front() {
		e := el.Value.(*entry)
		if len(e.hits) > 0 && e.hits[len(e.hits)-1].After(cutoff) {
			return
		}
		w.remove(el)
	}
}

func (w *Window) remove(el *list.Element) {
	delete(w.keys, el.Value.(*entry).key)
	w.order.Remove(el)
}

// Len is the number of tracked keys (for tests).
func (w *Window) Len() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.keys)
}
