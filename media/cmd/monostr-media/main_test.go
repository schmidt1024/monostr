package main

import (
	"testing"

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
	want := limits.Limits{MaxBytes: 10 << 20, PubkeyPerDay: 50, PubkeyQuota: 500 << 20, IPPerDay: 100, GlobalBytesPerDay: 5 << 30}
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
		"S3_ENDPOINT": "https://s3.example", "S3_REGION": "hel1", "S3_BUCKET": "b", "S3_ACCESS_KEY": "a", "S3_SECRET_KEY": "s",
	})))
	if err != nil {
		t.Fatal(err)
	}
	want := limits.Limits{MaxBytes: 2048, PubkeyPerDay: 3, PubkeyQuota: 4096, IPPerDay: 5, GlobalBytesPerDay: 8192}
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
