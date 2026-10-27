package main

import (
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/limits"
)

func getenv(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

var minimal = map[string]string{
	"MEDIA_PUBLIC_URL":   "https://media.example/",
	"MEDIA_CONTACT_NPUB": "npub1contact",
}

func with(extra map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range minimal {
		out[k] = v
	}
	for k, v := range extra {
		out[k] = v
	}
	return out
}

func TestLoadConfigDefaultsAreTheSpecLimits(t *testing.T) {
	c, err := loadConfig(getenv(minimal))
	if err != nil {
		t.Fatal(err)
	}
	// spec 2026-10-27 video §6: 2 GiB per pubkey, 20 GiB a day, videos up to 50 MiB, 5 a day, a word at 200 GB
	want := limits.Limits{MaxBytes: 10 << 20, PubkeyPerDay: 50, PubkeyQuota: 2 << 30, IPPerDay: 100, GlobalBytesPerDay: 20 << 30,
		MaxVideoBytes: 50 << 20, PubkeyVideosPerDay: 5, StorageAlertBytes: 200_000_000_000}
	if c.limits != want {
		t.Fatalf("limits: %+v", c.limits)
	}
	if c.threshold != 0.60 || c.publicURL != "https://media.example" || c.dbPath != "/data/media.db" || c.nsfwURL != "http://127.0.0.1:8081" {
		t.Fatalf("config: %+v", c)
	}
	if c.maxInFlight != 16 {
		t.Fatalf("uploads in flight: %d", c.maxInFlight)
	}
}

func TestLoadConfigUploadsInFlight(t *testing.T) {
	c, err := loadConfig(getenv(with(map[string]string{"MEDIA_MAX_UPLOADS_IN_FLIGHT": "4"})))
	if err != nil || c.maxInFlight != 4 {
		t.Fatalf("override: %d %v", c.maxInFlight, err)
	}
	for _, bad := range []string{"0", "-1", "many"} {
		if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_MAX_UPLOADS_IN_FLIGHT": bad}))); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestLoadConfigOverridesAndS3(t *testing.T) {
	c, err := loadConfig(getenv(with(map[string]string{
		"MEDIA_MAX_BYTES": "2048", "MEDIA_PUBKEY_UPLOADS_PER_DAY": "3", "MEDIA_PUBKEY_QUOTA_BYTES": "4096",
		"MEDIA_IP_UPLOADS_PER_DAY": "5", "MEDIA_GLOBAL_BYTES_PER_DAY": "8192", "MEDIA_NSFW_THRESHOLD": "0.33",
		"MEDIA_MAX_VIDEO_BYTES": "16384", "MEDIA_PUBKEY_VIDEOS_PER_DAY": "2", "MEDIA_STORAGE_ALERT_BYTES": "32768",
		"S3_ENDPOINT": "https://s3.example", "S3_REGION": "hel1", "S3_BUCKET": "b", "S3_ACCESS_KEY": "a", "S3_SECRET_KEY": "s",
	})))
	if err != nil {
		t.Fatal(err)
	}
	want := limits.Limits{MaxBytes: 2048, PubkeyPerDay: 3, PubkeyQuota: 4096, IPPerDay: 5, GlobalBytesPerDay: 8192,
		MaxVideoBytes: 16384, PubkeyVideosPerDay: 2, StorageAlertBytes: 32768}
	if c.limits != want || c.threshold != 0.33 || c.s3.Bucket != "b" || c.s3.Endpoint != "https://s3.example" {
		t.Fatalf("config: %+v", c)
	}
}

func TestLoadConfigRejects(t *testing.T) {
	for name, env := range map[string]map[string]string{
		"no public url":          {"MEDIA_CONTACT_NPUB": "npub1contact"},
		"relative public url":    with(map[string]string{"MEDIA_PUBLIC_URL": "media.example"}),
		"no contact":             {"MEDIA_PUBLIC_URL": "https://media.example"},
		"contact not an npub":    with(map[string]string{"MEDIA_CONTACT_NPUB": "someone@example.com"}),
		"threshold zero":         with(map[string]string{"MEDIA_NSFW_THRESHOLD": "0"}),
		"threshold above one":    with(map[string]string{"MEDIA_NSFW_THRESHOLD": "1.5"}),
		"threshold not a number": with(map[string]string{"MEDIA_NSFW_THRESHOLD": "strict"}),
		"max bytes zero":         with(map[string]string{"MEDIA_MAX_BYTES": "0"}),
		"quota not a number":     with(map[string]string{"MEDIA_PUBKEY_QUOTA_BYTES": "500MB"}),
		"negative daily limit":   with(map[string]string{"MEDIA_PUBKEY_UPLOADS_PER_DAY": "-1"}),
	} {
		if _, err := loadConfig(getenv(env)); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
}

// operatorNpub is a valid npub for the review tests.
var operatorNpub = func() string {
	n, err := nip19.EncodePublicKey(strings.Repeat("ab", 32))
	if err != nil {
		panic(err)
	}
	return n
}()

func TestConfigReject(t *testing.T) {
	c, err := loadConfig(getenv(with(map[string]string{"MEDIA_REVIEW_NPUB": operatorNpub, "MEDIA_NOSTR_NSEC": "nsec1x"})))
	if err != nil || c.reject != 0.85 || c.threshold != 0.60 {
		t.Fatalf("default: %+v %v", c, err)
	}
	if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_NSFW_REJECT": "0.5"}))); err == nil {
		t.Error("a reject below the threshold was accepted")
	}
	c, err = loadConfig(getenv(with(map[string]string{"MEDIA_NSFW_REJECT": "0.6"})))
	if err != nil || c.reject != c.threshold {
		t.Fatalf("reject equal to the threshold: %+v %v", c, err)
	}
	if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_NSFW_THRESHOLD": "NaN"}))); err == nil {
		t.Error("a NaN threshold was accepted")
	}
	for _, bad := range []string{"x", "1.5", "NaN"} {
		if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_NSFW_REJECT": bad}))); err == nil {
			t.Errorf("reject %q accepted", bad)
		}
	}
}

func TestConfigReviewNeedsNsec(t *testing.T) {
	if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_REVIEW_NPUB": operatorNpub}))); err == nil {
		t.Fatal("a review npub without a server key was accepted")
	}
	if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_REVIEW_NPUB": "npub1nonsense", "MEDIA_NOSTR_NSEC": "nsec1x"}))); err == nil {
		t.Fatal("a broken review npub was accepted")
	}
	c, err := loadConfig(getenv(with(map[string]string{"MEDIA_REVIEW_NPUB": operatorNpub, "MEDIA_NOSTR_NSEC": "nsec1x"})))
	if err != nil || c.reviewer != strings.Repeat("ab", 32) || c.noReviewer {
		t.Fatalf("configured: %+v %v", c, err)
	}
}

func TestConfigWithoutReviewer(t *testing.T) {
	c, err := loadConfig(getenv(with(map[string]string{"MEDIA_NSFW_REJECT": "0.9"})))
	if err != nil {
		t.Fatal(err)
	}
	if !c.noReviewer || c.reject != c.threshold {
		t.Fatalf("without a reviewer the grey zone must be off: %+v", c)
	}
}

func TestConfigRelaysAndDMCap(t *testing.T) {
	c, err := loadConfig(getenv(minimal))
	if err != nil || len(c.relays) != 1 || c.relays[0] != "wss://relay.monostr.com" || c.reviewDMsPerDay != 20 {
		t.Fatalf("defaults: %+v %v", c, err)
	}
	c, err = loadConfig(getenv(with(map[string]string{"MEDIA_NOSTR_RELAYS": "wss://a.example, wss://b.example", "MEDIA_REVIEW_DMS_PER_DAY": "3"})))
	if err != nil || len(c.relays) != 2 || c.relays[1] != "wss://b.example" || c.reviewDMsPerDay != 3 {
		t.Fatalf("override: %+v %v", c, err)
	}
	for _, bad := range []string{"http://x", "wss://a.example,ftp://b", ","} {
		if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_NOSTR_RELAYS": bad}))); err == nil {
			t.Errorf("relays %q accepted", bad)
		}
	}
	if _, err := loadConfig(getenv(with(map[string]string{"MEDIA_REVIEW_DMS_PER_DAY": "0"}))); err == nil {
		t.Error("a cap of 0 was accepted")
	}
}
