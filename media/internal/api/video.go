package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"monostr.com/media/internal/blossom"
	"monostr.com/media/internal/classify"
	"monostr.com/media/internal/imagecheck"
	"monostr.com/media/internal/store"
)

// Videos (spec 2026-10-27 video §6, §7).
const (
	// videoUploadTime is how long one admitted video may take to arrive and be
	// answered: 50 MiB through a slow line (1 Mbit/s is about 7 minutes). The
	// server-wide timeouts stay short; only an admitted video gets this much.
	videoUploadTime = 15 * time.Minute
	// maxVideoSeconds leaves room above the app's 121 s: encoding may add a little.
	maxVideoSeconds   = 125.0
	maxVideoShortEdge = 720
	// videoBucketTimeout bounds the write of one video to the bucket.
	videoBucketTimeout = 5 * time.Minute
	// tempPattern names the server's temp files, so that a start can clear what a crash left.
	tempPattern = "video-*.mp4"
)

// A body slower than videoMinRate (after videoGrace), or one that stalls for
// videoStall, gives up its upload slot.
var (
	videoGrace   = 30 * time.Second
	videoStall   = 30 * time.Second
	videoMinRate = int64(16 << 10) // bytes per second
	errTooSlow   = errors.New("api: video body slower than the minimum rate")
)

// rateReader fails once the average rate since start drops below min bytes
// per second, after a grace period, and every read gets a deadline stall
// ahead: neither a slow drip nor a stalled connection holds a slot for the
// whole upload time.
type rateReader struct {
	r           io.Reader
	min         int64
	grace       time.Duration
	now         func() time.Time
	start       time.Time
	n           int64
	stall       time.Duration
	end         time.Time             // no read after it, however fast the body is; zero: none
	setDeadline func(time.Time) error // nil: no per-read deadline
}

func (r *rateReader) Read(p []byte) (int, error) {
	now := r.now()
	if !r.end.IsZero() && !now.Before(r.end) {
		return 0, errTooSlow
	}
	if r.setDeadline != nil {
		deadline := now.Add(r.stall)
		if !r.end.IsZero() && r.end.Before(deadline) {
			deadline = r.end
		}
		_ = r.setDeadline(deadline)
	}
	if elapsed := now.Sub(r.start); elapsed > r.grace && float64(r.n) < elapsed.Seconds()*float64(r.min) {
		return 0, errTooSlow
	}
	n, err := r.r.Read(p)
	r.n += int64(n)
	return n, err
}

// uploadVideo takes an admitted video: to a temp file (never into memory)
// with its hash, the same hash, ban and duplicate checks as a picture, then
// the scorer's probe and frames decide, then the bucket. The temp file is gone
// on every way out.
func (s *server) uploadVideo(w http.ResponseWriter, r *http.Request, auth blossom.Auth, address string, now time.Time) {
	rc := http.NewResponseController(w)
	// the answer may take the whole upload time; the body gets a rolling deadline per read (rateReader)
	_ = rc.SetWriteDeadline(time.Now().Add(videoUploadTime))

	f, err := os.CreateTemp(s.tempDir, tempPattern)
	if err != nil {
		s.internal(w, "temp file", err)
		return
	}
	defer os.Remove(f.Name())
	defer f.Close()

	sum := sha256.New()
	body := &rateReader{r: http.MaxBytesReader(w, r.Body, s.cfg.Limits.MaxVideoBytes), min: videoMinRate, grace: videoGrace,
		now: time.Now, start: time.Now(), stall: videoStall, end: time.Now().Add(videoUploadTime), setDeadline: rc.SetReadDeadline}
	size, err := io.Copy(io.MultiWriter(f, sum), body)
	if err != nil {
		// a broken or too slow body cost a slot: it counts for the address, so it is not free to repeat
		s.ips.Add(address, now)
		var tooLarge *http.MaxBytesError
		switch {
		case errors.As(err, &tooLarge):
			reject(w, http.StatusRequestEntityTooLarge, CodeVideoTooLarge, fmt.Sprintf("video larger than %d bytes", s.cfg.Limits.MaxVideoBytes))
		case errors.Is(err, errTooSlow):
			reject(w, http.StatusRequestTimeout, CodeBadRequest, "the video arrives too slowly")
		default:
			reject(w, http.StatusBadRequest, CodeBadRequest, "cannot read the body")
		}
		return
	}
	hash := hex.EncodeToString(sum.Sum(nil))
	if !s.hashAllowed(w, r, auth, hash) {
		return
	}

	defer s.blobs.lock(hash)()

	if s.duplicate(w, auth, hash, address, now) {
		return
	}

	if _, err := f.Seek(0, io.SeekStart); err != nil {
		s.internal(w, "temp file rewind", err)
		return
	}
	info, err := s.classifier.Video(r.Context(), f, size)
	if err != nil {
		// as for a picture: a failed check counts for the address, never for the pubkey
		s.ips.Add(address, now)
		if errors.Is(err, classify.ErrUnreadable) {
			reject(w, http.StatusUnsupportedMediaType, CodeVideoFormat, "the bytes are not an MP4 video this server can read")
			return
		}
		s.log.Error("video classifier", "err", err.Error())
		reject(w, http.StatusServiceUnavailable, CodeUnavailable, "the content check is unavailable, try again later")
		return
	}
	if info.Container != "mp4" || info.VideoCodec != "h264" || (info.AudioCodec != "" && info.AudioCodec != "aac") {
		s.ips.Add(address, now)
		reject(w, http.StatusUnsupportedMediaType, CodeVideoFormat, "only MP4 with H.264 video and AAC sound (or none) is accepted")
		return
	}
	if info.Duration > maxVideoSeconds {
		s.ips.Add(address, now)
		reject(w, http.StatusRequestEntityTooLarge, CodeVideoTooLong, fmt.Sprintf("videos may last %.0f seconds at most", maxVideoSeconds))
		return
	}
	if min(info.Width, info.Height) > maxVideoShortEdge {
		s.ips.Add(address, now)
		reject(w, http.StatusRequestEntityTooLarge, CodeVideoFormat, fmt.Sprintf("the shorter side of a video may be %d pixels at most", maxVideoShortEdge))
		return
	}
	if info.Score >= s.cfg.Reject {
		if err := s.store.LogRejected(auth.Pubkey, hash, size, imagecheck.VideoMP4, info.Score, now.Unix()); err != nil {
			s.log.Error("log rejected", "err", err.Error())
		}
		s.ips.Add(address, now)
		reject(w, http.StatusForbidden, CodeNSFW, "this server does not accept adult content")
		return
	}

	if _, err := f.Seek(0, io.SeekStart); err != nil {
		s.internal(w, "temp file rewind", err)
		return
	}
	putCtx, cancel := context.WithTimeout(r.Context(), max(s.bucketTimeout, videoBucketTimeout))
	err = s.bucket.PutStream(putCtx, hash, f, size, imagecheck.VideoMP4)
	cancel()
	if err != nil {
		s.log.Error("bucket put", "err", err.Error())
		reject(w, http.StatusServiceUnavailable, CodeUnavailable, "storage is unavailable, try again later")
		return
	}
	blob := store.Blob{SHA256: hash, Size: size, Type: imagecheck.VideoMP4, Width: info.Width, Height: info.Height, CreatedAt: now.Unix()}
	s.record(w, auth, blob, info.Score, address, now)
}
