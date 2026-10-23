package review

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"strings"
	"testing"
	"time"

	_ "modernc.org/sqlite"

	"monostr.com/media/internal/storage/storagetest"
	"monostr.com/media/internal/store"
)

const operator = "0000000000000000000000000000000000000000000000000000000000000001"

func h(c string) string { return strings.Repeat(c, 64) }

type fakeMessenger struct {
	texts []string
	fail  bool
}

func (f *fakeMessenger) Send(_ context.Context, text string) error {
	if f.fail {
		return errors.New("relay down")
	}
	f.texts = append(f.texts, text)
	return nil
}

type env struct {
	t      *testing.T
	s      *store.Store
	path   string
	bucket *storagetest.Memory
	m      *fakeMessenger
	r      *Reviewer
	now    time.Time
	wraps  int
}

func newEnv(t *testing.T) *env {
	t.Helper()
	path := filepath.Join(t.TempDir(), "media.db")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	e := &env{t: t, s: s, path: path, bucket: storagetest.NewMemory(), m: &fakeMessenger{},
		now: time.Date(2026, 10, 22, 12, 0, 0, 0, time.UTC)}
	e.r = New(Config{PublicURL: "https://media.example", Operator: operator}, Deps{
		Store: s, Bucket: e.bucket, Messenger: e.m,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:    func() time.Time { return e.now },
	})
	return e
}

// add stores a picture with a case; singles is the daily cap, so 0 makes a
// summary case and a large number a single one.
func (e *env) add(c string, at int64, singles int) {
	e.t.Helper()
	b := store.Blob{SHA256: h(c), Size: 10, Type: "image/jpeg", Width: 4, Height: 4, CreatedAt: at}
	if _, err := e.s.AddStoredReview(b, h("9"), 0.7, 0, singles); err != nil {
		e.t.Fatal(err)
	}
	if err := e.bucket.Put(context.Background(), b.SHA256, []byte("x"), b.Type); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) say(sender string, kind int, text string) {
	e.wraps++
	e.r.Handle(context.Background(), Inbound{WrapID: h(string(rune('a' + e.wraps))), WrapAt: e.now.Unix(), SentAt: e.now.Unix(), Sender: sender, Kind: kind, Content: text})
}

func (e *env) sayOp(text string) { e.say(operator, 14, text) }

func (e *env) last() string {
	e.t.Helper()
	if len(e.m.texts) == 0 {
		e.t.Fatal("nothing sent")
	}
	return e.m.texts[len(e.m.texts)-1]
}

func (e *env) openCase(c string) store.ReviewCase {
	e.t.Helper()
	open, _ := e.s.OpenReviews(100)
	for _, x := range open {
		if x.SHA256 == h(c) {
			return x
		}
	}
	e.t.Fatalf("no open case %s", c)
	return store.ReviewCase{}
}

func TestFlushSendsDueOnce(t *testing.T) {
	e := newEnv(t)
	e.add("a", 100, 10)
	e.add("b", 101, 10)
	e.r.Flush(context.Background())
	if len(e.m.texts) != 2 {
		t.Fatalf("sent %d", len(e.m.texts))
	}
	if e.openCase("a").Notified != e.now.Unix() {
		t.Fatal("notified not set")
	}
	e.r.Flush(context.Background())
	if len(e.m.texts) != 2 {
		t.Fatalf("second flush sent more: %d", len(e.m.texts))
	}
}

func TestFlushRetrySchedule(t *testing.T) {
	e := newEnv(t)
	e.add("a", 100, 10)
	e.m.fail = true
	start := e.now
	for _, d := range []time.Duration{time.Minute, 5 * time.Minute, 30 * time.Minute, time.Hour, time.Hour} {
		e.r.Flush(context.Background())
		if got := e.openCase("a").NextTry; got != e.now.Add(d).Unix() {
			t.Fatalf("next try %v after start, want %v", time.Unix(got, 0).Sub(start), d)
		}
		// not resent before it is due
		e.m.fail = false
		e.now = e.now.Add(d - time.Second)
		e.r.Flush(context.Background())
		if len(e.m.texts) != 0 {
			t.Fatal("sent before due")
		}
		e.m.fail = true
		e.now = e.now.Add(time.Second)
	}
}

func TestSummaryHourly(t *testing.T) {
	e := newEnv(t)
	// 10:30 UTC is 12:30 in Zurich (summer time)
	oldest := time.Date(2026, 10, 22, 10, 30, 0, 0, time.UTC).Unix()
	e.add("a", oldest, 0)
	e.add("b", oldest+60, 0)
	e.add("c", oldest+120, 0)
	e.r.Flush(context.Background())
	if len(e.m.texts) != 1 || e.m.texts[0] != "3 weitere Prüffälle seit 12:30, siehe admin review" {
		t.Fatalf("texts: %q", e.m.texts)
	}
	e.now = e.now.Add(10 * time.Minute)
	e.add("d", e.now.Unix(), 0)
	e.r.Flush(context.Background())
	if len(e.m.texts) != 1 {
		t.Fatalf("summary within the hour: %q", e.m.texts)
	}
	e.now = e.now.Add(50 * time.Minute)
	e.r.Flush(context.Background())
	if len(e.m.texts) != 2 || !strings.HasPrefix(e.m.texts[1], "1 weitere") {
		t.Fatalf("texts: %q", e.m.texts)
	}
	e.r.Flush(context.Background())
	if len(e.m.texts) != 2 {
		t.Fatal("empty summary sent")
	}
}

func TestWegNewestNotified(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	e.add("b", 2, 10)
	e.add("c", 3, 10)
	if err := e.s.NoticeSent(h("a"), 100); err != nil {
		t.Fatal(err)
	}
	if err := e.s.NoticeSent(h("b"), 200); err != nil {
		t.Fatal(err)
	}
	e.sayOp("weg")
	if e.last() != "gelöscht #bbbb" {
		t.Fatalf("reply %q", e.last())
	}
	if ok, _ := e.s.HashBanned(h("b")); !ok {
		t.Fatal("b not banned")
	}
	e.openCase("a")
	e.openCase("c")
}

func TestWegNeverHitsSummaryCase(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 0)
	e.r.Flush(context.Background()) // summary goes out, the case is notified
	e.sayOp("weg")
	if e.last() != "kein offener Prüffall" {
		t.Fatalf("reply %q", e.last())
	}
	e.sayOp("weg aaaa")
	if e.last() != "gelöscht #aaaa" {
		t.Fatalf("reply %q", e.last())
	}
}

func TestWegByCode(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	e.add("b", 2, 10)
	e.sayOp(" WEG #AAAA ")
	if e.last() != "gelöscht #aaaa" {
		t.Fatalf("reply %q", e.last())
	}
	e.openCase("b")
}

func TestWegUnknownCode(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	e.sayOp("weg ffff")
	if e.last() != "#ffff nicht gefunden" {
		t.Fatalf("reply %q", e.last())
	}
	e.openCase("a")
}

func TestWegAmbiguousShortCode(t *testing.T) {
	e := newEnv(t)
	// two open cases whose hashes both start with "a3"
	x, y := "a3"+strings.Repeat("1", 62), "a3"+strings.Repeat("2", 62)
	for i, sha := range []string{x, y} {
		b := store.Blob{SHA256: sha, Size: 1, Type: "image/png", Width: 1, Height: 1, CreatedAt: int64(10 + i)}
		if _, err := e.s.AddStoredReview(b, h("9"), 0.7, 0, 10); err != nil {
			t.Fatal(err)
		}
	}
	e.sayOp("weg #a3")
	if e.last() != "#a3 ist mehrdeutig" {
		t.Fatalf("reply %q", e.last())
	}
	if ok, _ := e.s.HashBanned(x); ok {
		t.Fatal("ambiguous answer banned something")
	}
}

func TestWegAfterAdminDelete(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	if _, err := e.s.DeleteBlob(h("a")); err != nil {
		t.Fatal(err)
	}
	e.sayOp("weg aaaa")
	if e.last() != "#aaaa nicht gefunden" {
		t.Fatalf("reply %q", e.last())
	}
	if ok, _ := e.s.HashBanned(h("a")); ok {
		t.Fatal("banned although already removed")
	}
}

func TestWegBansAndDeletes(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	e.sayOp("weg aaaa")
	if ok, _ := e.s.HashBanned(h("a")); !ok {
		t.Fatal("not banned")
	}
	if _, found, _ := e.s.Blob(h("a")); found {
		t.Fatal("blob row still there")
	}
	if e.bucket.Has(h("a")) {
		t.Fatal("bucket object still there")
	}
	all, _ := e.s.Reviews(10)
	if len(all) != 1 || all[0].Outcome != store.ClosedWeg {
		t.Fatalf("cases: %+v", all)
	}
}

// TestWegDBErrorRepliesFailure makes the ban insert fail with a trigger set
// through a second connection: «weg» must then say so, leave the case open and
// the picture served, and succeed when sent again once the fault is gone.
func TestWegDBErrorRepliesFailure(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	db, err := sql.Open("sqlite", "file:"+e.path+"?_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`CREATE TRIGGER no_ban BEFORE INSERT ON banned_hashes BEGIN SELECT RAISE(ABORT, 'boom'); END`); err != nil {
		t.Fatal(err)
	}
	e.sayOp("weg aaaa")
	if want := "Fehler bei #aaaa, nichts gelöscht. Bitte später nochmal «weg aaaa»."; e.last() != want {
		t.Fatalf("reply %q", e.last())
	}
	e.openCase("a")
	if ok, _ := e.s.HashBanned(h("a")); ok {
		t.Fatal("banned")
	}
	if _, found, _ := e.s.Blob(h("a")); !found || !e.bucket.Has(h("a")) {
		t.Fatal("picture gone")
	}
	if _, err := db.Exec(`DROP TRIGGER no_ban`); err != nil {
		t.Fatal(err)
	}
	e.sayOp("weg aaaa")
	if e.last() != "gelöscht #aaaa" {
		t.Fatalf("retry reply %q", e.last())
	}
}

func TestHandleIgnoresStranger(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	e.say(h("5"), 14, "weg aaaa")
	e.say(operator, 1059, "weg aaaa")
	e.say(operator, 7, "weg aaaa")
	if len(e.m.texts) != 0 {
		t.Fatalf("replied: %q", e.m.texts)
	}
	e.openCase("a")
}

func TestHandleSameWrapTwice(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	in := Inbound{WrapID: h("e"), WrapAt: 5, SentAt: e.now.Unix(), Sender: operator, Kind: 14, Content: "weg aaaa"}
	e.r.Handle(context.Background(), in)
	e.r.Handle(context.Background(), in)
	if len(e.m.texts) != 1 {
		t.Fatalf("replies: %q", e.m.texts)
	}
}

func TestUnknownCommandReply(t *testing.T) {
	e := newEnv(t)
	e.sayOp("hallo")
	if e.last() != "Ich verstehe nur «weg» oder «weg <code>»." {
		t.Fatalf("reply %q", e.last())
	}
}

func TestHandleDropsOldMessage(t *testing.T) {
	e := newEnv(t)
	e.add("a", 1, 10)
	day := int64(24 * 3600)
	e.r.Handle(context.Background(), Inbound{WrapID: h("1"), WrapAt: e.now.Unix(), SentAt: e.now.Unix() - 8*day, Sender: operator, Kind: 14, Content: "weg aaaa"})
	if len(e.m.texts) != 0 {
		t.Fatalf("replied to an old message: %q", e.m.texts)
	}
	e.r.Handle(context.Background(), Inbound{WrapID: h("2"), WrapAt: e.now.Unix(), SentAt: e.now.Unix() - 6*day, Sender: operator, Kind: 14, Content: "weg aaaa"})
	if len(e.m.texts) != 1 {
		t.Fatalf("a 6 day old message should act: %q", e.m.texts)
	}
}

// sayAt is sayOp with an explicit send time, as a reply delayed by a relay
// blip would arrive.
func (e *env) sayAt(sentAt int64, text string) {
	e.wraps++
	e.r.Handle(context.Background(), Inbound{WrapID: h(string(rune('a' + e.wraps))), WrapAt: e.now.Unix(), SentAt: sentAt, Sender: operator, Kind: 14, Content: text})
}

func TestWegBareIgnoresCaseNotifiedAfterMessage(t *testing.T) {
	e := newEnv(t)
	t0 := e.now.Unix()
	e.add("a", 1, 10)
	e.add("b", 2, 10)
	if err := e.s.NoticeSent(h("a"), t0-300); err != nil {
		t.Fatal(err)
	}
	if err := e.s.NoticeSent(h("b"), t0+120); err != nil {
		t.Fatal(err)
	}
	e.sayAt(t0, "weg") // handled later, after b was announced
	if e.last() != "gelöscht #aaaa" {
		t.Fatalf("reply %q", e.last())
	}
	e.openCase("b")
	if ok, _ := e.s.HashBanned(h("b")); ok {
		t.Fatal("b banned")
	}
}

func TestWegBareAmbiguousWithinSkew(t *testing.T) {
	e := newEnv(t)
	t0 := e.now.Unix()
	e.add("a", 1, 10)
	e.add("b", 2, 10)
	if err := e.s.NoticeSent(h("a"), t0-300); err != nil {
		t.Fatal(err)
	}
	if err := e.s.NoticeSent(h("b"), t0+30); err != nil {
		t.Fatal(err)
	}
	e.sayAt(t0, "weg")
	if e.last() != "Mehrere neue Prüffälle, bitte «weg <code>»." {
		t.Fatalf("reply %q", e.last())
	}
	e.openCase("a")
	e.openCase("b")
}

func TestSummaryShowsDateWhenNotToday(t *testing.T) {
	e := newEnv(t)
	// 21:40 UTC on the 21st is 23:40 in Zurich
	e.add("a", time.Date(2026, 10, 21, 21, 40, 0, 0, time.UTC).Unix(), 0)
	e.r.Flush(context.Background())
	if len(e.m.texts) != 1 || e.m.texts[0] != "1 weitere Prüffälle seit 21.10. 23:40, siehe admin review" {
		t.Fatalf("texts: %q", e.m.texts)
	}
}
