// Command monostr-media runs the Monostr picture host (Blossom) and its
// operator commands:
//
//	monostr-media                 serve
//	monostr-media admin <command> see admin.go
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/api"
	"monostr.com/media/internal/classify"
	"monostr.com/media/internal/limits"
	"monostr.com/media/internal/nostrdm"
	"monostr.com/media/internal/review"
	"monostr.com/media/internal/storage"
	"monostr.com/media/internal/store"
)

// version is set with -ldflags "-X main.version=…".
var version = "dev"

type config struct {
	listen      string
	dbPath      string
	publicURL   string
	contactNpub string
	nsfwURL     string
	threshold   float64
	limits      limits.Limits
	maxInFlight int
	s3          storage.S3Config

	// reject is where refusal starts; between threshold and reject a picture
	// is stored and goes to review. It equals threshold when there is no reviewer.
	reject          float64
	reviewer        string // the operator's hex pubkey
	noReviewer      bool   // no MEDIA_REVIEW_NPUB: the grey zone is off
	nsec            string
	relays          []string
	reviewDMsPerDay int
}

// parseRelays reads a comma separated list of wss:// relay URLs.
func parseRelays(list string) ([]string, error) {
	var out []string
	for _, r := range strings.Split(list, ",") {
		r = strings.TrimSpace(r)
		if r == "" {
			continue
		}
		u, err := url.Parse(r)
		if err != nil || u.Scheme != "wss" || u.Host == "" {
			return nil, fmt.Errorf("MEDIA_NOSTR_RELAYS: %q is not a wss:// URL", r)
		}
		out = append(out, r)
	}
	if len(out) == 0 {
		return nil, errors.New("MEDIA_NOSTR_RELAYS names no relay")
	}
	return out, nil
}

func env(getenv func(string) string, key, def string) string {
	if v := strings.TrimSpace(getenv(key)); v != "" {
		return v
	}
	return def
}

func envInt(getenv func(string) string, key string, def int64) (int64, error) {
	v, err := strconv.ParseInt(env(getenv, key, strconv.FormatInt(def, 10)), 10, 64)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%s must be a positive whole number", key)
	}
	return v, nil
}

// loadConfig reads the environment; every limit has the default of spec section 1.
func loadConfig(getenv func(string) string) (config, error) {
	var c config
	var err error
	c.listen = env(getenv, "MEDIA_LISTEN", "127.0.0.1:8080")
	c.dbPath = env(getenv, "MEDIA_DB", "/data/media.db")
	c.publicURL = strings.TrimRight(env(getenv, "MEDIA_PUBLIC_URL", ""), "/")
	if !strings.HasPrefix(c.publicURL, "http://") && !strings.HasPrefix(c.publicURL, "https://") {
		return c, errors.New("MEDIA_PUBLIC_URL must be an absolute http(s) URL")
	}
	c.contactNpub = env(getenv, "MEDIA_CONTACT_NPUB", "")
	if !strings.HasPrefix(c.contactNpub, "npub1") {
		return c, errors.New("MEDIA_CONTACT_NPUB must be an npub")
	}
	c.nsfwURL = env(getenv, "MEDIA_NSFW_URL", "http://127.0.0.1:8081")
	c.threshold, err = strconv.ParseFloat(env(getenv, "MEDIA_NSFW_THRESHOLD", "0.60"), 64)
	if err != nil || math.IsNaN(c.threshold) || c.threshold <= 0 || c.threshold > 1 {
		return c, errors.New("MEDIA_NSFW_THRESHOLD must be a number above 0 and at most 1")
	}
	c.reject, err = strconv.ParseFloat(env(getenv, "MEDIA_NSFW_REJECT", "0.85"), 64)
	if err != nil || math.IsNaN(c.reject) || c.reject > 1 || c.reject < c.threshold {
		return c, errors.New("MEDIA_NSFW_REJECT must be a number from MEDIA_NSFW_THRESHOLD to 1")
	}
	c.nsec = env(getenv, "MEDIA_NOSTR_NSEC", "")
	if npub := env(getenv, "MEDIA_REVIEW_NPUB", ""); npub != "" {
		prefix, value, err := nip19.Decode(npub)
		hex, ok := value.(string)
		if err != nil || prefix != "npub" || !ok {
			return c, errors.New("MEDIA_REVIEW_NPUB must be an npub")
		}
		if c.nsec == "" {
			return c, errors.New("MEDIA_NOSTR_NSEC is required when MEDIA_REVIEW_NPUB is set")
		}
		c.reviewer = hex
	} else {
		// Nobody to look at the grey zone: a picture there is refused as before.
		c.noReviewer = true
		c.reject = c.threshold
	}
	if c.relays, err = parseRelays(env(getenv, "MEDIA_NOSTR_RELAYS", "wss://relay.monostr.com")); err != nil {
		return c, err
	}
	dms, err := envInt(getenv, "MEDIA_REVIEW_DMS_PER_DAY", api.DefaultReviewDMsPerDay)
	if err != nil {
		return c, err
	}
	c.reviewDMsPerDay = int(dms)
	if c.limits.MaxBytes, err = envInt(getenv, "MEDIA_MAX_BYTES", 10<<20); err != nil {
		return c, err
	}
	perDay, err := envInt(getenv, "MEDIA_PUBKEY_UPLOADS_PER_DAY", 50)
	if err != nil {
		return c, err
	}
	c.limits.PubkeyPerDay = int(perDay)
	if c.limits.PubkeyQuota, err = envInt(getenv, "MEDIA_PUBKEY_QUOTA_BYTES", 2<<30); err != nil {
		return c, err
	}
	ipPerDay, err := envInt(getenv, "MEDIA_IP_UPLOADS_PER_DAY", 100)
	if err != nil {
		return c, err
	}
	c.limits.IPPerDay = int(ipPerDay)
	if c.limits.GlobalBytesPerDay, err = envInt(getenv, "MEDIA_GLOBAL_BYTES_PER_DAY", 20<<30); err != nil {
		return c, err
	}
	if c.limits.MaxVideoBytes, err = envInt(getenv, "MEDIA_MAX_VIDEO_BYTES", 50<<20); err != nil {
		return c, err
	}
	videosPerDay, err := envInt(getenv, "MEDIA_PUBKEY_VIDEOS_PER_DAY", 5)
	if err != nil {
		return c, err
	}
	c.limits.PubkeyVideosPerDay = int(videosPerDay)
	if c.limits.StorageAlertBytes, err = envInt(getenv, "MEDIA_STORAGE_ALERT_BYTES", 200_000_000_000); err != nil {
		return c, err
	}
	inFlight, err := envInt(getenv, "MEDIA_MAX_UPLOADS_IN_FLIGHT", api.DefaultMaxInFlight)
	if err != nil {
		return c, err
	}
	c.maxInFlight = int(inFlight)
	c.s3 = storage.S3Config{
		Endpoint:  env(getenv, "S3_ENDPOINT", ""),
		Region:    env(getenv, "S3_REGION", ""),
		Bucket:    env(getenv, "S3_BUCKET", ""),
		AccessKey: env(getenv, "S3_ACCESS_KEY", ""),
		SecretKey: env(getenv, "S3_SECRET_KEY", ""),
	}
	return c, nil
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)
	if err := run(logger, os.Args[1:]); err != nil {
		if len(os.Args) > 1 {
			fmt.Fprintln(os.Stderr, "error:", err)
		} else {
			logger.Error("fatal", "err", err.Error())
		}
		os.Exit(1)
	}
}

func run(logger *slog.Logger, args []string) error {
	cfg, err := loadConfig(os.Getenv)
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.dbPath)
	if err != nil {
		return fmt.Errorf("open db: %w", err)
	}
	defer st.Close()
	bucket, err := storage.NewS3(cfg.s3)
	if err != nil {
		return err
	}
	if len(args) > 0 {
		if args[0] != "admin" {
			return fmt.Errorf("unknown command %q (only \"admin\")", args[0])
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()
		a := admin{store: st, bucket: bucket, publicURL: cfg.publicURL, threshold: cfg.threshold, out: os.Stdout, now: time.Now}
		return a.run(ctx, args[1:])
	}
	return serve(logger, cfg, st, bucket)
}

func serve(logger *slog.Logger, cfg config, st *store.Store, bucket storage.Bucket) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	deps := api.Deps{
		Store: st, Bucket: bucket, Classifier: classify.New(cfg.nsfwURL, 5*time.Second), Logger: logger,
	}
	if cfg.noReviewer {
		logger.Warn("no review npub: grey zone off")
	} else {
		// An upload never waits on Nostr: it only pokes the reviewer, and one
		// pending poke is as good as many.
		poke := make(chan struct{}, 1)
		deps.Notify = func() {
			select {
			case poke <- struct{}{}:
			default:
			}
		}
		client, err := nostrdm.New(ctx, cfg.nsec, cfg.reviewer, cfg.relays, cfg.publicURL, logger)
		if err != nil {
			return fmt.Errorf("review messages: %w", err)
		}
		npub, _ := nip19.EncodePublicKey(client.PublicKey())
		logger.Info("review messages on", "server_npub", npub, "relays", cfg.relays)
		reviewer := review.New(review.Config{PublicURL: cfg.publicURL, Operator: cfg.reviewer, StorageAlertBytes: cfg.limits.StorageAlertBytes}, review.Deps{
			Store: st, Bucket: bucket, Messenger: client, Logger: logger,
		})
		lastWrap := func() int64 {
			at, err := st.LastWrapAt()
			if err != nil {
				logger.Error("last wrap time", "err", err.Error())
			}
			return at
		}
		go reviewer.Run(ctx, poke)
		go client.Listen(ctx, lastWrap, reviewer.Handle)
		go client.AnnounceUntilDone(ctx)
	}
	handler, err := api.New(api.Config{
		PublicURL: cfg.publicURL, Limits: cfg.limits, Threshold: cfg.threshold, Reject: cfg.reject, ReviewDMsPerDay: cfg.reviewDMsPerDay,
		ContactNpub: cfg.contactNpub, Version: version, MaxInFlight: cfg.maxInFlight,
		// beside the database on the data volume: a video waits there while it is checked
		TempDir: filepath.Join(filepath.Dir(cfg.dbPath), "tmp"),
	}, deps)
	if err != nil {
		return err
	}
	// a 10 MB upload over a slow mobile link needs time; the header timeout guards against idle connections
	srv := &http.Server{Addr: cfg.listen, Handler: handler, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 5 * time.Minute, WriteTimeout: 5 * time.Minute}
	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()
	logger.Info("media server listening", "version", version, "addr", cfg.listen, "public", cfg.publicURL, "threshold", cfg.threshold, "reject", cfg.reject)
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	case err := <-errCh:
		return err
	}
}
