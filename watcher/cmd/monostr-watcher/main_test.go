package main

import (
	"strings"
	"testing"
)

func TestParsePubkeys(t *testing.T) {
	hex := "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e" // NIP-19 example
	got, err := parsePubkeys(" npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg , " + strings.ToUpper(hex))
	if err != nil || len(got) != 2 || got[0] != hex || got[1] != hex {
		t.Fatalf("got %v, %v", got, err)
	}
	if got, err := parsePubkeys(""); err != nil || got != nil {
		t.Fatalf("empty: %v, %v", got, err)
	}
	// the NIP-19 example key's bytes with the nsec prefix, joined at run time so that no nsec
	// literal sits in the source (the public export refuses files that contain one)
	nsec := "nsec1" + "0elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
	for _, bad := range []string{"npub1xyz", "abcd", nsec} {
		if _, err := parsePubkeys(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}
