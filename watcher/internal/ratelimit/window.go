// Package ratelimit is a tiny in-memory sliding-window limiter.
package ratelimit

import (
	"sync"
	"time"
)

type Window struct {
	limit int
	per   time.Duration
	mu    sync.Mutex
	hits  map[string][]time.Time
}

func New(limit int, per time.Duration) *Window {
	return &Window{limit: limit, per: per, hits: map[string][]time.Time{}}
}

// Allow records a hit for key at now and reports whether it is within the limit.
func (w *Window) Allow(key string, now time.Time) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	cutoff := now.Add(-w.per)
	// drop idle keys so the map cannot grow without bound
	for k, ts := range w.hits {
		if k != key && len(ts) > 0 && !ts[len(ts)-1].After(cutoff) {
			delete(w.hits, k)
		}
	}
	ts := w.hits[key]
	kept := ts[:0]
	for _, t := range ts {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= w.limit {
		w.hits[key] = kept
		return false
	}
	w.hits[key] = append(kept, now)
	return true
}

// Len is the number of tracked keys (for tests).
func (w *Window) Len() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return len(w.hits)
}
