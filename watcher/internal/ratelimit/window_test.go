package ratelimit

import (
	"strconv"
	"testing"
	"time"
)

// TestWindowFullMapNeverLocksOutCallers: junk keys filling the map evict the
// least recently hit keys; a fresh key and a known key are still allowed and
// the known key keeps its limit.
func TestWindowFullMapNeverLocksOutCallers(t *testing.T) {
	w := New(2, time.Hour).WithMaxKeys(100)
	t0 := time.Unix(1700000000, 0)
	if !w.Allow("known", t0) {
		t.Fatal("known key denied")
	}
	for i := 0; i < 99; i++ {
		w.Allow("junk"+strconv.Itoa(i), t0.Add(time.Second))
	}
	if w.Len() != 100 {
		t.Fatalf("len %d", w.Len())
	}
	if !w.Allow("known", t0.Add(2*time.Second)) {
		t.Fatal("known key denied with the map full")
	}
	if w.Allow("known", t0.Add(3*time.Second)) {
		t.Fatal("known key's limit lost")
	}
	if !w.Allow("fresh", t0.Add(4*time.Second)) {
		t.Fatal("fresh key denied with the map full")
	}
	if w.Len() != 100 {
		t.Fatalf("map grew past its cap: %d", w.Len())
	}
	if _, ok := w.keys["junk0"]; ok {
		t.Fatal("least recently hit key not evicted")
	}
}

func TestWindowPrunesIdleKeysWithoutFullScans(t *testing.T) {
	w := New(1, time.Minute)
	t0 := time.Unix(1700000000, 0)
	for i := 0; i < 1000; i++ {
		w.Allow(strconv.Itoa(i), t0)
	}
	w.Allow("late", t0.Add(2*time.Minute))
	if w.Len() != 1 {
		t.Fatalf("idle keys kept: %d", w.Len())
	}
}

func TestWindowAllowedDoesNotRecord(t *testing.T) {
	w := New(1, time.Hour)
	t0 := time.Unix(1700000000, 0)
	for i := 0; i < 3; i++ {
		if !w.Allowed("a", t0) {
			t.Fatal("peek denied")
		}
	}
	if w.Len() != 0 {
		t.Fatal("peek tracked a key")
	}
	if !w.Allow("a", t0) || w.Allowed("a", t0) || w.Allow("a", t0) {
		t.Fatal("limit not applied after the recorded hit")
	}
}

func BenchmarkWindowManyKeys(b *testing.B) {
	w := New(3, time.Hour)
	t0 := time.Unix(1700000000, 0)
	for i := 0; i < 50_000; i++ {
		w.Allow(strconv.Itoa(i), t0)
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		w.Allow("k"+strconv.Itoa(i%1000), t0.Add(time.Second))
	}
}

func TestWindow(t *testing.T) {
	w := New(3, time.Hour)
	t0 := time.Unix(1700000000, 0)
	for i := 0; i < 3; i++ {
		if !w.Allow("a", t0.Add(time.Duration(i)*time.Minute)) {
			t.Fatalf("hit %d denied", i)
		}
	}
	if w.Allow("a", t0.Add(10*time.Minute)) {
		t.Fatal("4th hit allowed")
	}
	if !w.Allow("b", t0) {
		t.Fatal("other key denied")
	}
	// first hit falls out of the window after an hour
	if !w.Allow("a", t0.Add(time.Hour+time.Second)) {
		t.Fatal("not allowed after window passed")
	}
	if w.Allow("a", t0.Add(time.Hour+2*time.Second)) {
		t.Fatal("window not sliding")
	}
}

func TestWindowForgetsIdleKeys(t *testing.T) {
	w := New(1, time.Minute)
	t0 := time.Unix(1700000000, 0)
	w.Allow("x", t0)
	w.Allow("y", t0.Add(2*time.Minute))
	if n := w.Len(); n != 1 {
		t.Fatalf("idle key kept: %d", n)
	}
}
