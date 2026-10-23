package review

import (
	"fmt"
	"strings"
	"time"
	_ "time/tzdata" // the container image is distroless and has no zoneinfo

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/imagecheck"
	"monostr.com/media/internal/store"
)

const (
	noticeFmt   = "Prüffall #%s · NSFW %s\n%s\nvon %s\nAntwort «weg» löscht und sperrt das Bild, ohne Antwort bleibt es."
	summaryFmt  = "%d weitere Prüffälle seit %s, siehe admin review"
	doneFmt     = "gelöscht #%s"
	noneText    = "kein offener Prüffall"
	notFoundFmt = "#%s nicht gefunden"
	ambigFmt    = "#%s ist mehrdeutig"
	failedFmt   = "Fehler bei #%s, nichts gelöscht. Bitte später nochmal «weg %s»."
	failedText  = "Fehler, nichts gelöscht. Bitte später nochmal."
	manyNewText = "Mehrere neue Prüffälle, bitte «weg <code>»."
	unknownText = "Ich verstehe nur «weg» oder «weg <code>»."
)

// zurich is the operator's clock; the fallback to UTC only matters if the
// embedded tz database were ever missing.
var zurich = func() *time.Location {
	loc, err := time.LoadLocation("Europe/Zurich")
	if err != nil {
		return time.UTC
	}
	return loc
}()

// Code is the short handle of a case in a DM: the first 4 hex characters of
// the hash, extended until no other open case shares the prefix.
func Code(sha256 string, open []string) string {
	n := 4
	for _, o := range open {
		if o == sha256 {
			continue
		}
		common := 0
		for common < len(o) && common < len(sha256) && o[common] == sha256[common] {
			common++
		}
		if common+1 > n {
			n = common + 1
		}
	}
	if n > len(sha256) {
		n = len(sha256)
	}
	return sha256[:n]
}

// ParseCommand reads «weg» or «weg <code>», tolerating case, surrounding
// whitespace and a leading "#" on the code. The code comes back lowercase.
func ParseCommand(text string) (code string, ok bool) {
	f := strings.Fields(strings.ToLower(text))
	if len(f) == 0 || f[0] != "weg" || len(f) > 2 {
		return "", false
	}
	if len(f) == 1 {
		return "", true
	}
	code = strings.TrimPrefix(f[1], "#")
	if code == "" {
		return "", false
	}
	for _, c := range code {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return "", false
		}
	}
	return code, true
}

func formatScore(score float64) string {
	return strings.Replace(fmt.Sprintf("%.2f", score), ".", ",", 1)
}

func noticeText(publicURL, code string, c store.ReviewCase) string {
	npub, err := nip19.EncodePublicKey(c.Pubkey)
	if err != nil {
		npub = c.Pubkey
	}
	url := strings.TrimRight(publicURL, "/") + "/" + c.SHA256 + "." + imagecheck.Ext(c.Type)
	return fmt.Sprintf(noticeFmt, code, formatScore(c.Score), url, npub)
}

// summaryText names the oldest case's time, with the date when it is not from
// today (Zurich) so "seit 23:40" is never read as today.
func summaryText(count int, oldest, now time.Time) string {
	o := oldest.In(zurich)
	layout := "15:04"
	if o.Format("2006-01-02") != now.In(zurich).Format("2006-01-02") {
		layout = "02.01. 15:04"
	}
	return fmt.Sprintf(summaryFmt, count, o.Format(layout))
}
