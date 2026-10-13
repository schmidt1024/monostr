// Command monostr-watcher runs the Monostr tip watcher (docs/superpowers/specs, section 4).
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/watcher/internal/api"
	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/monerod"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/ratelimit"
	"monostr.com/watcher/internal/relays"
	"monostr.com/watcher/internal/store"
	"monostr.com/watcher/internal/watcher"
)

// version is set with -ldflags "-X main.version=…".
var version = "dev"

type config struct {
	secretKey       string
	network         monero.Network
	relays          []string
	publicRelays    []string
	relayListRelays []string
	publicURL       string
	listen          string
	dbPath          string
	hookURL         string
	lwsAdminURL     string
	lwsAdminKey     string
	allowedPubkeys  []string
	monerodRPC      string
	sweepInterval   time.Duration
}

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// parsePubkeys reads a comma-separated list of npubs or 64-char hex pubkeys and returns hex.
func parsePubkeys(s string) ([]string, error) {
	var out []string
	for _, p := range splitList(s) {
		if strings.HasPrefix(p, "npub1") {
			prefix, v, err := nip19.Decode(p)
			if err != nil || prefix != "npub" {
				return nil, fmt.Errorf("invalid npub %q", p)
			}
			p = v.(string)
		}
		p = strings.ToLower(p)
		if !protocol.IsHex64(p) {
			return nil, fmt.Errorf("%q is neither an npub nor 64 hex chars", p)
		}
		out = append(out, p)
	}
	return out, nil
}

func loadConfig() (config, error) {
	var c config
	c.secretKey = env("WATCHER_SECRET_KEY", "")
	if !protocol.IsHex64(c.secretKey) {
		return c, errors.New("WATCHER_SECRET_KEY must be 64 lowercase hex chars")
	}
	net, err := monero.ParseNetwork(env("WATCHER_NETWORK", ""))
	if err != nil {
		return c, fmt.Errorf("WATCHER_NETWORK: %w", err)
	}
	c.network = net
	c.relays = splitList(env("WATCHER_RELAYS", ""))
	if len(c.relays) == 0 {
		return c, errors.New("WATCHER_RELAYS must list at least one relay")
	}
	c.publicRelays = splitList(env("WATCHER_PUBLIC_RELAYS", ""))
	if len(c.publicRelays) == 0 {
		c.publicRelays = c.relays
	}
	c.relayListRelays = splitList(env("WATCHER_RELAY_LIST_RELAYS", "wss://purplepag.es,wss://relay.damus.io"))
	c.publicURL = strings.TrimRight(env("WATCHER_PUBLIC_URL", ""), "/")
	if !strings.HasPrefix(c.publicURL, "http://") && !strings.HasPrefix(c.publicURL, "https://") {
		return c, errors.New("WATCHER_PUBLIC_URL must be an absolute http(s) URL")
	}
	c.listen = env("WATCHER_LISTEN", "127.0.0.1:8080")
	c.dbPath = env("WATCHER_DB", "/data/watcher.db")
	c.hookURL = env("WATCHER_HOOK_URL", "http://127.0.0.1:8080/internal/lws-hook")
	c.lwsAdminURL = env("LWS_ADMIN_URL", "http://127.0.0.1:8443/admin")
	c.lwsAdminKey = env("LWS_ADMIN_KEY", "")
	c.monerodRPC = env("MONEROD_RPC_URL", "http://127.0.0.1:18089")
	c.allowedPubkeys, err = parsePubkeys(env("WATCHER_ALLOWED_PUBKEYS", ""))
	if err != nil {
		return c, fmt.Errorf("WATCHER_ALLOWED_PUBKEYS: %w", err)
	}
	c.sweepInterval, err = time.ParseDuration(env("WATCHER_SWEEP_INTERVAL", "60s"))
	if err != nil || c.sweepInterval < time.Second {
		return c, errors.New("WATCHER_SWEEP_INTERVAL must be a duration >= 1s")
	}
	return c, nil
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)
	runner := run
	switch mode := env("WATCHER_MODE", "hosted"); mode {
	case "hosted":
	case "personal":
		runner = runPersonal
	default:
		logger.Error("fatal", "err", "WATCHER_MODE must be hosted or personal, got "+mode)
		os.Exit(1)
	}
	if err := runner(logger); err != nil {
		logger.Error("fatal", "err", err.Error())
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := loadConfig()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(cfg.dbPath)
	if err != nil {
		return fmt.Errorf("open db: %w", err)
	}
	defer st.Close()

	pool := relays.NewPool(ctx)
	svc, err := watcher.New(watcher.Config{
		SecretKey: cfg.secretKey, Network: cfg.network, Relays: cfg.relays, PublicRelays: cfg.publicRelays,
		RelayListRelays: cfg.relayListRelays,
		HookURL:         cfg.hookURL, Version: version,
	}, watcher.Deps{
		Store: st, LWS: lws.New(cfg.lwsAdminURL, cfg.lwsAdminKey), Relays: pool,
		Height: monerod.NewHeightClient(cfg.monerodRPC), Logger: logger,
	})
	if err != nil {
		return err
	}
	logger.Info("watcher starting", "version", version, "network", cfg.network.String(), "pubkey", svc.Pubkey(), "relays", cfg.relays)

	// Startup order (spec 4.4): the HTTP listener comes first so LWS hooks
	// fired during the rescan reach us; Backfill loads intents published
	// while we were down before Recover computes the rescan address set.
	handler := api.New(svc, cfg.publicURL, ratelimit.New(5, time.Hour), cfg.allowedPubkeys, logger)
	srv := &http.Server{Addr: cfg.listen, Handler: handler, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second}
	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()
	logger.Info("listening", "addr", cfg.listen)

	if err := svc.Backfill(ctx); err != nil {
		logger.Error("backfill", "err", err.Error())
	}
	if err := svc.Recover(ctx); err != nil {
		logger.Error("recover", "err", err.Error())
	}

	defer startLoops(ctx, svc, pool, cfg.sweepInterval, logger)()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return srv.Shutdown(shutdownCtx)
	case err := <-errCh:
		return err
	}
}

// startLoops runs the intent subscription and the periodic sweep/reconcile
// until ctx ends; stop closes the subscription.
func startLoops(ctx context.Context, svc *watcher.Service, pool *relays.Pool, sweepInterval time.Duration, logger *slog.Logger) (stop func()) {
	events := make(chan *nostr.Event, 256)
	sub := relays.NewSubscriber(pool, svc.IntentFilter(), events)
	sub.Reconcile(svc.SubscriptionRelays())
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case ev := <-events:
				if err := svc.HandleIntent(ctx, ev); err != nil {
					logger.Error("handle intent", "err", err.Error())
				}
			}
		}
	}()
	go func() {
		ticker := time.NewTicker(sweepInterval)
		defer ticker.Stop()
		reconcile := time.NewTicker(5 * time.Minute)
		defer reconcile.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := svc.Sweep(ctx); err != nil {
					logger.Error("sweep", "err", err.Error())
				}
			case <-reconcile.C:
				sub.Reconcile(svc.SubscriptionRelays())
				if err := svc.Backfill(ctx); err != nil {
					logger.Error("backfill", "err", err.Error())
				}
			}
		}
	}()
	return sub.Close
}
