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
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
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
	mu     sync.Mutex
	score  float64
	err    error
	health error
	calls  int
	delay  time.Duration
	gate   chan struct{}
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
	now    time.Time
	log    *bytes.Buffer
}

func (e *env) clock() time.Time { return e.now }

var generous = limits.Limits{MaxBytes: 1 << 20, PubkeyPerDay: 50, PubkeyQuota: 10 << 20, IPPerDay: 100, GlobalBytesPerDay: 100 << 20}

func newEnv(t *testing.T, lim limits.Limits) *env {
	t.Helper()
	return newEnvWith(t, Config{Limits: lim})
}

// newEnvWith takes a Config whose Limits (and whatever else a test sets) are
// kept; the address, threshold, contact and version are filled in.
func newEnvWith(t *testing.T, cfg Config) *env {
	t.Helper()
	cfg.PublicURL, cfg.Threshold, cfg.ContactNpub, cfg.Version = publicURL+"/", 0.4, "npub1contact", "test"
	st, err := store.Open(filepath.Join(t.TempDir(), "m.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	e := &env{t: t, store: st, bucket: storagetest.NewMemory(), nsfw: &fakeClassifier{score: 0.05},
		now: time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC), log: &bytes.Buffer{}}
	h, err := New(cfg,
		Deps{Store: st, Bucket: e.bucket, Classifier: e.nsfw, Logger: slog.New(slog.NewTextHandler(e.log, nil)), Now: e.clock})
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
	e.want(head(u, "1000", "video/mp4"), 415, CodeType)
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
