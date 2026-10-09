package classify

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"monostr.com/media/internal/imagetest"
)

// TestAgainstARunningScorer checks the contract with the real container
// "nsfw". It runs only when NSFW_URL names one, e.g.
//
//	NSFW_URL=http://127.0.0.1:8081 go test ./internal/classify/ -run Running -v
func TestAgainstARunningScorer(t *testing.T) {
	base := os.Getenv("NSFW_URL")
	if base == "" {
		t.Skip("NSFW_URL not set")
	}
	c := New(base, 10*time.Second)
	if err := c.Health(context.Background()); err != nil {
		t.Fatal(err)
	}
	for name, data := range map[string][]byte{
		"jpeg": imagetest.JPEG(400, 300, 1), "png": imagetest.PNG(400, 300, 1),
		"gif": imagetest.GIF(40, 30, 12), "webp": imagetest.WebPAnimated(),
	} {
		// the test pictures are flat colours, on which the model has no firm
		// opinion: only the contract is checked here, not the value
		score, err := c.Score(context.Background(), data)
		if err != nil || score < 0 || score > 1 {
			t.Errorf("%s: score %v, err %v", name, score, err)
		}
	}
	if _, err := c.Score(context.Background(), []byte("no picture")); !errors.Is(err, ErrUnreadable) {
		t.Errorf("garbage: %v", err)
	}
	// a header that claims a picture the decoder then cannot deliver
	if _, err := c.Score(context.Background(), imagetest.PNGHeader(100, 100)); !errors.Is(err, ErrUnreadable) {
		t.Errorf("truncated png: %v", err)
	}
}
