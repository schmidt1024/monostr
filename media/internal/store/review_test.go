package store

import (
	"path/filepath"
	"testing"
)

func TestRemoveForReview(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	closed, err := s.RemoveForReview(h("a"), 2000)
	must(t, err)
	if !closed {
		t.Fatal("not closed")
	}
	if banned, _ := s.HashBanned(h("a")); !banned {
		t.Fatal("not banned")
	}
	if _, ok, _ := s.Blob(h("a")); ok {
		t.Fatal("blob still there")
	}
	all, _ := s.Reviews(10)
	if len(all) != 1 || all[0].Outcome != ClosedWeg || all[0].Closed != 2000 {
		t.Fatalf("cases: %+v", all)
	}
}

func TestRemoveForReviewWithoutOpenCase(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	_, err = s.DeleteBlob(h("a")) // closes the case as removed
	must(t, err)
	closed, err := s.RemoveForReview(h("a"), 2000)
	must(t, err)
	if closed {
		t.Fatal("closed without an open case")
	}
	if banned, _ := s.HashBanned(h("a")); banned {
		t.Fatal("banned without an open case")
	}
	if closed, _ := s.RemoveForReview(h("f"), 2000); closed {
		t.Fatal("unknown hash closed")
	}
}

func TestAddStoredReviewSingleUntilCap(t *testing.T) {
	s := openTemp(t)
	var got []string
	for i, c := range []string{"a", "b", "c"} {
		d, err := s.AddStoredReview(blob(c, 100, int64(1000+i)), h("1"), 0.7, 1000, 2)
		must(t, err)
		got = append(got, d)
	}
	if got[0] != DeliverySingle || got[1] != DeliverySingle || got[2] != DeliverySummary {
		t.Fatalf("deliveries: %v", got)
	}
	var outcome string
	var score float64
	must(t, s.db.QueryRow(`SELECT outcome, score FROM uploads WHERE sha256 = ?`, h("a")).Scan(&outcome, &score))
	if outcome != OutcomeReview || score != 0.7 {
		t.Fatalf("log line: %q %v", outcome, score)
	}
	if _, ok, _ := s.Blob(h("a")); !ok {
		t.Fatal("blob missing")
	}
	if owner, _ := s.IsOwner(h("1"), h("a")); !owner {
		t.Fatal("owner missing")
	}
	// a later day starts the count again
	d, err := s.AddStoredReview(blob("d", 100, 90000), h("1"), 0.7, 86400, 2)
	must(t, err)
	if d != DeliverySingle {
		t.Fatalf("new day: %s", d)
	}
}

func TestDueNoticesAndRetry(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	due, err := s.DueNotices(0)
	must(t, err)
	if len(due) != 1 || due[0].SHA256 != h("a") || due[0].Delivery != DeliverySingle || due[0].Type != "image/jpeg" || due[0].Pubkey != h("1") {
		t.Fatalf("due: %+v", due)
	}
	must(t, s.NoticeFailed(h("a"), 1000))
	if due, _ = s.DueNotices(999); len(due) != 0 {
		t.Fatalf("due at 999: %+v", due)
	}
	due, _ = s.DueNotices(1000)
	if len(due) != 1 || due[0].Attempts != 1 || due[0].NextTry != 1000 {
		t.Fatalf("due at 1000: %+v", due)
	}
	must(t, s.NoticeSent(h("a"), 1001))
	if due, _ = s.DueNotices(5000); len(due) != 0 {
		t.Fatalf("due after sent: %+v", due)
	}
	// summary cases are never due as single notices
	_, err = s.AddStoredReview(blob("b", 100, 1002), h("1"), 0.7, 0, 1)
	must(t, err)
	if due, _ = s.DueNotices(5000); len(due) != 0 {
		t.Fatalf("summary case due: %+v", due)
	}
}

func TestDueNoticesSurviveReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.db")
	s, err := Open(path)
	must(t, err)
	_, err = s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	must(t, s.Close())
	s, err = Open(path)
	must(t, err)
	defer s.Close()
	if due, _ := s.DueNotices(0); len(due) != 1 {
		t.Fatalf("after reopen: %+v", due)
	}
}

func TestUnsummarizedAndMark(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 0)
	must(t, err)
	_, err = s.AddStoredReview(blob("b", 100, 1001), h("1"), 0.8, 0, 0)
	must(t, err)
	got, err := s.Unsummarized()
	must(t, err)
	if len(got) != 2 || got[0].SHA256 != h("a") || got[1].SHA256 != h("b") {
		t.Fatalf("unsummarized: %+v", got)
	}
	must(t, s.MarkSummarized([]string{h("a"), h("b")}, 2000))
	if got, _ = s.Unsummarized(); len(got) != 0 {
		t.Fatalf("after mark: %+v", got)
	}
}

func TestCloseReviewOnlyOpen(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	if ok, err := s.CloseReview(h("a"), ClosedWeg, 2000); err != nil || !ok {
		t.Fatalf("first close: %v %v", ok, err)
	}
	if ok, err := s.CloseReview(h("a"), ClosedWeg, 2001); err != nil || ok {
		t.Fatalf("second close: %v %v", ok, err)
	}
	if open, _ := s.OpenReviews(10); len(open) != 0 {
		t.Fatalf("open: %+v", open)
	}
	all, err := s.Reviews(10)
	must(t, err)
	if len(all) != 1 || all[0].Outcome != ClosedWeg || all[0].Closed != 2000 {
		t.Fatalf("all: %+v", all)
	}
}

func TestDeleteClosesAsRemoved(t *testing.T) {
	s := openTemp(t)
	for i, c := range []string{"a", "b", "c", "d"} {
		_, err := s.AddStoredReview(blob(c, 100, int64(1000+i)), h("1"), 0.7, 0, 9)
		must(t, err)
	}
	// c is shared with a second owner, d was closed as weg before
	_, err := s.AddDuplicate(blob("c", 100, 1000), h("2"), 1500)
	must(t, err)
	_, err = s.CloseReview(h("d"), ClosedWeg, 1600)
	must(t, err)

	outcome := func(c string) (string, int64) {
		all, err := s.Reviews(10)
		must(t, err)
		for _, r := range all {
			if r.SHA256 == h(c) {
				return r.Outcome, r.Closed
			}
		}
		t.Fatalf("no case %s", c)
		return "", 0
	}

	if _, err := s.DeleteBlob(h("a")); err != nil {
		t.Fatal(err)
	}
	if o, c := outcome("a"); o != ClosedRemoved || c == 0 {
		t.Fatalf("DeleteBlob: %q %d", o, c)
	}
	// shared blob: removing one owner keeps the case open
	if _, gone, _ := s.RemoveOwner(h("1"), h("c")); gone {
		t.Fatal("blob c gone with an owner left")
	}
	if o, _ := outcome("c"); o != "" {
		t.Fatalf("case closed with an owner left: %q", o)
	}
	if _, gone, err := s.RemoveOwner(h("2"), h("c")); err != nil || !gone {
		t.Fatalf("RemoveOwner: %v %v", gone, err)
	}
	if o, c := outcome("c"); o != ClosedRemoved || c == 0 {
		t.Fatalf("RemoveOwner: %q %d", o, c)
	}
	if _, err := s.PurgePubkey(h("1")); err != nil {
		t.Fatal(err)
	}
	if o, c := outcome("b"); o != ClosedRemoved || c == 0 {
		t.Fatalf("PurgePubkey: %q %d", o, c)
	}
	if o, c := outcome("d"); o != ClosedWeg || c != 1600 {
		t.Fatalf("closed case changed: %q %d", o, c)
	}
}

func TestWrapSeen(t *testing.T) {
	s := openTemp(t)
	if at, err := s.LastWrapAt(); err != nil || at != 0 {
		t.Fatalf("empty: %d %v", at, err)
	}
	if fresh, err := s.WrapSeen("a", 5); err != nil || !fresh {
		t.Fatalf("first: %v %v", fresh, err)
	}
	if fresh, err := s.WrapSeen("a", 6); err != nil || fresh {
		t.Fatalf("second: %v %v", fresh, err)
	}
	if at, _ := s.LastWrapAt(); at != 5 {
		t.Fatalf("last: %d", at)
	}
	if n, err := s.PruneWraps(6); err != nil || n != 1 {
		t.Fatalf("prune: %d %v", n, err)
	}
	if fresh, _ := s.WrapSeen("a", 7); !fresh {
		t.Fatal("not fresh after prune")
	}
}

func TestStatsOpenReviews(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	_, err = s.AddStoredReview(blob("b", 100, 1001), h("1"), 0.7, 0, 5)
	must(t, err)
	_, err = s.CloseReview(h("b"), ClosedWeg, 2000)
	must(t, err)
	st, err := s.Stats(0)
	must(t, err)
	if st.OpenReviews != 1 {
		t.Fatalf("open reviews: %d", st.OpenReviews)
	}
}

func TestReopenAfterRemoved(t *testing.T) {
	s := openTemp(t)
	_, err := s.AddStoredReview(blob("a", 100, 1000), h("1"), 0.7, 0, 5)
	must(t, err)
	if _, err := s.DeleteBlob(h("a")); err != nil {
		t.Fatal(err)
	}
	// the same bytes uploaded again open a new case; the old one stays as history
	if _, err := s.AddStoredReview(blob("a", 100, 2000), h("1"), 0.75, 0, 5); err != nil {
		t.Fatalf("re-upload: %v", err)
	}
	open, err := s.OpenReviews(10)
	must(t, err)
	if len(open) != 1 || open[0].At != 2000 || open[0].Score != 0.75 {
		t.Fatalf("open: %+v", open)
	}
	all, err := s.Reviews(10)
	must(t, err)
	if len(all) != 2 || all[0].Closed != 0 || all[1].Outcome != ClosedRemoved {
		t.Fatalf("all: %+v", all)
	}
}
