package monero

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

type vector struct {
	ViewSecret string `json:"view_secret"`
	SpendPub   string `json:"spend_public"`
	ViewPub    string `json:"view_public"`
	Mainnet    string `json:"mainnet"`
	Stagenet   string `json:"stagenet"`
	Integrated []struct {
		Mainnet  string `json:"mainnet"`
		Stagenet string `json:"stagenet"`
	} `json:"integrated"`
}

func loadVectors(t *testing.T) []vector {
	t.Helper()
	var f struct {
		Vectors []vector `json:"vectors"`
	}
	b, err := os.ReadFile("testdata/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Vectors) == 0 {
		t.Fatal("no vectors")
	}
	return f.Vectors
}

func TestParseStandardAddressVectors(t *testing.T) {
	for i, v := range loadVectors(t) {
		cases := []struct {
			s string
			n Network
		}{{v.Mainnet, Mainnet}, {v.Stagenet, Stagenet}}
		for _, c := range cases {
			a, err := ParseStandardAddress(c.s)
			if err != nil {
				t.Fatalf("vector %d: %v", i, err)
			}
			if a.Network != c.n || a.Encoded != c.s {
				t.Fatalf("vector %d: network/encoded mismatch", i)
			}
			if hex.EncodeToString(a.Spend[:]) != v.SpendPub || hex.EncodeToString(a.View[:]) != v.ViewPub {
				t.Fatalf("vector %d: key mismatch", i)
			}
		}
	}
}

func TestViewKeyMatchesVectors(t *testing.T) {
	for i, v := range loadVectors(t) {
		a, _ := ParseStandardAddress(v.Mainnet)
		vs, _ := hex.DecodeString(v.ViewSecret)
		if !ViewKeyMatches(vs, a.View) {
			t.Fatalf("vector %d: view key rejected", i)
		}
		wrong := append([]byte(nil), vs...)
		wrong[0] ^= 1
		if ViewKeyMatches(wrong, a.View) {
			t.Fatalf("vector %d: wrong view key accepted", i)
		}
	}
	if ViewKeyMatches(make([]byte, 31), [32]byte{}) {
		t.Fatal("short key accepted")
	}
	nonCanonical := make([]byte, 32)
	for i := range nonCanonical {
		nonCanonical[i] = 0xff
	}
	if ViewKeyMatches(nonCanonical, [32]byte{}) {
		t.Fatal("non-canonical scalar accepted")
	}
}

func TestParseStandardAddressRejects(t *testing.T) {
	v := loadVectors(t)[0]
	bad := map[string]string{
		"integrated mainnet":  v.Integrated[0].Mainnet,
		"integrated stagenet": v.Integrated[0].Stagenet,
		"empty":               "",
		"bad char":            v.Mainnet[:10] + "0" + v.Mainnet[11:],
		"bad checksum":        v.Mainnet[:94] + "1",
		"truncated":           v.Mainnet[:94],
		"garbage":             "4" + v.Mainnet,
	}
	for name, s := range bad {
		if _, err := ParseStandardAddress(s); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	// integrated addresses decode fine but are not standard
	if _, err := ParseStandardAddress(v.Integrated[0].Mainnet); err != ErrNotStandardAddress {
		t.Errorf("integrated: want ErrNotStandardAddress, got %v", err)
	}
}

func TestParseNetwork(t *testing.T) {
	for s, want := range map[string]Network{"mainnet": Mainnet, "stagenet": Stagenet} {
		got, err := ParseNetwork(s)
		if err != nil || got != want || got.String() != s {
			t.Fatalf("%s: got %v %v", s, got, err)
		}
	}
	if _, err := ParseNetwork("testnet"); err == nil {
		t.Fatal("testnet accepted")
	}
}
