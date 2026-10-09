// Package relaytest starts an in-process Nostr relay (khatru + slicestore)
// on a random port for tests.
package relaytest

import (
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/fiatjaf/eventstore/slicestore"
	"github.com/fiatjaf/khatru"
)

func Start(t testing.TB) (string, *slicestore.SliceStore) {
	t.Helper()
	store := &slicestore.SliceStore{}
	if err := store.Init(); err != nil {
		t.Fatal(err)
	}
	rl := khatru.NewRelay()
	rl.StoreEvent = append(rl.StoreEvent, store.SaveEvent)
	rl.QueryEvents = append(rl.QueryEvents, store.QueryEvents)
	rl.DeleteEvent = append(rl.DeleteEvent, store.DeleteEvent)
	rl.ReplaceEvent = append(rl.ReplaceEvent, store.ReplaceEvent)
	srv := httptest.NewServer(rl)
	t.Cleanup(srv.Close)
	return "ws" + strings.TrimPrefix(srv.URL, "http"), store
}
