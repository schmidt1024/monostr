package protocol

import (
	"reflect"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

func TestBuildStatus(t *testing.T) {
	recipient := strings.Repeat("a", 64)
	ev := BuildStatus(recipient, "4abc", "mainnet", 3512345, "0.4.0", 1700000000)
	if ev.Kind != 10038 || ev.CreatedAt != 1700000000 || ev.Content != "" {
		t.Fatalf("event %+v", ev)
	}
	want := nostr.Tags{
		{"p", recipient},
		{"address", "4abc"},
		{"network", "mainnet"},
		{"height", "3512345"},
		{"version", "0.4.0"},
		{"protocol", "0.5"},
	}
	if !reflect.DeepEqual(ev.Tags, want) {
		t.Fatalf("tags %v", ev.Tags)
	}
}
