package api

import (
	"context"
	"fmt"
	"html/template"
	"net/http"
	"time"
)

var pageTemplate = template.Must(template.New("page").Parse(`<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{.Host}}</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:3rem auto;padding:0 1rem;color:#111;background:#fff}code{word-break:break-all}@media(prefers-color-scheme:dark){body{color:#eee;background:#111}a{color:#f60}}</style>
<h1>{{.Host}}</h1>
<p>Picture host of <a href="https://monostr.com">Monostr</a>, a Nostr client with Monero tips. It speaks the <a href="https://github.com/hzrd149/blossom">Blossom</a> protocol.</p>
<h2>Rules</h2>
<ul>
<li>Pictures only: JPEG, PNG, WebP, GIF, up to {{.MaxBytes}} each.</li>
<li>No adult content. Uploads are checked automatically and refused.</li>
<li>Per account: {{.PubkeyPerDay}} uploads a day, {{.PubkeyQuota}} in all.</li>
<li>Everything stored here is public to anyone who has the address.</li>
<li>The server keeps which account uploaded which picture and when. It keeps no IP addresses.</li>
</ul>
<h2>Report a picture</h2>
<p>Send its address in a Nostr message to <code>{{.Contact}}</code>.</p>
</html>
`))

func humanBytes(n int64) string {
	const mb = 1 << 20
	if n >= 1<<30 && n%(1<<30) == 0 {
		return fmt.Sprintf("%d GB", n>>30)
	}
	if n >= mb {
		return fmt.Sprintf("%d MB", n/mb)
	}
	return fmt.Sprintf("%d bytes", n)
}

// page is GET /: the rules, the limits and where to report.
func (s *server) page(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
	pageTemplate.Execute(w, map[string]any{
		"Host":         s.host,
		"MaxBytes":     humanBytes(s.cfg.Limits.MaxBytes),
		"PubkeyPerDay": s.cfg.Limits.PubkeyPerDay,
		"PubkeyQuota":  humanBytes(s.cfg.Limits.PubkeyQuota),
		"Contact":      s.cfg.ContactNpub,
	})
}

// healthz reports whether the database, the bucket and the scorer answer.
func (s *server) healthz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	state := func(err error) string {
		if err != nil {
			return "down"
		}
		return "ok"
	}
	checks := map[string]error{
		"store":  s.store.Ping(),
		"bucket": s.bucket.Ping(ctx),
		"nsfw":   s.classifier.Health(ctx),
	}
	status := http.StatusOK
	out := map[string]string{"version": s.cfg.Version}
	for name, err := range checks {
		out[name] = state(err)
		if err != nil {
			status = http.StatusServiceUnavailable
			s.log.Error("health", "part", name, "err", err.Error())
		}
	}
	writeJSON(w, status, out)
}
