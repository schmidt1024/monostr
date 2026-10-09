package main

import (
	"bytes"
	"context"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/storage/storagetest"
	"monostr.com/media/internal/store"
)

type adminEnv struct {
	t      *testing.T
	admin  admin
	store  *store.Store
	bucket *storagetest.Memory
	out    *bytes.Buffer
	now    time.Time
}

func newAdminEnv(t *testing.T) *adminEnv {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "m.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	e := &adminEnv{t: t, store: st, bucket: storagetest.NewMemory(), out: &bytes.Buffer{}, now: time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC)}
	e.bucket.Now = func() time.Time { return e.now }
	e.admin = admin{store: st, bucket: e.bucket, publicURL: "https://media.example", threshold: 0.4, out: e.out, now: func() time.Time { return e.now }}
	return e
}

func (e *adminEnv) run(args ...string) (string, error) {
	e.t.Helper()
	e.out.Reset()
	err := e.admin.run(context.Background(), args)
	return e.out.String(), err
}

func (e *adminEnv) ok(args ...string) string {
	e.t.Helper()
	out, err := e.run(args...)
	if err != nil {
		e.t.Fatalf("admin %v: %v", args, err)
	}
	return out
}

func h(c string) string { return strings.Repeat(c, 64) }

// add stores a picture for pubkey in both the database and the bucket.
func (e *adminEnv) add(c, pubkey string, size int64, score float64) {
	e.t.Helper()
	b := store.Blob{SHA256: h(c), Size: size, Type: "image/jpeg", Width: 4, Height: 3, CreatedAt: e.now.Unix()}
	if err := e.store.AddStored(b, pubkey, score); err != nil {
		e.t.Fatal(err)
	}
	e.bucket.Put(context.Background(), h(c), []byte(c), "image/jpeg")
}

func TestDeleteAndBanHash(t *testing.T) {
	e := newAdminEnv(t)
	e.add("a", h("1"), 100, 0.1)
	e.add("b", h("1"), 100, 0.1)

	if out := e.ok("delete", strings.ToUpper(h("a"))); !strings.Contains(out, "deleted "+h("a")) {
		t.Fatalf("delete: %q", out)
	}
	if _, ok, _ := e.store.Blob(h("a")); ok || e.bucket.Has(h("a")) {
		t.Fatal("picture survived delete")
	}
	if out := e.ok("delete", h("a")); !strings.Contains(out, "no such picture") {
		t.Fatalf("second delete: %q", out)
	}

	e.ok("ban-hash", h("b"), "reported", "by", "npub1x")
	if banned, _ := e.store.HashBanned(h("b")); !banned || e.bucket.Has(h("b")) {
		t.Fatal("ban-hash did not ban and delete")
	}
	// a picture that was never here can be banned ahead of time
	e.ok("ban-hash", h("c"))
	if banned, _ := e.store.HashBanned(h("c")); !banned {
		t.Fatal("ban of an unknown hash")
	}
	e.ok("unban-hash", h("c"))
	if banned, _ := e.store.HashBanned(h("c")); banned {
		t.Fatal("unban-hash")
	}
	if _, err := e.run("unban-hash", h("c")); err == nil {
		t.Fatal("unban of a hash that is not banned succeeded")
	}
	if _, err := e.run("delete", "not-a-hash"); err == nil {
		t.Fatal("delete accepted a malformed hash")
	}
}

func TestBanPubkeyWithAndWithoutPurge(t *testing.T) {
	e := newAdminEnv(t)
	npub, err := nip19.EncodePublicKey(h("1"))
	if err != nil {
		t.Fatal(err)
	}
	e.add("a", h("1"), 100, 0.1)
	e.add("b", h("1"), 100, 0.1)
	e.store.AddDuplicate(store.Blob{SHA256: h("b"), Size: 100, Type: "image/jpeg"}, h("2"), e.now.Unix())

	e.ok("ban-pubkey", npub, "spam")
	if banned, _ := e.store.PubkeyBanned(h("1")); !banned {
		t.Fatal("npub not banned")
	}
	if !e.bucket.Has(h("a")) {
		t.Fatal("ban without --purge removed pictures")
	}
	if out := e.ok("ban-pubkey", h("1"), "--purge"); !strings.Contains(out, "purged 1 pictures") {
		t.Fatalf("purge: %q", out)
	}
	if e.bucket.Has(h("a")) || !e.bucket.Has(h("b")) {
		t.Fatal("purge must remove the sole-owner picture and keep the shared one")
	}
	e.ok("unban-pubkey", npub)
	if banned, _ := e.store.PubkeyBanned(h("1")); banned {
		t.Fatal("unban-pubkey")
	}
	for _, bad := range []string{"npub1notvalid", "xyz", h("1")[:63]} {
		if _, err := e.run("ban-pubkey", bad); err == nil {
			t.Errorf("ban-pubkey accepted %q", bad)
		}
	}
}

func TestLogStatsNear(t *testing.T) {
	e := newAdminEnv(t)
	e.add("a", h("1"), 100, 0.05)
	e.add("b", h("1"), 200, 0.35)
	e.store.LogRejected(h("1"), h("c"), 300, "image/png", 0.91, e.now.Unix())

	out := e.ok("log", h("1"))
	if !strings.Contains(out, "3 lines") || !strings.Contains(out, "nsfw") || !strings.Contains(out, "0.9100") || !strings.Contains(out, "2026-10-02 12:00:00") {
		t.Fatalf("log: %q", out)
	}
	if out := e.ok("log", h("1"), "1"); !strings.Contains(out, "1 lines") {
		t.Fatalf("log with a count: %q", out)
	}
	out = e.ok("stats")
	for _, want := range []string{"pictures        2", "bytes           300", "accounts        1", "stored today    2 (300 bytes)", "refused today   1", h("1")} {
		if !strings.Contains(out, want) {
			t.Errorf("stats lacks %q:\n%s", want, out)
		}
	}
	out = e.ok("near", "0.3")
	if !strings.Contains(out, "https://media.example/"+h("b")+".jpg") || strings.Contains(out, h("a")) || !strings.Contains(out, "1 pictures scored from 0.30 to below 0.40") {
		t.Fatalf("near: %q", out)
	}
	for _, bad := range [][]string{{"near", "0.4"}, {"near", "x"}, {"near", "-1"}, {"log", h("1"), "0"}, {"stats", "extra"}} {
		if _, err := e.run(bad...); err == nil {
			t.Errorf("%v accepted", bad)
		}
	}
}

func TestGCRemovesOnlyOldObjectsWithoutARow(t *testing.T) {
	e := newAdminEnv(t)
	e.add("a", h("1"), 100, 0.1)                                               // known
	e.bucket.Put(context.Background(), h("b"), []byte("orphan"), "image/jpeg") // old orphan
	e.now = e.now.Add(2 * time.Hour)
	e.bucket.Put(context.Background(), h("c"), []byte("fresh"), "image/jpeg") // an upload in flight

	out := e.ok("gc")
	if !strings.Contains(out, "removed "+h("b")) || !strings.Contains(out, "3 objects, 1 removed") {
		t.Fatalf("gc: %q", out)
	}
	if !e.bucket.Has(h("a")) || e.bucket.Has(h("b")) || !e.bucket.Has(h("c")) {
		t.Fatal("gc removed the wrong objects")
	}
}

func TestUsage(t *testing.T) {
	e := newAdminEnv(t)
	for _, args := range [][]string{{}, {"frobnicate"}, {"delete"}, {"ban-hash"}, {"gc", "now"}} {
		if _, err := e.run(args...); err == nil || !strings.Contains(err.Error(), "usage: monostr-media admin") {
			t.Errorf("%v: %v", args, err)
		}
	}
}
