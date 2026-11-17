package protocol

import (
	"encoding/hex"
	"testing"
)

// Shared with the app (PaymentIdTest) and the spec (tips 0.6 changelog).
func TestBoundPIDVectors(t *testing.T) {
	nonce, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f")
	for pub, want := range map[string]string{
		"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798": "bc14ada49f3e78d6",
		"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5": "4b827455c05329d0",
	} {
		got, err := BoundPID(pub, nonce)
		if err != nil || got != want {
			t.Fatalf("BoundPID(%s) = %q, %v; want %q", pub, got, err, want)
		}
	}
}

func TestBoundPIDRejectsWrongSizes(t *testing.T) {
	nonce := make([]byte, 16)
	if _, err := BoundPID("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f817", nonce); err == nil {
		t.Fatal("31-byte pubkey accepted")
	}
	if _, err := BoundPID("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", nonce[:15]); err == nil {
		t.Fatal("15-byte nonce accepted")
	}
}
