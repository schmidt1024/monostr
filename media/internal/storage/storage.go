// Package storage keeps the picture bytes in an S3-compatible bucket, one
// object per blob, keyed by the SHA-256.
package storage

import (
	"context"
	"errors"
	"io"
	"time"
)

// ErrNotFound: the bucket has no object under that key.
var ErrNotFound = errors.New("storage: object not found")

// Object is one key in the bucket with the time it was last written.
type Object struct {
	Key      string
	Modified time.Time
}

// Bucket is what the server needs from the object store.
type Bucket interface {
	Put(ctx context.Context, key string, data []byte, contentType string) error
	// Open returns a seekable reader over the object; ErrNotFound when it is missing.
	Open(ctx context.Context, key string) (io.ReadSeekCloser, error)
	// Delete removes the object; a missing object is no error.
	Delete(ctx context.Context, key string) error
	List(ctx context.Context) ([]Object, error)
	Ping(ctx context.Context) error
}
