package api

import (
	"net/http"

	"monostr.com/media/internal/blossom"
)

// accountLimits is the answer of GET /limits: what the signed-in account may do.
type accountLimits struct {
	MaxVideoSeconds int   `json:"max_video_seconds"`
	MaxVideoBytes   int64 `json:"max_video_bytes"`
	VideosPerDay    int   `json:"videos_per_day"`
	QuotaBytes      int64 `json:"quota_bytes"`
	UsedBytes       int64 `json:"used_bytes"`
}

// accountLimits answers GET /limits with the limits of the caller only. The
// token must be a limits token for this server, so the whitelist cannot be
// enumerated: nobody learns another pubkey's limits, and nothing about the
// pubkey is logged.
func (s *server) accountLimits(w http.ResponseWriter, r *http.Request) {
	auth, err := blossom.VerifyQuery(r.Header.Get("Authorization"), blossom.ActionLimits, s.host, s.now().Unix())
	if err != nil {
		reject(w, http.StatusUnauthorized, CodeAuth, err.Error())
		return
	}
	lim := s.limitsFor(auth.Pubkey)
	used, err := s.store.Usage(auth.Pubkey)
	if err != nil {
		s.internal(w, "usage", err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, accountLimits{
		MaxVideoSeconds: lim.MaxVideoSeconds, MaxVideoBytes: lim.MaxVideoBytes,
		VideosPerDay: lim.PubkeyVideosPerDay, QuotaBytes: lim.PubkeyQuota, UsedBytes: used,
	})
}
