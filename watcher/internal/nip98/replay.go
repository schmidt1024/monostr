package nip98

import (
	"container/list"
	"sync"
)

// DefaultReplayCapacity bounds how many event ids a ReplayCache keeps.
const DefaultReplayCapacity = 200_000

// ReplayCache remembers the ids of accepted authorization events for
// 2 × MaxSkew, the longest any of them verifies, so a captured header
// cannot be sent a second time. Ids are kept in arrival order: expired
// ones are dropped from the front and, when full, the oldest is evicted
// (it is the closest to expiry anyway).
type ReplayCache struct {
	mu       sync.Mutex
	capacity int
	seen     map[string]*list.Element
	order    *list.List // of seenID, oldest first
}

type seenID struct {
	id      string
	expires int64
}

func NewReplayCache(capacity int) *ReplayCache {
	return &ReplayCache{capacity: max(capacity, 1), seen: map[string]*list.Element{}, order: list.New()}
}

// Add records id at now and reports whether it is new; false means replay.
func (c *ReplayCache) Add(id string, now int64) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for el := c.order.Front(); el != nil && el.Value.(seenID).expires <= now; el = c.order.Front() {
		c.remove(el)
	}
	if _, dup := c.seen[id]; dup {
		return false
	}
	for len(c.seen) >= c.capacity {
		c.remove(c.order.Front())
	}
	c.seen[id] = c.order.PushBack(seenID{id: id, expires: now + 2*MaxSkew})
	return true
}

func (c *ReplayCache) remove(el *list.Element) {
	delete(c.seen, el.Value.(seenID).id)
	c.order.Remove(el)
}

// Len is the number of remembered ids (for tests).
func (c *ReplayCache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.seen)
}
