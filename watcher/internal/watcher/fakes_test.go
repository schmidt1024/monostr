package watcher

import (
	"context"
	"errors"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/lws"
	"monostr.com/watcher/internal/monero"
	"monostr.com/watcher/internal/protocol"
	"monostr.com/watcher/internal/ratelimit"
	"monostr.com/watcher/internal/store"
)

const (
	mainnetAddr  = "42nsXK8WbVGTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQdF28r3"
	stagenetAddr = "52zucA3UF6NTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnQf9eTdc"
	viewKey      = "cea3c5dfea43f31197bd4b7166da59f90af44b4afac2b0732d9fcbe2b7fa0c06"
	mainnetAddr2 = "49qUXGDJkmKec7SCQmAMLBGUAFk9ikkEAX9yFKNFvvHe3JFr9Bpbs7ejM5HBGqzAV6V8J3qkfqCoQLEVX4M2Y59TShdHphX" // vectors.json[1]
	viewKey2     = "b4c9b41540857d0a3dadbae45269eb63823e0cd766e3f202ccd07cf6b2482708"
	integrated   = "4CVYY7x1CknTNayQ6Kjw5UdgqbQY5KCCufdxdCgF7NgTfjC69Mna7DJSYyie77hZTQ8H92G2HwgFhgEUYnDzrnLnbxc5xHroTU8U3vykdq"
	hookURL      = "http://127.0.0.1:8080/internal/lws-hook"
	t0           = int64(1700000000)
)

type fakeLWS struct {
	mu          sync.Mutex
	ensured     []string // "address key"
	status      []string // "status address"
	hooks       []string // "address pid token url conf"
	deleted     []string
	rescans     []string // "height address,address"
	nextEventID int
	failEnsure  error
	failHook    error
	failRescan  error
	rescanErrs  []error // per-call result, recorded attempts included; nil past the end
}

func (f *fakeLWS) EnsureAccount(_ context.Context, address, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failEnsure != nil {
		return f.failEnsure
	}
	f.ensured = append(f.ensured, address+" "+key)
	return nil
}
func (f *fakeLWS) ModifyAccountStatus(_ context.Context, status string, addrs []string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, a := range addrs {
		f.status = append(f.status, status+" "+a)
	}
	return nil
}
func (f *fakeLWS) WebhookAdd(_ context.Context, address, pid, token, url string, conf uint32) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failHook != nil {
		return "", f.failHook
	}
	f.nextEventID++
	f.hooks = append(f.hooks, address+" "+pid+" "+token+" "+url+" "+strconv.Itoa(int(conf)))
	return "event" + strconv.Itoa(f.nextEventID), nil
}
func (f *fakeLWS) WebhookDeleteUUID(_ context.Context, ids []string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.deleted = append(f.deleted, ids...)
	return nil
}
func (f *fakeLWS) Rescan(_ context.Context, height uint64, addrs []string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failRescan != nil {
		return f.failRescan
	}
	f.rescans = append(f.rescans, strconv.FormatUint(height, 10)+" "+strings.Join(addrs, ","))
	if i := len(f.rescans) - 1; i < len(f.rescanErrs) {
		return f.rescanErrs[i]
	}
	return nil
}

type published struct {
	urls []string
	ev   nostr.Event
}

type fakeRelays struct {
	mu             sync.Mutex
	published      []published
	lists          map[string][2][]string // pubkey → {read, write}
	fetched        []string
	fail           bool
	fetch          []*nostr.Event
	fetchedURLs    [][]string
	fetchedFilters []nostr.Filter
	// onPublish, if set, runs after an event was recorded and before
	// Publish returns, outside the lock: it lets a test land a concurrent
	// hook while the service is waiting for the relays.
	onPublish func(ev nostr.Event)
}

func (f *fakeRelays) Publish(_ context.Context, urls []string, ev nostr.Event) (int, []error) {
	f.mu.Lock()
	f.published = append(f.published, published{urls: urls, ev: ev})
	fail, onPublish := f.fail, f.onPublish
	f.mu.Unlock()
	if onPublish != nil {
		onPublish(ev)
	}
	if fail {
		return 0, []error{errors.New("relay down")}
	}
	return len(urls), nil
}
func (f *fakeRelays) FetchRelayList(_ context.Context, _ []string, pubkey string) ([]string, []string, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fetched = append(f.fetched, pubkey)
	l, ok := f.lists[pubkey]
	return l[0], l[1], ok
}
func (f *fakeRelays) Fetch(_ context.Context, urls []string, filter nostr.Filter) []*nostr.Event {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.fetchedURLs = append(f.fetchedURLs, urls)
	f.fetchedFilters = append(f.fetchedFilters, filter)
	return f.fetch
}
func (f *fakeRelays) events() []nostr.Event {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]nostr.Event, 0, len(f.published))
	for _, p := range f.published {
		out = append(out, p.ev)
	}
	return out
}

type fakeHeight struct{ h uint64 }

func (f *fakeHeight) Height(context.Context) (uint64, error) { return f.h, nil }

type env struct {
	svc    *Service
	st     *store.Store
	lws    *fakeLWS
	relays *fakeRelays
	now    int64
	sk     string // watcher key
	pk     string
}

func newEnv(t *testing.T, network monero.Network) *env {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "w.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	e := &env{st: st, lws: &fakeLWS{}, relays: &fakeRelays{lists: map[string][2][]string{}}, now: t0}
	e.sk = nostr.GeneratePrivateKey()
	e.pk, _ = nostr.GetPublicKey(e.sk)
	svc, err := New(Config{
		SecretKey: e.sk, Network: network, Relays: []string{"wss://w1.example", "wss://w2.example"},
		RelayListRelays: []string{"wss://index.example"}, HookURL: hookURL, Version: "test",
		Now: func() int64 { return e.now },
	}, Deps{Store: st, LWS: e.lws, Relays: e.relays, Height: &fakeHeight{h: 3200000}, IntentLimit: ratelimit.New(3, time.Hour)})
	if err != nil {
		t.Fatal(err)
	}
	e.svc = svc
	return e
}

func (e *env) register(t *testing.T, recipientSK string) string {
	t.Helper()
	pk, _ := nostr.GetPublicKey(recipientSK)
	if err := e.svc.Register(context.Background(), pk, mainnetAddr, viewKey); err != nil {
		t.Fatal(err)
	}
	return pk
}

func intentEvent(t *testing.T, senderSK, recipientPK, pid string, amount int64, createdAt int64) *nostr.Event {
	t.Helper()
	ev := &nostr.Event{
		Kind: protocol.KindTipIntent, CreatedAt: nostr.Timestamp(createdAt), Content: "",
		Tags: nostr.Tags{
			{"e", strings.Repeat("b", 64)}, {"p", recipientPK}, {"amount", strconv.FormatInt(amount, 10)},
			{"pid", pid}, {"type", "like"}, {"expiration", strconv.FormatInt(createdAt+86400, 10)},
		},
	}
	if err := ev.Sign(senderSK); err != nil {
		t.Fatal(err)
	}
	return ev
}

func hook(token, pid, eventID, txHash string, conf uint32, amount int64) lws.Hook {
	return lws.Hook{Token: token, PaymentID: pid, EventID: eventID, TxHash: txHash, Confirmations: conf, Amount: amount, Block: 1}
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for i := 0; i < 100; i++ {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timeout waiting for %s", what)
}
