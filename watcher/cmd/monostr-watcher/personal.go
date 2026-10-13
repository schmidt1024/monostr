package main

import (
	"context"
	"log/slog"
	"net/http"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/watcher/internal/nodes"
	"monostr.com/watcher/internal/personal"
	"monostr.com/watcher/internal/relays"
	"monostr.com/watcher/internal/store"
	"monostr.com/watcher/internal/walletrpc"
	"monostr.com/watcher/internal/watcher"
)

var _ watcher.LWSClient = (*walletrpc.Backend)(nil)

const (
	pollInterval   = 15 * time.Second
	statusInterval = 5 * time.Minute
	registerRetry  = 10 * time.Second
)

// registerWithRetry calls register until it succeeds: wallet-rpc starts
// together with the watcher and may not answer yet.
func registerWithRetry(ctx context.Context, register func(context.Context) error, every time.Duration, logger *slog.Logger) error {
	for {
		err := register(ctx)
		if err == nil {
			return nil
		}
		logger.Warn("registering the recipient failed, retrying", "err", err.Error())
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(every):
		}
	}
}

// registerBoth runs register until it has succeeded once, then ensureCheck;
// a retry after a failed ensureCheck does not register again.
func registerBoth(register, ensureCheck func(context.Context) error) func(context.Context) error {
	registered := false
	return func(ctx context.Context) error {
		if !registered {
			if err := register(ctx); err != nil {
				return err
			}
			registered = true
		}
		return ensureCheck(ctx)
	}
}

// scanSync is the scan wallet: positions 1, 3, 5, … of WATCHER_NODES.
func scanSync(nd *nodes.Nodes) walletrpc.Sync {
	return walletrpc.Sync{Role: "scan", Group: nd.ScanGroup(), Node: nd.Current, Height: nd.Height, Advance: nd.Advance}
}

// checkSync is the check wallet: positions 2, 4, 6, … of WATCHER_NODES.
func checkSync(nd *nodes.Nodes) walletrpc.Sync {
	return walletrpc.Sync{Role: "check", Group: nd.CheckGroup(), Node: nd.CheckNode, Height: nd.CheckHeight, Advance: nd.AdvanceCheck}
}

// lastRecordedHeight reads the chain height the sweep last recorded (0 = none).
func lastRecordedHeight(st *store.Store, logger *slog.Logger) func() uint64 {
	return func() uint64 {
		v, ok, err := st.GetMeta(watcher.MetaLastHeight)
		if err != nil {
			logger.Warn("last recorded height unavailable", "err", err.Error())
			return 0
		}
		if !ok {
			return 0
		}
		h, err := strconv.ParseUint(v, 10, 64)
		if err != nil {
			return 0
		}
		return h
	}
}

func runPersonal(logger *slog.Logger) error {
	cfg, err := loadPersonalConfig()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if cfg.proxy != nil {
		// relays (go-nostr dials with http.DefaultClient) and nodes go through the proxy;
		// the wallet-rpc client has its own transport
		http.DefaultTransport.(*http.Transport).Proxy = http.ProxyURL(cfg.proxy)
	}

	st, err := store.Open(cfg.dbPath)
	if err != nil {
		return err
	}
	defer st.Close()
	nd, err := nodes.New(cfg.nodes)
	if err != nil {
		return err
	}
	scan, check := scanSync(nd), checkSync(nd)
	scan.LastHeight = lastRecordedHeight(st, logger)
	check.LastHeight = scan.LastHeight
	wallet := walletrpc.New(cfg.walletRPC)
	backend := walletrpc.NewBackend(wallet, scan, proxyHostPort(cfg.proxy))
	checkWallet := walletrpc.New(cfg.walletCheckRPC)
	checkBackend := walletrpc.NewBackend(checkWallet, check, proxyHostPort(cfg.proxy))
	pool := relays.NewPool(ctx)
	svc, err := watcher.New(watcher.Config{
		SecretKey: cfg.secretKey, Network: cfg.network, Relays: cfg.relays,
		RelayListRelays: cfg.relayListRelays, Version: version,
	}, watcher.Deps{
		Store: st, LWS: backend, Relays: pool,
		Height: nd, Logger: logger,
	})
	if err != nil {
		return err
	}
	nprofile, _ := nip19.EncodeProfile(svc.Pubkey(), cfg.relays)
	logger.Info("personal watcher starting", "version", version, "network", cfg.network.String(),
		"nprofile", nprofile, "scan_node", nd.Current(), "check_node", nd.CheckNode(), "proxy", cfg.proxy != nil)

	// svc.Register opens the scan wallet (backend); startup also waits for the check wallet
	if err := registerWithRetry(ctx, registerBoth(func(ctx context.Context) error {
		return svc.Register(ctx, cfg.recipient, cfg.address, cfg.viewKey)
	}, func(ctx context.Context) error {
		return checkBackend.EnsureAccount(ctx, cfg.address, cfg.viewKey)
	}), registerRetry, logger); err != nil {
		if ctx.Err() != nil {
			return nil // stopped while wallet-rpc was not ready yet
		}
		return err
	}
	if err := svc.Backfill(ctx); err != nil {
		logger.Error("backfill", "err", err.Error())
	}
	if err := svc.Recover(ctx); err != nil {
		logger.Error("recover", "err", err.Error())
	}
	defer startLoops(ctx, svc, pool, cfg.sweepInterval, logger)()

	status := &personal.StatusPublisher{
		SecretKey: cfg.secretKey, Recipient: cfg.recipient, Address: cfg.address,
		Network: cfg.network.String(), Version: version, Relays: cfg.relays, Pool: pool,
		Height: nd.Height, Now: func() int64 { return time.Now().Unix() }, Log: logger,
	}
	poller := &personal.Poller{
		Wallet: wallet, CheckWallet: checkWallet, Nodes: nd, Intents: st, Handler: svc, Recipient: cfg.recipient,
		Proxy: proxyHostPort(cfg.proxy), Log: logger, Now: time.Now,
		Ensure: func(ctx context.Context) error {
			return backend.EnsureAccount(ctx, cfg.address, cfg.viewKey)
		},
		CheckEnsure: func(ctx context.Context) error {
			return checkBackend.EnsureAccount(ctx, cfg.address, cfg.viewKey)
		},
	}
	poll := func() {
		if err := poller.Poll(ctx); err != nil {
			logger.Error("poll", "err", err.Error())
		}
	}
	// a watcher that cannot read its wallet does not announce itself, so the
	// app shows it offline once the last status is older than 15 minutes
	publishStatus := func() {
		if !poller.Healthy(time.Now()) {
			logger.Warn("no successful wallet poll in the last 5 minutes, status not published")
			return
		}
		if err := status.Publish(ctx); err != nil {
			logger.Error("status", "err", err.Error())
		}
	}
	poll()
	publishStatus()
	pollTicker := time.NewTicker(pollInterval)
	defer pollTicker.Stop()
	statusTicker := time.NewTicker(statusInterval)
	defer statusTicker.Stop()
	logger.Info("personal watcher running; paste the nprofile into monostr", "nprofile", nprofile)
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-pollTicker.C:
			poll()
		case <-statusTicker.C:
			publishStatus()
		}
	}
}
