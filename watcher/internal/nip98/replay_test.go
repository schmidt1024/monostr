package nip98

import (
	"strconv"
	"testing"
)

func TestReplayCacheRejectsRepeatsWithinWindow(t *testing.T) {
	c := NewReplayCache(10)
	if !c.Add("a", 1000) {
		t.Fatal("first use rejected")
	}
	if c.Add("a", 1000+2*MaxSkew-1) {
		t.Fatal("replay accepted inside 2 x MaxSkew")
	}
	// once expired (Verify rejects the event by then anyway) the id is forgotten
	if !c.Add("b", 1000+2*MaxSkew) || c.Len() != 1 {
		t.Fatalf("expired id kept: %d", c.Len())
	}
}

func TestReplayCacheBounded(t *testing.T) {
	c := NewReplayCache(3)
	for i := 0; i < 5; i++ {
		if !c.Add(strconv.Itoa(i), 1000) {
			t.Fatalf("id %d rejected", i)
		}
	}
	if c.Len() != 3 {
		t.Fatalf("len %d", c.Len())
	}
	if c.Add("4", 1000) {
		t.Fatal("newest id forgotten")
	}
}
