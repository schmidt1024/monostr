package main

import (
	"path/filepath"
	"strings"
	"testing"

	"monostr.com/watcher/internal/store"
)

func TestLoadHookSecretIsGeneratedOnceAndKept(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "w.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	first, err := loadHookSecret(st, "")
	if err != nil || !validHookSecret(first) {
		t.Fatalf("generated %q %v", first, err)
	}
	again, _ := loadHookSecret(st, "")
	if again != first {
		t.Fatal("secret not kept: existing webhooks would break on restart")
	}
	configured := strings.Repeat("A", 32)
	if got, _ := loadHookSecret(st, configured); got != configured {
		t.Fatalf("configured secret ignored: %q", got)
	}
}

func TestValidHookSecret(t *testing.T) {
	for s, ok := range map[string]bool{
		strings.Repeat("a", 32):       true,
		strings.Repeat("a", 31):       false,
		strings.Repeat("a", 31) + "/": false,
		strings.Repeat("a", 31) + "?": false,
		strings.Repeat("aZ0-_", 8):    true,
	} {
		if validHookSecret(s) != ok {
			t.Errorf("%q: want %v", s, ok)
		}
	}
}
