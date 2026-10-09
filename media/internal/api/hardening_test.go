package api

import (
	"bytes"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"monostr.com/media/internal/blossom/blossomtest"
	"monostr.com/media/internal/classify"
	"monostr.com/media/internal/imagetest"
	"monostr.com/media/internal/store"
)

// parallel uploads n distinct pictures at once, each by the user and from the
// address that pick returns for it, and gives back the statuses.
func (e *env) parallel(n int, pick func(i int) (user, string)) []int {
	e.t.Helper()
	statuses := make([]int, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		u, addr := pick(i)
		data := imagetest.PNG(40, 30, uint8(i+1))
		wg.Add(1)
		go func() {
			defer wg.Done()
			req, _ := http.NewRequest(http.MethodPut, e.srv.URL+"/upload", bytes.NewReader(data))
			req.Header.Set("Authorization", blossomtest.Auth(u.sk, "upload", hashOf(data), e.now.Unix()))
			req.Header.Set("Content-Type", "image/png")
			req.Header.Set("X-Forwarded-For", addr)
			res, err := http.DefaultClient.Do(req)
			if err != nil {
				return
			}
			res.Body.Close()
			statuses[i] = res.StatusCode
		}()
	}
	wg.Wait()
	return statuses
}

// hold makes the classifier keep every call until the returned func is called;
// the end of the test calls it at the latest, so that a failing test does not
// leave requests hanging.
func (e *env) hold() (release func()) {
	gate := make(chan struct{})
	e.nsfw.mu.Lock()
	e.nsfw.gate = gate
	e.nsfw.mu.Unlock()
	release = sync.OnceFunc(func() {
		e.nsfw.mu.Lock()
		e.nsfw.gate = nil
		e.nsfw.mu.Unlock()
		close(gate)
	})
	e.t.Cleanup(release)
	return release
}

func count(statuses []int, want int) int {
	n := 0
	for _, s := range statuses {
		if s == want {
			n++
		}
	}
	return n
}

// The limits are checked and reserved in one step: requests that arrive
// together cannot all pass a check that each of them alone would pass.
func TestParallelUploadsDoNotPassTheLimits(t *testing.T) {
	addr := func(i int) string { return fmt.Sprintf("203.0.113.%d", i+1) }

	lim := generous
	lim.PubkeyPerDay = 2
	e := newEnv(t, lim)
	e.nsfw.delay = 20 * time.Millisecond
	one := newUser(t)
	statuses := e.parallel(30, func(i int) (user, string) { return one, addr(i) })
	if count(statuses, 201) != 2 || e.bucket.Len() != 2 {
		t.Fatalf("pubkey limit 2: %d stored, %d objects, statuses %v", count(statuses, 201), e.bucket.Len(), statuses)
	}
	if count(statuses, 429) != 28 {
		t.Fatalf("the others must be told about the limit: %v", statuses)
	}

	size := int64(len(imagetest.PNG(40, 30, 1)))
	lim = generous
	lim.GlobalBytesPerDay = 3*size + size/2 // room for three pictures of this size
	g := newEnv(t, lim)
	g.nsfw.delay = 20 * time.Millisecond
	users := make([]user, 30)
	for i := range users {
		users[i] = newUser(t)
	}
	statuses = g.parallel(30, func(i int) (user, string) { return users[i], addr(i) })
	if n := count(statuses, 201); n < 1 || n > 3 || g.bucket.Len() != n {
		t.Fatalf("global cap of three pictures: %d stored, %d objects, statuses %v", n, g.bucket.Len(), statuses)
	}
	stored, _ := g.store.StoredBytesSince(0)
	if stored > lim.GlobalBytesPerDay {
		t.Fatalf("the global cap was passed: %d > %d", stored, lim.GlobalBytesPerDay)
	}

	lim = generous
	lim.IPPerDay = 3
	p := newEnv(t, lim)
	p.nsfw.delay = 20 * time.Millisecond
	statuses = p.parallel(30, func(i int) (user, string) { return users[i], "198.51.100.7" })
	if n := count(statuses, 201); n != 3 || p.bucket.Len() != 3 {
		t.Fatalf("address limit 3: %d stored, %d objects, statuses %v", n, p.bucket.Len(), statuses)
	}
}

// Every upload in flight holds its picture in memory; without a bound a few
// dozen slow uploads fill the container.
func TestUploadsInFlightAreBounded(t *testing.T) {
	e := newEnvWith(t, Config{Limits: generous, MaxInFlight: 2})
	release := e.hold()
	users := []user{newUser(t), newUser(t)}
	first := make(chan []int)
	go func() {
		first <- e.parallel(2, func(i int) (user, string) { return users[i], fmt.Sprintf("203.0.113.%d", i+1) })
	}()
	deadline := time.Now().Add(5 * time.Second)
	for e.nsfw.waiting() < 2 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if e.nsfw.waiting() != 2 {
		t.Fatal("the first two uploads never reached the classifier")
	}

	third := e.put(newUser(t), imagetest.JPEG(40, 30, 9), "image/jpeg", map[string]string{"X-Forwarded-For": "203.0.113.3"})
	e.want(third, 503, CodeUnavailable)
	if third.header.Get("Retry-After") == "" {
		t.Fatal("a busy server should say when to come back")
	}
	// reading pictures is not affected
	e.want(e.request(http.MethodGet, "/"+strings.Repeat("a", 64), nil, nil), 404, CodeNotFound)

	release()
	if statuses := <-first; count(statuses, 201) != 2 {
		t.Fatalf("the uploads in flight: %v", statuses)
	}
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 9), "image/jpeg", nil), 201, "")
}

func TestOneAddressCannotHoldManyUploadsAtOnce(t *testing.T) {
	e := newEnvWith(t, Config{Limits: generous, MaxInFlight: 50})
	release := e.hold()
	users := make([]user, maxInFlightPerAddress)
	for i := range users {
		users[i] = newUser(t)
	}
	held := make(chan []int)
	go func() {
		held <- e.parallel(maxInFlightPerAddress, func(i int) (user, string) { return users[i], "203.0.113.1" })
	}()
	deadline := time.Now().Add(5 * time.Second)
	for e.nsfw.waiting() < maxInFlightPerAddress && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if e.nsfw.waiting() != maxInFlightPerAddress {
		t.Fatal("the held uploads never reached the classifier")
	}
	more := e.put(newUser(t), imagetest.JPEG(40, 30, 9), "image/jpeg", map[string]string{"X-Forwarded-For": "203.0.113.1"})
	e.want(more, 429, CodeRate)
	release()
	if statuses := <-held; count(statuses, 201) != maxInFlightPerAddress {
		t.Fatalf("the held uploads: %v", statuses)
	}
}

// One signed authorization can be replayed until it expires. A replay must
// not grow the log, and it must count against the address.
func TestRepeatedDuplicatesLeaveNoTrailAndCountAgainstTheAddress(t *testing.T) {
	lim := generous
	lim.IPPerDay = 4
	e := newEnv(t, lim)
	owner, other := newUser(t), newUser(t)
	jpg := imagetest.JPEG(40, 30, 1)
	from := map[string]string{"X-Forwarded-For": "203.0.113.1"}

	e.want(e.put(owner, jpg, "image/jpeg", from), 201, "")
	for i := 0; i < 3; i++ {
		e.want(e.put(owner, jpg, "image/jpeg", from), 200, "")
	}
	log, _ := e.store.Log(owner.pk, 50)
	if len(log) != 1 || log[0].Outcome != store.OutcomeStored {
		t.Fatalf("log of the owner after three replays: %+v", log)
	}
	// one stored and three duplicates used up the address's four uploads
	e.want(e.put(owner, jpg, "image/jpeg", from), 429, CodeRate)

	// another person's copy is a new ownership and is logged, once
	elsewhere := map[string]string{"X-Forwarded-For": "203.0.113.2"}
	e.want(e.put(other, jpg, "image/jpeg", elsewhere), 200, "")
	e.want(e.put(other, jpg, "image/jpeg", elsewhere), 200, "")
	log, _ = e.store.Log(other.pk, 50)
	if len(log) != 1 || log[0].Outcome != store.OutcomeDuplicate {
		t.Fatalf("log of the second owner: %+v", log)
	}
}

// The response to an upload can get lost. The client then sends it again, and
// that must work even when the first attempt used up the last of a limit.
func TestARetryOfAnUploadThatWentThroughIsNotStoppedByTheLimits(t *testing.T) {
	jpg := imagetest.JPEG(40, 30, 1)
	size := int64(len(jpg))

	lim := generous
	lim.PubkeyPerDay = 1
	e := newEnv(t, lim)
	u := newUser(t)
	e.want(e.put(u, jpg, "image/jpeg", nil), 201, "")
	e.want(e.put(u, jpg, "image/jpeg", nil), 200, "")
	e.want(e.put(u, imagetest.JPEG(40, 30, 2), "image/jpeg", nil), 429, CodeRate) // a new picture is still stopped

	lim = generous
	lim.PubkeyQuota = size + 10
	q := newEnv(t, lim)
	q.want(q.put(u, jpg, "image/jpeg", nil), 201, "")
	q.want(q.put(u, jpg, "image/jpeg", nil), 200, "")
	q.want(q.put(u, imagetest.JPEG(40, 30, 2), "image/jpeg", nil), 413, CodeQuota)

	lim = generous
	lim.GlobalBytesPerDay = size + 10
	g := newEnv(t, lim)
	g.want(g.put(u, jpg, "image/jpeg", nil), 201, "")
	g.want(g.put(u, jpg, "image/jpeg", nil), 200, "")
	// a copy for another person stores nothing new, so the day's cap does not stop it
	g.want(g.put(newUser(t), jpg, "image/jpeg", nil), 200, "")
	g.want(g.put(newUser(t), imagetest.JPEG(40, 30, 2), "image/jpeg", nil), 429, CodeRate)
}

// Naming a picture the server already has does not open a way around the limits.
func TestNamingAStoredPictureDoesNotLetAnotherOneIn(t *testing.T) {
	lim := generous
	lim.PubkeyPerDay = 1
	e := newEnv(t, lim)
	u := newUser(t)
	stored, other := imagetest.JPEG(40, 30, 1), imagetest.JPEG(40, 30, 2)
	e.want(e.put(u, stored, "image/jpeg", nil), 201, "")
	// the authorization names the stored picture, the body is another one
	lie := blossomtest.Auth(u.sk, "upload", hashOf(stored), e.now.Unix())
	e.want(e.put(u, other, "image/jpeg", map[string]string{"Authorization": lie}), 409, CodeHashMismatch)
	// the header names the stored picture, authorization and body are another
	// one: a header the authorization does not cover announces nothing, so the
	// upload meets the limit like any new picture
	e.want(e.put(u, other, "image/jpeg", map[string]string{"X-SHA-256": hashOf(stored)}), 429, CodeRate)
	e.want(e.put(u, other, "image/jpeg", nil), 429, CodeRate)
	if e.bucket.Len() != 1 {
		t.Fatalf("objects: %d", e.bucket.Len())
	}
}

// A content check that fails costs the server work; it counts against the
// address, so that failing on purpose is not free. It never counts against
// the pubkey.
func TestFailedContentChecksCountAgainstTheAddress(t *testing.T) {
	lim := generous
	lim.IPPerDay = 2
	e := newEnv(t, lim)
	u := newUser(t)
	from := map[string]string{"X-Forwarded-For": "203.0.113.1"}
	e.nsfw.err = errors.New("timeout")
	e.want(e.put(u, imagetest.JPEG(40, 30, 1), "image/jpeg", from), 503, CodeUnavailable)
	e.nsfw.err = classify.ErrUnreadable
	e.want(e.put(u, imagetest.JPEG(40, 30, 2), "image/jpeg", from), 415, CodeType)
	e.nsfw.err = nil
	e.want(e.put(u, imagetest.JPEG(40, 30, 3), "image/jpeg", from), 429, CodeRate)
	if n, _ := e.store.UploadsSince(u.pk, 0); n != 0 {
		t.Fatalf("failed checks counted against the pubkey: %d", n)
	}
	// another address is not affected
	e.want(e.put(u, imagetest.JPEG(40, 30, 3), "image/jpeg", map[string]string{"X-Forwarded-For": "203.0.113.2"}), 201, "")
}

func TestASlowBucketDoesNotHoldAnUploadForever(t *testing.T) {
	e := newEnvWith(t, Config{Limits: generous, BucketTimeout: 50 * time.Millisecond})
	e.bucket.BlockPut = true
	started := time.Now()
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 503, CodeUnavailable)
	if took := time.Since(started); took > 3*time.Second {
		t.Fatalf("gave up only after %v", took)
	}
	e.bucket.BlockPut = false
	e.want(e.put(newUser(t), imagetest.JPEG(40, 30, 1), "image/jpeg", nil), 201, "")
}
