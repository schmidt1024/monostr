package classify

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func scorer(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return New(srv.URL+"/", time.Second)
}

func TestScoreSendsTheBytesAndReadsTheScore(t *testing.T) {
	var got []byte
	c := scorer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/classify" {
			t.Errorf("request: %s %s", r.Method, r.URL.Path)
		}
		got, _ = io.ReadAll(r.Body)
		w.Write([]byte(`{"score": 0.4321, "frames": 1}`))
	})
	score, err := c.Score(context.Background(), []byte("picture"))
	if err != nil || score != 0.4321 || string(got) != "picture" {
		t.Fatalf("score %v, err %v, body %q", score, err, got)
	}
}

func TestScoreErrors(t *testing.T) {
	cases := map[string]struct {
		status int
		body   string
		want   error
	}{
		"unreadable picture": {422, `{"error":"unreadable image"}`, ErrUnreadable},
		"server error":       {500, `boom`, ErrUnavailable},
		"too large":          {413, `{}`, ErrUnavailable},
		"not json":           {200, `hello`, ErrUnavailable},
		"no score":           {200, `{"frames": 1}`, ErrUnavailable},
		"score out of range": {200, `{"score": 1.5}`, ErrUnavailable},
		"negative score":     {200, `{"score": -0.1}`, ErrUnavailable},
	}
	for name, c := range cases {
		client := scorer(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(c.status)
			w.Write([]byte(c.body))
		})
		if _, err := client.Score(context.Background(), []byte("x")); !errors.Is(err, c.want) {
			t.Errorf("%s: got %v, want %v", name, err, c.want)
		}
	}
}

func TestScoreZeroIsAValidScore(t *testing.T) {
	c := scorer(t, func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{"score": 0}`)) })
	if score, err := c.Score(context.Background(), []byte("x")); err != nil || score != 0 {
		t.Fatalf("score %v, err %v", score, err)
	}
}

func TestAnUnreachableOrSlowScorerIsUnavailable(t *testing.T) {
	down := New("http://127.0.0.1:1", time.Second)
	if _, err := down.Score(context.Background(), []byte("x")); !errors.Is(err, ErrUnavailable) {
		t.Errorf("unreachable: %v", err)
	}
	if err := down.Health(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Errorf("unreachable health: %v", err)
	}
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-release }))
	defer srv.Close()
	defer close(release)
	slow := New(srv.URL, 50*time.Millisecond)
	if _, err := slow.Score(context.Background(), []byte("x")); !errors.Is(err, ErrUnavailable) {
		t.Errorf("slow: %v", err)
	}
}

func TestHealth(t *testing.T) {
	up := scorer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/healthz" {
			w.WriteHeader(404)
		}
	})
	if err := up.Health(context.Background()); err != nil {
		t.Fatal(err)
	}
	sick := scorer(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500) })
	if err := sick.Health(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("sick: %v", err)
	}
}

func TestVideoSendsTheStreamAndReadsTheProbe(t *testing.T) {
	var got []byte
	var length int64
	c := scorer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/video" {
			t.Errorf("request: %s %s", r.Method, r.URL.Path)
		}
		length = r.ContentLength
		got, _ = io.ReadAll(r.Body)
		w.Write([]byte(`{"score": 0.12, "frames": 3, "width": 720, "height": 1280, "duration": 61.5, "video_codec": "h264", "audio_codec": null, "container": "mp4"}`))
	})
	info, err := c.Video(context.Background(), strings.NewReader("movie"), 5)
	if err != nil {
		t.Fatal(err)
	}
	want := VideoInfo{Score: 0.12, Width: 720, Height: 1280, Duration: 61.5, VideoCodec: "h264", AudioCodec: "", Container: "mp4"}
	if info != want || string(got) != "movie" || length != 5 {
		t.Fatalf("info %+v, body %q, length %d", info, got, length)
	}
}

func TestVideoErrors(t *testing.T) {
	cases := map[string]struct {
		status int
		body   string
		want   error
	}{
		"unreadable": {422, `{"error":"unreadable video"}`, ErrUnreadable},
		"fault":      {500, `{"error":"scorer fault"}`, ErrUnavailable},
		"bad score":  {200, `{"score": 1.5, "width": 1, "height": 1}`, ErrUnavailable},
		"no score":   {200, `{"width": 1, "height": 1}`, ErrUnavailable},
		"not json":   {200, `oops`, ErrUnavailable},
		"no size":    {200, `{"score": 0.1}`, ErrUnavailable},
	}
	for name, tc := range cases {
		c := scorer(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			w.Write([]byte(tc.body))
		})
		if _, err := c.Video(context.Background(), strings.NewReader("x"), 1); !errors.Is(err, tc.want) {
			t.Errorf("%s: %v", name, err)
		}
	}
}

// closeRecorder is a body that notes whether it was closed.
type closeRecorder struct {
	io.Reader
	closed bool
}

func (c *closeRecorder) Close() error { c.closed = true; return nil }

func TestVideoLeavesTheCallersFileOpen(t *testing.T) {
	// the server rewinds its temp file after the check to store it: the HTTP client must not close it
	c := scorer(t, func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.Write([]byte(`{"score": 0.1, "width": 720, "height": 1280, "duration": 3, "video_codec": "h264", "container": "mp4"}`))
	})
	body := &closeRecorder{Reader: strings.NewReader("movie")}
	if _, err := c.Video(context.Background(), body, 5); err != nil {
		t.Fatal(err)
	}
	if body.closed {
		t.Fatal("the scorer client closed the caller's reader")
	}
}
