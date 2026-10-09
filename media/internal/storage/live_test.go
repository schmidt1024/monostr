package storage

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"testing"
	"time"
)

// TestAgainstARealBucket checks the S3 adapter against a real bucket. It runs
// only when the S3_TEST_* variables name one; it writes one object
// "selftest-<time>" and removes it again.
func TestAgainstARealBucket(t *testing.T) {
	cfg := S3Config{
		Endpoint: os.Getenv("S3_TEST_ENDPOINT"), Region: os.Getenv("S3_TEST_REGION"), Bucket: os.Getenv("S3_TEST_BUCKET"),
		AccessKey: os.Getenv("S3_TEST_ACCESS_KEY"), SecretKey: os.Getenv("S3_TEST_SECRET_KEY"),
	}
	if cfg.Endpoint == "" {
		t.Skip("S3_TEST_ENDPOINT not set")
	}
	b, err := NewS3(cfg)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := b.Ping(ctx); err != nil {
		t.Fatalf("ping: %v", err)
	}
	key := fmt.Sprintf("selftest-%d", time.Now().UnixNano())
	data := bytes.Repeat([]byte("monostr "), 1000)
	if _, err := b.Open(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("open of a missing object: %v", err)
	}
	if err := b.Put(ctx, key, data, "image/png"); err != nil {
		t.Fatalf("put: %v", err)
	}
	defer b.Delete(ctx, key)

	obj, err := b.Open(ctx, key)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	got, err := io.ReadAll(obj)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("read: %d bytes, %v", len(got), err)
	}
	// a ranged read, as http.ServeContent does it
	if end, err := obj.Seek(0, io.SeekEnd); err != nil || end != int64(len(data)) {
		t.Fatalf("seek to end: %d, %v", end, err)
	}
	if _, err := obj.Seek(8, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	part := make([]byte, 7)
	if _, err := io.ReadFull(obj, part); err != nil || string(part) != "monostr" {
		t.Fatalf("ranged read: %q, %v", part, err)
	}
	obj.Close()

	objects, err := b.List(ctx)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	found := false
	for _, o := range objects {
		if o.Key == key {
			found = true
			if time.Since(o.Modified) > time.Hour {
				t.Errorf("modified time of a fresh object: %v", o.Modified)
			}
		}
	}
	if !found {
		t.Fatal("the object is missing from the listing")
	}
	if err := b.Delete(ctx, key); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if err := b.Delete(ctx, key); err != nil {
		t.Fatalf("delete of a missing object must not fail: %v", err)
	}
	if _, err := b.Open(ctx, key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("open after delete: %v", err)
	}
}
