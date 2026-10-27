// Package classify asks the NSFW scorer beside the server (container "nsfw")
// how a picture rates.
package classify

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

var (
	// ErrUnreadable: the scorer could not read the bytes as a picture.
	ErrUnreadable = errors.New("classify: unreadable picture")
	// ErrUnavailable: the scorer did not answer, or not in a usable way.
	ErrUnavailable = errors.New("classify: scorer unavailable")
)

// Client talks to one scorer.
type Client struct {
	base  string
	http  *http.Client
	video *http.Client
}

// VideoTimeout bounds one video check: up to 50 MiB to send and twelve frames to take and score.
const VideoTimeout = 3 * time.Minute

// VideoInfo is what the scorer saw in a video (spec 2026-10-27 video §6, §7):
// the size as displayed, the codecs ("" for no audio), the container, the
// duration in seconds and the highest score of its frames.
type VideoInfo struct {
	Score                  float64
	Width, Height          int
	Duration               float64
	VideoCodec, AudioCodec string
	Container              string
}

// New builds a client for the scorer at base (http://nsfw:8081); every call
// gives up after timeout.
func New(base string, timeout time.Duration) *Client {
	return &Client{base: strings.TrimRight(base, "/"), http: &http.Client{Timeout: timeout}, video: &http.Client{Timeout: VideoTimeout}}
}

// Score returns the NSFW probability (0..1) of the picture.
func (c *Client) Score(ctx context.Context, data []byte) (float64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/classify", bytes.NewReader(data))
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	res, err := c.http.Do(req)
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 4<<10))
	if err != nil {
		return 0, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	switch {
	case res.StatusCode == http.StatusUnprocessableEntity:
		return 0, ErrUnreadable
	case res.StatusCode != http.StatusOK:
		return 0, fmt.Errorf("%w: status %d", ErrUnavailable, res.StatusCode)
	}
	var out struct {
		Score *float64 `json:"score"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.Score == nil || *out.Score < 0 || *out.Score > 1 {
		return 0, fmt.Errorf("%w: bad answer", ErrUnavailable)
	}
	return *out.Score, nil
}

// Video sends size bytes from r to the scorer and returns what it saw.
func (c *Client) Video(ctx context.Context, r io.Reader, size int64) (VideoInfo, error) {
	// only a Reader: the transport closes a body that is also a Closer, and the caller still needs its file
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/video", struct{ io.Reader }{r})
	if err != nil {
		return VideoInfo{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "video/mp4")
	res, err := c.video.Do(req)
	if err != nil {
		return VideoInfo{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 4<<10))
	if err != nil {
		return VideoInfo{}, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	switch {
	case res.StatusCode == http.StatusUnprocessableEntity:
		return VideoInfo{}, ErrUnreadable
	case res.StatusCode != http.StatusOK:
		return VideoInfo{}, fmt.Errorf("%w: status %d", ErrUnavailable, res.StatusCode)
	}
	var out struct {
		Score      *float64 `json:"score"`
		Width      int      `json:"width"`
		Height     int      `json:"height"`
		Duration   float64  `json:"duration"`
		VideoCodec string   `json:"video_codec"`
		AudioCodec *string  `json:"audio_codec"`
		Container  string   `json:"container"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.Score == nil || *out.Score < 0 || *out.Score > 1 || out.Width < 1 || out.Height < 1 {
		return VideoInfo{}, fmt.Errorf("%w: bad answer", ErrUnavailable)
	}
	info := VideoInfo{Score: *out.Score, Width: out.Width, Height: out.Height, Duration: out.Duration, VideoCodec: out.VideoCodec, Container: out.Container}
	if out.AudioCodec != nil {
		info.AudioCodec = *out.AudioCodec
	}
	return info, nil
}

// Health reports whether the scorer is up.
func (c *Client) Health(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+"/healthz", nil)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: status %d", ErrUnavailable, res.StatusCode)
	}
	return nil
}
