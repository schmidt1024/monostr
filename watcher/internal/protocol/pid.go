package protocol

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
)

// pidPrefix starts the hash a bound payment id is taken from (tips 0.6).
const pidPrefix = "monostr-tips/pid"

// PIDNonceSize is the byte length of the pid_nonce tag value.
const PIDNonceSize = 16

// BoundPID derives the payment id of an intent signed by pubkeyHex with nonce:
// the first 8 bytes of sha256("monostr-tips/pid" || pubkey || nonce), as 16 hex
// chars. A pid copied into an intent under another key does not match it.
func BoundPID(pubkeyHex string, nonce []byte) (string, error) {
	pub, err := hex.DecodeString(pubkeyHex)
	if err != nil || len(pub) != 32 {
		return "", errors.New("pubkey must be 32 bytes")
	}
	if len(nonce) != PIDNonceSize {
		return "", errors.New("pid nonce must be 16 bytes")
	}
	h := sha256.New()
	h.Write([]byte(pidPrefix))
	h.Write(pub)
	h.Write(nonce)
	return hex.EncodeToString(h.Sum(nil)[:8]), nil
}
