package classify

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
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
