package imagecheck_test

import (
	"errors"
	"testing"

	"monostr.com/media/internal/imagecheck"
	"monostr.com/media/internal/imagetest"
)

func TestInspectAcceptsEveryFormat(t *testing.T) {
	cases := []struct {
		name     string
		data     []byte
		declared string
		want     imagecheck.Info
	}{
		{"jpeg", imagetest.JPEG(40, 30, 1), "image/jpeg", imagecheck.Info{Type: "image/jpeg", Ext: "jpg", Width: 40, Height: 30}},
		{"jpeg declared as image/jpg", imagetest.JPEG(40, 30, 1), "image/jpg", imagecheck.Info{Type: "image/jpeg", Ext: "jpg", Width: 40, Height: 30}},
		{"jpeg with parameters", imagetest.JPEG(40, 30, 1), "Image/JPEG; charset=binary", imagecheck.Info{Type: "image/jpeg", Ext: "jpg", Width: 40, Height: 30}},
		{"png", imagetest.PNG(40, 30, 1), "image/png", imagecheck.Info{Type: "image/png", Ext: "png", Width: 40, Height: 30}},
		{"gif", imagetest.GIF(40, 30, 1), "image/gif", imagecheck.Info{Type: "image/gif", Ext: "gif", Width: 40, Height: 30}},
		{"animated gif", imagetest.GIF(40, 30, 4), "image/gif", imagecheck.Info{Type: "image/gif", Ext: "gif", Width: 40, Height: 30}},
		{"webp", imagetest.WebP(), "image/webp", imagecheck.Info{Type: "image/webp", Ext: "webp", Width: 40, Height: 30}},
		{"lossless webp", imagetest.WebPLossless(), "image/webp", imagecheck.Info{Type: "image/webp", Ext: "webp", Width: 40, Height: 30}},
		{"animated webp", imagetest.WebPAnimated(), "image/webp", imagecheck.Info{Type: "image/webp", Ext: "webp", Width: 40, Height: 30}},
	}
	for _, c := range cases {
		got, err := imagecheck.Inspect(c.data, c.declared)
		got.Frames = 0 // the frame count has its own test (frames_test.go)
		if err != nil || got != c.want {
			t.Errorf("%s: got %+v, %v", c.name, got, err)
		}
	}
}

func TestInspectRejectsWhatIsNoAcceptedPicture(t *testing.T) {
	jpg := imagetest.JPEG(40, 30, 1)
	cases := map[string]struct {
		data     []byte
		declared string
	}{
		"type not accepted":              {jpg, "image/svg+xml"},
		"video type":                     {jpg, "video/mp4"},
		"empty type":                     {jpg, ""},
		"png bytes declared as jpeg":     {imagetest.PNG(40, 30, 1), "image/jpeg"},
		"html declared as png":           {[]byte("<html><script>alert(1)</script></html>"), "image/png"},
		"svg declared as png":            {[]byte(`<svg xmlns="http://www.w3.org/2000/svg"/>`), "image/png"},
		"empty body":                     {nil, "image/jpeg"},
		"jpeg cut off inside the header": {jpg[:12], "image/jpeg"},
	}
	for name, c := range cases {
		if _, err := imagecheck.Inspect(c.data, c.declared); !errors.Is(err, imagecheck.ErrType) {
			t.Errorf("%s: got %v, want ErrType", name, err)
		}
	}
}

func TestInspectRejectsOversizedDimensions(t *testing.T) {
	for name, data := range map[string][]byte{
		"side above the limit":   imagetest.PNGHeader(20000, 100),
		"pixels above the limit": imagetest.PNGHeader(8000, 7000),
	} {
		if _, err := imagecheck.Inspect(data, "image/png"); !errors.Is(err, imagecheck.ErrTooManyPixels) {
			t.Errorf("%s: got %v, want ErrTooManyPixels", name, err)
		}
	}
	// just inside both limits
	if _, err := imagecheck.Inspect(imagetest.PNGHeader(7000, 7000), "image/png"); err != nil {
		t.Errorf("49 megapixels: %v", err)
	}
}

func TestNormalizeAndExt(t *testing.T) {
	if got, ok := imagecheck.Normalize("image/JPG"); !ok || got != "image/jpeg" {
		t.Errorf("image/JPG: %q %v", got, ok)
	}
	if _, ok := imagecheck.Normalize("application/octet-stream"); ok {
		t.Error("octet-stream accepted")
	}
	if imagecheck.Ext("image/webp") != "webp" || imagecheck.Ext("text/plain") != "" {
		t.Error("Ext")
	}
}
