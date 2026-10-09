// Package limits holds the configured upload limits and the per-IP day
// counter. The per-pubkey and the global numbers come from the store; the IP
// counter lives in memory only, so that no address is ever written to disk.
package limits

import (
	"net/netip"
	"sync"
	"time"
)

// Limits are the configured ceilings (spec section 4.3).
type Limits struct {
	MaxBytes          int64 // one file
	PubkeyPerDay      int   // uploads per pubkey and UTC day
	PubkeyQuota       int64 // bytes one pubkey may hold
	IPPerDay          int   // uploads per address and UTC day
	GlobalBytesPerDay int64 // newly stored bytes per UTC day, all users
}

// DayStart is the unix time at which now's UTC day began.
func DayStart(now time.Time) int64 {
	y, m, d := now.UTC().Date()
	return time.Date(y, m, d, 0, 0, 0, 0, time.UTC).Unix()
}

// IPKey is the counting key for a client address: an IPv4 address as it is,
// an IPv6 address reduced to its /64. Anything unparsable counts under one
// shared key.
func IPKey(addr string) string {
	ip, err := netip.ParseAddr(addr)
	if err != nil {
		return "unknown"
	}
	ip = ip.Unmap()
	if ip.Is4() {
		return ip.String()
	}
	prefix, err := ip.Prefix(64)
	if err != nil {
		return "unknown"
	}
	return prefix.String()
}

// Daily counts events per key within the current UTC day and forgets
// everything when the day changes.
type Daily struct {
	mu     sync.Mutex
	day    int64
	counts map[string]int
}

func NewDaily() *Daily { return &Daily{counts: map[string]int{}} }

func (d *Daily) roll(now time.Time) {
	if day := DayStart(now); day != d.day {
		d.day = day
		d.counts = map[string]int{}
	}
}

// Count is the number of events recorded for key today.
func (d *Daily) Count(key string, now time.Time) int {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.roll(now)
	return d.counts[key]
}

// Add records one event for key.
func (d *Daily) Add(key string, now time.Time) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.roll(now)
	d.counts[key]++
}
