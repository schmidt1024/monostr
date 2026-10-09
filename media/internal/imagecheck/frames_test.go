package imagecheck_test

import (
	"errors"
	"testing"

	"monostr.com/media/internal/imagecheck"
	"monostr.com/media/internal/imagetest"
)

func TestInspectCountsFrames(t *testing.T) {
	cases := map[string]struct {
		data     []byte
		declared string
		frames   int
	}{
		"jpeg":             {imagetest.JPEG(40, 30, 1), "image/jpeg", 1},
		"png":              {imagetest.PNG(40, 30, 1), "image/png", 1},
		"still gif":        {imagetest.GIF(40, 30, 1), "image/gif", 1},
		"animated gif":     {imagetest.GIF(40, 30, 4), "image/gif", 4},
		"still webp":       {imagetest.WebP(), "image/webp", 1},
		"animated webp":    {imagetest.WebPAnimated(), "image/webp", 4},
		"gif, tiny frames": {imagetest.GIFCanvas(500, 400, 25), "image/gif", 25},
		"webp container":   {imagetest.WebPCanvas(500, 400, 7), "image/webp", 7},
		"apng":             {imagetest.APNGHeader(500, 400, 9), "image/png", 9},
	}
	for name, c := range cases {
		got, err := imagecheck.Inspect(c.data, c.declared)
		if err != nil || got.Frames != c.frames {
			t.Errorf("%s: frames %d, err %v, want %d", name, got.Frames, err, c.frames)
		}
	}
}

// A few kilobytes can announce a huge canvas and thousands of frames; a
// decoder that composes every frame on the canvas would work for minutes.
func TestInspectRejectsAnimationsOverTheFramePixelBudget(t *testing.T) {
	for name, c := range map[string]struct {
		data     []byte
		declared string
	}{
		"gif: 2000 tiny frames on 7000 x 7000": {imagetest.GIFCanvas(7000, 7000, 2000), "image/gif"},
		"gif: 6 frames on 7000 x 7000":         {imagetest.GIFCanvas(7000, 7000, 6), "image/gif"},
		"webp: 100 frames on 4000 x 4000":      {imagetest.WebPCanvas(4000, 4000, 100), "image/webp"},
		"apng: 1000 frames on 2000 x 2000":     {imagetest.APNGHeader(2000, 2000, 1000), "image/png"},
	} {
		if len(c.data) > 64<<10 {
			t.Fatalf("%s: the test picture should be small, is %d bytes", name, len(c.data))
		}
		if _, err := imagecheck.Inspect(c.data, c.declared); !errors.Is(err, imagecheck.ErrTooManyPixels) {
			t.Errorf("%s: got %v, want ErrTooManyPixels", name, err)
		}
	}
	// ordinary animations stay inside: 300 frames of 800 x 600 are 144 million frame pixels
	if _, err := imagecheck.Inspect(imagetest.GIFCanvas(800, 600, 300), "image/gif"); err != nil {
		t.Errorf("300 frames of 800 x 600: %v", err)
	}
	// one frame of the largest still is fine as before
	if _, err := imagecheck.Inspect(imagetest.GIFCanvas(7000, 7000, 1), "image/gif"); err != nil {
		t.Errorf("one frame on 7000 x 7000: %v", err)
	}
}

// A GIF that a lenient decoder still reads must not hide frames from the count.
func TestFramesAreCountedPastOddBytes(t *testing.T) {
	gif := imagetest.GIFCanvas(7000, 7000, 2000)
	// a stray byte between the colour table and the first frame; decoders skip it
	odd := append(append(append([]byte{}, gif[:19]...), 0x00), gif[19:]...)
	if _, err := imagecheck.Inspect(odd, "image/gif"); !errors.Is(err, imagecheck.ErrTooManyPixels) {
		t.Errorf("stray byte: got %v, want ErrTooManyPixels", err)
	}
	// no trailer at the end
	if _, err := imagecheck.Inspect(gif[:len(gif)-1], "image/gif"); !errors.Is(err, imagecheck.ErrTooManyPixels) {
		t.Errorf("missing trailer: got %v, want ErrTooManyPixels", err)
	}
}
