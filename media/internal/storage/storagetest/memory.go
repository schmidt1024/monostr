// Package storagetest is an in-memory Bucket for tests.
package storagetest

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sort"
	"sync"
	"time"

	"monostr.com/media/internal/storage"
)

type object struct {
	data     []byte
	modified time.Time
}

// Memory is a Bucket that keeps objects in a map. FailPut, FailOpen and
// FailPing make the next calls fail, to play an unreachable bucket; BlockPut
// makes Put hang until its context ends, to play one that does not answer.
type Memory struct {
	mu       sync.Mutex
	objects  map[string]object
	Now      func() time.Time
	FailPut  bool
	FailOpen bool
	FailPing bool
	BlockPut bool
}

func NewMemory() *Memory {
	return &Memory{objects: map[string]object{}, Now: time.Now}
}

var errDown = errors.New("storagetest: bucket unreachable")

type reader struct{ *bytes.Reader }

func (reader) Close() error { return nil }

func (m *Memory) Put(ctx context.Context, key string, data []byte, _ string) error {
	m.mu.Lock()
	block := m.BlockPut
	m.mu.Unlock()
	if block {
		<-ctx.Done()
		return ctx.Err()
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.FailPut {
		return errDown
	}
	m.objects[key] = object{data: append([]byte(nil), data...), modified: m.Now()}
	return nil
}

func (m *Memory) Open(_ context.Context, key string) (io.ReadSeekCloser, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.FailOpen {
		return nil, errDown
	}
	o, ok := m.objects[key]
	if !ok {
		return nil, storage.ErrNotFound
	}
	return reader{bytes.NewReader(o.data)}, nil
}

func (m *Memory) Delete(_ context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.objects, key)
	return nil
}

func (m *Memory) List(context.Context) ([]storage.Object, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]storage.Object, 0, len(m.objects))
	for k, o := range m.objects {
		out = append(out, storage.Object{Key: k, Modified: o.modified})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}

func (m *Memory) Ping(context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.FailPing {
		return errDown
	}
	return nil
}

// Has reports whether the bucket holds key.
func (m *Memory) Has(key string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.objects[key]
	return ok
}

// Len is the number of objects.
func (m *Memory) Len() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.objects)
}
