// Package limits holds the configured upload limits and the per-IP day
// counter. The per-pubkey and the global numbers come from the store; the IP
// counter lives in memory only, so that no address is ever written to disk.
package limits

import (
	"net/netip"
	"sync"
	"time"
)

const (
	// DefaultMaxVideoSeconds is the longest video an ordinary account may upload;
	// it leaves room above the app's 121 s, as encoding may add a little.
	DefaultMaxVideoSeconds = 125
	// VideoSecondsCeiling and VideoBytesCeiling are what the scorer sidecar
	// accepts at most (spec 2026-11-19 media-trusted-video); no setting may pass them.
	VideoSecondsCeiling = 900
	VideoBytesCeiling   = 314572800 // 300 MiB
)

// Limits are the configured ceilings (spec section 4.3).
type Limits struct {
	MaxBytes          int64 // one file
	PubkeyPerDay      int   // uploads per pubkey and UTC day
	PubkeyQuota       int64 // bytes one pubkey may hold
	IPPerDay          int   // uploads per address and UTC day
	GlobalBytesPerDay int64 // newly stored bytes per UTC day, all users

	MaxVideoBytes      int64 // one video (spec 2026-10-27 video §6)
	MaxVideoSeconds    int   // longest video, in seconds
	PubkeyVideosPerDay int   // videos per pubkey and UTC day; they count toward PubkeyPerDay too
	StorageAlertBytes  int64 // the operator hears once when all stored bytes pass this; 0 = never
}

// Trusted are the video limits of the whitelisted pubkeys (spec 2026-11-19
// media-trusted-video). They are kept apart from Limits so that Limits stays comparable.
type Trusted struct {
	Pubkeys         map[string]bool // lowercase hex
	MaxVideoBytes   int64
	MaxVideoSeconds int
	VideosPerDay    int
	QuotaBytes      int64
}

// For is l with the four video values of t when pubkey is whitelisted, else l
// unchanged. Pictures, uploads per day, per address and per day in all stay as they are.
func (l Limits) For(pubkey string, t Trusted) Limits {
	if !t.Pubkeys[pubkey] {
		return l
	}
	l.MaxVideoBytes = t.MaxVideoBytes
	l.MaxVideoSeconds = t.MaxVideoSeconds
	l.PubkeyVideosPerDay = t.VideosPerDay
	l.PubkeyQuota = t.QuotaBytes
	return l
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
