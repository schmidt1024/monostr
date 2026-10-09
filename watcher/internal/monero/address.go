// Package monero holds the minimal Monero primitives the watcher needs:
// standard-address parsing and the view-key ↔ address check.
package monero

import (
	"bytes"
	"errors"
	"fmt"
	"math/big"

	"filippo.io/edwards25519"
	"golang.org/x/crypto/sha3"
)

type Network int

const (
	Mainnet Network = iota
	Stagenet
)

func ParseNetwork(s string) (Network, error) {
	switch s {
	case "mainnet":
		return Mainnet, nil
	case "stagenet":
		return Stagenet, nil
	}
	return 0, fmt.Errorf("unknown network %q", s)
}

func (n Network) String() string {
	if n == Stagenet {
		return "stagenet"
	}
	return "mainnet"
}

// Address is a parsed standard (primary) Monero address.
type Address struct {
	Network Network
	Spend   [32]byte
	View    [32]byte
	Encoded string
}

var (
	ErrInvalidAddress     = errors.New("invalid monero address")
	ErrNotStandardAddress = errors.New("not a standard monero address")
)

const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// encodedBlockSizes[n] is the number of base58 characters that encode n bytes.
var encodedBlockSizes = []int{0, 2, 3, 5, 6, 7, 9, 10, 11}

const (
	netMainnetStandard  = 18
	netStagenetStandard = 24
)

func decodeBlock(block string, size int) ([]byte, error) {
	n := new(big.Int)
	for i := 0; i < len(block); i++ {
		idx := bytes.IndexByte([]byte(alphabet), block[i])
		if idx < 0 {
			return nil, ErrInvalidAddress
		}
		n.Mul(n, big.NewInt(58))
		n.Add(n, big.NewInt(int64(idx)))
	}
	b := n.Bytes()
	if len(b) > size {
		return nil, ErrInvalidAddress
	}
	out := make([]byte, size)
	copy(out[size-len(b):], b)
	return out, nil
}

// Base58Decode decodes Monero's block-wise base58 (8-byte blocks → 11 chars).
func Base58Decode(s string) ([]byte, error) {
	if s == "" {
		return nil, ErrInvalidAddress
	}
	var out []byte
	for len(s) > 0 {
		blk := s
		if len(blk) > 11 {
			blk = s[:11]
		}
		size := -1
		for i, es := range encodedBlockSizes {
			if es == len(blk) {
				size = i
			}
		}
		if size <= 0 {
			return nil, ErrInvalidAddress
		}
		b, err := decodeBlock(blk, size)
		if err != nil {
			return nil, err
		}
		out = append(out, b...)
		s = s[len(blk):]
	}
	return out, nil
}

// ParseStandardAddress accepts only primary addresses on mainnet or stagenet.
// Subaddresses and integrated addresses yield ErrNotStandardAddress.
func ParseStandardAddress(s string) (Address, error) {
	raw, err := Base58Decode(s)
	if err != nil {
		return Address{}, err
	}
	if len(raw) < 5 {
		return Address{}, ErrInvalidAddress
	}
	body, sum := raw[:len(raw)-4], raw[len(raw)-4:]
	h := sha3.NewLegacyKeccak256()
	h.Write(body)
	if !bytes.Equal(h.Sum(nil)[:4], sum) {
		return Address{}, ErrInvalidAddress
	}
	var a Address
	switch body[0] {
	case netMainnetStandard:
		a.Network = Mainnet
	case netStagenetStandard:
		a.Network = Stagenet
	default:
		return Address{}, ErrNotStandardAddress
	}
	if len(body) != 65 {
		return Address{}, ErrInvalidAddress
	}
	copy(a.Spend[:], body[1:33])
	copy(a.View[:], body[33:65])
	a.Encoded = s
	return a, nil
}

// ViewKeyMatches reports whether viewSecret·G equals viewPublic.
// A view key that is not a canonical 32-byte scalar never matches.
func ViewKeyMatches(viewSecret []byte, viewPublic [32]byte) bool {
	if len(viewSecret) != 32 {
		return false
	}
	s, err := new(edwards25519.Scalar).SetCanonicalBytes(viewSecret)
	if err != nil {
		return false
	}
	p := new(edwards25519.Point).ScalarBaseMult(s)
	return bytes.Equal(p.Bytes(), viewPublic[:])
}
