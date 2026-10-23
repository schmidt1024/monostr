package review

import (
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr/nip19"

	"monostr.com/media/internal/store"
)

func TestParseCommand(t *testing.T) {
	good := map[string]string{
		"weg": "", " Weg ": "", "WEG": "",
		"weg a3f9": "a3f9", "weg #A3F9": "a3f9", " weg  #a3f9 ": "a3f9",
	}
	for in, want := range good {
		code, ok := ParseCommand(in)
		if !ok || code != want {
			t.Errorf("ParseCommand(%q) = %q, %v; want %q, true", in, code, ok, want)
		}
	}
	for _, in := range []string{"weg xyz", "hallo", "wegg", "", "weg a3 f9", "weg #"} {
		if _, ok := ParseCommand(in); ok {
			t.Errorf("ParseCommand(%q) accepted", in)
		}
	}
}

func TestCode(t *testing.T) {
	a := "a3f9c" + strings.Repeat("1", 59)
	if got := Code(a, []string{a}); got != "a3f9" {
		t.Fatalf("alone: %q", got)
	}
	b := "a3f9c" + strings.Repeat("2", 59)
	open := []string{a, b}
	if got := Code(a, open); got != "a3f9c1" {
		t.Fatalf("a: %q", got)
	}
	if got := Code(b, open); got != "a3f9c2" {
		t.Fatalf("b: %q", got)
	}
}

func TestNoticeText(t *testing.T) {
	pub := strings.Repeat("ab", 32)
	npub, _ := nip19.EncodePublicKey(pub)
	sha := strings.Repeat("c", 64)
	c := store.ReviewCase{SHA256: sha, Pubkey: pub, Type: "image/jpeg", Score: 0.6725}
	want := "Prüffall #cccc · NSFW 0,67\nhttps://media.example/" + sha + ".jpg\nvon " + npub +
		"\nAntwort «weg» löscht und sperrt das Bild, ohne Antwort bleibt es."
	got := noticeText("https://media.example", "cccc", c)
	if got != want {
		t.Fatalf("got\n%s\nwant\n%s", got, want)
	}
	if !strings.Contains(got, "von npub1") {
		t.Fatal("npub missing")
	}
}
