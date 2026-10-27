package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/media/internal/blossom/blossomtest"
	"monostr.com/media/internal/classify"
	"monostr.com/media/internal/imagetest"
	"monostr.com/media/internal/limits"
	"monostr.com/media/internal/storage/storagetest"
	"monostr.com/media/internal/store"
)

const publicURL = "https://media.example"

// fakeClassifier answers with a fixed score or error and counts its calls. A
// delay makes every call take that long; a gate holds every call until it is
// closed, so that a test can look at uploads in flight.
type fakeClassifier struct {
	mu sync.Mutex
	// video is what Video answers (its Score is taken from score); videoErr fails it
	video    classify.VideoInfo
	videoErr error
	score    float64
	err      error
	health   error
	calls    int
	delay    time.Duration
	gate     chan struct{}
}

func (f *fakeClassifier) Score(context.Context, []byte) (float64, error) {
	f.mu.Lock()
	f.calls++
	score, err, delay, gate := f.score, f.err, f.delay, f.gate
	f.mu.Unlock()
	if gate != nil {
		<-gate
	}
	time.Sleep(delay)
	return score, err
}

func (f *fakeClassifier) Video(_ context.Context, r io.Reader, size int64) (classify.VideoInfo, error) {
	f.mu.Lock()
	f.calls++
	info, err, score := f.video, f.videoErr, f.score
	f.mu.Unlock()
	if n, _ := io.Copy(io.Discard, r); n != size {
		return classify.VideoInfo{}, fmt.Errorf("read %d of %d bytes", n, size)
	}
	info.Score = score
	return info, err
}

// waiting is the number of calls so far, for tests that wait for uploads to reach the classifier.
func (f *fakeClassifier) waiting() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func (f *fakeClassifier) Health(context.Context) error { return f.health }

type env struct {
	t      *testing.T
	store  *store.Store
	bucket *storagetest.Memory
	nsfw   *fakeClassifier
	srv    *httptest.Server
	tmp    string // the server's temp dir for videos
	now    time.Time
	log    *bytes.Buffer
	// notified counts the calls of Deps.Notify
	notified atomic.Int32
}

func (e *env) clock() time.Time { return e.now }

var generous = limits.Limits{MaxBytes: 1 << 20, PubkeyPerDay: 50, PubkeyQuota: 10 << 20, IPPerDay: 100, GlobalBytesPerDay: 100 << 20,
	MaxVideoBytes: 3 << 20, PubkeyVideosPerDay: 5}

func newEnv(t *testing.T, lim limits.Limits) *env {
	t.Helper()
	return newEnvWith(t, Config{Limits: lim})
}

// newEnvWith takes a Config whose Limits (and whatever else a test sets) are
// kept; the address, threshold, contact and version are filled in.
func newEnvWith(t *testing.T, cfg Config) *env {
	t.Helper()
	cfg.PublicURL, cfg.ContactNpub, cfg.Version = publicURL+"/", "npub1contact", "test"
	if cfg.Threshold == 0 {
		cfg.Threshold = 0.4
	}
	st, err := store.Open(filepath.Join(t.TempDir(), "m.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	if cfg.TempDir == "" {
		cfg.TempDir = t.TempDir()
	}
	e := &env{t: t, store: st, bucket: storagetest.NewMemory(), tmp: cfg.TempDir,
		nsfw: &fakeClassifier{score: 0.05, video: goodVideo},
		now:  time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC), log: &bytes.Buffer{}}
	h, err := New(cfg,
		Deps{Store: st, Bucket: e.bucket, Classifier: e.nsfw, Logger: slog.New(slog.NewTextHandler(e.log, nil)), Now: e.clock,
			Notify: func() { e.notified.Add(1) }})
	if err != nil {
		t.Fatal(err)
	}
	e.srv = httptest.NewServer(h)
	t.Cleanup(e.srv.Close)
	return e
}

type user struct{ sk, pk string }

func newUser(t *testing.T) user {
	t.Helper()
	sk := nostr.GeneratePrivateKey()
	pk, err := nostr.GetPublicKey(sk)
	if err != nil {
		t.Fatal(err)
	}
	return user{sk, pk}
}

func hashOf(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// result is a response with its body already read.
type result struct {
	status int
	header http.Header
	body   []byte
}

func (r result) code() string { return r.header.Get("X-Monostr-Reason") }

func (e *env) do(req *http.Request) result {
	e.t.Helper()
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	return result{res.StatusCode, res.Header, body}
}

func (e *env) request(method, path string, body []byte, header map[string]string) result {
	e.t.Helper()
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, e.srv.URL+path, reader)
	if err != nil {
		e.t.Fatal(err)
	}
	for k, v := range header {
		req.Header.Set(k, v)
	}
	return e.do(req)
}

// put uploads data as u with a correct authorization; extra headers override.
func (e *env) put(u user, data []byte, contentType string, extra map[string]string) result {
	e.t.Helper()
	header := map[string]string{
		"Authorization": blossomtest.Auth(u.sk, "upload", hashOf(data), e.now.Unix()),
		"Content-Type":  contentType,
	}
	for k, v := range extra {
		header[k] = v
	}
	return e.request(http.MethodPut, "/upload", data, header)
}

func (e *env) del(u user, hash string) result {
	e.t.Helper()
	return e.request(http.MethodDelete, "/"+hash, nil, map[string]string{
		"Authorization": blossomtest.Auth(u.sk, "delete", hash, e.now.Unix()),
	})
}

func (e *env) want(r result, status int, code string) {
	e.t.Helper()
	if r.status != status || r.code() != code {
		e.t.Fatalf("got %d %q (%s), want %d %q", r.status, r.code(), r.header.Get("X-Reason"), status, code)
	}
	if status >= 400 && r.header.Get("X-Reason") == "" {
		e.t.Fatalf("rejection %d without X-Reason", status)
	}
}

func TestUploadStoresAPictureAndServesIt(t *testing.T) {
	e := newEnv(t, generous)
	u := newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	hash := hashOf(jpg)

	r := e.put(u, jpg, "image/jpeg", nil)
	e.want(r, 201, "")
	var d descriptor
	if err := json.Unmarshal(r.body, &d); err != nil {
		t.Fatal(err)
	}
	if d != (descriptor{URL: publicURL + "/" + hash + ".jpg", SHA256: hash, Size: int64(len(jpg)), Type: "image/jpeg", Uploaded: e.now.Unix()}) {
		t.Fatalf("descriptor: %+v", d)
	}
	if !e.bucket.Has(hash) {
		t.Fatal("object missing in the bucket")
	}
	blob, ok, _ := e.store.Blob(hash)
	if !ok || blob.Width != 40 || blob.Height != 30 {
		t.Fatalf("blob row: %+v %v", blob, ok)
	}
	log, _ := e.store.Log(u.pk, 10)
	if len(log) != 1 || log[0].Outcome != store.OutcomeStored || log[0].Score != 0.05 {
		t.Fatalf("log: %+v", log)
	}

	for _, path := range []string{"/" + hash + ".jpg", "/" + hash, "/" + hash + ".png", "/" + strings.ToUpper(hash) + ".jpg"} {
		g := e.request(http.MethodGet, path, nil, nil)
		if g.status != 200 || !bytes.Equal(g.body, jpg) {
			t.Fatalf("GET %s: %d, %d bytes", path, g.status, len(g.body))
		}
		for k, v := range map[string]string{
			"Content-Type":                "image/jpeg",
			"Cache-Control":               "public, max-age=31536000, immutable",
			"Etag":                        `"` + hash + `"`,
			"X-Content-Type-Options":      "nosniff",
			"Content-Security-Policy":     "default-src 'none'",
			"Access-Control-Allow-Origin": "*",
		} {
			if g.header.Get(k) != v {
				t.Errorf("GET %s: header %s = %q, want %q", path, k, g.header.Get(k), v)
			}
		}
	}
	head := e.request(http.MethodHead, "/"+hash+".jpg", nil, nil)
	if head.status != 200 || len(head.body) != 0 || head.header.Get("Content-Length") != strconv.Itoa(len(jpg)) {
		t.Fatalf("HEAD: %d, %d bytes, length %q", head.status, len(head.body), head.header.Get("Content-Length"))
	}
	part := e.request(http.MethodGet, "/"+hash, nil, map[string]string{"Range": "bytes=0-9"})
	if part.status != 206 || !bytes.Equal(part.body, jpg[:10]) {
		t.Fatalf("range: %d, %d bytes", part.status, len(part.body))
	}
	cached := e.request(http.MethodGet, "/"+hash, nil, map[string]string{"If-None-Match": `"` + hash + `"`})
	if cached.status != 304 {
		t.Fatalf("If-None-Match: %d", cached.status)
	}
}

func TestEveryAcceptedFormatGetsItsExtension(t *testing.T) {
	e := newEnv(t, generous)
	u := newUser(t)
	for ext, c := range map[string]struct {
		data []byte
		typ  string
	}{
		"png":  {imagetest.PNG(40, 30, 1), "image/png"},
		"gif":  {imagetest.GIF(40, 30, 3), "image/gif"},
		"webp": {imagetest.WebPAnimated(), "image/webp"},
		"jpg":  {imagetest.JPEG(40, 30, 2), "image/jpg"},
	} {
		r := e.put(u, c.data, c.typ, nil)
		e.want(r, 201, "")
		var d descriptor
		json.Unmarshal(r.body, &d)
		if !strings.HasSuffix(d.URL, "."+ext) {
			t.Errorf("%s: url %s", ext, d.URL)
		}
	}
}

func TestASecondUploadOfTheSameBytesIsADuplicate(t *testing.T) {
	e := newEnv(t, generous)
	a, b := newUser(t), newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	hash := hashOf(jpg)
	e.want(e.put(a, jpg, "image/jpeg", nil), 201, "")
	e.now = e.now.Add(time.Minute)

	again := e.put(a, jpg, "image/jpeg", nil)
	e.want(again, 200, "")
	var d descriptor
	json.Unmarshal(again.body, &d)
	if d.Uploaded != e.now.Add(-time.Minute).Unix() {
		t.Fatalf("uploaded: %d", d.Uploaded)
	}
	e.want(e.put(b, jpg, "image/jpeg", nil), 200, "")
	if e.nsfw.calls != 1 {
		t.Fatalf("the classifier ran %d times for one picture", e.nsfw.calls)
	}
	for _, u := range []user{a, b} {
		if n, _ := e.store.Usage(u.pk); n != int64(len(jpg)) {
			t.Fatalf("usage: %d", n)
		}
	}
	if n, _ := e.store.UploadsSince(a.pk, 0); n != 1 {
		t.Fatalf("a duplicate counted as an upload of the day: %d", n)
	}

	// the picture goes when the last owner has left
	e.want(e.del(a, hash), 204, "")
	if g := e.request(http.MethodGet, "/"+hash, nil, nil); g.status != 200 {
		t.Fatalf("GET after the first owner left: %d", g.status)
	}
	e.want(e.del(a, hash), 404, CodeNotFound)
	e.want(e.del(b, hash), 204, "")
	e.want(e.request(http.MethodGet, "/"+hash, nil, nil), 404, CodeNotFound)
	if e.bucket.Len() != 0 {
		t.Fatal("object left in the bucket")
	}
	if n, _ := e.store.Usage(b.pk); n != 0 {
		t.Fatalf("usage after delete: %d", n)
	}
	// and can be uploaded anew
	e.want(e.put(a, jpg, "image/jpeg", nil), 201, "")
}

func TestDeleteNeedsTheOwnersDeleteAuthorizationForThatBlob(t *testing.T) {
	e := newEnv(t, generous)
	owner, stranger := newUser(t), newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	hash := hashOf(jpg)
	e.want(e.put(owner, jpg, "image/jpeg", nil), 201, "")

	e.want(e.del(stranger, hash), 404, CodeNotFound)
	e.want(e.request(http.MethodDelete, "/"+hash, nil, nil), 401, CodeAuth)
	uploadToken := map[string]string{"Authorization": blossomtest.Auth(owner.sk, "upload", hash, e.now.Unix())}
	e.want(e.request(http.MethodDelete, "/"+hash, nil, uploadToken), 401, CodeAuth)
	otherBlob := map[string]string{"Authorization": blossomtest.Auth(owner.sk, "delete", strings.Repeat("c", 64), e.now.Unix())}
	e.want(e.request(http.MethodDelete, "/"+hash, nil, otherBlob), 401, CodeAuth)
	e.want(e.request(http.MethodDelete, "/not-a-hash", nil, otherBlob), 404, CodeNotFound)
	if !e.bucket.Has(hash) {
		t.Fatal("a refused delete removed the object")
	}
	e.want(e.request(http.MethodDelete, "/"+hash+".jpg", nil, map[string]string{
		"Authorization": blossomtest.Auth(owner.sk, "delete", hash, e.now.Unix())}), 204, "")
}

func TestUploadAuthorization(t *testing.T) {
	e := newEnv(t, generous)
	u := newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	now := e.now.Unix()
	expired := blossomtest.Header(blossomtest.Event(u.sk, now-600, nostr.Tags{{"t", "upload"}, {"x", hashOf(jpg)}, {"expiration", "1"}}))
	foreign := blossomtest.Header(blossomtest.Event(u.sk, now, nostr.Tags{{"t", "upload"}, {"x", hashOf(jpg)}, {"expiration", "99999999999"}, {"server", "other.example"}}))
	own := blossomtest.Header(blossomtest.Event(u.sk, now, nostr.Tags{{"t", "upload"}, {"x", hashOf(jpg)}, {"expiration", "99999999999"}, {"server", "media.example"}}))
	for name, auth := range map[string]string{
		"missing":        "",
		"delete token":   blossomtest.Auth(u.sk, "delete", hashOf(jpg), now),
		"expired":        expired,
		"another server": foreign,
	} {
		r := e.put(u, jpg, "image/jpeg", map[string]string{"Authorization": auth})
		if r.status != 401 || r.code() != CodeAuth {
			t.Errorf("%s: %d %q", name, r.status, r.code())
		}
	}
	if e.bucket.Len() != 0 || e.nsfw.calls != 0 {
		t.Fatal("an unauthorized upload got past the door")
	}
	e.want(e.put(u, jpg, "image/jpeg", map[string]string{"Authorization": own}), 201, "")
}

func TestHashMismatch(t *testing.T) {
	e := newEnv(t, generous)
	u := newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	other := blossomtest.Auth(u.sk, "upload", strings.Repeat("c", 64), e.now.Unix())
	e.want(e.put(u, jpg, "image/jpeg", map[string]string{"Authorization": other}), 409, CodeHashMismatch)
	e.want(e.put(u, jpg, "image/jpeg", map[string]string{"X-SHA-256": strings.Repeat("c", 64)}), 409, CodeHashMismatch)
	if e.bucket.Len() != 0 {
		t.Fatal("stored despite the mismatch")
	}
	e.want(e.put(u, jpg, "image/jpeg", map[string]string{"X-SHA-256": strings.ToUpper(hashOf(jpg))}), 201, "")
}

func TestBans(t *testing.T) {
	e := newEnv(t, generous)
	banned, free := newUser(t), newUser(t)
	jpg, png := imagetest.JPEG(40, 30, 1), imagetest.PNG(40, 30, 1)
	e.store.BanPubkey(banned.pk, "", 1)
	e.store.BanHash(hashOf(png), "", 1)

	e.want(e.put(banned, jpg, "image/jpeg", nil), 403, CodeBanned)
	e.want(e.put(free, png, "image/png", nil), 403, CodeBannedContent)
	if e.bucket.Len() != 0 || e.nsfw.calls != 0 {
		t.Fatal("a banned upload reached the classifier or the bucket")
	}
	e.want(e.put(free, jpg, "image/jpeg", nil), 201, "")
}

func TestNSFWIsRefusedLoggedAndCountedAsAnUpload(t *testing.T) {
	lim := generous
	lim.PubkeyPerDay = 2
	e := newEnv(t, lim)
	u := newUser(t)
	e.nsfw.score = 0.4 // exactly the threshold refuses
	first, second := imagetest.JPEG(40, 30, 1), imagetest.JPEG(40, 30, 2)

	e.want(e.put(u, first, "image/jpeg", nil), 403, CodeNSFW)
	if e.bucket.Len() != 0 {
		t.Fatal("a refused picture was stored")
	}
	if _, ok, _ := e.store.Blob(hashOf(first)); ok {
		t.Fatal("a refused picture has a blob row")
	}
	log, _ := e.store.Log(u.pk, 10)
	if len(log) != 1 || log[0].Outcome != store.OutcomeNSFW || log[0].Score != 0.4 || log[0].SHA256 != hashOf(first) {
		t.Fatalf("log: %+v", log)
	}
	e.want(e.put(u, second, "image/jpeg", nil), 403, CodeNSFW)
	// two refusals used up the day: probing the threshold is not free
	e.nsfw.score = 0.01
	e.want(e.put(u, imagetest.JPEG(40, 30, 3), "image/jpeg", nil), 429, CodeRate)
}

func TestJustBelowTheThresholdIsStored(t *testing.T) {
	e := newEnv(t, generous)
	e.nsfw.score = 0.3999
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 201, "")
}

func TestLengthSizeAndType(t *testing.T) {
	lim := generous
	lim.MaxBytes = 2000
	e := newEnv(t, lim)
	u := newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)

	// a body without a length (chunked)
	req, _ := http.NewRequest(http.MethodPut, e.srv.URL+"/upload", io.NopCloser(bytes.NewReader(jpg)))
	req.Header.Set("Authorization", blossomtest.Auth(u.sk, "upload", hashOf(jpg), e.now.Unix()))
	req.Header.Set("Content-Type", "image/jpeg")
	e.want(e.do(req), 411, CodeLength)

	big := imagetest.JPEG(400, 300, 1)
	if len(big) <= 2000 {
		t.Fatalf("test picture too small: %d", len(big))
	}
	e.want(e.put(u, big, "image/jpeg", nil), 413, CodeTooLarge)

	e.want(e.put(u, jpg, "image/svg+xml", nil), 415, CodeType)
	e.want(e.put(u, jpg, "", nil), 415, CodeType)
	e.want(e.put(u, imagetest.PNG(40, 30, 1), "image/jpeg", nil), 415, CodeType)
	html := []byte("<html><script>alert(1)</script></html>")
	e.want(e.put(u, html, "image/png", nil), 415, CodeType)
	e.want(e.put(u, imagetest.PNGHeader(20000, 100), "image/png", nil), 413, CodeTooLarge)
	if e.nsfw.calls != 0 || e.bucket.Len() != 0 {
		t.Fatal("a malformed upload reached the classifier or the bucket")
	}

	e.nsfw.err = classify.ErrUnreadable
	e.want(e.put(u, jpg, "image/jpeg", nil), 415, CodeType)
	if n, _ := e.store.UploadsSince(u.pk, 0); n != 0 {
		t.Fatalf("malformed uploads counted: %d", n)
	}
}

func TestPubkeyDailyLimitAndQuota(t *testing.T) {
	lim := generous
	lim.PubkeyPerDay = 2
	e := newEnv(t, lim)
	u, other := newUser(t), newUser(t)
	e.want(e.put(u, imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 201, "")
	e.want(e.put(u, imagetest.JPEG(40, 30, 2), "image/jpeg", nil), 201, "")
	e.want(e.put(u, imagetest.JPEG(40, 30, 3), "image/jpeg", nil), 429, CodeRate)
	e.want(e.put(other, imagetest.JPEG(40, 30, 3), "image/jpeg", nil), 201, "")
	// the limit is per UTC day
	e.now = time.Date(2026, 10, 3, 0, 0, 1, 0, time.UTC)
	e.want(e.put(u, imagetest.JPEG(40, 30, 4), "image/jpeg", nil), 201, "")

	first := imagetest.JPEG(40, 30, 10)
	lim = generous
	lim.PubkeyQuota = int64(len(first)) + 10
	q := newEnv(t, lim)
	e.want(q.put(u, first, "image/jpeg", nil), 201, "")
	q.want(q.put(u, imagetest.JPEG(40, 30, 11), "image/jpeg", nil), 413, CodeQuota)
	// deleting frees the quota
	q.want(q.del(u, hashOf(first)), 204, "")
	q.want(q.put(u, imagetest.JPEG(40, 30, 11), "image/jpeg", nil), 201, "")
}

func TestIPDailyLimitCountsAnIPv6NetworkAsOne(t *testing.T) {
	lim := generous
	lim.IPPerDay = 2
	e := newEnv(t, lim)
	from := func(ip string) map[string]string { return map[string]string{"X-Forwarded-For": ip} }
	n := uint8(0)
	next := func() []byte { n++; return imagetest.JPEG(40, 30, n) }

	e.want(e.put(newUser(t), next(), "image/jpeg", from("2001:db8:1:2::10")), 201, "")
	e.want(e.put(newUser(t), next(), "image/jpeg", from("2001:db8:1:2:ffff::99")), 201, "")
	e.want(e.put(newUser(t), next(), "image/jpeg", from("2001:db8:1:2::77")), 429, CodeRate)
	e.want(e.put(newUser(t), next(), "image/jpeg", from("2001:db8:1:3::10")), 201, "")
	e.want(e.put(newUser(t), next(), "image/jpeg", from("203.0.113.7")), 201, "")
	e.want(e.put(newUser(t), next(), "image/jpeg", from("203.0.113.7, 10.0.0.1")), 201, "")
	e.want(e.put(newUser(t), next(), "image/jpeg", from("203.0.113.7")), 429, CodeRate)
	e.now = e.now.Add(24 * time.Hour)
	e.want(e.put(newUser(t), next(), "image/jpeg", from("203.0.113.7")), 201, "")
	if strings.Contains(e.log.String(), "203.0.113.7") || strings.Contains(e.log.String(), "2001:db8") {
		t.Fatal("an address reached the log")
	}
}

func TestGlobalDailyCap(t *testing.T) {
	first := imagetest.JPEG(40, 30, 1)
	lim := generous
	lim.GlobalBytesPerDay = int64(len(first)) + 10
	e := newEnv(t, lim)
	e.want(e.put(newUser(t), first, "image/jpeg", nil), 201, "")
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 2), "image/jpeg", nil), 429, CodeRate)
	e.now = e.now.Add(24 * time.Hour)
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 2), "image/jpeg", nil), 201, "")
}

func TestNothingIsStoredWhileTheClassifierOrTheBucketIsDown(t *testing.T) {
	e := newEnv(t, generous)
	u := newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)

	e.nsfw.err = errors.New("connection refused")
	e.want(e.put(u, jpg, "image/jpeg", nil), 503, CodeUnavailable)
	if e.bucket.Len() != 0 {
		t.Fatal("stored without a content check")
	}
	e.nsfw.err = nil
	e.bucket.FailPut = true
	e.want(e.put(u, jpg, "image/jpeg", nil), 503, CodeUnavailable)
	if _, ok, _ := e.store.Blob(hashOf(jpg)); ok {
		t.Fatal("a blob row without an object")
	}
	if n, _ := e.store.UploadsSince(u.pk, 0); n != 0 {
		t.Fatalf("failed uploads counted: %d", n)
	}
	e.bucket.FailPut = false
	e.want(e.put(u, jpg, "image/jpeg", nil), 201, "")
}

func TestPreflight(t *testing.T) {
	lim := generous
	lim.MaxBytes = 2000
	e := newEnv(t, lim)
	u := newUser(t)
	hash := strings.Repeat("a", 64)
	head := func(u user, length, typ string) result {
		return e.request(http.MethodHead, "/upload", nil, map[string]string{
			"Authorization":    blossomtest.Auth(u.sk, "upload", hash, e.now.Unix()),
			"X-SHA-256":        hash,
			"X-Content-Length": length,
			"X-Content-Type":   typ,
		})
	}
	e.want(head(u, "1000", "image/png"), 200, "")
	e.want(head(u, "5000", "image/png"), 413, CodeTooLarge)
	e.want(head(u, "", "image/png"), 411, CodeLength)
	e.want(head(u, "1000", "video/mp4"), 200, "")
	e.want(head(u, "1000", "video/webm"), 415, CodeType)
	e.want(e.request(http.MethodHead, "/upload", nil, nil), 401, CodeAuth)
	e.want(e.request(http.MethodHead, "/upload", nil, map[string]string{
		"Authorization": blossomtest.Auth(u.sk, "upload", hash, e.now.Unix()), "X-SHA-256": strings.Repeat("b", 64),
		"X-Content-Length": "1000", "X-Content-Type": "image/png"}), 409, CodeHashMismatch)
	e.want(e.request(http.MethodHead, "/upload", nil, map[string]string{
		"Authorization":    blossomtest.Auth(u.sk, "upload", hash, e.now.Unix()),
		"X-Content-Length": "1000", "X-Content-Type": "image/png"}), 400, CodeBadRequest)
	e.store.BanHash(hash, "", 1)
	e.want(head(u, "1000", "image/png"), 403, CodeBannedContent)
	if n, _ := e.store.UploadsSince(u.pk, 0); n != 0 {
		t.Fatalf("a preflight counted as an upload: %d", n)
	}
}

func TestUnknownAndMalformedBlobPaths(t *testing.T) {
	e := newEnv(t, generous)
	for _, path := range []string{"/" + strings.Repeat("a", 64), "/" + strings.Repeat("a", 64) + ".jpg", "/favicon.ico", "/upload", "/" + strings.Repeat("a", 63), "/" + strings.Repeat("g", 64)} {
		e.want(e.request(http.MethodGet, path, nil, nil), 404, CodeNotFound)
	}
}

func TestABlobWhoseObjectIsMissingIsNotFound(t *testing.T) {
	e := newEnv(t, generous)
	jpg := imagetest.JPEG(40, 30, 1)
	e.want(e.put(newUser(t), jpg, "image/jpeg", nil), 201, "")
	e.bucket.Delete(context.Background(), hashOf(jpg))
	e.want(e.request(http.MethodGet, "/"+hashOf(jpg), nil, nil), 404, CodeNotFound)
	e.bucket.FailOpen = true
	e.want(e.request(http.MethodGet, "/"+hashOf(jpg), nil, nil), 503, CodeUnavailable)
}

func TestCORSPreflight(t *testing.T) {
	e := newEnv(t, generous)
	for _, path := range []string{"/upload", "/" + strings.Repeat("a", 64)} {
		r := e.request(http.MethodOptions, path, nil, map[string]string{"Origin": "https://app.example", "Access-Control-Request-Method": "PUT"})
		if r.status != 204 || r.header.Get("Access-Control-Allow-Origin") != "*" ||
			r.header.Get("Access-Control-Allow-Methods") != "GET, HEAD, PUT, DELETE" ||
			r.header.Get("Access-Control-Allow-Headers") != "Authorization, *" {
			t.Fatalf("OPTIONS %s: %d %v", path, r.status, r.header)
		}
	}
	if r := e.put(newUser(t), []byte("x"), "text/plain", nil); r.header.Get("Access-Control-Expose-Headers") != "X-Reason, X-Monostr-Reason" {
		t.Fatalf("expose headers: %q", r.header.Get("Access-Control-Expose-Headers"))
	}
}

func TestPageAndHealth(t *testing.T) {
	e := newEnv(t, generous)
	page := e.request(http.MethodGet, "/", nil, nil)
	if page.status != 200 || !strings.Contains(page.header.Get("Content-Type"), "text/html") {
		t.Fatalf("page: %d %s", page.status, page.header.Get("Content-Type"))
	}
	for _, want := range []string{"media.example", "npub1contact", "1 MB each", "50 uploads a day", "10 MB in all", "No adult content"} {
		if !strings.Contains(string(page.body), want) {
			t.Errorf("page lacks %q", want)
		}
	}
	ok := e.request(http.MethodGet, "/healthz", nil, nil)
	if ok.status != 200 || !strings.Contains(string(ok.body), `"nsfw":"ok"`) || !strings.Contains(string(ok.body), `"version":"test"`) {
		t.Fatalf("healthz: %d %s", ok.status, ok.body)
	}
	e.nsfw.health = errors.New("down")
	if r := e.request(http.MethodGet, "/healthz", nil, nil); r.status != 503 || !strings.Contains(string(r.body), `"nsfw":"down"`) || !strings.Contains(string(r.body), `"bucket":"ok"`) {
		t.Fatalf("healthz with the scorer down: %d %s", r.status, r.body)
	}
	e.nsfw.health = nil
	e.bucket.FailPing = true
	if r := e.request(http.MethodGet, "/healthz", nil, nil); r.status != 503 || !strings.Contains(string(r.body), `"bucket":"down"`) {
		t.Fatalf("healthz with the bucket down: %d %s", r.status, r.body)
	}
}

func TestNewRejectsABadPublicURL(t *testing.T) {
	for _, bad := range []string{"", "media.example", "ftp://media.example"} {
		if _, err := New(Config{PublicURL: bad}, Deps{}); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestConcurrentUploadsOfOnePictureStoreItOnce(t *testing.T) {
	e := newEnv(t, generous)
	jpg := imagetest.JPEG(40, 30, 1)
	users := make([]user, 8)
	for i := range users {
		users[i] = newUser(t)
	}
	statuses := make([]int, len(users))
	var wg sync.WaitGroup
	for i, u := range users {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req, _ := http.NewRequest(http.MethodPut, e.srv.URL+"/upload", bytes.NewReader(jpg))
			req.Header.Set("Authorization", blossomtest.Auth(u.sk, "upload", hashOf(jpg), e.now.Unix()))
			req.Header.Set("Content-Type", "image/jpeg")
			// eight people, eight addresses: one address may not hold that many uploads at once
			req.Header.Set("X-Forwarded-For", fmt.Sprintf("203.0.113.%d", i+1))
			res, err := http.DefaultClient.Do(req)
			if err != nil {
				return
			}
			res.Body.Close()
			statuses[i] = res.StatusCode
		}()
	}
	wg.Wait()
	created := 0
	for _, s := range statuses {
		switch s {
		case 201:
			created++
		case 200:
		default:
			t.Fatalf("statuses: %v", statuses)
		}
	}
	if created != 1 || e.nsfw.calls != 1 || e.bucket.Len() != 1 {
		t.Fatalf("created %d, classifier calls %d, objects %d", created, e.nsfw.calls, e.bucket.Len())
	}
}

// greyEnv is an env with the grey zone from 0.60 up to 0.85.
func greyEnv(t *testing.T) *env {
	t.Helper()
	return newEnvWith(t, Config{Limits: generous, Threshold: 0.60, Reject: 0.85})
}

func TestGreyZoneStoresAndNotifies(t *testing.T) {
	e := greyEnv(t)
	u := newUser(t)
	plain, grey := imagetest.JPEG(40, 30, 1), imagetest.JPEG(40, 30, 2)

	e.nsfw.score = 0.05
	want := e.put(u, plain, "image/jpeg", nil)
	e.want(want, 201, "")
	e.nsfw.score = 0.67
	got := e.put(u, grey, "image/jpeg", nil)
	e.want(got, 201, "")

	// the uploader cannot tell: same fields, only hash/url/size differ
	var a, b map[string]any
	if json.Unmarshal(want.body, &a) != nil || json.Unmarshal(got.body, &b) != nil {
		t.Fatalf("bodies: %s / %s", want.body, got.body)
	}
	if len(a) != len(b) {
		t.Fatalf("field sets differ: %s / %s", want.body, got.body)
	}
	for k := range a {
		if _, ok := b[k]; !ok {
			t.Fatalf("field %q missing: %s", k, got.body)
		}
	}
	if b["type"] != a["type"] {
		t.Fatalf("type differs: %s / %s", want.body, got.body)
	}
	if _, ok, _ := e.store.Blob(hashOf(grey)); !ok || e.bucket.Len() != 2 {
		t.Fatal("the grey-zone picture is not stored")
	}
	cases, err := e.store.OpenReviews(10)
	if err != nil || len(cases) != 1 || cases[0].SHA256 != hashOf(grey) || cases[0].Delivery != store.DeliverySingle {
		t.Fatalf("cases: %+v, %v", cases, err)
	}
	if n := e.notified.Load(); n != 1 {
		t.Fatalf("notify called %d times", n)
	}
}

func TestScoreAtThresholdIsAReviewCase(t *testing.T) {
	e := greyEnv(t)
	e.nsfw.score = 0.60
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 201, "")
	cases, err := e.store.OpenReviews(10)
	if err != nil || len(cases) != 1 {
		t.Fatalf("cases: %+v, %v", cases, err)
	}
}

func TestRejectBandUnchanged(t *testing.T) {
	e := greyEnv(t)
	e.nsfw.score = 0.85
	r := e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil)
	e.want(r, 403, CodeNSFW)
	if got := r.header.Get("X-Reason"); got != "this server does not accept adult content" {
		t.Fatalf("reason: %q", got)
	}
	if cases, _ := e.store.OpenReviews(10); len(cases) != 0 || e.notified.Load() != 0 || e.bucket.Len() != 0 {
		t.Fatalf("a refused picture left cases %+v, notices %d", cases, e.notified.Load())
	}
}

func TestBelowThresholdNoCase(t *testing.T) {
	e := greyEnv(t)
	e.nsfw.score = 0.59
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 201, "")
	if cases, _ := e.store.OpenReviews(10); len(cases) != 0 || e.notified.Load() != 0 {
		t.Fatalf("cases %+v, notices %d", cases, e.notified.Load())
	}
}

func TestNoGreyZoneWhenRejectUnset(t *testing.T) {
	e := newEnvWith(t, Config{Limits: generous, Threshold: 0.60})
	e.nsfw.score = 0.67
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 403, CodeNSFW)
	if cases, _ := e.store.OpenReviews(10); len(cases) != 0 || e.notified.Load() != 0 {
		t.Fatalf("cases %+v, notices %d", cases, e.notified.Load())
	}
}

func TestGreyZoneDuplicateAndAfterWeg(t *testing.T) {
	e := greyEnv(t)
	a, b := newUser(t), newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	e.nsfw.score = 0.67

	e.want(e.put(a, jpg, "image/jpeg", nil), 201, "")
	e.want(e.put(b, jpg, "image/jpeg", nil), 200, "")
	if cases, _ := e.store.OpenReviews(10); len(cases) != 1 {
		t.Fatalf("cases: %+v", cases)
	}
	if n := e.notified.Load(); n != 1 {
		t.Fatalf("notify called %d times", n)
	}

	// what «weg» does
	hash := hashOf(jpg)
	if err := e.store.BanHash(hash, "review", e.now.Unix()); err != nil {
		t.Fatal(err)
	}
	if _, err := e.store.DeleteBlob(hash); err != nil {
		t.Fatal(err)
	}
	e.want(e.put(newUser(t), jpg, "image/jpeg", nil), 403, CodeBannedContent)
}

func TestNewRejectsRejectBelowThreshold(t *testing.T) {
	if _, err := New(Config{PublicURL: publicURL, Threshold: 0.6, Reject: 0.5}, Deps{}); err == nil {
		t.Fatal("Reject below Threshold was accepted")
	}
	if _, err := New(Config{PublicURL: publicURL, Threshold: 0.6, Reject: 1.5}, Deps{}); err == nil {
		t.Fatal("Reject above 1 was accepted")
	}
}

// videoLimits: small numbers so that no test sends megabytes; a video may be larger than a picture.
func videoLimits() limits.Limits {
	lim := generous
	lim.MaxBytes = 1000
	lim.MaxVideoBytes = 3000
	lim.PubkeyVideosPerDay = 5
	return lim
}

func TestVideoOverMaxVideoBytesIsTooLarge(t *testing.T) {
	e := newEnv(t, videoLimits())
	e.want(e.put(newUser(t), bytes.Repeat([]byte{1}, 3001), "video/mp4", nil), 413, CodeVideoTooLarge)
}

func TestPictureStillCappedAtMaxBytes(t *testing.T) {
	e := newEnv(t, videoLimits())
	e.want(e.put(newUser(t), bytes.Repeat([]byte{1}, 1001), "image/jpeg", nil), 413, CodeTooLarge)
}

func TestSixthVideoOfTheDayIsRefused(t *testing.T) {
	e := newEnv(t, videoLimits())
	u := newUser(t)
	for i := 0; i < 5; i++ {
		b := store.Blob{SHA256: hashOf([]byte{byte(i)}), Size: 10, Type: "video/mp4", Width: 720, Height: 1280, CreatedAt: e.now.Unix()}
		if err := e.store.AddStored(b, u.pk, 0); err != nil {
			t.Fatal(err)
		}
	}
	e.want(e.put(u, bytes.Repeat([]byte{7}, 100), "video/mp4", nil), 429, CodeVideoRate)
	if e.nsfw.waiting() != 0 {
		t.Fatal("a refused video reached the classifier")
	}
}

func TestVideosCountTowardTheDailyUploads(t *testing.T) {
	lim := videoLimits()
	lim.PubkeyPerDay = 2
	e := newEnv(t, lim)
	u := newUser(t)
	for i := 0; i < 2; i++ {
		b := store.Blob{SHA256: hashOf([]byte{byte(i)}), Size: 10, Type: "video/mp4", Width: 720, Height: 1280, CreatedAt: e.now.Unix()}
		if err := e.store.AddStored(b, u.pk, 0); err != nil {
			t.Fatal(err)
		}
	}
	e.want(e.put(u, imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 429, CodeRate)
}

func TestTypeRefusalNamesVideos(t *testing.T) {
	e := newEnv(t, videoLimits())
	res := e.put(newUser(t), []byte("plain"), "text/plain", nil)
	e.want(res, 415, CodeType)
}

// goodVideo is what the scorer sees in a video the app made (spec 2026-10-27 video §4).
var goodVideo = classify.VideoInfo{Width: 720, Height: 1280, Duration: 61, VideoCodec: "h264", AudioCodec: "aac", Container: "mp4"}

func (e *env) tempFiles() []string {
	entries, _ := os.ReadDir(e.tmp)
	var names []string
	for _, x := range entries {
		names = append(names, x.Name())
	}
	return names
}

func TestVideoUploadIsStoredAndServedWithRange(t *testing.T) {
	e := newEnv(t, videoLimits())
	clip := bytes.Repeat([]byte("mp4 "), 500)
	res := e.put(newUser(t), clip, "video/mp4", nil)
	e.want(res, 201, "")
	var d descriptor
	if err := json.Unmarshal(res.body, &d); err != nil || d.Type != "video/mp4" || !strings.HasSuffix(d.URL, hashOf(clip)+".mp4") || d.Size != int64(len(clip)) {
		t.Fatalf("descriptor %+v, %v", d, err)
	}
	b, ok, _ := e.store.Blob(hashOf(clip))
	if !ok || b.Width != 720 || b.Height != 1280 || b.Type != "video/mp4" {
		t.Fatalf("blob %+v", b)
	}
	got := e.request(http.MethodGet, "/"+hashOf(clip)+".mp4", nil, map[string]string{"Range": "bytes=0-9"})
	if got.status != http.StatusPartialContent || string(got.body) != "mp4 mp4 mp" || got.header.Get("Content-Type") != "video/mp4" {
		t.Fatalf("ranged get: %d %q %q", got.status, got.body, got.header.Get("Content-Type"))
	}
	if left := e.tempFiles(); len(left) != 0 {
		t.Fatalf("temp files left: %v", left)
	}
}

func TestVideoLengthLimit(t *testing.T) {
	e := newEnv(t, videoLimits())
	u := newUser(t)
	e.nsfw.video.Duration = 124.9 // the app took 121 s; encoding may add a little
	e.want(e.put(u, []byte("video one"), "video/mp4", nil), 201, "")
	e.nsfw.video.Duration = 126
	e.want(e.put(u, []byte("video two"), "video/mp4", nil), 413, CodeVideoTooLong)
}

func TestVideoFormatChecks(t *testing.T) {
	e := newEnv(t, videoLimits())
	u := newUser(t)
	try := func(i int, change func(*classify.VideoInfo), status int, code string) {
		t.Helper()
		e.nsfw.video = goodVideo
		change(&e.nsfw.video)
		e.want(e.put(u, []byte(fmt.Sprintf("video %d", i)), "video/mp4", nil), status, code)
	}
	try(1, func(v *classify.VideoInfo) { v.VideoCodec = "hevc" }, 415, CodeVideoFormat)
	try(2, func(v *classify.VideoInfo) { v.AudioCodec = "opus" }, 415, CodeVideoFormat)
	try(3, func(v *classify.VideoInfo) { v.Container = "matroska" }, 415, CodeVideoFormat)
	try(4, func(v *classify.VideoInfo) { v.Width, v.Height = 1280, 1280 }, 413, CodeVideoFormat)
	try(5, func(v *classify.VideoInfo) { v.Width, v.Height = 1280, 720 }, 201, "")
	try(6, func(v *classify.VideoInfo) { v.AudioCodec = "" }, 201, "") // no sound is fine
}

func TestVideoThatIsNoVideoIsRefused(t *testing.T) {
	e := newEnv(t, videoLimits())
	e.nsfw.videoErr = classify.ErrUnreadable
	e.want(e.put(newUser(t), []byte("not a video"), "video/mp4", nil), 415, CodeVideoFormat)
}

func TestVideoNSFWRejectedAndGreyZoneReviewed(t *testing.T) {
	e := newEnvWith(t, Config{Limits: videoLimits(), Threshold: 0.60, Reject: 0.85})
	u := newUser(t)
	e.nsfw.score = 0.9
	e.want(e.put(u, []byte("explicit video"), "video/mp4", nil), 403, CodeNSFW)
	if e.bucket.Len() != 0 {
		t.Fatal("a refused video was stored")
	}
	e.nsfw.score = 0.7
	e.want(e.put(u, []byte("grey video"), "video/mp4", nil), 201, "")
	open, _ := e.store.OpenReviews(10)
	if len(open) != 1 || e.notified.Load() != 1 {
		t.Fatalf("reviews %d, notified %d", len(open), e.notified.Load())
	}
}

func TestVideoTempFileIsRemovedOnEveryPath(t *testing.T) {
	e := newEnv(t, videoLimits())
	u := newUser(t)
	// stored
	e.want(e.put(u, []byte("video ok"), "video/mp4", nil), 201, "")
	// the authorization names other bytes
	e.want(e.put(u, []byte("video x"), "video/mp4", map[string]string{"Authorization": blossomtest.Auth(u.sk, "upload", hashOf([]byte("other")), e.now.Unix())}), 409, CodeHashMismatch)
	// the scorer is down
	e.nsfw.videoErr = classify.ErrUnavailable
	e.want(e.put(u, []byte("video y"), "video/mp4", nil), 503, CodeUnavailable)
	e.nsfw.videoErr = nil
	// the bucket is down
	e.bucket.FailPut = true
	e.want(e.put(u, []byte("video z"), "video/mp4", nil), 503, CodeUnavailable)
	e.bucket.FailPut = false
	// the client goes away in the middle of the body
	data := bytes.Repeat([]byte{9}, 2000)
	conn, err := net.Dial("tcp", strings.TrimPrefix(e.srv.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintf(conn, "PUT /upload HTTP/1.1\r\nHost: x\r\nAuthorization: %s\r\nContent-Type: video/mp4\r\nContent-Length: %d\r\n\r\n",
		blossomtest.Auth(u.sk, "upload", hashOf(data), e.now.Unix()), len(data))
	conn.Write(data[:500])
	time.Sleep(100 * time.Millisecond)
	conn.Close()
	deadline := time.Now().Add(2 * time.Second)
	for len(e.tempFiles()) != 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if left := e.tempFiles(); len(left) != 0 {
		t.Fatalf("temp files left: %v", left)
	}
}

func TestSlowVideoBodyIsCut(t *testing.T) {
	clock := time.Unix(0, 0)
	r := &rateReader{r: strings.NewReader(strings.Repeat("x", 100)), min: 10, grace: time.Second, now: func() time.Time { return clock }}
	r.start = clock
	buf := make([]byte, 5)
	if _, err := r.Read(buf); err != nil {
		t.Fatalf("within the grace: %v", err)
	}
	clock = clock.Add(2 * time.Second) // 5 bytes in 2 s: below 10 bytes/s
	if _, err := r.Read(buf); !errors.Is(err, errTooSlow) {
		t.Fatalf("slow body: %v", err)
	}
}

func TestStartClearsWhatACrashLeftInTheTempDir(t *testing.T) {
	dir := t.TempDir()
	stale := filepath.Join(dir, "video-123.mp4")
	other := filepath.Join(dir, "keep.txt")
	os.WriteFile(stale, []byte("half a video"), 0o600)
	os.WriteFile(other, []byte("not ours"), 0o600)
	newEnvWith(t, Config{Limits: videoLimits(), TempDir: dir})
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Fatal("a stale video stayed")
	}
	if _, err := os.Stat(other); err != nil {
		t.Fatal("a file that is not ours was removed")
	}
}

func TestEveryVideoReadHasAStallDeadline(t *testing.T) {
	clock := time.Unix(100, 0)
	var deadlines []time.Time
	r := &rateReader{r: strings.NewReader("abc"), min: 1, grace: time.Minute, now: func() time.Time { return clock }, start: clock,
		stall: 30 * time.Second, setDeadline: func(d time.Time) error { deadlines = append(deadlines, d); return nil }}
	r.Read(make([]byte, 1))
	clock = clock.Add(time.Second)
	r.Read(make([]byte, 1))
	if len(deadlines) != 2 || !deadlines[0].Equal(time.Unix(130, 0)) || !deadlines[1].Equal(time.Unix(131, 0)) {
		t.Fatalf("deadlines: %v", deadlines)
	}
}

func TestAStalledVideoCountsForTheAddress(t *testing.T) {
	lim := videoLimits()
	lim.IPPerDay = 1
	e := newEnv(t, lim)
	u := newUser(t)
	data := bytes.Repeat([]byte{9}, 2000)
	conn, err := net.Dial("tcp", strings.TrimPrefix(e.srv.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	fmt.Fprintf(conn, "PUT /upload HTTP/1.1\r\nHost: x\r\nAuthorization: %s\r\nContent-Type: video/mp4\r\nContent-Length: %d\r\n\r\n",
		blossomtest.Auth(u.sk, "upload", hashOf(data), e.now.Unix()), len(data))
	conn.Write(data[:100])
	time.Sleep(100 * time.Millisecond)
	conn.Close()
	deadline := time.Now().Add(2 * time.Second)
	for len(e.tempFiles()) != 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	time.Sleep(50 * time.Millisecond)
	// the broken upload used the address's one upload of the day
	e.want(e.put(u, imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 429, CodeRate)
}

func TestAVideoBodyHasAnAbsoluteEnd(t *testing.T) {
	clock := time.Unix(100, 0)
	var deadlines []time.Time
	// a minute's grace: only the end can stop these reads
	r := &rateReader{r: strings.NewReader(strings.Repeat("x", 1000)), min: 1, grace: time.Minute, now: func() time.Time { return clock }, start: clock,
		stall: 30 * time.Second, end: clock.Add(40 * time.Second), setDeadline: func(d time.Time) error { deadlines = append(deadlines, d); return nil }}
	clock = clock.Add(20 * time.Second)
	if _, err := r.Read(make([]byte, 100)); err != nil {
		t.Fatal(err)
	}
	if !deadlines[0].Equal(time.Unix(140, 0)) { // the end comes before now + stall
		t.Fatalf("deadline %v", deadlines[0])
	}
	clock = clock.Add(25 * time.Second) // past the end, however fast
	if _, err := r.Read(make([]byte, 100)); !errors.Is(err, errTooSlow) {
		t.Fatalf("past the end: %v", err)
	}
}
