// Package imagecheck decides from the bytes alone whether an upload is one of
// the picture formats the server accepts, and how large the picture is. It
// reads headers only; nothing is decoded.
package imagecheck

import (
	"bytes"
	"errors"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"mime"
	"net/http"
	"strings"

	_ "golang.org/x/image/webp"
)

const (
	// MaxSide is the largest accepted width or height in pixels.
	MaxSide = 16384
	// MaxPixels is the largest accepted width x height.
	MaxPixels = 50_000_000
	// MaxAnimationPixels is the largest accepted frames x width x height of an
	// animated picture. Decoders compose every frame on the full canvas, so
	// this product, not the file size, is what an animation costs to read:
	// 300 frames of 800 x 600 fit, 2000 frames on a 7000 x 7000 canvas do not.
	MaxAnimationPixels = 250_000_000
)

var (
	// ErrType: the declared type is not accepted, the bytes are no picture of
	// an accepted format, or the two disagree.
	ErrType = errors.New("imagecheck: not an accepted picture")
	// ErrTooManyPixels: the picture is larger than MaxSide or MaxPixels, or
	// its frames together exceed MaxAnimationPixels.
	ErrTooManyPixels = errors.New("imagecheck: picture dimensions too large")
)

// Info describes an accepted picture.
type Info struct {
	Type   string // image/jpeg, image/png, image/webp or image/gif
	Ext    string // jpg, png, webp or gif
	Width  int
	Height int
	Frames int // 1 for a still picture
}

var extensions = map[string]string{
	"image/jpeg": "jpg",
	"image/png":  "png",
	"image/webp": "webp",
	"image/gif":  "gif",
}

// Ext is the file extension for an accepted type, "" for any other.
func Ext(mediaType string) string { return extensions[mediaType] }

// Normalize reduces a Content-Type header to an accepted media type
// ("image/jpg" counts as "image/jpeg"); ok is false for anything else.
func Normalize(contentType string) (mediaType string, ok bool) {
	mediaType, _, err := mime.ParseMediaType(contentType)
	if err != nil {
		return "", false
	}
	mediaType = strings.ToLower(mediaType)
	if mediaType == "image/jpg" {
		mediaType = "image/jpeg"
	}
	_, ok = extensions[mediaType]
	return mediaType, ok
}

// Inspect checks data against the declared Content-Type.
func Inspect(data []byte, declared string) (Info, error) {
	want, ok := Normalize(declared)
	if !ok {
		return Info{}, ErrType
	}
	if got := http.DetectContentType(data); got != want {
		return Info{}, ErrType
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil || cfg.Width < 1 || cfg.Height < 1 {
		return Info{}, ErrType
	}
	if cfg.Width > MaxSide || cfg.Height > MaxSide || cfg.Width*cfg.Height > MaxPixels {
		return Info{}, ErrTooManyPixels
	}
	n := frames(data, want)
	if int64(n)*int64(cfg.Width)*int64(cfg.Height) > MaxAnimationPixels {
		return Info{}, ErrTooManyPixels
	}
	return Info{Type: want, Ext: extensions[want], Width: cfg.Width, Height: cfg.Height, Frames: n}, nil
}
