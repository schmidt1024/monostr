package api

import (
	_ "embed"
	"html/template"
	"net/http"

	"github.com/nbd-wtf/go-nostr/nip19"
)

//go:embed home.html
var homeHTML string

var homeTmpl = template.Must(template.New("home").Parse(homeHTML))

type homeData struct {
	InfoResponse
	Npub string
	Open bool
}

// home answers a browser that opens the bare watcher URL (it is named in the store listing).
func (s *server) home(w http.ResponseWriter, r *http.Request) {
	info := s.svc.Info(r.Context())
	npub, err := nip19.EncodePublicKey(info.Pubkey)
	if err != nil {
		npub = info.Pubkey
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src https://monostr.com")
	homeTmpl.Execute(w, homeData{InfoResponse: info, Npub: npub, Open: len(s.allowed) == 0})
}
