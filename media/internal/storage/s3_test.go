package storage

import "testing"

func TestParseEndpoint(t *testing.T) {
	cases := []struct {
		in     string
		host   string
		secure bool
	}{
		{"https://hel1.your-objectstorage.com", "hel1.your-objectstorage.com", true},
		{"https://hel1.your-objectstorage.com/", "hel1.your-objectstorage.com", true},
		{"hel1.your-objectstorage.com", "hel1.your-objectstorage.com", true},
		{"http://127.0.0.1:9000", "127.0.0.1:9000", false},
	}
	for _, c := range cases {
		host, secure, err := ParseEndpoint(c.in)
		if err != nil || host != c.host || secure != c.secure {
			t.Errorf("%q: %q %v %v", c.in, host, secure, err)
		}
	}
	for _, bad := range []string{"", "ftp://example.com", "https://example.com/bucket"} {
		if _, _, err := ParseEndpoint(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestNewS3NeedsBucketAndKeys(t *testing.T) {
	full := S3Config{Endpoint: "https://s3.example", Region: "hel1", Bucket: "b", AccessKey: "a", SecretKey: "s"}
	if _, err := NewS3(full); err != nil {
		t.Fatalf("full config: %v", err)
	}
	for name, cfg := range map[string]S3Config{
		"no bucket":   {Endpoint: full.Endpoint, AccessKey: "a", SecretKey: "s"},
		"no key":      {Endpoint: full.Endpoint, Bucket: "b", SecretKey: "s"},
		"no secret":   {Endpoint: full.Endpoint, Bucket: "b", AccessKey: "a"},
		"no endpoint": {Bucket: "b", AccessKey: "a", SecretKey: "s"},
	} {
		if _, err := NewS3(cfg); err == nil {
			t.Errorf("%s accepted", name)
		}
	}
}
