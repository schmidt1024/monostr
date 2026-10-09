package ratelimit

import (
	"testing"
	"time"
)

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
