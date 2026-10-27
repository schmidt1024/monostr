package storagetest

import (
	"context"
	"io"
	"strings"
	"testing"
)

func TestFakePutStreamStoresTheBytes(t *testing.T) {
	m := NewMemory()
	if err := m.PutStream(context.Background(), "k", strings.NewReader("video bytes"), 11, "video/mp4"); err != nil {
		t.Fatal(err)
	}
	r, err := m.Open(context.Background(), "k")
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(r)
	if string(got) != "video bytes" {
		t.Fatalf("stored %q", got)
	}
}

func TestFakePutStreamFailsLikePut(t *testing.T) {
	m := NewMemory()
	m.FailPut = true
	if err := m.PutStream(context.Background(), "k", strings.NewReader("x"), 1, "video/mp4"); err == nil {
		t.Fatal("a failing bucket took the stream")
	}
}
