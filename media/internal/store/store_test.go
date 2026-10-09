package store

import (
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func openTemp(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "m.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func h(c string) string { return strings.Repeat(c, 64) }

func blob(c string, size int64, at int64) Blob {
	return Blob{SHA256: h(c), Size: size, Type: "image/jpeg", Width: 40, Height: 30, CreatedAt: at}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func TestStoredBlobRoundTrip(t *testing.T) {
	s := openTemp(t)
	must(t, s.Ping())
	if _, ok, err := s.Blob(h("a")); ok || err != nil {
		t.Fatalf("unknown blob: %v %v", ok, err)
	}
	must(t, s.AddStored(blob("a", 100, 1000), h("1"), 0.07))
	got, ok, err := s.Blob(h("a"))
	if err != nil || !ok || got != blob("a", 100, 1000) {
		t.Fatalf("blob: %+v %v %v", got, ok, err)
	}
	// the same blob cannot be stored twice
	if err := s.AddStored(blob("a", 100, 1001), h("2"), 0.07); err == nil {
		t.Fatal("second AddStored of one hash succeeded")
	}
	if n, _ := s.Usage(h("2")); n != 0 {
		t.Fatalf("a failed AddStored left an owner behind: usage %d", n)
	}
}

func TestTwoOwnersOneBlob(t *testing.T) {
	s := openTemp(t)
	a := blob("a", 100, 1000)
	must(t, s.AddStored(a, h("1"), 0.1))
	if owner, _ := s.IsOwner(h("2"), h("a")); owner {
		t.Fatal("owner before the upload")
	}
	if added, err := s.AddDuplicate(a, h("2"), 1001); err != nil || !added {
		t.Fatalf("first duplicate: %v %v", added, err)
	}
	// again: still one ownership, and a replay leaves no second line in the log
	if added, err := s.AddDuplicate(a, h("2"), 1002); err != nil || added {
		t.Fatalf("replayed duplicate: %v %v", added, err)
	}
	if log, _ := s.Log(h("2"), 10); len(log) != 1 || log[0].Outcome != OutcomeDuplicate || log[0].At != 1001 {
		t.Fatalf("log after a replay: %+v", log)
	}
	// the first owner uploading it again is a replay too
	if added, err := s.AddDuplicate(a, h("1"), 1003); err != nil || added {
		t.Fatalf("the owner's own replay: %v %v", added, err)
	}
	if log, _ := s.Log(h("1"), 10); len(log) != 1 || log[0].Outcome != OutcomeStored {
		t.Fatalf("log of the first owner: %+v", log)
	}
	for _, pk := range []string{h("1"), h("2")} {
		if owner, err := s.IsOwner(pk, h("a")); err != nil || !owner {
			t.Fatalf("owner %s: %v %v", pk[:2], owner, err)
		}
	}
	if owner, _ := s.IsOwner(h("1"), h("f")); owner {
		t.Fatal("owner of an unknown blob")
	}
	for _, pk := range []string{h("1"), h("2")} {
		if n, _ := s.Usage(pk); n != 100 {
			t.Fatalf("usage of %s: %d", pk[:2], n)
		}
	}
	was, gone, err := s.RemoveOwner(h("1"), h("a"))
	if err != nil || !was || gone {
		t.Fatalf("first owner leaves: %v %v %v", was, gone, err)
	}
	if _, ok, _ := s.Blob(h("a")); !ok {
		t.Fatal("blob gone while an owner is left")
	}
	was, gone, err = s.RemoveOwner(h("1"), h("a"))
	if err != nil || was || gone {
		t.Fatalf("not an owner any more: %v %v %v", was, gone, err)
	}
	was, gone, err = s.RemoveOwner(h("2"), h("a"))
	if err != nil || !was || !gone {
		t.Fatalf("last owner leaves: %v %v %v", was, gone, err)
	}
	if _, ok, _ := s.Blob(h("a")); ok {
		t.Fatal("blob left without owners")
	}
	if was, _, _ := s.RemoveOwner(h("2"), h("f")); was {
		t.Fatal("ownership of an unknown blob")
	}
}

func TestDailyCountsAndQuota(t *testing.T) {
	s := openTemp(t)
	const day = int64(86400)
	must(t, s.AddStored(blob("a", 100, day-1), h("1"), 0)) // yesterday
	must(t, s.AddStored(blob("b", 200, day), h("1"), 0))
	must(t, s.AddStored(blob("c", 400, day+5), h("2"), 0))
	must(t, s.LogRejected(h("1"), h("d"), 800, "image/png", 0.9, day+6))
	if _, err := s.AddDuplicate(blob("c", 400, day+5), h("1"), day+7); err != nil {
		t.Fatal(err)
	}

	if n, _ := s.UploadsSince(h("1"), day); n != 2 { // stored b + rejected d; the duplicate does not count
		t.Fatalf("uploads of 1 today: %d", n)
	}
	if n, _ := s.UploadsSince(h("1"), 0); n != 3 {
		t.Fatalf("uploads of 1 ever: %d", n)
	}
	if n, _ := s.StoredBytesSince(day); n != 600 { // b + c; neither the rejected nor the duplicate
		t.Fatalf("stored today: %d", n)
	}
	if n, _ := s.Usage(h("1")); n != 700 { // a + b + c
		t.Fatalf("usage of 1: %d", n)
	}
	if n, _ := s.Usage(h("9")); n != 0 {
		t.Fatalf("usage of a stranger: %d", n)
	}
}

func TestBans(t *testing.T) {
	s := openTemp(t)
	if b, _ := s.HashBanned(h("a")); b {
		t.Fatal("banned before the ban")
	}
	must(t, s.BanHash(h("a"), "report 1", 1000))
	must(t, s.BanHash(h("a"), "report 2", 1001)) // again: no error
	if b, _ := s.HashBanned(h("a")); !b {
		t.Fatal("hash not banned")
	}
	if was, _ := s.UnbanHash(h("a")); !was {
		t.Fatal("unban reported nothing")
	}
	if was, _ := s.UnbanHash(h("a")); was {
		t.Fatal("second unban reported a ban")
	}
	if b, _ := s.HashBanned(h("a")); b {
		t.Fatal("still banned")
	}
	must(t, s.BanPubkey(h("1"), "", 1000))
	if b, _ := s.PubkeyBanned(h("1")); !b {
		t.Fatal("pubkey not banned")
	}
	if b, _ := s.PubkeyBanned(h("2")); b {
		t.Fatal("another pubkey banned")
	}
	if was, _ := s.UnbanPubkey(h("1")); !was {
		t.Fatal("unban reported nothing")
	}
}

func TestDeleteBlobAndPurge(t *testing.T) {
	s := openTemp(t)
	a, b, c := blob("a", 100, 1000), blob("b", 200, 1000), blob("c", 400, 1000)
	must(t, s.AddStored(a, h("1"), 0))
	must(t, s.AddStored(b, h("1"), 0))
	must(t, s.AddStored(c, h("2"), 0))
	if _, err := s.AddDuplicate(b, h("2"), 1001); err != nil { // b is shared
		t.Fatal(err)
	}

	gone, err := s.PurgePubkey(h("1"))
	if err != nil || !reflect.DeepEqual(gone, []string{h("a")}) {
		t.Fatalf("purge: %v %v", gone, err)
	}
	if _, ok, _ := s.Blob(h("a")); ok {
		t.Fatal("sole-owner blob survived the purge")
	}
	if _, ok, _ := s.Blob(h("b")); !ok {
		t.Fatal("shared blob was purged")
	}
	if n, _ := s.Usage(h("1")); n != 0 {
		t.Fatalf("usage after purge: %d", n)
	}
	if n, _ := s.Usage(h("2")); n != 600 {
		t.Fatalf("usage of the other owner: %d", n)
	}

	if existed, _ := s.DeleteBlob(h("b")); !existed {
		t.Fatal("delete reported nothing")
	}
	if existed, _ := s.DeleteBlob(h("b")); existed {
		t.Fatal("second delete reported a blob")
	}
	if n, _ := s.Usage(h("2")); n != 400 { // the ownership went with the blob
		t.Fatalf("usage after delete: %d", n)
	}
}

func TestLogNearAndStats(t *testing.T) {
	s := openTemp(t)
	const day = int64(86400)
	must(t, s.AddStored(blob("a", 100, day-10), h("1"), 0.05))
	must(t, s.AddStored(blob("b", 200, day+1), h("1"), 0.31))
	must(t, s.AddStored(blob("c", 400, day+2), h("2"), 0.39))
	must(t, s.LogRejected(h("2"), h("d"), 800, "image/png", 0.93, day+3))
	must(t, s.AddStored(blob("e", 50, day+4), h("2"), 0.35))
	if _, err := s.DeleteBlob(h("e")); err != nil { // deleted pictures are not "near" any more
		t.Fatal(err)
	}

	log, err := s.Log(h("2"), 10)
	if err != nil || len(log) != 3 || log[0].SHA256 != h("e") || log[1].Outcome != OutcomeNSFW || log[1].Score != 0.93 || log[2].SHA256 != h("c") {
		t.Fatalf("log: %+v %v", log, err)
	}
	if log, _ := s.Log(h("2"), 1); len(log) != 1 {
		t.Fatalf("limit: %d", len(log))
	}

	near, err := s.Near(0.30, 0.40, 10)
	if err != nil || len(near) != 2 || near[0].SHA256 != h("c") || near[1].SHA256 != h("b") {
		t.Fatalf("near: %+v %v", near, err)
	}

	st, err := s.Stats(day)
	if err != nil {
		t.Fatal(err)
	}
	want := Stats{Blobs: 3, Bytes: 700, Owners: 2, StoredToday: 3, BytesToday: 650, RejectedToday: 1,
		Top: []Uploader{{h("2"), 1, 400}, {h("1"), 2, 300}}}
	if !reflect.DeepEqual(st, want) {
		t.Fatalf("stats:\n got %+v\nwant %+v", st, want)
	}
}

func TestReopenKeepsData(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.db")
	s, err := Open(path)
	must(t, err)
	must(t, s.AddStored(blob("a", 100, 1000), h("1"), 0))
	must(t, s.Close())
	s, err = Open(path)
	must(t, err)
	defer s.Close()
	if _, ok, _ := s.Blob(h("a")); !ok {
		t.Fatal("blob lost over a reopen")
	}
}
