package imagetest

import (
	"crypto/sha256"
	"testing"
)

func TestSeedsGiveDistinctBytes(t *testing.T) {
	seen := map[[32]byte]uint8{}
	for seed := uint8(0); seed < 40; seed++ {
		for _, data := range [][]byte{JPEG(40, 30, seed), PNG(40, 30, seed)} {
			sum := sha256.Sum256(data)
			if other, dup := seen[sum]; dup {
				t.Fatalf("seed %d gives the same bytes as seed %d", seed, other)
			}
			seen[sum] = seed
		}
	}
}
