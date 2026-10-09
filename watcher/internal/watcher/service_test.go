package watcher

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"

	"monostr.com/watcher/internal/api"
	"monostr.com/watcher/internal/monero"
)

func TestInfo(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	info := e.svc.Info(context.Background())
	if info.Pubkey != e.pk || info.Network != "mainnet" || info.Height != 3200000 || info.Version != "test" ||
		len(info.Relays) != 2 || info.Relays[0] != "wss://w1.example" {
		t.Fatalf("%+v", info)
	}
}

func TestRegisterHappyPath(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk, _ := nostr.GetPublicKey(rsk)
	e.relays.lists[rpk] = [2][]string{{"wss://r.example"}, {"wss://w.example"}}
	if err := e.svc.Register(context.Background(), rpk, mainnetAddr, viewKey); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.ensured) != 1 || e.lws.ensured[0] != mainnetAddr+" "+viewKey {
		t.Fatalf("lws: %v", e.lws.ensured)
	}
	a, ok, _ := e.st.GetAccount(rpk)
	if !ok || a.Address != mainnetAddr || a.CreatedAt != t0 {
		t.Fatalf("account %+v %v", a, ok)
	}
	eventually(t, "relay list fetch", func() bool {
		read, _, ok, _ := e.st.GetRelayCache(rpk + ":read")
		return ok && len(read) == 1 && read[0] == "wss://r.example"
	})
	write, _, _, _ := e.st.GetRelayCache(rpk + ":write")
	if len(write) != 1 || write[0] != "wss://w.example" {
		t.Fatalf("write cache %v", write)
	}
}

func TestRegisterRejects(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rpk := strings.Repeat("c", 64)
	wrongKey := "0" + viewKey[1:]
	cases := map[string][2]string{
		"garbage address": {"4abc", viewKey},
		"integrated":      {integrated, viewKey},
		"wrong network":   {stagenetAddr, viewKey},
		"wrong view key":  {mainnetAddr, wrongKey},
		"short view key":  {mainnetAddr, "abcd"},
	}
	for name, c := range cases {
		err := e.svc.Register(context.Background(), rpk, c[0], c[1])
		var herr *api.HTTPError
		if !errors.As(err, &herr) || herr.Status != 400 {
			t.Errorf("%s: %v", name, err)
		}
		if strings.Contains(err.Error(), viewKey[:16]) {
			t.Errorf("%s: view key in error", name)
		}
	}
	if len(e.lws.ensured) != 0 {
		t.Fatal("lws called for rejected registration")
	}
	if _, ok, _ := e.st.GetAccount(rpk); ok {
		t.Fatal("account stored despite rejection")
	}
	e.lws.failEnsure = errors.New("lws add_account: HTTP 500")
	err := e.svc.Register(context.Background(), rpk, mainnetAddr, viewKey)
	var herr *api.HTTPError
	if !errors.As(err, &herr) || herr.Status != 502 {
		t.Fatalf("lws failure: %v", err)
	}
}

func TestRegisterReplacesAddress(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk := e.register(t, rsk)
	sender := nostr.GeneratePrivateKey()
	if err := e.svc.HandleIntent(context.Background(), intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0)); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.hooks) != 1 {
		t.Fatalf("hooks %v", e.lws.hooks)
	}
	// same address again: no inactive, intents untouched
	e.svc.Register(context.Background(), rpk, mainnetAddr, viewKey)
	if len(e.lws.status) != 0 || len(e.lws.deleted) != 0 {
		t.Fatalf("same address caused changes: %v %v", e.lws.status, e.lws.deleted)
	}
	// new address (second mainnet vector): old one retired, its open intents dropped
	if err := e.svc.Register(context.Background(), rpk, mainnetAddr2, viewKey2); err != nil {
		t.Fatal(err)
	}
	if len(e.lws.status) != 1 || e.lws.status[0] != "inactive "+mainnetAddr {
		t.Fatalf("old account not deactivated: %v", e.lws.status)
	}
	if len(e.lws.deleted) != 1 || e.lws.deleted[0] != "event1" {
		t.Fatalf("old webhook not deleted: %v", e.lws.deleted)
	}
	rows, _ := e.st.IntentsForRecipient(rpk)
	if len(rows) != 0 {
		t.Fatalf("open intents of old address kept: %v", rows)
	}
	a, _, _ := e.st.GetAccount(rpk)
	if a.Address != mainnetAddr2 {
		t.Fatal("address not replaced")
	}
	// new intents go to the new address
	e.svc.HandleIntent(context.Background(), intentEvent(t, sender, rpk, "0123456789abcdee", 5, t0+1))
	if len(e.lws.hooks) != 2 || !strings.HasPrefix(e.lws.hooks[1], mainnetAddr2+" ") {
		t.Fatalf("hooks %v", e.lws.hooks)
	}
}

func TestUnregister(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk := e.register(t, rsk)
	sender := nostr.GeneratePrivateKey()
	e.svc.HandleIntent(context.Background(), intentEvent(t, sender, rpk, "0123456789abcdef", 5, t0))
	if err := e.svc.Unregister(context.Background(), rpk); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := e.st.GetAccount(rpk); ok {
		t.Fatal("account kept")
	}
	if len(e.lws.status) != 1 || e.lws.status[0] != "inactive "+mainnetAddr {
		t.Fatalf("status %v", e.lws.status)
	}
	if len(e.lws.deleted) != 1 {
		t.Fatalf("webhook not deleted: %v", e.lws.deleted)
	}
	if rows, _ := e.st.IntentsForRecipient(rpk); len(rows) != 0 {
		t.Fatal("open intent kept")
	}
	// idempotent
	if err := e.svc.Unregister(context.Background(), rpk); err != nil {
		t.Fatalf("second unregister: %v", err)
	}
	// intents for an unregistered recipient are ignored
	e.svc.HandleIntent(context.Background(), intentEvent(t, sender, rpk, "0123456789abcdee", 5, t0))
	if len(e.lws.hooks) != 1 {
		t.Fatal("intent accepted after unregister")
	}
}

func TestSubscriptionRelaysAndFilter(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk, _ := nostr.GetPublicKey(rsk)
	e.relays.lists[rpk] = [2][]string{{"wss://w1.example", "wss://read.example"}, {"wss://write.example"}}
	e.register(t, rsk)
	eventually(t, "cache", func() bool { _, _, ok, _ := e.st.GetRelayCache(rpk + ":read"); return ok })
	urls := e.svc.SubscriptionRelays()
	if len(urls) != 3 || urls[0] != "wss://w1.example" || urls[1] != "wss://w2.example" || urls[2] != "wss://read.example" {
		t.Fatalf("urls %v", urls)
	}
	f := e.svc.IntentFilter()
	if len(f.Kinds) != 1 || f.Kinds[0] != 9738 || f.Since == nil || int64(*f.Since) != t0-86400 {
		t.Fatalf("filter %+v", f)
	}
	e.st.SetMeta(metaLastSeenIntent, "1700005000")
	if f := e.svc.IntentFilter(); int64(*f.Since) != 1700005000-IntentLookbackSeconds {
		t.Fatalf("since %d", *f.Since)
	}
}

// TestRefreshRelayListKeepsCacheOnFailure is finding F3: a transient relay
// failure (found == false) must not wipe out a previously cached, good
// relay list — that would drop the recipient's write relays for a day.
func TestRefreshRelayListKeepsCacheOnFailure(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	rsk := nostr.GeneratePrivateKey()
	rpk, _ := nostr.GetPublicKey(rsk)
	e.relays.lists[rpk] = [2][]string{{"wss://r.example"}, {"wss://w.example"}}
	e.register(t, rsk)
	eventually(t, "relay list fetch", func() bool { _, _, ok, _ := e.st.GetRelayCache(rpk + ":read"); return ok })

	readBefore, atBefore, _, _ := e.st.GetRelayCache(rpk + ":read")
	writeBefore, _, _, _ := e.st.GetRelayCache(rpk + ":write")

	delete(e.relays.lists, rpk)
	e.now = t0 + 100
	if found := e.svc.RefreshRelayList(context.Background(), rpk); found {
		t.Fatal("found true despite failed fetch")
	}

	readAfter, atAfter, _, _ := e.st.GetRelayCache(rpk + ":read")
	writeAfter, _, _, _ := e.st.GetRelayCache(rpk + ":write")
	if strings.Join(readAfter, ",") != strings.Join(readBefore, ",") {
		t.Fatalf("read cache changed: %v -> %v", readBefore, readAfter)
	}
	if strings.Join(writeAfter, ",") != strings.Join(writeBefore, ",") {
		t.Fatalf("write cache changed: %v -> %v", writeBefore, writeAfter)
	}
	if atAfter != atBefore {
		t.Fatalf("fetched_at changed: %d -> %d", atBefore, atAfter)
	}
}

// TestInfoAdvertisesPublicRelays is finding I5: in stagenet the watcher
// talks to its relay over the compose network (ws://relay:7777) but phones
// must be told the public URL.
func TestInfoAdvertisesPublicRelays(t *testing.T) {
	e := newEnv(t, monero.Mainnet)
	e.svc.cfg.PublicRelays = []string{"wss://stagenet.watcher.example/relay"}
	info := e.svc.Info(context.Background())
	if len(info.Relays) != 1 || info.Relays[0] != "wss://stagenet.watcher.example/relay" {
		t.Fatalf("relays %v", info.Relays)
	}
	e.svc.cfg.PublicRelays = nil
	if info := e.svc.Info(context.Background()); len(info.Relays) != 2 || info.Relays[0] != "wss://w1.example" {
		t.Fatalf("fallback relays %v", info.Relays)
	}
}
