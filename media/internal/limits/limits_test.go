package limits

import (
	"strings"
	"testing"
	"time"
)

func TestDayStart(t *testing.T) {
	late := time.Date(2026, 10, 2, 23, 59, 59, 0, time.UTC)
	if got := DayStart(late); got != time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC).Unix() {
		t.Fatalf("day start: %d", got)
	}
	// a local time that is already the next day in UTC
	berlin := time.FixedZone("CEST", 2*3600)
	if got := DayStart(time.Date(2026, 10, 3, 1, 0, 0, 0, berlin)); got != time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC).Unix() {
		t.Fatalf("day start across zones: %d", got)
	}
}

func TestIPKey(t *testing.T) {
	cases := map[string]string{
		"203.0.113.7":          "203.0.113.7",
		"::ffff:203.0.113.7":   "203.0.113.7",
		"2001:db8:1:2:3:4:5:6": "2001:db8:1:2::/64",
		"2001:db8:1:2:ffff::1": "2001:db8:1:2::/64",
		"2001:db8:1:3::1":      "2001:db8:1:3::/64",
		"not an address":       "unknown",
		"":                     "unknown",
		"203.0.113.7:4711":     "unknown",
		"fe80::1%eth0":         "fe80::/64",
	}
	for in, want := range cases {
		if got := IPKey(in); got != want {
			t.Errorf("IPKey(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestDailyCountsPerKeyAndForgetsAtMidnightUTC(t *testing.T) {
	d := NewDaily()
	noon := time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)
	if d.Count("a", noon) != 0 {
		t.Fatal("fresh counter is not zero")
	}
	d.Add("a", noon)
	d.Add("a", noon.Add(time.Hour))
	d.Add("b", noon)
	if d.Count("a", noon.Add(2*time.Hour)) != 2 || d.Count("b", noon) != 1 {
		t.Fatalf("counts: a=%d b=%d", d.Count("a", noon), d.Count("b", noon))
	}
	next := time.Date(2026, 10, 3, 0, 0, 1, 0, time.UTC)
	if d.Count("a", next) != 0 {
		t.Fatal("yesterday's count survived midnight")
	}
	d.Add("a", next)
	if d.Count("a", next) != 1 {
		t.Fatal("count on the new day")
	}
}

func TestForSwapsTheFourVideoValuesForATrustedPubkey(t *testing.T) {
	aa, bb := strings.Repeat("aa", 32), strings.Repeat("bb", 32)
	def := Limits{MaxBytes: 10, PubkeyPerDay: 50, PubkeyQuota: 2 << 30, MaxVideoBytes: 50 << 20, MaxVideoSeconds: 125,
		PubkeyVideosPerDay: 5, IPPerDay: 100, GlobalBytesPerDay: 20 << 30}
	tr := Trusted{Pubkeys: map[string]bool{aa: true}, MaxVideoBytes: 300 << 20, MaxVideoSeconds: 900, VideosPerDay: 20, QuotaBytes: 20 << 30}
	want := def
	want.MaxVideoBytes, want.MaxVideoSeconds, want.PubkeyVideosPerDay, want.PubkeyQuota = 300<<20, 900, 20, 20<<30
	if got := def.For(aa, tr); got != want {
		t.Fatalf("trusted: %+v", got)
	}
	if got := def.For(bb, tr); got != def {
		t.Fatalf("other: %+v", got)
	}
	if got := def.For(aa, Trusted{}); got != def {
		t.Fatalf("no whitelist: %+v", got)
	}
}
